package com.yyon.grapplinghook.content.entity.grapplinghook;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.physics.ServerHookEntityTracker;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Function;
import java.util.function.Predicate;

public final class HookHostDisassembly {

    private HookHostDisassembly() {}

    /**
     * Shared reanchor-after-host-disappeared flow used by compat modules (Create contraption
     * disassembly, Sable sub-level removal).
     *
     * <p>For every tracked hook on {@code level} whose attachment is of {@code attachmentType}
     * and matches {@code belongsToHost}: transform the attachment's local-space block position
     * through {@code localCenterToWorld}, and either reattach the hook to that world block
     * (if the block is solid and close enough to the hook) or detach. Per-hook failures are
     * logged with {@code logTag} and treated as "detach this one and keep going."
     */
    public static <A extends HookAttachment> void reanchorAfterHostGone(
            Level level,
            Class<A> attachmentType,
            Predicate<A> belongsToHost,
            Function<A, BlockPos> localBlockOf,
            Function<Vec3, Vec3> localCenterToWorld,
            String logTag) {
        if (level.isClientSide) return;

        for (GrapplinghookEntity hook : ServerHookEntityTracker.getAllTrackedHooks()) {
            try {
                if (hook == null || !hook.isAlive()) continue;
                if (hook.level() != level) continue;

                HookAttachment attachment = hook.attachment();
                if (!attachmentType.isInstance(attachment)) continue;
                A typed = attachmentType.cast(attachment);
                if (!belongsToHost.test(typed)) continue;

                BlockPos localBlock = localBlockOf.apply(typed);
                if (localBlock == null) {
                    hook.detachFromContraption();
                    continue;
                }

                Vec3 localCenter = new Vec3(localBlock.getX() + 0.5, localBlock.getY() + 0.5, localBlock.getZ() + 0.5);
                Vec3 worldCenter = localCenterToWorld.apply(localCenter);
                BlockPos candidate = BlockPos.containing(worldCenter);

                BlockState state = level.getBlockState(candidate);
                Vec3 hookPos = hook.position();
                double dist = HookAnchorMigration.distancePointToAabb(hookPos, new AABB(candidate));

                if (state.isAir() || dist > HookAnchorMigration.DISASSEMBLY_REANCHOR_MAX_DIST) {
                    hook.detachFromContraption();
                    continue;
                }

                hook.reattachToBlock(candidate, hookPos);
            } catch (Throwable err) {
                GrappleMod.LOGGER.error("{} reanchor for hook {} failed; detaching as fallback",
                        logTag, hook != null ? hook.getId() : "null", err);
                if (hook != null && hook.isAlive()) {
                    try { hook.detachFromContraption(); } catch (Throwable ignored) {}
                }
            }
        }
    }
}
