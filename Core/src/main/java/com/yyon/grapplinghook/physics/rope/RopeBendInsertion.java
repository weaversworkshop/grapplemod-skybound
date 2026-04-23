package com.yyon.grapplinghook.physics.rope;

import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import com.yyon.grapplinghook.physics.raycast.MultiSpaceRaycaster;
import com.yyon.grapplinghook.physics.raycast.WrapEdgeFinder;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

final class RopeBendInsertion {

    private static final double CONTRAPTION_BEND_OFFSET = 0.08;
    private static final double MOVING_HOST_DEDUP_RADIUS = 0.6;
    private static final double SUBLEVEL_BEND_OFFSET = 0.18;
    private static final double MIN_SEGMENT_LEN = 0.3;

    private static final float CONTRAPTION_PARTIAL_TICKS = 1.0f;

    private RopeBendInsertion() {}

    static Vec insertContraption(RopeSegmentHandler handler, int index,
                                 MultiSpaceRaycaster.MultiSpaceHit hit, AnchorSpace.Contraption c) {
        Direction face = hit.face();
        Vec worldBendPos = new Vec(
                hit.worldHit().x + face.getStepX() * CONTRAPTION_BEND_OFFSET,
                hit.worldHit().y + face.getStepY() * CONTRAPTION_BEND_OFFSET,
                hit.worldHit().z + face.getStepZ() * CONTRAPTION_BEND_OFFSET);

        Entity entity = handler.world.getEntity(c.entityId());
        if (entity == null) return null;

        if (wouldCreateShortSegment(handler, index, worldBendPos)) {
            return null;
        }

        if (hasAnyContraptionBendNear(handler, c.entityId(), worldBendPos)) {
            return null;
        }

        Vec3 nativeLocal = GrappleModIntegrations.getContraptionIntegration()
                .worldToLocal(entity, worldBendPos.toVec3d(), CONTRAPTION_PARTIAL_TICKS);
        Vec nativePos = new Vec(nativeLocal.x, nativeLocal.y, nativeLocal.z);

        handler.addBend(index, RopeBend.contraption(c.entityId(), nativePos, worldBendPos, null, face));
        return worldBendPos;
    }

    static Vec insertSubLevel(RopeSegmentHandler handler, Vec top, int index,
                              MultiSpaceRaycaster.MultiSpaceHit hit, AnchorSpace.SubLevel sl) {
        SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
        if (!sli.isSubLevelLoaded(sl.subLevelId())) return null;

        Direction face = hit.face();
        BlockPos plotBlock = hit.nativeBlock();
        Vec3 plotHit = hit.nativeHit();
        Vec3 plotBendPos;

        if (plotBlock != null && face != null) {
            Vec3 plotRayEnd = sli.worldToPlot(sl.subLevelId(), top.toVec3d(), CONTRAPTION_PARTIAL_TICKS);
            List<AABB> plotBoxes = sli.getPlotCollisionBoxes(sl.subLevelId(), plotBlock);
            AABB hitBox = WrapEdgeFinder.findBoxContainingHit(plotBoxes, plotHit, face);
            Vec wrapBend = null;
            if (hitBox != null) {
                List<Direction> ranked = WrapEdgeFinder.rankedWrapFaces(null, null, hitBox, face, plotHit, plotRayEnd, plotBoxes);
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

        if (wouldCreateShortSegment(handler, index, worldBendPos)) {
            return null;
        }

        if (hasAnySubLevelBendNear(handler, sl.subLevelId(), worldBendPos)) {
            return null;
        }

        handler.addBend(index, RopeBend.subLevel(sl.subLevelId(), nativePos, worldBendPos, null, face));
        return worldBendPos;
    }

    private static boolean wouldCreateShortSegment(RopeSegmentHandler handler, int index, Vec candidateWorldPos) {
        Vec neighborAbove = handler.bends.get(index - 1).worldPos;
        Vec neighborBelow = handler.bends.get(index).worldPos;
        return candidateWorldPos.sub(neighborAbove).length() < MIN_SEGMENT_LEN
                || candidateWorldPos.sub(neighborBelow).length() < MIN_SEGMENT_LEN;
    }

    private static boolean hasAnySubLevelBendNear(RopeSegmentHandler handler, UUID subLevelId, Vec candidateWorldPos) {
        for (RopeBend b : handler.bends) {
            if (!(b.space instanceof AnchorSpace.SubLevel sl)) continue;
            if (!sl.subLevelId().equals(subLevelId)) continue;
            if (b.worldPos.sub(candidateWorldPos).length() < MOVING_HOST_DEDUP_RADIUS) return true;
        }
        return false;
    }

    private static boolean hasAnyContraptionBendNear(RopeSegmentHandler handler, int entityId, Vec candidateWorldPos) {
        for (RopeBend b : handler.bends) {
            if (!(b.space instanceof AnchorSpace.Contraption c)) continue;
            if (c.entityId() != entityId) continue;
            if (b.worldPos.sub(candidateWorldPos).length() < MOVING_HOST_DEDUP_RADIUS) return true;
        }
        return false;
    }
}
