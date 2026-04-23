package com.yyon.grapplinghook.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

public interface SubLevelIntegration {

    boolean isSubLevelLoaded(UUID subLevelId);

    @Nullable UUID findSubLevelAlongRay(Vec3 rayStart, Vec3 rayEnd);

    @Nullable Vec3 raycastSubLevel(UUID subLevelId, Vec3 rayStart, Vec3 rayEnd, float partialTicks);

    record SubLevelRaycastHit(Vec3 worldHit, Direction face, Vec3 plotHit, BlockPos plotBlock) {}

    default @Nullable SubLevelRaycastHit raycastSubLevelDetailed(UUID subLevelId, Vec3 rayStart, Vec3 rayEnd, float partialTicks) {
        return null;
    }

    Vec3 plotToWorld(UUID subLevelId, Vec3 plotPoint, float partialTicks);

    Vec3 worldToPlot(UUID subLevelId, Vec3 worldPoint, float partialTicks);

    BlockPos worldToPlotBlock(UUID subLevelId, Vec3 worldPoint, float partialTicks);

    @Nullable BlockPos getCapturedPlotPos(UUID subLevelId, BlockPos worldPos);

    boolean isPlotBlockSolid(UUID subLevelId, BlockPos plotBlock);

    List<AABB> getPlotCollisionBoxes(UUID subLevelId, BlockPos plotBlock);

    @Nullable UUID findSubLevelForPlotBlock(BlockPos plotPos);

    boolean anyTrackedSubLevelOverlaps(AABB probe);

    default void forEachTrackedSubLevel(BiConsumer<UUID, AABB> visitor) {}
}
