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

/**
 * Single-entry raycast that considers all registered "spaces" along a world-space
 * segment and returns the closest hit across them.
 *
 * <p>Spaces today: WORLD (vanilla blocks via {@link GrappleModUtils#rayTraceBlocks})
 * and CONTRAPTION (per-block hit in a Create contraption's local frame, via
 * {@link ContraptionIntegration#raycastContraptionDetailed}). SUBLEVEL support is
 * planned for Phase 3.</p>
 *
 * <p>Conceptually we could partition the ray into per-space spans and raycast each
 * span in isolation. In practice we just raycast against each space over the full
 * segment and keep the closest hit — cheaper to reason about, and the wasted work
 * (ray continuing past a contraption into WORLD when the contraption hit is closer)
 * is negligible given the small number of contraptions ropes typically touch.</p>
 */
public final class MultiSpaceRaycaster {

    /**
     * Union type for a raycast that resolved in any of the supported spaces.
     *
     * @param worldHit   world-space hit location
     * @param face       outward-pointing face direction struck
     * @param space      which space the hit was resolved in (drives how a resulting
     *                   rope bend is stored / refreshed per tick)
     * @param nativeHit  position in the hit space's native coordinates — equals
     *                   {@code worldHit} for {@link AnchorSpace.World}, else the
     *                   contraption-local / plot-space point
     * @param nativeBlock native-space {@link BlockPos} of the block struck, when the
     *                   integration provided it. Populated for SUBLEVEL hits (plot
     *                   coords) so Core can do edge-wrap placement on unit-cube
     *                   plot blocks. Null for WORLD / CONTRAPTION hits.
     */
    public record MultiSpaceHit(Vec3 worldHit, Direction face, AnchorSpace space, Vec3 nativeHit,
                                @Nullable BlockPos nativeBlock) {}

    /**
     * Broad-phase inflation (blocks) for finding contraption entities near the
     * ray. A contraption's {@link Entity#getBoundingBox()} reflects only its
     * current rotated block positions, which rotate in and out of any tight ray
     * AABB each tick — dropping contraptions from the candidate list mid-swing.
     * Mirrors {@code CreateContraptionIntegration.CONTRAPTION_SEARCH_RADIUS}.
     * Narrow-phase {@link ContraptionIntegration#raycastContraptionDetailed}
     * does the real per-block test, so loose broad-phase is cheap.
     */
    private static final double CONTRAPTION_BROAD_PHASE_INFLATE = 40.0;

    private MultiSpaceRaycaster() {}

    /**
     * Raycast through all spaces simultaneously. {@code context} is the entity
     * whose raycast we're representing (typically the hook) — passed to vanilla
     * {@link GrappleModUtils#rayTraceBlocks} so any entity-filtering in its clip
     * context is respected. {@code partialTicks} is forwarded to
     * {@link ContraptionIntegration#raycastContraptionDetailed} for rotation-aware
     * sampling on moving contraptions.
     */
    public static @Nullable MultiSpaceHit raycast(Entity context, Level level, Vec rayStart, Vec rayEnd, float partialTicks) {
        MultiSpaceHit closest = null;
        double closestDistSq = Double.MAX_VALUE;

        // WORLD span — vanilla raycast over the full segment. GrappleModUtils.rayTraceBlocks
        // now walks voxels via Level.getBlockState directly rather than through
        // BlockGetter.clip, so Sable's mixin on the latter never fires — no need to
        // partition the ray around sub-level AABBs.
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

        // CONTRAPTION spans — find contraption entities whose bounding box intersects
        // the ray's swept AABB, then ask each for a detailed hit. A contraption whose
        // integration returns null (e.g. the integration-less noop, or a compat
        // module that hasn't implemented the detailed overload) is silently skipped.
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

        // SUBLEVEL spans — enumerate tracked sub-levels and ask each integration
        // for a detailed hit. Sub-levels have no Minecraft entity to query via
        // Level.getEntities, so the integration exposes its tracked set via
        // forEachTrackedSubLevel. Broad-phase: skip sub-levels whose apparent
        // AABB doesn't intersect the (inflated) ray AABB. Narrow-phase: the
        // integration's raycastSubLevelDetailed walks plot-space voxels.
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
