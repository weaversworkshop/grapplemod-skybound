package com.yyon.grapplinghook.content.entity.grapplinghook;

import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

final class HookFlightController {

    private HookFlightController() {}

    /**
     * Runs the in-flight pre-tick scans: contraption ray + sub-level AABB scan.
     * Returns true if the caller should use {@link #manualProjectileStep} instead of {@code super.tick()}
     * (because the ray crosses a tracked sub-level AABB, and running Sable's patched projectile raycast
     * through plot-space chunks could hang the server).
     */
    static boolean runPreTickScans(GrapplinghookEntity hook, SubLevelIntegration sli) {
        if (hook.attachment() == null && !hook.level().isClientSide) {
            Vec3 rayStart = hook.position();
            Vec3 rayEnd = rayStart.add(hook.getDeltaMovement());
            @Nullable EntityHitResult contraptionHit = GrappleModIntegrations
                    .getContraptionIntegration()
                    .findContraptionAlongRay(hook.level(), rayStart, rayEnd);
            if (contraptionHit != null) {
                hook.onHit(contraptionHit);
            }
        }

        // Sable's ProjectileUtilMixin patches vanilla projectile collision; if its ray crosses a tracked sub-level AABB it can hang the server.
        boolean anySubLevelCrossed = false;
        if (hook.attachment() == null) {
            Vec3 rayStart = hook.position();
            Vec3 rayEnd = rayStart.add(hook.getDeltaMovement());

            UUID[] bestUuid = { null };
            SubLevelIntegration.SubLevelRaycastHit[] bestHit = { null };
            double[] bestDistSq = { Double.POSITIVE_INFINITY };
            boolean[] crossedRef = { false };
            boolean isServer = !hook.level().isClientSide;

            sli.forEachTrackedSubLevelSwept((uuid, aabb) -> {
                if (!aabb.clip(rayStart, rayEnd).isPresent()) return;
                crossedRef[0] = true;
                if (!isServer) return;
                SubLevelIntegration.SubLevelRaycastHit hit = sli.raycastSubLevelDetailed(
                        uuid, rayStart, rayEnd, GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);
                if (hit == null) return;
                double distSq = hit.worldHit().distanceToSqr(rayStart);
                if (distSq < bestDistSq[0]) {
                    bestDistSq[0] = distSq;
                    bestUuid[0] = uuid;
                    bestHit[0] = hit;
                }
            });

            anySubLevelCrossed = crossedRef[0];

            if (bestHit[0] != null) {
                hook.serverAttach(
                        new HookAttachment.SubLevelBlock(bestUuid[0], bestHit[0].plotBlock(), bestHit[0].plotHit()),
                        true);
                hook.setDeltaMovement(0, 0, 0);
                anySubLevelCrossed = false;
            }
        }

        return anySubLevelCrossed;
    }

    static void manualProjectileStep(GrapplinghookEntity hook) {
        Vec3 delta = hook.getDeltaMovement();
        Vec3 start = hook.position();
        Vec3 end = start.add(delta);

        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                hook.level(), hook, start, end,
                hook.getBoundingBox().expandTowards(delta).inflate(1.0),
                e -> !e.isSpectator() && e.isAlive() && e.isPickable() && !(e instanceof GrapplinghookEntity));

        BlockHitResult blockHit = GrappleModUtils.rayTraceBlocks(hook, hook.level(), new Vec(start), new Vec(end));

        HitResult hit = null;
        if (entityHit != null && blockHit != null) {
            double entityDistSq = entityHit.getLocation().distanceToSqr(start);
            double blockDistSq = blockHit.getLocation().distanceToSqr(start);
            hit = entityDistSq < blockDistSq ? entityHit : blockHit;
        } else if (entityHit != null) {
            hit = entityHit;
        } else if (blockHit != null) {
            hit = blockHit;
        }

        if (hit != null) {
            hook.onHit(hit);
            if (hook.isRemoved()) return;
            if (hook.attachment() != null) return;
        }

        hook.setPos(end.x, end.y, end.z);

        float drag = hook.isInWater() ? 0.8F : 0.99F;
        double gravity = hook.getGravity();
        hook.setDeltaMovement(delta.x * drag, (delta.y - gravity) * drag, delta.z * drag);
    }
}
