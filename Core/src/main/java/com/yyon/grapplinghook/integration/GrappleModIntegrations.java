package com.yyon.grapplinghook.integration;

import com.yyon.grapplinghook.GrappleMod;

/**
 * Registry for optional third-party mod integrations. Compat modules call into
 * this from their module class once they've confirmed the target mod is present.
 * Core queries the registered integrations to decide whether to enable
 * compat-only code paths.
 */
public final class GrappleModIntegrations {

    private static ContraptionIntegration contraptionIntegration = new NoopContraptionIntegration();

    private GrappleModIntegrations() {}

    /**
     * Install a {@link ContraptionIntegration}. Typically called exactly once from
     * a compat module's module class when it loads. Passing {@code null} clears
     * the registration back to the no-op default.
     */
    public static void setContraptionIntegration(ContraptionIntegration impl) {
        contraptionIntegration = (impl != null) ? impl : new NoopContraptionIntegration();
        GrappleMod.LOGGER.info("Contraption integration installed: {}",
                contraptionIntegration.getClass().getName());
    }

    public static ContraptionIntegration getContraptionIntegration() {
        return contraptionIntegration;
    }

    public static boolean hasContraptionIntegration() {
        return !(contraptionIntegration instanceof NoopContraptionIntegration);
    }
}
