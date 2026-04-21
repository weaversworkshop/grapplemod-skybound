package com.weaversworkshop.grapplemod.compat.sable;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * Client-side counterpart to {@link SableCompatEntrypoint}. Registers a client-tick
 * listener that populates the {@link SableSubLevelIntegration}'s {@code UUID → SubLevel}
 * cache on the client, so the hook's client-side {@code worldHitPoint} lookups can
 * actually project plot coords to apparent world coords (without this, the rope renders
 * pointing to the plot region millions of blocks away).
 */
public class SableClientCompatEntrypoint implements ClientModInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Grapple <-> Sable] Client entrypoint running.");

        if (!FabricLoader.getInstance().isModLoaded("sable")) {
            LOGGER.info("[Grapple <-> Sable] 'sable' not installed; skipping client integration.");
            return;
        }

        LOGGER.info("[Grapple <-> Sable] 'sable' detected; loading client integration module.");

        try {
            Class.forName("com.weaversworkshop.grapplemod.compat.sable.SableClientModule")
                    .getConstructor()
                    .newInstance();
            LOGGER.info("[Grapple <-> Sable] Client integration module loaded SUCCESSFULLY.");
        } catch (Exception err) {
            LOGGER.error("[Grapple <-> Sable] FAILED to initialize client integration module — "
                    + "client-side sub-level tracking will be disabled; ropes to Sable blocks will misrender.",
                    err);
        }
    }
}
