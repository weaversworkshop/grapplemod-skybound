package com.weaversworkshop.grapplemod.compat.create;

import com.mojang.logging.LogUtils;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
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
    }
}
