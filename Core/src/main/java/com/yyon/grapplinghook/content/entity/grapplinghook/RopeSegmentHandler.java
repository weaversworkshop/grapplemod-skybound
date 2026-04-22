package com.yyon.grapplinghook.content.entity.grapplinghook;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.config.GrappleModCommonConfig;
import com.yyon.grapplinghook.network.NetworkManager;
import com.yyon.grapplinghook.network.clientbound.RopeSegmentUpdateS2CPayload;
import com.yyon.grapplinghook.physics.AnchorSpace;
import com.yyon.grapplinghook.physics.RopeBend;
import com.yyon.grapplinghook.physics.ServerHookEntityTracker;
import com.yyon.grapplinghook.physics.io.RopeSnapshot;
import com.yyon.grapplinghook.physics.raycast.WrapEdgeFinder;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.NullableDirection;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class RopeSegmentHandler {

	private static final double BEND_OFFSET = 0.05d;
	private static final double INTO_BLOCK = 0.05d;

	private final GrapplinghookEntity hookEntity;
	private final Level world;

	/**
	 * The rope as an ordered list of {@link RopeBend bends}, hook at index {@code 0},
	 * player at index {@code size-1}. Each middle bend carries its own
	 * {@link AnchorSpace} so wrap points on a moving sub-level or contraption can
	 * track their host in future phases.
	 *
	 * <p>Phase 1 invariant: every bend is {@link AnchorSpace.World}, and
	 * {@link RopeBend#nativePos} equals {@link RopeBend#worldPos}. The wrap/unwrap
	 * math below still operates purely on {@code worldPos}, so vanilla behavior is
	 * unchanged until later phases enable foreign-space bends.</p>
	 */
	private LinkedList<RopeBend> bends;

	private Vec prevHookPos;
	private Vec prevHolderPos;

	private double ropeLen;

	public RopeSegmentHandler(GrapplinghookEntity hookEntity, Vec hookpos, Vec playerpos) {
		this.bends = new LinkedList<>();

		this.pushSegment(hookpos, null, null);
		this.pushSegment(playerpos, null, null);

		this.world = hookEntity.level();
		this.hookEntity = hookEntity;
		this.prevHookPos = new Vec(hookpos);
		this.prevHolderPos = new Vec(playerpos);
	}

	public RopeSegmentHandler(GrapplinghookEntity hookEntity, Entity holder, RopeSnapshot ropeSnapshot) {
		ServerHookEntityTracker.checkOwnerIsNotHookElseWarn(holder);

		this.bends = new LinkedList<>();

		this.loadFromSnapshot(ropeSnapshot);

		this.ropeLen = ropeSnapshot.getRopeLength();

		this.world = hookEntity.level();
		this.hookEntity = hookEntity;
		this.prevHookPos = Vec.positionVec(hookEntity);
		this.prevHolderPos = Vec.positionVec(holder);
	}

	/** Replace the current rope shape with the snapshot's contents. */
	public void loadFromSnapshot(RopeSnapshot snapshot) {
		this.bends = new LinkedList<>(snapshot.getBends());
	}


	private void pushSegment(Vec segment, Direction topSide, Direction bottomSide) {
		this.bends.add(RopeBend.world(segment, topSide, bottomSide));
	}

	private void removeSegmentAt(int index) {
		this.bends.remove(index);
	}


	public void forceSetPos(Vec hookpos, Vec playerpos) {
		this.prevHookPos = new Vec(hookpos);
		this.prevHolderPos = new Vec(playerpos);
		this.setEndpoint(0, new Vec(hookpos));
		this.setEndpoint(this.bends.size() - 1, new Vec(playerpos));
	}

	public void updatePos(Vec hookpos, Vec playerpos, double ropelen) {
		this.setEndpoint(0, hookpos);
		this.setEndpoint(this.bends.size() - 1, playerpos);
		this.ropeLen = ropelen;
	}

	/**
	 * Maximum iterations of the unwrap+wrap convergence loop per tick. 1-2 suffices
	 * in practice; the cap is a safety net against pathological geometries.
	 */
	private static final int MAX_ITERS = 15;

	/** Depth cap for {@link #updateSegmentSurface} recursion; matches the legacy value. */
	private static final int MAX_SURFACE_RECURSION = 10;

	/**
	 * Tolerance used when shrinking redundant-raycast endpoints away from bend
	 * positions. Bends sit {@code BEND_OFFSET} off real block surfaces; without
	 * margin the redundant raycast can graze the block at its endpoints and report
	 * a false hit even when the rope physically has room to straighten.
	 */
	private static final double SHRINK_MARGIN = 0.07;

	/**
	 * Minimum deflection cosine for a new bend to be kept. Bends that would
	 * barely deflect the rope produce visual "micro-kinks" near block corners
	 * without meaningfully changing the rope's path. A candidate bend is
	 * rejected if {@code dot(incoming, outgoing) > MIN_BEND_DEFLECTION_COS},
	 * i.e. the rope changes direction by less than {@code acos(this)}.
	 *
	 * <p>{@code 0.97} ≈ 14° minimum deflection — rejects barely-bending candidates,
	 * keeps real wraps that turn the rope more than a trivial amount.</p>
	 */
	private static final double MIN_BEND_DEFLECTION_COS = 0.97;

	public void update(Vec hookpos, Vec playerpos, double ropelen, boolean movinghook) {
		if (this.prevHookPos == null) {
			this.prevHookPos = hookpos;
			this.prevHolderPos = playerpos;
		}

		this.setEndpoint(0, hookpos);
		this.setEndpoint(this.bends.size() - 1, playerpos);
		this.ropeLen = ropelen;

		boolean useLegacy = GrappleModCommonConfig.get().useLegacyRopeWrap();

		if (useLegacy) {
			// Preserve the exact v1 order: unwrap once, wrap once.
			this.unwrapPass(hookpos, playerpos, movinghook);
			this.wrapPassLegacy(hookpos, playerpos, movinghook);
		} else {
			// Convergence loop: unwrap+wrap alternate until the bend list is stable.
			// A wrap can add bends that unwrap must then evaluate; an unwrap can leave
			// a new longer segment that wrap must then check for intersections. The
			// invariant we converge toward: every adjacent pair of bends is either
			// unblocked by world geometry (wrap has nothing to add) or blocked at a
			// bend that the unwrap plane test agrees with.
			for (int iter = 0; iter < MAX_ITERS; iter++) {
				int before = this.bends.size();
				this.unwrapPass(hookpos, playerpos, movinghook);
				this.wrapPassSurface(hookpos, playerpos, movinghook);
				if (this.bends.size() == before) break;
				if (iter == MAX_ITERS - 1) {
					GrappleMod.LOGGER.debug("[Rope] convergence loop hit MAX_ITERS={} with size still changing", MAX_ITERS);
				}
			}
		}

		this.prevHookPos = hookpos;
		this.prevHolderPos = playerpos;
	}

	// ------------------------------------------------------------------
	// Unwrap / wrap passes (extracted from the old monolithic update())
	// ------------------------------------------------------------------

	/**
	 * Signed-distance plane test — remove bends whose rope vector has passed to the
	 * outside of the unwrap plane. Algorithm unchanged from v1; now isolated so the
	 * convergence loop can re-run it between wrap passes.
	 */
	private void unwrapPass(Vec hookpos, Vec playerpos, boolean movinghook) {
		// Backward unwrap — check the bend closest to the player.
		while (this.bends.size() > 2) {
			int index = this.bends.size() - 2;
			RopeBend bend = this.bends.get(index);
			Vec closest = bend.worldPos;
			Vec ropevec = playerpos.sub(closest);
			Vec beforepoint = this.bends.get(index - 1).worldPos;
			Vec edgevec = this.getNormal(bend.bottomSide).cross(this.getNormal(bend.topSide));
			Vec planenormal = beforepoint.sub(closest).cross(edgevec);
			if (ropevec.dot(planenormal) > 0) {
				this.removeSegment(index);
			} else break;
		}

		if (!movinghook) return;

		// Forward unwrap — check the bend closest to the hook.
		while (this.bends.size() > 2) {
			int index = 1;
			RopeBend bend = this.bends.get(index);
			Vec farthest = bend.worldPos;
			Vec ropevec = farthest.sub(hookpos);
			Vec beforepoint = this.bends.get(index + 1).worldPos;
			Vec edgevec = this.getNormal(bend.bottomSide).cross(this.getNormal(bend.topSide));
			Vec planenormal = beforepoint.sub(farthest).cross(edgevec);
			if (ropevec.dot(planenormal) > 0 || ropevec.length() < 0.1) {
				this.removeSegment(index);
			} else break;
		}

		// Rope-length overflow — eject farthest bends until the rope fits.
		while (this.bends.size() > 2 && this.getDistToFarthest() > this.ropeLen) {
			this.removeSegment(1);
		}

		// Redundant-bend sweep. The plane test above only evaluates the two
		// endpoint-adjacent bends — middle bends never get re-checked, so a bend
		// can get "stuck" in the middle of the rope long after it's geometrically
		// unnecessary (observed when swinging underneath a tree: rope wraps the
		// bottom edge of a leaf block and won't let go). A bend is unnecessary if
		// the straight line between its neighbors doesn't pass through any solid
		// block — if that's clear, removing the bend can't re-introduce an
		// intersection, so it's always safe. Walk backwards so removals don't
		// shift indices we haven't visited yet.
		for (int i = this.bends.size() - 2; i >= 1; i--) {
			if (this.bends.size() <= 2) break;
			if (i >= this.bends.size() - 1) continue;
			Vec prev = this.bends.get(i - 1).worldPos;
			Vec next = this.bends.get(i + 1).worldPos;
			// Shrink the raycast endpoints slightly toward the segment's centre so
			// the test has margin away from the bend positions themselves. Bends sit
			// BEND_OFFSET off real block surfaces; a ray that starts/ends exactly on
			// those positions can graze the block and register a hit even though the
			// rope physically has room to straighten. Shrinking by SHRINK_MARGIN
			// gives the check enough clearance to distinguish a real obstruction
			// from a tangent-at-endpoint artifact.
			Vec direction = next.sub(prev);
			double length = direction.length();
			if (length > 0.02) {
				double shrink = Math.min(SHRINK_MARGIN, length * 0.1);
				Vec unit = direction.scale(1.0 / length);
				prev = prev.add(unit.scale(shrink));
				next = next.sub(unit.scale(shrink));
			}
			if (GrappleModUtils.rayTraceBlocks(this.hookEntity, this.world, prev, next) == null) {
				this.removeSegment(i);
			}
		}
	}

	/** v1 wrap dispatch: calls {@link #updateSegmentLegacy} on the two endpoint-adjacent segments. */
	private void wrapPassLegacy(Vec hookpos, Vec playerpos, boolean movinghook) {
		if (movinghook) {
			Vec farthest = this.bends.get(1).worldPos;
			Vec prevfarthest = this.bends.size() == 2 ? this.prevHolderPos : farthest;
			this.updateSegmentLegacy(hookpos, this.prevHookPos, farthest, prevfarthest, 1, 0);
		}

		Vec closest = this.bends.get(this.bends.size() - 2).worldPos;
		Vec prevclosest = this.bends.size() == 2 ? this.prevHookPos : closest;
		this.updateSegmentLegacy(closest, prevclosest, playerpos, this.prevHolderPos, this.bends.size() - 1, 0);
	}

	/**
	 * New surface-walking wrap dispatch. Calls {@link #updateSegmentSurface} on the
	 * two endpoint-adjacent segments. Middle segments are between fixed world bends
	 * so their collision state can't change during a tick; Phase 2+ will extend this
	 * to sweep every segment for moving sub-level / contraption bends.
	 */
	private void wrapPassSurface(Vec hookpos, Vec playerpos, boolean movinghook) {
		if (movinghook) {
			Vec farthest = this.bends.get(1).worldPos;
			this.updateSegmentSurface(hookpos, farthest, 1, 0);
		}

		Vec closest = this.bends.get(this.bends.size() - 2).worldPos;
		this.updateSegmentSurface(closest, playerpos, this.bends.size() - 1, 0);
	}

	/**
	 * Surface-walking wrap detection. Raycasts from {@code bottom} to {@code top};
	 * if the ray hits a block, computes the correct wrap edge via
	 * {@link WrapEdgeFinder} and inserts a bend. Recurses on both halves so
	 * multi-block protrusions resolve in one tick.
	 *
	 * <p>Works at rest (no prev-tick dependency) and handles arbitrary
	 * {@link net.minecraft.world.phys.shapes.VoxelShape}s (stairs, slabs, walls,
	 * fences, modded block shapes).</p>
	 */
	private void updateSegmentSurface(Vec top, Vec bottom, int index, int depth) {
		if (depth >= MAX_SURFACE_RECURSION) {
			GrappleMod.LOGGER.warn("[Rope] updateSegmentSurface recursion cap hit at depth {}", depth);
			return;
		}

		BlockHitResult hit = GrappleModUtils.rayTraceBlocks(this.hookEntity, this.world, bottom, top);
		if (hit == null) return;

		WrapEdgeFinder.WrapResult wrap = WrapEdgeFinder.findWrap(
				this.world,
				hit.getBlockPos(),
				hit.getLocation(),
				hit.getDirection(),
				top.toVec3d());
		if (wrap == null) return;

		if (this.isDuplicateNeighborBend(index, wrap.hitFace(), wrap.wrapFace())) return;

		// Reject micro-bends: if the new bend barely deflects the rope, the visual
		// kink near a block corner isn't worth it. Measures the angle between the
		// incoming leg (top → bend) and the outgoing leg (bend → bottom). A tight
		// wrap around a real corner produces a large deflection; a trivial graze
		// produces a tiny one.
		Vec bendPos = wrap.bendPoint();
		Vec incoming = bendPos.sub(top);
		Vec outgoing = bottom.sub(bendPos);
		double incomingLen = incoming.length();
		double outgoingLen = outgoing.length();
		if (incomingLen > 1e-6 && outgoingLen > 1e-6) {
			double cos = incoming.dot(outgoing) / (incomingLen * outgoingLen);
			if (cos > MIN_BEND_DEFLECTION_COS) return;
		}

		this.actuallyAddSegment(index, bendPos, wrap.hitFace(), wrap.wrapFace());

		// Intentionally no rope-length rollback here — if the new bend pushes the
		// bent rope length over {@code ropeLen}, {@code GrapplingHookPhysicsController}
		// handles it via the existing taut-rope pipeline: the player is pulled taut
		// within {@code ropeSnapBuffer} (default 5 blocks), and the grapple disables
		// cleanly if they stretch beyond that. Keeping the bend gives the physically
		// correct behavior (wrapping a rope around a corner shortens the free end),
		// whereas the legacy rollback silently let the rope clip through blocks.

		// Recurse into both halves. Process the playerward (higher-index) half first
		// so any inserts it makes don't shift the hookward half's insertion index.
		updateSegmentSurface(bendPos, bottom, index + 1, depth + 1);
		updateSegmentSurface(top, bendPos, index, depth + 1);
	}

	/**
	 * Guard against adding the same bend twice — mirrors the legacy algorithm's
	 * "ignore bends too close to another bend" check but on actual neighbor identity
	 * instead of distance. If either adjacent bend already has the same (hit, wrap)
	 * face pair we're about to insert, assume the surface is already handled.
	 */
	private boolean isDuplicateNeighborBend(int index, Direction hitFace, Direction wrapFace) {
		if (index - 1 >= 0 && index - 1 < this.bends.size()) {
			RopeBend prev = this.bends.get(index - 1);
			if (prev.bottomSide == hitFace && prev.topSide == wrapFace) return true;
		}
		if (index < this.bends.size()) {
			RopeBend here = this.bends.get(index);
			if (here.bottomSide == hitFace && here.topSide == wrapFace) return true;
		}
		return false;
	}

	public void removeSegment(int index) {
		this.removeSegmentAt(index);

		if (!this.world.isClientSide) {
			RopeSegmentUpdateS2CPayload addmessage = new RopeSegmentUpdateS2CPayload(
					this.hookEntity.getId(), false, index,
					new Vec(0, 0, 0), NullableDirection.DOWN, NullableDirection.DOWN,
					AnchorSpace.World.INSTANCE);
			Vec playerpoint = Vec.positionVec(this.hookEntity.shootingEntity);

			NetworkManager.packetToClient(addmessage, GrappleModUtils.getPlayersThatCanSeeChunkAt((ServerLevel) world, playerpoint));
		}
	}

	/**
	 * Legacy corner-hunting wrap algorithm from v1. Retained behind the
	 * {@code legacyRopeWrap} config flag so we can A/B-compare against the new
	 * surface-walking algorithm (see {@link #updateSegmentSurface}). Known limitations
	 * documented on the new algorithm — do not extend this; add logic to the new one.
	 */
	@Deprecated
	public void updateSegmentLegacy(Vec top, Vec prevtop, Vec bottom, Vec prevbottom, int index, int numberrecursions) {
		BlockHitResult bottomraytraceresult = GrappleModUtils.rayTraceBlocks(this.hookEntity, this.world, bottom, top);

        // if rope hit block
        if (bottomraytraceresult != null) {
        	if (GrappleModUtils.rayTraceBlocks(this.hookEntity, this.world, prevbottom, prevtop) != null) {
        		return;
        	}

            Vec bottomhitvec = new Vec(bottomraytraceresult.getLocation());

            Direction bottomside = bottomraytraceresult.getDirection();
            Vec bottomnormal = this.getNormal(bottomside);

            // calculate where bottomhitvec was along the rope in the previous tick
            double prevropelen = prevtop.sub(prevbottom).length();

            Vec cornerbound1 = bottomhitvec.add(bottomnormal.withMagnitude(-INTO_BLOCK));

            Vec bound_option1 = linePlaneIntersection(prevtop, prevbottom, cornerbound1, bottomnormal);
            Vec bound_option2 = linePlaneIntersection(top, prevtop, cornerbound1, bottomnormal);
            Vec bound_option3 = linePlaneIntersection(prevbottom, bottom, cornerbound1, bottomnormal);

            for (Vec cornerbound2 : new Vec[] {bound_option1, bound_option2, bound_option3}) {
            	if (cornerbound2 == null) {
            		continue;
            	}

            	// the corner must be in the line (cornerbound2, cornerbound1)
            	BlockHitResult cornerraytraceresult = GrappleModUtils.rayTraceBlocks(this.hookEntity, this.world, cornerbound2, cornerbound1);
                if (cornerraytraceresult != null) {
                	Vec cornerhitpos = new Vec(cornerraytraceresult.getLocation());
                	Direction cornerside = cornerraytraceresult.getDirection();

                	if (!(cornerside == bottomside || cornerside.getOpposite() == bottomside)) {
                		// add a bend around the corner
                		Vec actualcorner = cornerhitpos.add(bottomnormal.withMagnitude(INTO_BLOCK));
                		Vec bend = actualcorner.add(bottomnormal.withMagnitude(BEND_OFFSET)).add(getNormal(cornerside).withMagnitude(BEND_OFFSET));
                		Vec topropevec = bend.sub(top);
                		Vec bottomropevec = bend.sub(bottom);

                		// ignore bends that are too close to another bend
                		if (topropevec.length() < 0.05) {
                			if (this.bends.get(index - 1).bottomSide == bottomside && this.bends.get(index - 1).topSide == cornerside) {
                    			continue;
                			}
                		}
                		if (bottomropevec.length() < 0.05) {
                			if (this.bends.get(index).bottomSide == bottomside && this.bends.get(index).topSide == cornerside) {
                    			continue;
                			}
                		}

                		this.actuallyAddSegment(index, bend, bottomside, cornerside);

                		// if not enough rope length left, undo
                		if(this.getDistToAnchor() + .2 > this.ropeLen) {
                			this.removeSegment(index);
                			continue;
                		}

                		// now to recurse on top section of rope
                		double newropelen = topropevec.length() + bottomropevec.length();

                		double prevtoptobend = topropevec.length() * prevropelen / newropelen;
                		Vec prevbend = prevtop.add(prevbottom.sub(prevtop).withMagnitude(prevtoptobend));

                		if (numberrecursions < 10) {
                    		updateSegmentLegacy(top, prevtop, bend, prevbend, index, numberrecursions+1);
                		} else {
                			GrappleMod.LOGGER.warn("Warning: number recursions exceeded");
                		}
                		break;
                	}
                }
            }
        }
	}

	public Vec linePlaneIntersection(Vec linepoint1, Vec linepoint2, Vec planepoint, Vec planenormal) {
		// calculate the intersection of a line and a plane
		// formula: https://en.wikipedia.org/wiki/Line%E2%80%93plane_intersection#Algebraic_form

		Vec linevec = linepoint2.sub(linepoint1);

		if (linevec.dot(planenormal) == 0) {
			return null;
		}

		double d = planepoint.sub(linepoint1).dot(planenormal) / linevec.dot(planenormal);
		return linepoint1.add(linevec.scale(d));
	}

	public boolean hookPastBend(double ropelen) {
		return (this.getDistToFarthest() > ropelen);
	}

	/**
	 * Adapter: historical entry point where wrap detection produces world-space bends.
	 * Builds a {@link AnchorSpace.World} {@link RopeBend} and delegates. Later phases
	 * will add parallel overloads that take a space tag + native position so
	 * foreign-space bends can be inserted.
	 */
	public void actuallyAddSegment(int index, Vec bendPoint, Direction bottomSide, Direction topSide) {
		this.actuallyAddSegment(index, bendPoint, NullableDirection.fromVanilla(bottomSide), NullableDirection.fromVanilla(topSide));
	}

	public void actuallyAddSegment(int index, Vec bendPoint, NullableDirection bottomSide, NullableDirection topSide) {
		this.addBend(index, RopeBend.world(bendPoint, topSide.toVanilla(), bottomSide.toVanilla()));
	}

	/**
	 * Insert a pre-built {@link RopeBend} at {@code index}. Used by the wrap
	 * detection path above (via the {@code actuallyAddSegment} adapters) and by
	 * the client-side network receiver when applying an incremental update.
	 */
	public void addBend(int index, RopeBend bend) {
		this.bends.add(index, bend);

		if (!this.world.isClientSide) {
			RopeSegmentUpdateS2CPayload addmessage = new RopeSegmentUpdateS2CPayload(
					this.hookEntity.getId(), true, index,
					bend.worldPos,
					NullableDirection.fromVanilla(bend.topSide),
					NullableDirection.fromVanilla(bend.bottomSide),
					bend.space);
			Vec playerpoint = Vec.positionVec(this.hookEntity.shootingEntity);

			NetworkManager.packetToClient(addmessage, GrappleModUtils.getPlayersThatCanSeeChunkAt((ServerLevel) world, playerpoint));
		}
	}


	public Vec getNormal(Direction facing) {
		Vec3i facingvec = facing.getNormal();
		return new Vec(facingvec.getX(), facingvec.getY(), facingvec.getZ());
	}

	public BlockPos getBendBlock(int index) {
		RopeBend bend = this.bends.get(index);
		Vec bendpos = bend.worldPos;
		bendpos.mutableAdd(this.getNormal(bend.bottomSide).withMagnitude(-INTO_BLOCK * 2));
		bendpos.mutableAdd(this.getNormal(bend.topSide).withMagnitude(-INTO_BLOCK * 2));
		return BlockPos.containing(bendpos.toVec3d());
	}

	public Vec getClosest(Vec hookpos) {
		this.setEndpoint(0, hookpos);
		return this.bends.get(this.bends.size() - 2).worldPos;
	}

	public double getDistToAnchor() {
		double dist = 0;
		for (int i = 0; i < this.bends.size() - 2; i++) {
			dist += this.bends.get(i).worldPos.sub(this.bends.get(i + 1).worldPos).length();
		}

		return dist;
	}

	public Vec getFarthest() {
		return this.bends.get(1).worldPos;
	}

	public double getDistToFarthest() {
		double dist = 0;
		for (int i = 1; i < this.bends.size() - 1; i++) {
			dist += this.bends.get(i).worldPos.sub(this.bends.get(i + 1).worldPos).length();
		}

		return dist;
	}

	public double getDist(Vec hookpos, Vec playerpos) {
		this.setEndpoint(0, hookpos);
		this.setEndpoint(this.bends.size() - 1, playerpos);
		double dist = 0;
		for (int i = 0; i < this.bends.size() - 1; i++) {
			dist += this.bends.get(i).worldPos.sub(this.bends.get(i + 1).worldPos).length();
		}

		return dist;
	}

	public AABB getBoundingBox(Vec hookpos, Vec playerpos) {
		this.updatePos(hookpos, playerpos, this.ropeLen);
		Vec minvec = new Vec(hookpos);
		Vec maxvec = new Vec(hookpos);
		for (int i = 1; i < this.bends.size(); i++) {
			Vec segpos = this.bends.get(i).worldPos;
			if (segpos.x < minvec.x) {
				minvec.x = segpos.x;
			} else if (segpos.x > maxvec.x) {
				maxvec.x = segpos.x;
			}
			if (segpos.y < minvec.y) {
				minvec.y = segpos.y;
			} else if (segpos.y > maxvec.y) {
				maxvec.y = segpos.y;
			}
			if (segpos.z < minvec.z) {
				minvec.z = segpos.z;
			} else if (segpos.z > maxvec.z) {
				maxvec.z = segpos.z;
			}
		}

		return new AABB(minvec.x, minvec.y, minvec.z, maxvec.x, maxvec.y, maxvec.z);
	}

	/**
	 * Rewrite the world-space position of an endpoint bend (index 0 = hook, size-1 = player).
	 * Endpoints are always {@link AnchorSpace.World}, so replacing {@code worldPos} is
	 * equivalent to the old {@code segments.set(i, pos)} behavior — no space-dispatch
	 * needed.
	 */
	private void setEndpoint(int index, Vec pos) {
		this.bends.get(index).worldPos = pos;
	}


	// ------------------------------------------------------------------
	// External views — legacy parallel-list API for callers that predate RopeBend
	// (renderer, tests, snapshot copy path).
	// ------------------------------------------------------------------

	public List<Vec> getSegments() {
		return this.bends.stream()
				.map(b -> b.worldPos)
				.collect(Collectors.toUnmodifiableList());
	}

	public List<Direction> getTopSides() {
		return this.bends.stream()
				.map(b -> b.topSide)
				.collect(Collectors.toUnmodifiableList());
	}

	public List<Direction> getBottomSides() {
		return this.bends.stream()
				.map(b -> b.bottomSide)
				.collect(Collectors.toUnmodifiableList());
	}

	/** Authoritative bend list — used by {@link RopeSnapshot#RopeSnapshot(RopeSegmentHandler)}. */
	public List<RopeBend> getBends() {
		return Collections.unmodifiableList(this.bends);
	}

	public double getCurrentRopeLength() {
		return this.ropeLen;
	}
}
