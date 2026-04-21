package com.weaversworkshop.grapplemod.compat.sable;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;

/**
 * Real client-side integration. Only instantiated when Sable is confirmed present
 * and {@link SableCompatModule} has already run (the main entrypoint fires before
 * the client entrypoint in Fabric's initialization order). Mirrors the server
 * tick-poll but skips the assemble/disassemble event dispatch — hook migration on
 * assembly is a server-side concern; the client just needs its UUID → SubLevel
 * cache kept in sync so {@code HookAttachment.SubLevelBlock.worldHitPoint} can
 * project plot coords to apparent world coords for rendering.
 */
public class SableClientModule {

    private static final Logger LOGGER = LogUtils.getLogger();

    public SableClientModule() {
        SableCompatModule parent = SableCompatModule.getInstance();
        if (parent == null) {
            LOGGER.error("[Grapple <-> Sable] Main module not yet initialized when client module started; "
                    + "client-side sub-level tracking will be DISABLED. Ropes to Sable blocks will misrender.");
            return;
        }
        SableSubLevelIntegration integration = parent.integration();
        LOGGER.info("[Grapple <-> Sable] Client module reusing integration instance from main module: {}",
                integration.getClass().getName());

        ClientTickEvents.END_WORLD_TICK.register(level -> {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) return;
            for (SubLevel sl : container.getAllSubLevels()) {
                integration.trackSubLevel(sl.getUniqueId(), sl, level);
            }
        });
        LOGGER.info("[Grapple <-> Sable] Client sub-level tracking registered on ClientTickEvents.END_WORLD_TICK.");
    }
}
