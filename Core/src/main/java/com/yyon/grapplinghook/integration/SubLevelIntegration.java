package com.yyon.grapplinghook.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * SPI for mods that contribute UUID-keyed "sub-level" moving structures — blocks
 * that live in a far-away plot region of the level and are projected to apparent
 * world-space via a pose transform. Used by the Sable physics engine and
 * everything built on top of it (Create: Aeronautics, Offroad, etc.).
 *
 * <p>Sibling to {@link ContraptionIntegration}, but fundamentally different in
 * shape: sub-levels are identified by a persistent {@link UUID} rather than a
 * Minecraft entity id, and their transform is a world/plot pose rather than a
 * Minecraft entity transform. The compat module tracks {@code UUID → Level}
 * internally (via server-tick polling of its own container registry) so the
 * SPI surface stays level-free.</p>
 *
 * <p>Coordinate conventions:</p>
 * <ul>
 *   <li>World-space: the usual Minecraft world coordinates (what the player sees).</li>
 *   <li>Plot-space: the far-away region where sub-level blocks are actually stored.
 *       A sub-level's {@code Pose3d} projects plot-space to world-space every tick.</li>
 * </ul>
 */
public interface SubLevelIntegration {

    /** True iff a sub-level with this UUID is currently loaded and tracked. */
    boolean isSubLevelLoaded(UUID subLevelId);

    /**
     * Scan for any sub-level whose apparent (world-space) bounding box the ray
     * segment passes through. Used by the hook's broad-phase collision pass,
     * since sub-levels have no pickable Minecraft entity for vanilla raycasts
     * to hit. If multiple intersect, returns the closest to {@code rayStart}.
     *
     * @return the sub-level's UUID, or {@code null} if none found.
     */
    @Nullable UUID findSubLevelAlongRay(Vec3 rayStart, Vec3 rayEnd);

    /**
     * Per-block raycast against the sub-level's internal block structure.
     *
     * @return the <em>apparent-world-space</em> hit point on an actual block
     *         face, or {@code null} if the ray misses every block.
     */
    @Nullable Vec3 raycastSubLevel(UUID subLevelId, Vec3 rayStart, Vec3 rayEnd, float partialTicks);

    /** Plot-space point → apparent world-space, via the sub-level's current pose. */
    Vec3 plotToWorld(UUID subLevelId, Vec3 plotPoint, float partialTicks);

    /** Apparent world-space point → plot-space, via the sub-level's inverse pose. */
    Vec3 worldToPlot(UUID subLevelId, Vec3 worldPoint, float partialTicks);

    /** Floor of {@link #worldToPlot} to a {@link BlockPos} in plot coords. */
    BlockPos worldToPlotBlock(UUID subLevelId, Vec3 worldPoint, float partialTicks);

    /**
     * Check whether {@code worldPos} corresponds to a real block in this sub-level's
     * plot storage. Used to migrate hooks anchored to a static world block onto a
     * sub-level that just absorbed it during assembly.
     *
     * @return the captured block's position in plot-space, or {@code null} if the
     *         sub-level does not contain a block at that (projected) position.
     */
    @Nullable BlockPos getCapturedPlotPos(UUID subLevelId, BlockPos worldPos);

    /**
     * Reverse lookup: given a block position that lives in plot coordinates (e.g. one
     * returned by Sable's patched vanilla projectile raycast), find the sub-level UUID
     * whose plot region contains it. Used when a vanilla hit result smuggles plot
     * coords back into the hook's collision handler and we need to recover the UUID
     * to build a proper {@code HookAttachment.SubLevelBlock}.
     *
     * @return the owning sub-level's UUID, or {@code null} if no tracked sub-level
     *         claims that plot block.
     */
    @Nullable UUID findSubLevelForPlotBlock(BlockPos plotPos);

    /**
     * Cheap O(n) test: does any tracked sub-level's apparent-world AABB intersect
     * {@code probe}? Used as a gate for the in-flight hook to skip
     * {@code super.tick()}'s vanilla projectile raycast when near a sub-level,
     * since that raycast routes through Sable's {@code ProjectileUtilMixin} and
     * can walk millions of voxels in plot space (the same class of hang as
     * {@code BlockGetter.clip} does for rope wrapping).
     */
    boolean anyTrackedSubLevelOverlaps(AABB probe);
}
