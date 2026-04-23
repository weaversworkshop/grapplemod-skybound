package com.yyon.grapplinghook.content.entity.grapplinghook;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.config.GrappleModCommonConfig;
import com.yyon.grapplinghook.network.NetworkManager;
import com.yyon.grapplinghook.network.clientbound.RopeSegmentUpdateS2CPayload;
import com.yyon.grapplinghook.physics.AnchorSpace;
import com.yyon.grapplinghook.physics.RopeBend;
import com.yyon.grapplinghook.physics.ServerHookEntityTracker;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import com.yyon.grapplinghook.integration.ContraptionIntegration;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
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

	public void loadFromSnapshot(RopeSnapshot snapshot) {
		this.bends = new LinkedList<>();
		for (RopeBend src : snapshot.getBends()) {
			RopeBend fixed = src;
			if (src.space instanceof AnchorSpace.SubLevel sl) {
				SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
				if (sli.isSubLevelLoaded(sl.subLevelId())) {
					Vec3 plot = sli.worldToPlot(sl.subLevelId(), src.worldPos.toVec3d(), CONTRAPTION_PARTIAL_TICKS);
					fixed = new RopeBend(src.space, src.worldPos,
							new Vec(plot.x, plot.y, plot.z), src.topSide, src.bottomSide);
				}
			} else if (src.space instanceof AnchorSpace.Contraption c) {
				Entity host = this.world.getEntity(c.entityId());
				if (host != null && host.isAlive()) {
					Vec3 local = GrappleModIntegrations.getContraptionIntegration()
							.worldToLocal(host, src.worldPos.toVec3d(), CONTRAPTION_PARTIAL_TICKS);
					fixed = new RopeBend(src.space, src.worldPos,
							new Vec(local.x, local.y, local.z), src.topSide, src.bottomSide);
				}
			}
			this.bends.add(fixed);
		}
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

	private static final int MAX_ITERS = 15;
	private static final int MAX_SURFACE_RECURSION = 10;
	private static final double SHRINK_MARGIN = 0.01;
	private static final double MIN_BEND_DEFLECTION_COS = 0.97;

	public void update(Vec hookpos, Vec playerpos, double ropelen, boolean movinghook) {
		if (this.prevHookPos == null) {
			this.prevHookPos = hookpos;
			this.prevHolderPos = playerpos;
		}

		this.setEndpoint(0, hookpos);
		this.setEndpoint(this.bends.size() - 1, playerpos);
		this.ropeLen = ropelen;

		this.refreshWorldCoords();

		boolean useLegacy = GrappleModCommonConfig.get().useLegacyRopeWrap();

		if (useLegacy) {
			this.unwrapPass(hookpos, playerpos, movinghook);
			this.redundantBendSweep();
			this.movingHostSweep();
			this.wrapPassLegacy(hookpos, playerpos, movinghook);
		} else {
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

	private void unwrapPass(Vec hookpos, Vec playerpos, boolean movinghook) {
		while (this.bends.size() > 2) {
			int index = this.bends.size() - 2;
			RopeBend bend = this.bends.get(index);
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

		while (this.bends.size() > 2 && this.getDistToFarthest() > this.ropeLen) {
			if (!(this.bends.get(1).space instanceof AnchorSpace.World)) break;
			this.removeSegment(1);
		}
	}

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
				double jumpSq = newWorld.distanceToSqr(bend.worldPos.toVec3d());
				if (jumpSq > 64 * 64) {
					GrappleMod.LOGGER.warn("[Grapple] Contraption localToWorld returned a position {}m from the previous bend world pos; dropping bend. entityId={} class={} native={} oldWorld={} newWorld={}",
							Math.sqrt(jumpSq), c.entityId(), host.getClass().getName(),
							bend.nativePos, bend.worldPos, newWorld);
					this.removeSegment(i);
					continue;
				}
				bend.worldPos = new Vec(newWorld.x, newWorld.y, newWorld.z);
			} else if (bend.space instanceof AnchorSpace.SubLevel sl) {
				SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
				if (!sli.isSubLevelLoaded(sl.subLevelId())) {
					this.removeSegment(i);
					continue;
				}
				Vec3 newWorld = sli.plotToWorld(sl.subLevelId(), bend.nativePos.toVec3d(), CONTRAPTION_PARTIAL_TICKS);
				double jumpSq = newWorld.distanceToSqr(bend.worldPos.toVec3d());
				if (jumpSq > 64 * 64) {
					GrappleMod.LOGGER.warn("[Grapple] Sub-level plotToWorld returned a position {}m from the previous bend world pos; dropping bend. uuid={} native={} oldWorld={} newWorld={}",
							Math.sqrt(jumpSq), sl.subLevelId(), bend.nativePos, bend.worldPos, newWorld);
					this.removeSegment(i);
					continue;
				}
				bend.worldPos = new Vec(newWorld.x, newWorld.y, newWorld.z);
			}
		}
	}

	private void movingHostSweep() {
		if (!GrappleModIntegrations.hasContraptionIntegration()
				&& !GrappleModIntegrations.hasSubLevelIntegration()) return;
		int i = 1;
		while (i < this.bends.size()) {
			Vec top = this.bends.get(i - 1).worldPos;
			Vec bot = this.bends.get(i).worldPos;
			MultiSpaceRaycaster.MultiSpaceHit hit = MultiSpaceRaycaster.raycast(
					this.hookEntity, this.world, bot, top, CONTRAPTION_PARTIAL_TICKS);
			Vec inserted = null;
			if (hit != null && hit.space() instanceof AnchorSpace.Contraption c) {
				inserted = insertContraptionBend(top, bot, i, hit, c);
			} else if (hit != null && hit.space() instanceof AnchorSpace.SubLevel sl) {
				inserted = insertSubLevelBend(top, bot, i, hit, sl);
			}
			if (inserted != null) {
				i++;
			}
			i++;
		}
	}

	private void redundantBendSweep() {
		boolean hookOnMovingHost = this.hookEntity.isAttachedToMovingBody();
		for (int i = this.bends.size() - 2; i >= 1; i--) {
			if (this.bends.size() <= 2) break;
			if (i >= this.bends.size() - 1) continue;
			RopeBend bend = this.bends.get(i);
			if (bend.space instanceof AnchorSpace.World && !hookOnMovingHost) continue;

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

	private void wrapPassSurface(Vec hookpos, Vec playerpos, boolean movinghook) {
		if (movinghook) {
			Vec farthest = this.bends.get(1).worldPos;
			this.updateSegmentSurface(hookpos, farthest, 1, 0);
		}

		Vec closest = this.bends.get(this.bends.size() - 2).worldPos;
		this.updateSegmentSurface(closest, playerpos, this.bends.size() - 1, 0);
	}

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
		} else if (hit.space() instanceof AnchorSpace.SubLevel sl) {
			placeSubLevelBend(top, bottom, index, depth, hit, sl);
		}
	}

	private void placeSubLevelBend(Vec top, Vec bottom, int index, int depth,
	                               MultiSpaceRaycaster.MultiSpaceHit hit, AnchorSpace.SubLevel sl) {
		Vec worldBendPos = insertSubLevelBend(top, bottom, index, hit, sl);
		if (worldBendPos == null) return;
		updateSegmentSurface(worldBendPos, bottom, index + 1, depth + 1);
		updateSegmentSurface(top, worldBendPos, index, depth + 1);
	}

	private static final float CONTRAPTION_PARTIAL_TICKS = 1.0f;

	private void placeWorldBend(Vec top, Vec bottom, int index, int depth, MultiSpaceRaycaster.MultiSpaceHit hit) {
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

	private void placeContraptionBend(Vec top, Vec bottom, int index, int depth,
	                                  MultiSpaceRaycaster.MultiSpaceHit hit, AnchorSpace.Contraption c) {
		Vec worldBendPos = insertContraptionBend(top, bottom, index, hit, c);
		if (worldBendPos == null) return;
		updateSegmentSurface(worldBendPos, bottom, index + 1, depth + 1);
		updateSegmentSurface(top, worldBendPos, index, depth + 1);
	}

	private Vec insertContraptionBend(Vec top, Vec bottom, int index,
	                                  MultiSpaceRaycaster.MultiSpaceHit hit, AnchorSpace.Contraption c) {
		Direction face = hit.face();
		Vec worldBendPos = new Vec(
				hit.worldHit().x + face.getStepX() * CONTRAPTION_BEND_OFFSET,
				hit.worldHit().y + face.getStepY() * CONTRAPTION_BEND_OFFSET,
				hit.worldHit().z + face.getStepZ() * CONTRAPTION_BEND_OFFSET);

		Entity entity = this.world.getEntity(c.entityId());
		if (entity == null) return null;

		if (hasAnyContraptionBendNear(c.entityId(), worldBendPos)) {
			return null;
		}

		Vec3 nativeLocal = GrappleModIntegrations.getContraptionIntegration()
				.worldToLocal(entity, worldBendPos.toVec3d(), CONTRAPTION_PARTIAL_TICKS);
		Vec nativePos = new Vec(nativeLocal.x, nativeLocal.y, nativeLocal.z);

		this.addBend(index, RopeBend.contraption(c.entityId(), nativePos, worldBendPos, null, face));
		return worldBendPos;
	}

	private Vec insertSubLevelBend(Vec top, Vec bottom, int index,
	                               MultiSpaceRaycaster.MultiSpaceHit hit, AnchorSpace.SubLevel sl) {
		SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
		if (!sli.isSubLevelLoaded(sl.subLevelId())) return null;

		Direction face = hit.face();
		BlockPos plotBlock = hit.nativeBlock();
		Vec3 plotHit = hit.nativeHit();
		Vec3 plotBendPos;

		if (plotBlock != null && face != null) {
			Vec3 plotRayEnd = sli.worldToPlot(sl.subLevelId(), top.toVec3d(), CONTRAPTION_PARTIAL_TICKS);
			java.util.List<net.minecraft.world.phys.AABB> plotBoxes = sli.getPlotCollisionBoxes(sl.subLevelId(), plotBlock);
			net.minecraft.world.phys.AABB hitBox = WrapEdgeFinder.findBoxContainingHit(plotBoxes, plotHit, face);
			Vec wrapBend = null;
			if (hitBox != null) {
				java.util.List<Direction> ranked = WrapEdgeFinder.rankedWrapFaces(null, null, hitBox, face, plotHit, plotRayEnd, plotBoxes);
				for (Direction wrapFace : ranked) {
					wrapBend = WrapEdgeFinder.computeBendPoint(hitBox, face, wrapFace, plotHit, plotRayEnd);
					break;
				}
			}
			if (wrapBend != null) {
				plotBendPos = new Vec3(wrapBend.x, wrapBend.y, wrapBend.z);
			} else {
				plotBendPos = plotHit.add(
						face.getStepX() * SUBLEVEL_BEND_OFFSET,
						face.getStepY() * SUBLEVEL_BEND_OFFSET,
						face.getStepZ() * SUBLEVEL_BEND_OFFSET);
			}
		} else {
			plotBendPos = plotHit.add(
					face.getStepX() * SUBLEVEL_BEND_OFFSET,
					face.getStepY() * SUBLEVEL_BEND_OFFSET,
					face.getStepZ() * SUBLEVEL_BEND_OFFSET);
		}

		Vec3 worldBend = sli.plotToWorld(sl.subLevelId(), plotBendPos, CONTRAPTION_PARTIAL_TICKS);
		Vec worldBendPos = new Vec(worldBend.x, worldBend.y, worldBend.z);
		Vec nativePos = new Vec(plotBendPos.x, plotBendPos.y, plotBendPos.z);

		if (index == 1 && hookAttachedToSubLevelBlock(sl.subLevelId(), plotBlock)) {
			return null;
		}
		if (hasAnySubLevelBendNear(sl.subLevelId(), worldBendPos)) {
			return null;
		}

		this.addBend(index, RopeBend.subLevel(sl.subLevelId(), nativePos, worldBendPos, null, face));
		return worldBendPos;
	}

	private boolean hookAttachedToSubLevelBlock(java.util.UUID subLevelId, BlockPos plotBlock) {
		if (plotBlock == null) return false;
		return this.hookEntity.attachment() instanceof HookAttachment.SubLevelBlock slb
				&& slb.subLevelId().equals(subLevelId)
				&& slb.plotBlock().equals(plotBlock);
	}

	private boolean hasAnySubLevelBendNear(java.util.UUID subLevelId, Vec candidateWorldPos) {
		for (RopeBend b : this.bends) {
			if (!(b.space instanceof AnchorSpace.SubLevel sl)) continue;
			if (!sl.subLevelId().equals(subLevelId)) continue;
			if (b.worldPos.sub(candidateWorldPos).length() < MOVING_HOST_DEDUP_RADIUS) return true;
		}
		return false;
	}

	private boolean hasAnyContraptionBendNear(int entityId, Vec candidateWorldPos) {
		for (RopeBend b : this.bends) {
			if (!(b.space instanceof AnchorSpace.Contraption c)) continue;
			if (c.entityId() != entityId) continue;
			if (b.worldPos.sub(candidateWorldPos).length() < MOVING_HOST_DEDUP_RADIUS) return true;
		}
		return false;
	}

	private boolean isMicroBend(Vec a, Vec b, Vec c) {
		Vec incoming = b.sub(a);
		Vec outgoing = c.sub(b);
		double il = incoming.length();
		double ol = outgoing.length();
		if (il < 1e-6 || ol < 1e-6) return false;
		return incoming.dot(outgoing) / (il * ol) > MIN_BEND_DEFLECTION_COS;
	}

	private static final double CONTRAPTION_BEND_OFFSET = 0.08;
	private static final double MOVING_HOST_DEDUP_RADIUS = 0.6;
	private static final double SUBLEVEL_BEND_OFFSET = 0.18;

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

	@Deprecated
	public void updateSegmentLegacy(Vec top, Vec prevtop, Vec bottom, Vec prevbottom, int index, int numberrecursions) {
		MultiSpaceRaycaster.MultiSpaceHit msHit = MultiSpaceRaycaster.raycast(
				this.hookEntity, this.world, bottom, top, CONTRAPTION_PARTIAL_TICKS);
		if (msHit == null) return;

		if (msHit.space() instanceof AnchorSpace.Contraption c) {
			insertContraptionBend(top, bottom, index, msHit, c);
			return;
		}

		if (msHit.space() instanceof AnchorSpace.SubLevel sl) {
			insertSubLevelBend(top, bottom, index, msHit, sl);
			return;
		}

		BlockHitResult bottomraytraceresult = GrappleModUtils.rayTraceBlocks(this.hookEntity, this.world, bottom, top);

        if (bottomraytraceresult != null) {
        	if (GrappleModUtils.rayTraceBlocks(this.hookEntity, this.world, prevbottom, prevtop) != null) {
        		return;
        	}

            Vec bottomhitvec = new Vec(bottomraytraceresult.getLocation());

            Direction bottomside = bottomraytraceresult.getDirection();
            Vec bottomnormal = this.getNormal(bottomside);

            double prevropelen = prevtop.sub(prevbottom).length();

            Vec cornerbound1 = bottomhitvec.add(bottomnormal.withMagnitude(-INTO_BLOCK));

            Vec bound_option1 = linePlaneIntersection(prevtop, prevbottom, cornerbound1, bottomnormal);
            Vec bound_option2 = linePlaneIntersection(top, prevtop, cornerbound1, bottomnormal);
            Vec bound_option3 = linePlaneIntersection(prevbottom, bottom, cornerbound1, bottomnormal);

            for (Vec cornerbound2 : new Vec[] {bound_option1, bound_option2, bound_option3}) {
            	if (cornerbound2 == null) {
            		continue;
            	}

            	BlockHitResult cornerraytraceresult = GrappleModUtils.rayTraceBlocks(this.hookEntity, this.world, cornerbound2, cornerbound1);
                if (cornerraytraceresult != null) {
                	Vec cornerhitpos = new Vec(cornerraytraceresult.getLocation());
                	Direction cornerside = cornerraytraceresult.getDirection();

                	if (!(cornerside == bottomside || cornerside.getOpposite() == bottomside)) {
                		Vec actualcorner = cornerhitpos.add(bottomnormal.withMagnitude(INTO_BLOCK));
                		Vec bend = actualcorner.add(bottomnormal.withMagnitude(BEND_OFFSET)).add(getNormal(cornerside).withMagnitude(BEND_OFFSET));
                		Vec topropevec = bend.sub(top);
                		Vec bottomropevec = bend.sub(bottom);

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

                		if(this.getDistToAnchor() + .2 > this.ropeLen) {
                			this.removeSegment(index);
                			continue;
                		}

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

	public void actuallyAddSegment(int index, Vec bendPoint, Direction bottomSide, Direction topSide) {
		this.actuallyAddSegment(index, bendPoint, NullableDirection.fromVanilla(bottomSide), NullableDirection.fromVanilla(topSide));
	}

	public void actuallyAddSegment(int index, Vec bendPoint, NullableDirection bottomSide, NullableDirection topSide) {
		this.addBend(index, RopeBend.world(bendPoint, topSide.toVanilla(), bottomSide.toVanilla()));
	}

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
		Vec bendpos = new Vec(bend.worldPos);
		if (bend.bottomSide != null)
			bendpos.mutableAdd(this.getNormal(bend.bottomSide).withMagnitude(-INTO_BLOCK * 2));
		if (bend.topSide != null)
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

	private void setEndpoint(int index, Vec pos) {
		this.bends.get(index).worldPos = pos;
	}

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

	public List<RopeBend> getBends() {
		return Collections.unmodifiableList(this.bends);
	}

	public double getCurrentRopeLength() {
		return this.ropeLen;
	}
}
