package com.weaversworkshop.grapplemod.compat.sable;

import com.mojang.logging.LogUtils;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Real integration module. Only instantiated when Sable is confirmed present
 * by {@link SableCompatEntrypoint}. Any {@code dev.ryanhcode.sable.*} imports
 * belong in this class (or in {@link SableSubLevelIntegration} / the mixin),
 * not in the entrypoint.
 */
public class SableCompatModule {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Shared handle so the client-side module can grab the same integration instance
     * without going through {@link GrappleModIntegrations} (which exposes the SPI type,
     * not the Sable-specific subclass that the client tick sync needs to call).
     */
    private static SableCompatModule instance;

    /** Per-level snapshot of last tick's UUID set, used to diff for assembly/disassembly. */
    private final Map<ServerLevel, Set<UUID>> lastSeen = new HashMap<>();

    /** Shared integration, exposed so the tick poll can update its {@code UUID → SubLevel} cache. */
    private final SableSubLevelIntegration integration;

    public SableCompatModule() {
        LOGGER.info("[Grapple <-> Sable] Integration module initializing.");
        instance = this;
        this.integration = new SableSubLevelIntegration();
        GrappleModIntegrations.setSubLevelIntegration(this.integration);

        // Sable does not expose a public event bus for sub-level create/destroy at the
        // time of writing. We poll SubLevelContainer.getAllSubLevels() at end-of-tick
        // and diff the UUID set to synthesize assemble/disassemble events. Cheap: the
        // set is per-level and typically tiny (one entry per active ship/vehicle).
        ServerTickEvents.END_WORLD_TICK.register(this::onLevelTickEnd);
    }

    private void onLevelTickEnd(ServerLevel level) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        Set<UUID> current = new HashSet<>();
        for (SubLevel sl : container.getAllSubLevels()) {
            UUID id = sl.getUniqueId();
            current.add(id);
            this.integration.trackSubLevel(id, sl, level);
        }

        Set<UUID> previous = lastSeen.getOrDefault(level, Set.of());

        for (UUID fresh : current) {
            if (!previous.contains(fresh)) {
                GrapplinghookEntity.onSubLevelAssembled(fresh, level);
            }
        }

        for (UUID gone : previous) {
            if (!current.contains(gone)) {
                GrapplinghookEntity.onSubLevelDisassembled(gone, level);
                this.integration.untrackSubLevel(gone);
            }
        }

        lastSeen.put(level, current);
    }

    public static SableCompatModule getInstance() { return instance; }

    public SableSubLevelIntegration integration() { return integration; }
}
