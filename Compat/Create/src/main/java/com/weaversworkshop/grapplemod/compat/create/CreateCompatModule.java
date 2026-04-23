package com.weaversworkshop.grapplemod.compat.create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.integration.ContraptionIntegration;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.physics.ServerHookEntityTracker;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Real integration module. Only instantiated when Create is confirmed present
 * by {@link CreateCompatEntrypoint}. Any {@code com.simibubi.create.*} imports
 * belong in this class (or in {@link CreateContraptionIntegration}), not in the
 * entrypoint.
 */
public class CreateCompatModule {

    private static final Logger LOGGER = LogUtils.getLogger();

    public CreateCompatModule() {
        LOGGER.info("[Grapple <-> Create] Integration module initializing.");
        GrappleModIntegrations.setContraptionIntegration(new CreateContraptionIntegration());

        // Fires the moment a freshly-assembled contraption joins the server world.
        // We use it to migrate any hook anchored to a block the contraption just
        // captured onto the contraption itself, so the player isn't left hanging
        // at the now-empty block position.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof AbstractContraptionEntity) {
                onContraptionAssembled(entity);
            }
        });

        // Fires on chunk unload AND actual disassembly. We only care about the latter —
        // a DISCARDED removal reason signals Create has placed blocks back into the world
        // and the entity is being cleaned up. Chunk unload (UNLOADED_*) should leave the
        // hook's contraption reference alone so it can resume when the chunk comes back.
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (!(entity instanceof AbstractContraptionEntity ace)) return;
            var reason = ace.getRemovalReason();
            if (reason == null || !reason.shouldDestroy()) return;
            onContraptionDisassembled(entity);
        });
    }

    private static void onContraptionAssembled(Entity contraptionEntity) {
        ContraptionIntegration ci = GrappleModIntegrations.getContraptionIntegration();
        if (ci == null || !ci.isContraption(contraptionEntity)) return;

        Level contraptionLevel = contraptionEntity.level();
        if (contraptionLevel.isClientSide) return;

        for (GrapplinghookEntity hook : ServerHookEntityTracker.getAllTrackedHooks()) {
            if (hook == null || !hook.isAlive()) continue;
            if (hook.level() != contraptionLevel) continue;
            if (!(hook.attachment() instanceof HookAttachment.Block block)) continue;

            BlockPos localKey = ci.getCapturedLocalPos(contraptionEntity, block.pos());
            if (localKey == null) continue;

            Vec3 localOffset = ci.worldToLocal(contraptionEntity, block.subHitPoint(), GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);

            hook.reattachToContraption(contraptionEntity, localOffset, localKey);
        }
    }

    private static void onContraptionDisassembled(Entity contraptionEntity) {
        ContraptionIntegration ci = GrappleModIntegrations.getContraptionIntegration();
        if (ci == null || !ci.isContraption(contraptionEntity)) return;

        Level level = contraptionEntity.level();
        if (level.isClientSide) return;

        for (GrapplinghookEntity hook : ServerHookEntityTracker.getAllTrackedHooks()) {
            if (hook == null || !hook.isAlive()) continue;
            if (hook.level() != level) continue;
            if (!(hook.attachment() instanceof HookAttachment.ContraptionBlock cb)) continue;
            if (cb.entity() != contraptionEntity) continue;

            BlockPos localBlock = cb.localBlockPos();
            if (localBlock == null) {
                hook.detachFromContraption();
                continue;
            }

            Vec3 localCenter = new Vec3(localBlock.getX() + 0.5, localBlock.getY() + 0.5, localBlock.getZ() + 0.5);
            Vec3 worldCenter = ci.localToWorld(contraptionEntity, localCenter, GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);
            BlockPos candidate = BlockPos.containing(worldCenter);

            BlockState state = level.getBlockState(candidate);
            Vec3 hookPos = hook.position();
            double dist = GrapplinghookEntity.distancePointToAabb(hookPos, new AABB(candidate));

            if (state.isAir() || dist > GrapplinghookEntity.DISASSEMBLY_REANCHOR_MAX_DIST) {
                hook.detachFromContraption();
                continue;
            }

            hook.reattachToBlock(candidate, hookPos);
        }
    }
}
