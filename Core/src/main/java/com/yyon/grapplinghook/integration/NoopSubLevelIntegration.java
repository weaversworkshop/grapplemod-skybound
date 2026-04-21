package com.yyon.grapplinghook.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Default no-op implementation. Used when no Sable compat module is installed —
 * every query reports "no sub-levels exist," which keeps the Core grapple logic
 * unaffected.
 */
public final class NoopSubLevelIntegration implements SubLevelIntegration {

    @Override
    public boolean isSubLevelLoaded(UUID subLevelId) {
        return false;
    }

    @Override
    public @Nullable UUID findSubLevelAlongRay(Vec3 rayStart, Vec3 rayEnd) {
        return null;
    }

    @Override
    public @Nullable Vec3 raycastSubLevel(UUID subLevelId, Vec3 rayStart, Vec3 rayEnd, float partialTicks) {
        return null;
    }

    @Override
    public Vec3 plotToWorld(UUID subLevelId, Vec3 plotPoint, float partialTicks) {
        return plotPoint;
    }

    @Override
    public Vec3 worldToPlot(UUID subLevelId, Vec3 worldPoint, float partialTicks) {
        return worldPoint;
    }

    @Override
    public BlockPos worldToPlotBlock(UUID subLevelId, Vec3 worldPoint, float partialTicks) {
        return BlockPos.containing(worldPoint);
    }

    @Override
    public @Nullable BlockPos getCapturedPlotPos(UUID subLevelId, BlockPos worldPos) {
        return null;
    }

    @Override
    public @Nullable UUID findSubLevelForPlotBlock(BlockPos plotPos) {
        return null;
    }

    @Override
    public boolean anyTrackedSubLevelOverlaps(AABB probe) {
        return false;
    }
}
