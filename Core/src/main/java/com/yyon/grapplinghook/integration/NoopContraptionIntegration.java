package com.yyon.grapplinghook.integration;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Default no-op implementation. Used when no compat module has registered
 * support — every query falls back to "nothing is a contraption."
 */
public final class NoopContraptionIntegration implements ContraptionIntegration {

    @Override
    public boolean isContraption(Entity entity) {
        return false;
    }

    @Override
    public @Nullable EntityHitResult findContraptionAlongRay(Level level, Vec3 rayStart, Vec3 rayEnd) {
        return null;
    }

    @Override
    public @Nullable Vec3 raycastContraption(Entity contraption, Vec3 rayStart, Vec3 rayEnd, float partialTicks) {
        return null;
    }

    @Override
    public Vec3 worldToLocal(Entity contraption, Vec3 worldPoint, float partialTicks) {
        return worldPoint;
    }

    @Override
    public Vec3 localToWorld(Entity contraption, Vec3 localPoint, float partialTicks) {
        return localPoint;
    }
}
