package com.weaversworkshop.grapplemod.compat.sable;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public class SableCompatEntrypoint implements ModInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOGGER.info("[Grapple <-> Sable] Entrypoint running (main / common).");

        if (!FabricLoader.getInstance().isModLoaded("sable")) {
            LOGGER.info("[Grapple <-> Sable] 'sable' not installed; skipping integration. "
                    + "SubLevelIntegration SPI will stay at the no-op default.");
            return;
        }

        LOGGER.info("[Grapple <-> Sable] 'sable' detected; loading integration module.");

        try {
            // Reflective load keeps Sable-touching code off the classpath unless
            // Sable is actually present.
            Class.forName("com.weaversworkshop.grapplemod.compat.sable.SableCompatModule")
                    .getConstructor()
                    .newInstance();
            LOGGER.info("[Grapple <-> Sable] SPI integration module loaded SUCCESSFULLY.");
        } catch (Exception err) {
            LOGGER.error("[Grapple <-> Sable] FAILED to initialize SPI integration module — "
                    + "SubLevelIntegration will stay at no-op; grappling Sable objects will misbehave.",
                    err);
        }
    }
}
