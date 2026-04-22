package com.yyon.grapplinghook.content.entity.grapplinghook;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.config.GrappleModCommonConfig;
import com.yyon.grapplinghook.network.NetworkManager;
import com.yyon.grapplinghook.network.clientbound.RopeSegmentUpdateS2CPayload;
import com.yyon.grapplinghook.physics.AnchorSpace;
import com.yyon.grapplinghook.physics.RopeBend;
import com.yyon.grapplinghook.physics.ServerHookEntityTracker;
import com.yyon.grapplinghook.integration.ContraptionIntegration;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.physics.io.RopeSnapshot;
import com.yyon.grapplinghook.physics.raycast.MultiSpaceRaycaster;
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
import net.minecraft.world.phys.Vec3;

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

		// Non-WORLD bends need their worldPos refreshed each tick — the native
		// position (contraption-local / plot-space) stays fixed, but the host
		// entity / sub-level moves, so worldPos must be recomputed via the
		// integration's localToWorld / plotToWorld. No-op when every bend is
		// WORLD (the endpoint-only or all-static-blocks common case).
		this.refreshWorldCoords();

		boolean useLegacy = GrappleModCommonConfig.get().useLegacyRopeWrap();

		if (useLegacy) {
			// True v1 path for WORLD bends: plane-test unwrap + corner-hunting
			// wrap, once. Plus two non-WORLD augmentations so contraption/sub-level
			// collision works in legacy mode: a cleanup pass (for bends whose host
			// no longer crosses their segment) and a moving-host sweep over every
			// rope segment (v1's wrap pass only checks endpoint-adjacent segments,
			// which misses contraptions passing through middle segments between
			// existing wraps). The legacyRopeWrap flag controls the *wrap placement
			// algorithm*, not which spaces the rope can hit.
			this.unwrapPass(hookpos, playerpos, movinghook);
			this.nonWorldBendCleanup();
			this.movingHostSweep();
			this.wrapPassLegacy(hookpos, playerpos, movinghook);
		} else {
			// Surface-algorithm convergence loop: plane-test unwrap + redundant
			// sweep + moving-host sweep + surface wrap, iterated until the bend
			// list stops changing.
			for (int iter = 0; iter < MAX_ITERS; iter++) {
				int before = this.bends.size();
				this.unwrapPass(hookpos, playerpos, movinghook);
				this.redundantBendSweep();
				this.movingHostSweep();
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
	/**
	 * Plane-test unwrap — the original v1 algorithm, intact. Backward loop pops
	 * bends closest to the player if the rope vector passes to the unwrap-side of
	 * the plane defined by the bend's two face normals; forward loop mirrors on
	 * the hook side (only when the hook is moving); final guard evicts far-side
	 * bends if the chain exceeds {@code ropeLen}.
	 *
	 * <p>Both the legacy and surface paths call this. The legacy path runs it
	 * once per tick (preserving v1 behavior). The surface path adds the separate
	 * {@link #redundantBendSweep()} after it, which is <em>not</em> part of v1.</p>
	 */
	private void unwrapPass(Vec hookpos, Vec playerpos, boolean movinghook) {
		// Backward unwrap — check the bend closest to the player.
		while (this.bends.size() > 2) {
			int index = this.bends.size() - 2;
			RopeBend bend = this.bends.get(index);
			// Face-contact bends (e.g. CONTRAPTION) have null topSide — no well-defined
			// edge axis. Plane test doesn't apply; leave the bend and stop unwrapping
			// this direction. The raycast-based redundant sweep handles their removal.
			if (bend.topSide == null || bend.bottomSide == null) break;
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
			if (bend.topSide == null || bend.bottomSide == null) break;
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
	}

	/**
	 * Surface-algorithm-only redundant-bend sweep — for each middle bend, raycast
	 * from its previous neighbor to its next neighbor; if the line is clear the
	 * bend is unnecessary (removing it can't re-introduce an intersection since
	 * we just verified there isn't one) and gets dropped. This catches "stuck"
	 * middle bends that the plane test never reaches (plane test only looks at
	 * endpoint-adjacent bends).
	 *
	 * <p><b>Not part of v1.</b> Kept out of {@link #unwrapPass} so that the
	 * {@code legacyRopeWrap=true} path matches v1 exactly.</p>
	 */
	/**
	 * Per-tick refresh of world positions for bends anchored to moving hosts
	 * (CONTRAPTION today, SUBLEVEL in Phase 3). For each non-WORLD bend we look
	 * up the host via the integration, transform the stored native position into
	 * world space, and write back to {@link RopeBend#worldPos}. If the host has
	 * been removed (entity despawned, sub-level disassembled), drop the bend —
	 * the next unwrap pass relaxes the chain.
	 *
	 * <p>Walks backwards so drops don't shift indices we haven't visited. Any
	 * dropped bend's segments will be re-checked by the wrap pass and replaced
	 * (or stay clear) on this same tick's convergence loop.</p>
	 */
	private void refreshWorldCoords() {
		if (this.bends.size() <= 2) return;
		for (int i = this.bends.size() - 2; i >= 1; i--) {
			if (i >= this.bends.size() - 1) continue;
			RopeBend bend = this.bends.get(i);
			if (bend.space instanceof AnchorSpace.World) continue;

			if (bend.space instanceof AnchorSpace.Contraption c) {
				Entity host = this.world.getEntity(c.entityId());
				if (host == null || !host.isAlive()) {
					this.removeSegment(i);
					continue;
				}
				Vec3 newWorld = GrappleModIntegrations.getContraptionIntegration()
						.localToWorld(host, bend.nativePos.toVec3d(), CONTRAPTION_PARTIAL_TICKS);
				bend.worldPos = new Vec(newWorld.x, newWorld.y, newWorld.z);
			}
			// SUBLEVEL: reserved for Phase 3 — plotToWorld here, with removed-check.
		}
	}

	/**
	 * Cleanup pass that only considers non-WORLD bends (CONTRAPTION today,
	 * SUBLEVEL in Phase 3). For each such middle bend, raycasts between its
	 * neighbors; if the line is clear in *every* space the bend is unnecessary.
	 * Safe to run in the legacy wrap path because it never touches WORLD bends —
	 * legacy's v1-faithful behavior for world wraps is preserved.
	 */
	private void nonWorldBendCleanup() {
		for (int i = this.bends.size() - 2; i >= 1; i--) {
			if (this.bends.size() <= 2) break;
			if (i >= this.bends.size() - 1) continue;
			RopeBend bend = this.bends.get(i);
			if (bend.space instanceof AnchorSpace.World) continue;

			Vec prev = this.bends.get(i - 1).worldPos;
			Vec next = this.bends.get(i + 1).worldPos;
			Vec direction = next.sub(prev);
			double length = direction.length();
			if (length > 0.02) {
				double shrink = Math.min(SHRINK_MARGIN, length * 0.1);
				Vec unit = direction.scale(1.0 / length);
				prev = prev.add(unit.scale(shrink));
				next = next.sub(unit.scale(shrink));
			}
			if (MultiSpaceRaycaster.raycast(this.hookEntity, this.world, prev, next, CONTRAPTION_PARTIAL_TICKS) == null) {
				this.removeSegment(i);
			}
		}
	}

	/**
	 * Moving-host sweep — raycast every rope segment for CONTRAPTION (and later
	 * SUBLEVEL) hits, inserting face-contact bends wherever a moving host crosses
	 * a segment. Fills a gap in the endpoint-only wrap pass: v1's assumption that
	 * middle segments sit between fixed neighbors with static geometry holds for
	 * world blocks but not for moving contraptions, which can sweep through any
	 * segment — including the hook↔middle-bend span when the hook is anchored to
	 * a static block and the player has wrapped the rope somewhere else.
	 *
	 * <p>Only inserts non-WORLD bends here. World-block wraps still go through
	 * the endpoint-adjacent wrap pass so the v1 corner-hunting / surface-walking
	 * algorithms remain the authoritative path for static geometry.</p>
	 *
	 * <p>Early-outs when no contraption integration is registered so vanilla
	 * gameplay pays nothing for this pass.</p>
	 */
	private void movingHostSweep() {
		if (!GrappleModIntegrations.hasContraptionIntegration()) return;
		int i = 1;
		while (i < this.bends.size()) {
			Vec top = this.bends.get(i - 1).worldPos;
			Vec bot = this.bends.get(i).worldPos;
			MultiSpaceRaycaster.MultiSpaceHit hit = MultiSpaceRaycaster.raycast(
					this.hookEntity, this.world, bot, top, CONTRAPTION_PARTIAL_TICKS);
			if (hit != null && hit.space() instanceof AnchorSpace.Contraption c) {
				Vec inserted = insertContraptionBend(top, bot, i, hit, c);
				if (inserted != null) {
					// A new bend took index i; advance past it so we don't
					// immediately re-examine the same sub-segment.
					i++;
				}
			}
			i++;
		}
	}

	private void redundantBendSweep() {
		for (int i = this.bends.size() - 2; i >= 1; i--) {
			if (this.bends.size() <= 2) break;
			if (i >= this.bends.size() - 1) continue;
			Vec prev = this.bends.get(i - 1).worldPos;
			Vec next = this.bends.get(i + 1).worldPos;
			// Shrink endpoints so a ray grazing at the bend offset doesn't report
			// a false hit (bends sit BEND_OFFSET off real block surfaces).
			Vec direction = next.sub(prev);
			double length = direction.length();
			if (length > 0.02) {
				double shrink = Math.min(SHRINK_MARGIN, length * 0.1);
				Vec unit = direction.scale(1.0 / length);
				prev = prev.add(unit.scale(shrink));
				next = next.sub(unit.scale(shrink));
			}
			// Multi-space redundant check: if NEITHER vanilla blocks NOR any
			// contraption lies on the straight line between neighbors, the bend is
			// geometrically unnecessary. Drops CONTRAPTION bends correctly once the
			// rope can pass through without them.
			if (MultiSpaceRaycaster.raycast(this.hookEntity, this.world, prev, next, CONTRAPTION_PARTIAL_TICKS) == null) {
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

		MultiSpaceRaycaster.MultiSpaceHit hit = MultiSpaceRaycaster.raycast(
				this.hookEntity, this.world, bottom, top, CONTRAPTION_PARTIAL_TICKS);
		if (hit == null) return;

		if (hit.space() instanceof AnchorSpace.World) {
			placeWorldBend(top, bottom, index, depth, hit);
		} else if (hit.space() instanceof AnchorSpace.Contraption c) {
			placeContraptionBend(top, bottom, index, depth, hit, c);
		}
		// SUBLEVEL: reserved for Phase 3.
	}

	/** Partial-ticks value passed to {@link ContraptionIntegration#raycastContraptionDetailed} for rotation sampling. */
	private static final float CONTRAPTION_PARTIAL_TICKS = 1.0f;

	/** World-block hit — the existing WrapEdgeFinder-based edge placement (silhouette + pinch + micro-bend filters). */
	private void placeWorldBend(Vec top, Vec bottom, int index, int depth, MultiSpaceRaycaster.MultiSpaceHit hit) {
		// Derive a BlockPos from the hit location for the VoxelShape lookup. The hit
		// face's inward normal pushes us slightly into the block before flooring.
		Direction hitFace = hit.face();
		Vec3 loc = hit.worldHit();
		net.minecraft.core.BlockPos blockPos = net.minecraft.core.BlockPos.containing(
				loc.x - hitFace.getStepX() * 0.01,
				loc.y - hitFace.getStepY() * 0.01,
				loc.z - hitFace.getStepZ() * 0.01);

		WrapEdgeFinder.WrapResult wrap = WrapEdgeFinder.findWrap(
				this.world, blockPos, loc, hitFace, top.toVec3d());
		if (wrap == null) return;

		if (this.isDuplicateNeighborBend(index, wrap.hitFace(), wrap.wrapFace())) return;

		Vec bendPos = wrap.bendPoint();
		if (isMicroBend(top, bendPos, bottom)) return;

		this.actuallyAddSegment(index, bendPos, wrap.hitFace(), wrap.wrapFace());

		updateSegmentSurface(bendPos, bottom, index + 1, depth + 1);
		updateSegmentSurface(top, bendPos, index, depth + 1);
	}

	/**
	 * Contraption hit — place a simple face-contact bend. No edge finding because
	 * the contraption's blocks rotate per tick and have no stable world-space edges;
	 * we just anchor the bend to the hit point in the contraption's local frame via
	 * {@link RopeBend#contraption}. Subsequent ticks refresh the bend's world
	 * position through the integration's {@code localToWorld} in
	 * {@link #refreshWorldCoords}. The bend's {@code topSide} is intentionally null
	 * (face-contact only — no wrap edge axis); {@link #unwrapPass}'s plane test
	 * skips bends with null sides, and the redundant-bend sweep handles removal via
	 * raycast.
	 */
	private void placeContraptionBend(Vec top, Vec bottom, int index, int depth,
	                                  MultiSpaceRaycaster.MultiSpaceHit hit, AnchorSpace.Contraption c) {
		Vec worldBendPos = insertContraptionBend(top, bottom, index, hit, c);
		if (worldBendPos == null) return;
		updateSegmentSurface(worldBendPos, bottom, index + 1, depth + 1);
		updateSegmentSurface(top, worldBendPos, index, depth + 1);
	}

	/**
	 * Shared contraption-bend insertion used by both the surface and legacy wrap
	 * paths. Offsets the hit point outward along the face, resolves the bend's
	 * local-frame position via the integration, and inserts the bend. Returns the
	 * world-space bend position on success (so callers that want to recurse can
	 * use it), or {@code null} if the bend was rejected (missing host, micro-bend).
	 */
	private Vec insertContraptionBend(Vec top, Vec bottom, int index,
	                                  MultiSpaceRaycaster.MultiSpaceHit hit, AnchorSpace.Contraption c) {
		Direction face = hit.face();
		Vec worldBendPos = new Vec(
				hit.worldHit().x + face.getStepX() * CONTRAPTION_BEND_OFFSET,
				hit.worldHit().y + face.getStepY() * CONTRAPTION_BEND_OFFSET,
				hit.worldHit().z + face.getStepZ() * CONTRAPTION_BEND_OFFSET);

		Entity entity = this.world.getEntity(c.entityId());
		if (entity == null) return null;
		Vec3 nativeLocal = GrappleModIntegrations.getContraptionIntegration()
				.worldToLocal(entity, worldBendPos.toVec3d(), CONTRAPTION_PARTIAL_TICKS);
		Vec nativePos = new Vec(nativeLocal.x, nativeLocal.y, nativeLocal.z);

		// NOTE: intentionally skip isMicroBend here. Contraption bends are
		// face-contact anchors, not wrap-around-a-corner bends — the rope deflects
		// only by CONTRAPTION_BEND_OFFSET (~0.08 blocks) off the hit block's face,
		// which for long rope segments is well below the micro-bend threshold.
		// Running the filter here would silently drop every contraption bend and
		// the rope would phase through the contraption.

		this.addBend(index, RopeBend.contraption(c.entityId(), nativePos, worldBendPos, null, face));
		return worldBendPos;
	}

	private boolean isMicroBend(Vec a, Vec b, Vec c) {
		Vec incoming = b.sub(a);
		Vec outgoing = c.sub(b);
		double il = incoming.length();
		double ol = outgoing.length();
		if (il < 1e-6 || ol < 1e-6) return false;
		return incoming.dot(outgoing) / (il * ol) > MIN_BEND_DEFLECTION_COS;
	}

	/** Outward offset from a contraption-block face where the bend sits. Matches {@code WrapEdgeFinder.BEND_OFFSET}. */
	private static final double CONTRAPTION_BEND_OFFSET = 0.08;

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
		// Route primary hit detection through MultiSpaceRaycaster so CONTRAPTION
		// bends work in legacy mode too — the legacyRopeWrap flag controls wrap
		// placement, not which spaces the rope can collide with.
		MultiSpaceRaycaster.MultiSpaceHit msHit = MultiSpaceRaycaster.raycast(
				this.hookEntity, this.world, bottom, top, CONTRAPTION_PARTIAL_TICKS);
		if (msHit == null) return;

		// CONTRAPTION hit — place a simple face-contact bend (no corner-hunt).
		// Contraption blocks rotate per tick so there are no stable world edges to
		// wrap; we just anchor to the hit face in the contraption's local frame.
		if (msHit.space() instanceof AnchorSpace.Contraption c) {
			insertContraptionBend(top, bottom, index, msHit, c);
			return;
		}

		// WORLD hit — continue with the v1 corner-hunting algorithm exactly.
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
