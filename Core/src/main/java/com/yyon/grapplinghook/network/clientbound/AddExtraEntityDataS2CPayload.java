package com.yyon.grapplinghook.network.clientbound;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.content.entity.grapplinghook.IExtendedSpawnPacketEntity;
import com.yyon.grapplinghook.network.S2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;

public record AddExtraEntityDataS2CPayload(int entityId, byte[] extraData) implements S2CPayload {
    public static final ResourceLocation IDENTIFIER = GrappleMod.id("spawn_data");
    public static final CustomPacketPayload.Type<AddExtraEntityDataS2CPayload> PAYLOAD_TYPE = new Type<>(IDENTIFIER);

    public static final StreamCodec<RegistryFriendlyByteBuf, AddExtraEntityDataS2CPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT,
            AddExtraEntityDataS2CPayload::entityId,
            ByteBufCodecs.BYTE_ARRAY,
            AddExtraEntityDataS2CPayload::extraData,
            AddExtraEntityDataS2CPayload::new
    );

    public AddExtraEntityDataS2CPayload(Entity entity) {
        this(entity.getId(), extractExtraData(entity));
    }

    @NotNull
    @Override
    public Type<AddExtraEntityDataS2CPayload> type() {
        return PAYLOAD_TYPE;
    }

    private static byte[] extractExtraData(Entity entity) {
        if(!(entity instanceof IExtendedSpawnPacketEntity exSpawn))
            return new byte[0];

        FriendlyByteBuf byteBuf = new FriendlyByteBuf(Unpooled.buffer());
        exSpawn.writeSpawnData(byteBuf);

        return byteBuf.array();
    }
}
