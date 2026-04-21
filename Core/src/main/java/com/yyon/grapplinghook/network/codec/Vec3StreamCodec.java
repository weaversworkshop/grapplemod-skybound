package com.yyon.grapplinghook.network.codec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/**
 * Shared stream codec for {@link Vec3} values. Replaces three identical xyz-decomposition
 * blocks that previously lived inline in {@code GrappleAttachS2CPayload.GrappleAttachTarget.EntityOffset},
 * {@code GrappleReanchorToEntityS2CPayload}, and {@code GrappleReanchorToBlockS2CPayload}.
 */
public final class Vec3StreamCodec {

    public static final StreamCodec<RegistryFriendlyByteBuf, Vec3> INSTANCE = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, v -> v.x,
            ByteBufCodecs.DOUBLE, v -> v.y,
            ByteBufCodecs.DOUBLE, v -> v.z,
            Vec3::new
    );

    private Vec3StreamCodec() {}
}
