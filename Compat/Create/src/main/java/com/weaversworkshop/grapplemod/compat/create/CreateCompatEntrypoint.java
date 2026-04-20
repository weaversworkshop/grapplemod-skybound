package com.weaversworkshop.grapplemod.compat.create;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public class CreateCompatEntrypoint implements ModInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        if (!FabricLoader.getInstance().isModLoaded("create")) {
            LOGGER.info("[Grapple <-> Create] 'create' not installed; skipping integration.");
            return;
        }

        LOGGER.info("[Grapple <-> Create] 'create' detected; loading integration module.");

        try {
            // Reflective load keeps Create-touching code off the classpath unless
            // Create is actually present. If someone runs this jar without Create,
            // the JVM never tries to resolve CreateCompatModule's imports.
            Class.forName("com.weaversworkshop.grapplemod.compat.create.CreateCompatModule")
                    .getConstructor()
                    .newInstance();
        } catch (Exception err) {
            LOGGER.error("[Grapple <-> Create] Failed to initialize integration module", err);
        }
    }
}
