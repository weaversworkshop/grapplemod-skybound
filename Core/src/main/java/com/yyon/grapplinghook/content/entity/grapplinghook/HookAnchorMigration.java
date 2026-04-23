package com.yyon.grapplinghook.content.entity.grapplinghook;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class HookAnchorMigration {

    public static final double DISASSEMBLY_REANCHOR_MAX_DIST = 1.47;

    private HookAnchorMigration() {}

    public static boolean tryMigrate(GrapplinghookEntity hook, SubLevelIntegration sli, HookAttachment.SubLevelBlock slb) {
        Vec3 lastWorldPos;
        try {
            lastWorldPos = slb.worldHitPoint(GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);
        } catch (Throwable ignored) {
            lastWorldPos = hook.position();
        }
        BlockPos worldCandidate = BlockPos.containing(lastWorldPos);

        UUID[] winner = { null };
        BlockPos[] winnerPlotBlock = { null };
        final Vec3 probePoint = lastWorldPos;
        sli.forEachTrackedSubLevel((uuid, aabb) -> {
            if (winner[0] != null) return;
            if (uuid.equals(slb.subLevelId())) return;
            if (!aabb.contains(probePoint)) return;
            BlockPos plotBlock = sli.worldToPlotBlock(uuid, probePoint, GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);
            if (sli.isPlotBlockSolid(uuid, plotBlock)) {
                winner[0] = uuid;
                winnerPlotBlock[0] = plotBlock;
            }
        });

        if (winner[0] != null) {
            Vec3 newPlotHit = sli.worldToPlot(winner[0], lastWorldPos, GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);
            GrappleMod.LOGGER.info("[Grapple <-> Sable] Sub-level anchor migrated hookId={} {} → {} (plotBlock {})",
                    hook.getId(), slb.subLevelId(), winner[0], winnerPlotBlock[0]);
            hook.reattachToSubLevel(winner[0], winnerPlotBlock[0], newPlotHit);
            return true;
        }

        BlockState worldState = hook.level().getBlockState(worldCandidate);
        double dist = distancePointToAabb(hook.position(), new AABB(worldCandidate));
        if (!worldState.isAir() && dist <= DISASSEMBLY_REANCHOR_MAX_DIST) {
            GrappleMod.LOGGER.info("[Grapple <-> Sable] Sub-level anchor lost, falling back to world block for hookId={} at {}",
                    hook.getId(), worldCandidate);
            hook.reattachToBlock(worldCandidate, lastWorldPos);
            return true;
        }
        return false;
    }

    public static double distancePointToAabb(Vec3 p, AABB box) {
        double dx = Math.max(Math.max(box.minX - p.x, 0), p.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - p.y, 0), p.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - p.z, 0), p.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
