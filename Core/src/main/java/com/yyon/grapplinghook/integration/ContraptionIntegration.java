package com.yyon.grapplinghook.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public interface ContraptionIntegration {

    boolean isContraption(Entity entity);

    @Nullable EntityHitResult findContraptionAlongRay(Level level, Vec3 rayStart, Vec3 rayEnd);

    @Nullable Vec3 raycastContraption(Entity contraption, Vec3 rayStart, Vec3 rayEnd, float partialTicks);

    default @Nullable ContraptionRaycastHit raycastContraptionDetailed(
            Entity contraption, Vec3 rayStart, Vec3 rayEnd, float partialTicks) {
        return null;
    }

    record ContraptionRaycastHit(Vec3 worldHit, Direction face, Vec3 localHit) {}

    Vec3 worldToLocal(Entity contraption, Vec3 worldPoint, float partialTicks);

    Vec3 localToWorld(Entity contraption, Vec3 localPoint, float partialTicks);

    @Nullable BlockPos getCapturedLocalPos(Entity contraption, BlockPos worldPos);
}
