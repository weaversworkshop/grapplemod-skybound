package com.weaversworkshop.grapplemod.compat.create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
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
                GrapplinghookEntity.onContraptionAssembled(entity);
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
            GrapplinghookEntity.onContraptionDisassembled(entity);
        });
    }
}
