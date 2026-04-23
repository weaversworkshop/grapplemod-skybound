package com.yyon.grapplinghook.physics.raycast;

import com.yyon.grapplinghook.integration.ContraptionIntegration;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import com.yyon.grapplinghook.physics.AnchorSpace;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class MultiSpaceRaycaster {

    public record MultiSpaceHit(Vec3 worldHit, Direction face, AnchorSpace space, Vec3 nativeHit,
                                @Nullable BlockPos nativeBlock) {}

    private static final double CONTRAPTION_BROAD_PHASE_INFLATE = 40.0;

    private MultiSpaceRaycaster() {}

    public static @Nullable MultiSpaceHit raycast(Entity context, Level level, Vec rayStart, Vec rayEnd, float partialTicks) {
        MultiSpaceHit closest = null;
        double closestDistSq = Double.MAX_VALUE;

        BlockHitResult worldHit = GrappleModUtils.rayTraceBlocks(context, level, rayStart, rayEnd);
        if (worldHit != null) {
            Vec3 loc = worldHit.getLocation();
            double distSq = loc.distanceToSqr(rayStart.toVec3d());
            closest = new MultiSpaceHit(
                    loc,
                    worldHit.getDirection(),
                    AnchorSpace.World.INSTANCE,
                    loc,
                    worldHit.getBlockPos());
            closestDistSq = distSq;
        }

        ContraptionIntegration ci = GrappleModIntegrations.getContraptionIntegration();
        if (GrappleModIntegrations.hasContraptionIntegration()) {
            AABB searchBox = new AABB(rayStart.toVec3d(), rayEnd.toVec3d()).inflate(CONTRAPTION_BROAD_PHASE_INFLATE);
            List<Entity> contraptions = level.getEntities(context, searchBox, ci::isContraption);
            for (Entity contraption : contraptions) {
                ContraptionIntegration.ContraptionRaycastHit hit = ci.raycastContraptionDetailed(
                        contraption, rayStart.toVec3d(), rayEnd.toVec3d(), partialTicks);
                if (hit == null) continue;
                double distSq = hit.worldHit().distanceToSqr(rayStart.toVec3d());
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closest = new MultiSpaceHit(
                            hit.worldHit(),
                            hit.face(),
                            new AnchorSpace.Contraption(contraption.getId()),
                            hit.localHit(),
                            null);
                }
            }
        }

        if (GrappleModIntegrations.hasSubLevelIntegration()) {
            SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
            AABB searchBox = new AABB(rayStart.toVec3d(), rayEnd.toVec3d()).inflate(CONTRAPTION_BROAD_PHASE_INFLATE);
            double[] closestBox = { closestDistSq };
            MultiSpaceHit[] closestRef = { closest };
            sli.forEachTrackedSubLevel((uuid, aabb) -> {
                if (!aabb.intersects(searchBox)) return;
                SubLevelIntegration.SubLevelRaycastHit hit = sli.raycastSubLevelDetailed(
                        uuid, rayStart.toVec3d(), rayEnd.toVec3d(), partialTicks);
                if (hit == null) return;
                double distSq = hit.worldHit().distanceToSqr(rayStart.toVec3d());
                if (distSq < closestBox[0]) {
                    closestBox[0] = distSq;
                    closestRef[0] = new MultiSpaceHit(
                            hit.worldHit(),
                            hit.face(),
                            new AnchorSpace.SubLevel(uuid),
                            hit.plotHit(),
                            hit.plotBlock());
                }
            });
            closest = closestRef[0];
            closestDistSq = closestBox[0];
        }

        return closest;
    }
}
