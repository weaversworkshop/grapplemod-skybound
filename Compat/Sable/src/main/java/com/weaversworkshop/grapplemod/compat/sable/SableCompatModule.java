package com.weaversworkshop.grapplemod.compat.sable;

import com.mojang.logging.LogUtils;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.content.entity.grapplinghook.HookHostDisassembly;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import com.yyon.grapplinghook.physics.ServerHookEntityTracker;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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
        try {
            onLevelTickEndInner(level);
        } catch (Throwable err) {
            // Never let a Sable-side hiccup tear down the server tick loop.
            LOGGER.error("[Grapple <-> Sable] Sub-level tick poll threw — swallowing so the server keeps ticking.",
                    err);
        }
    }

    private void onLevelTickEndInner(ServerLevel level) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        Set<UUID> current = new HashSet<>();
        for (SubLevel sl : container.getAllSubLevels()) {
            // Skip sub-levels marked removed but not yet evicted from the container —
            // their backing plot state may already be nulled.
            if (sl.isRemoved()) continue;
            UUID id = sl.getUniqueId();
            current.add(id);
            this.integration.trackSubLevel(id, sl, level);
        }

        Set<UUID> previous = lastSeen.getOrDefault(level, Set.of());

        for (UUID fresh : current) {
            if (!previous.contains(fresh)) {
                try {
                    onSubLevelAssembled(fresh, level);
                } catch (Throwable err) {
                    LOGGER.error("[Grapple <-> Sable] onSubLevelAssembled({}) threw", fresh, err);
                }
            }
        }

        for (UUID gone : previous) {
            if (!current.contains(gone)) {
                LOGGER.info("[Grapple <-> Sable] Sub-level disappeared from container: uuid={} — dispatching disassembly handler",
                        gone);
                try {
                    onSubLevelDisassembled(gone, level);
                } catch (Throwable err) {
                    LOGGER.error("[Grapple <-> Sable] onSubLevelDisassembled({}) threw", gone, err);
                }
                // Untrack AFTER the handler so plotToWorld etc. can still resolve the
                // final pose during re-anchor. The handler itself bails out early if
                // the SubLevel is already marked removed.
                this.integration.untrackSubLevel(gone);
            }
        }

        lastSeen.put(level, current);
    }

    public static SableCompatModule getInstance() { return instance; }

    public SableSubLevelIntegration integration() { return integration; }

    private static void onSubLevelAssembled(UUID subLevelId, Level level) {
        SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
        if (!sli.isSubLevelLoaded(subLevelId)) return;
        if (level.isClientSide) return;

        for (GrapplinghookEntity hook : ServerHookEntityTracker.getAllTrackedHooks()) {
            if (hook == null || !hook.isAlive()) continue;
            if (hook.level() != level) continue;
            if (!(hook.attachment() instanceof HookAttachment.Block block)) continue;

            BlockPos plotBlock = sli.getCapturedPlotPos(subLevelId, block.pos());
            if (plotBlock == null) {
                LOGGER.info("[Grapple <-> Sable] onSubLevelAssembled uuid={} hookId={} worldBlock={} — getCapturedPlotPos returned null; leaving hook on static block.",
                        subLevelId, hook.getId(), block.pos());
                continue;
            }

            Vec3 plotHit = sli.worldToPlot(subLevelId, block.subHitPoint(), GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);
            LOGGER.info("[Grapple <-> Sable] onSubLevelAssembled uuid={} hookId={} migrating Block→SubLevelBlock: worldBlock={} → plotBlock={} plotHit={}",
                    subLevelId, hook.getId(), block.pos(), plotBlock, plotHit);
            hook.reattachToSubLevel(subLevelId, plotBlock, plotHit);
        }
    }

    private static void onSubLevelDisassembled(UUID subLevelId, Level level) {
        SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();

        HookHostDisassembly.reanchorAfterHostGone(
                level,
                HookAttachment.SubLevelBlock.class,
                slb -> slb.subLevelId().equals(subLevelId),
                HookAttachment.SubLevelBlock::plotBlock,
                plotCenter -> sli.plotToWorld(subLevelId, plotCenter, GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS),
                "[Grapple <-> Sable]"
        );
    }
}
