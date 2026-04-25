package com.yyon.grapplinghook.physics.persistence;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.content.registry.internal.ModEntities;
import com.yyon.grapplinghook.network.NetworkManager;
import com.yyon.grapplinghook.network.clientbound.GrappleDetachS2CPayload;
import com.yyon.grapplinghook.physics.ServerHookEntityTracker;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class HookPersistenceManager {

    private static final String NBT_ROOT = "GrappleModPersistedHooks";
    private static final int GRACE_TICKS = 600;

    private static final Map<UUID, List<Pending>> pending = new ConcurrentHashMap<>();

    private HookPersistenceManager() {}

    public static void saveForPlayer(ServerPlayer player, CompoundTag parent) {
        Set<GrapplinghookEntity> hooks = ServerHookEntityTracker.getHooksThrownBy(player);
        ListTag list = new ListTag();
        for (GrapplinghookEntity hook : hooks) {
            if (hook == null || !hook.isAlive()) continue;
            PersistedHook ph = PersistedHook.capture(hook);
            if (ph == null) continue;
            list.add(ph.toNbt());
        }
        if (list.isEmpty()) {
            parent.remove(NBT_ROOT);
        } else {
            parent.put(NBT_ROOT, list);
        }
    }

    public static void loadForPlayer(ServerPlayer player, CompoundTag parent) {
        if (!parent.contains(NBT_ROOT, Tag.TAG_LIST)) {
            pending.remove(player.getUUID());
            return;
        }
        ListTag list = parent.getList(NBT_ROOT, Tag.TAG_COMPOUND);
        List<Pending> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            PersistedHook ph = PersistedHook.fromNbt(list.getCompound(i));
            if (ph != null) entries.add(new Pending(ph));
        }
        if (entries.isEmpty()) pending.remove(player.getUUID());
        else pending.put(player.getUUID(), entries);

        parent.remove(NBT_ROOT);
    }

    public static void tickServer(MinecraftServer server) {
        if (pending.isEmpty()) return;
        Iterator<Map.Entry<UUID, List<Pending>>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<Pending>> e = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) continue;
            List<Pending> entries = e.getValue();
            entries.removeIf(pr -> tryRestore(server, player, pr));
            if (entries.isEmpty()) it.remove();
        }
    }

    public static void clearAll() { pending.clear(); }

    private static boolean tryRestore(MinecraftServer server, ServerPlayer player, Pending pr) {
        PersistedHook ph = pr.hook;
        pr.ticks++;

        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ph.dimension()));
        if (level == null) {
            GrappleMod.LOGGER.warn("Dropping persisted hook: dimension {} not found", ph.dimension());
            return true;
        }
        if (player.level() != level) {
            if (pr.ticks > GRACE_TICKS) {
                GrappleMod.LOGGER.info("Dropping persisted hook: player {} not in dimension {} after {} ticks",
                        player.getGameProfile().getName(), ph.dimension(), pr.ticks);
                return true;
            }
            return false;
        }

        if (pr.spawnedHook != null) {
            if (!pr.spawnedHook.isAlive()) return true;
            pr.spawnedHook.serverAttach(pr.resolvedTarget, true);
            return true;
        }

        HookAttachment resolved = ph.attachment().tryResolve(level);
        if (resolved == null) {
            if (pr.ticks <= GRACE_TICKS && ph.attachment().mayBecomeAvailableLater(level)) return false;
            GrappleMod.LOGGER.info("Dropping persisted hook for {}: anchor no longer resolvable",
                    player.getGameProfile().getName());
            notifyClientDetach(player);
            return true;
        }

        pr.spawnedHook = spawnHook(level, player, ph);
        pr.resolvedTarget = resolved;
        return false;
    }

    private static GrapplinghookEntity spawnHook(ServerLevel level, ServerPlayer player, PersistedHook ph) {
        GrapplinghookEntity hook = new GrapplinghookEntity(ModEntities.GRAPPLE_HOOK.get(), level);
        hook.initForRestore(player, ph.mainHand(), ph.inDoublePair(), ph.customization(), ph.ropeLength(), ph.hookPos());

        level.addFreshEntity(hook);
        hook.getSegmentHandler().loadFromSnapshot(ph.ropeSnapshot());
        ServerHookEntityTracker.addGrappleEntity(player, hook);
        return hook;
    }

    private static void notifyClientDetach(ServerPlayer player) {
        NetworkManager.packetToClient(new GrappleDetachS2CPayload(player.getId()), player);
    }

    private static final class Pending {
        final PersistedHook hook;
        int ticks;
        GrapplinghookEntity spawnedHook;
        HookAttachment resolvedTarget;
        Pending(PersistedHook hook) { this.hook = hook; this.ticks = 0; }
    }
}
