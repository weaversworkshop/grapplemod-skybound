package com.yyon.grapplinghook.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * SPI for mods that contribute moving, rotatable "contraption-like" entities
 * (Create's mechanical pistons / bearings, etc.). Compat modules implement this
 * and register via {@link GrappleModIntegrations#setContraptionIntegration}.
 *
 * <p>Core holds zero knowledge of the underlying mod — everything goes through
 * this interface so the grapple logic stays mod-agnostic.</p>
 *
 * <p>Coordinate conventions:</p>
 * <ul>
 *   <li>World-space: the usual Minecraft world coordinates.</li>
 *   <li>Contraption-local: coordinates relative to the contraption's anchor,
 *       with its current rotation "undone." Points in this space are fixed
 *       relative to the moving structure.</li>
 * </ul>
 */
public interface ContraptionIntegration {

    /** Should this entity use the local-offset attach path? */
    boolean isContraption(Entity entity);

    /**
     * Scan for any contraption entity whose bounding box the ray segment passes
     * through. Used to work around contraptions typically reporting
     * {@code isPickable() == false}, which causes vanilla projectile raycasts
     * to filter them out. If multiple intersect, returns the closest to
     * {@code rayStart}.
     *
     * @return an {@link EntityHitResult} with the contraption entity and the
     *         ray's entry point on its AABB, or {@code null} if none found.
     */
    @Nullable EntityHitResult findContraptionAlongRay(Level level, Vec3 rayStart, Vec3 rayEnd);

    /**
     * Raycast against the contraption's internal block structure (per-block
     * precision, not just the bounding AABB).
     *
     * @return the world-space hit point on an actual block face, or {@code null}
     *         if the ray misses every block in the structure.
     */
    @Nullable Vec3 raycastContraption(Entity contraption, Vec3 rayStart, Vec3 rayEnd, float partialTicks);

    /** World-space point → contraption-local, incorporating current rotation + translation. */
    Vec3 worldToLocal(Entity contraption, Vec3 worldPoint, float partialTicks);

    /** Contraption-local point → world-space, incorporating current rotation + translation. */
    Vec3 localToWorld(Entity contraption, Vec3 localPoint, float partialTicks);

    /**
     * Check whether {@code worldPos} is one of the blocks that this contraption captured
     * during assembly. Used to migrate hooks anchored to a static block onto the
     * contraption that just absorbed it.
     *
     * @return the block's key in the contraption's local block map, or {@code null}
     *         if the contraption does not contain that block.
     */
    @Nullable BlockPos getCapturedLocalPos(Entity contraption, BlockPos worldPos);
}
