package com.yyon.grapplinghook.physics.persistence;

import com.mojang.serialization.DataResult;
import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.content.customization.data.HookCustomization;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import com.yyon.grapplinghook.physics.io.RopeSnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public record PersistedHook(
        HookCustomization customization,
        boolean mainHand,
        boolean inDoublePair,
        ResourceLocation dimension,
        Vec3 hookPos,
        double ropeLength,
        RopeSnapshot ropeSnapshot,
        PersistedAttachment attachment
) {
    private static final String NBT_CUSTOMIZATION = "customization";
    private static final String NBT_MAIN_HAND = "main_hand";
    private static final String NBT_DOUBLE_PAIR = "double_pair";
    private static final String NBT_DIMENSION = "dim";
    private static final String NBT_HOOK_X = "hx";
    private static final String NBT_HOOK_Y = "hy";
    private static final String NBT_HOOK_Z = "hz";
    private static final String NBT_ROPE_LENGTH = "rope_length";
    private static final String NBT_ROPE_SNAPSHOT = "rope";
    private static final String NBT_ATTACHMENT = "attach";

    public static @Nullable PersistedHook capture(GrapplinghookEntity hook) {
        HookAttachment attachment = hook.attachment();
        if (attachment == null) return null;
        PersistedAttachment persisted = PersistedAttachment.fromHook(attachment);
        if (persisted == null) return null;

        return new PersistedHook(
                HookCustomization.copyAllFrom(hook.getCurrentCustomizations()),
                hook.isHeldInMainHand(),
                hook.isInDoublePair,
                hook.level().dimension().location(),
                hook.position(),
                hook.ropeLength,
                new RopeSnapshot(hook.getSegmentHandler()),
                persisted
        );
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        HookCustomization.CODEC.encodeStart(NbtOps.INSTANCE, customization)
                .resultOrPartial(err -> GrappleMod.LOGGER.warn("Failed to encode hook customization for persistence: {}", err))
                .ifPresent(encoded -> tag.put(NBT_CUSTOMIZATION, encoded));
        tag.putBoolean(NBT_MAIN_HAND, mainHand);
        tag.putBoolean(NBT_DOUBLE_PAIR, inDoublePair);
        tag.putString(NBT_DIMENSION, dimension.toString());
        tag.putDouble(NBT_HOOK_X, hookPos.x);
        tag.putDouble(NBT_HOOK_Y, hookPos.y);
        tag.putDouble(NBT_HOOK_Z, hookPos.z);
        tag.putDouble(NBT_ROPE_LENGTH, ropeLength);
        tag.put(NBT_ROPE_SNAPSHOT, ropeSnapshot.toNBT());
        CompoundTag attachTag = new CompoundTag();
        attachment.writeNbt(attachTag);
        tag.put(NBT_ATTACHMENT, attachTag);
        return tag;
    }

    public static @Nullable PersistedHook fromNbt(CompoundTag tag) {
        try {
            HookCustomization customization;
            if (tag.contains(NBT_CUSTOMIZATION)) {
                DataResult<HookCustomization> decoded = HookCustomization.CODEC
                        .parse(NbtOps.INSTANCE, tag.get(NBT_CUSTOMIZATION));
                customization = decoded.result().orElseGet(HookCustomization::new);
            } else {
                customization = new HookCustomization();
            }

            CompoundTag attachTag = tag.getCompound(NBT_ATTACHMENT);
            PersistedAttachment attachment = PersistedAttachment.readNbt(attachTag);
            if (attachment == null) return null;

            CompoundTag ropeTag = tag.getCompound(NBT_ROPE_SNAPSHOT);
            RopeSnapshot snapshot = new RopeSnapshot(ropeTag);

            ResourceLocation dim = ResourceLocation.tryParse(tag.getString(NBT_DIMENSION));
            if (dim == null) return null;

            return new PersistedHook(
                    customization,
                    tag.getBoolean(NBT_MAIN_HAND),
                    tag.getBoolean(NBT_DOUBLE_PAIR),
                    dim,
                    new Vec3(tag.getDouble(NBT_HOOK_X), tag.getDouble(NBT_HOOK_Y), tag.getDouble(NBT_HOOK_Z)),
                    tag.getDouble(NBT_ROPE_LENGTH),
                    snapshot,
                    attachment
            );
        } catch (Exception e) {
            GrappleMod.LOGGER.warn("Failed to decode persisted hook", e);
            return null;
        }
    }
}
