package com.yyon.grapplinghook.network.clientbound;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.network.S2CPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

/**
 * Lightweight "the anchor this hook is attached to changed" notification.
 *
 * <p>Used when a block the hook was anchored to becomes part of a contraption.
 * We swap the hook's anchor target onto the contraption entity <em>without</em>
 * tearing down the physics controller. Sending the full
 * {@link GrappleAttachS2CPayload} instead would trigger the client to rebuild
 * its {@link com.yyon.grapplinghook.client.physics.context.GrapplingHookPhysicsController},
 * which in turn disables the old controller and fires a
 * {@link com.yyon.grapplinghook.network.serverbound.HaltCustomPhysicsC2SPayload}
 * back — killing the very hook we just reanchored.</p>
 */
public record GrappleReanchorToEntityS2CPayload(int hookId, int newEntityId, Vec3 localOffset) implements S2CPayload {

    public static final ResourceLocation IDENTIFIER = GrappleMod.id("grapple_reanchor_entity");
    public static final CustomPacketPayload.Type<GrappleReanchorToEntityS2CPayload> PAYLOAD_TYPE = new Type<>(IDENTIFIER);

    public static final StreamCodec<RegistryFriendlyByteBuf, GrappleReanchorToEntityS2CPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, GrappleReanchorToEntityS2CPayload::hookId,
            ByteBufCodecs.INT, GrappleReanchorToEntityS2CPayload::newEntityId,
            ByteBufCodecs.DOUBLE, p -> p.localOffset().x,
            ByteBufCodecs.DOUBLE, p -> p.localOffset().y,
            ByteBufCodecs.DOUBLE, p -> p.localOffset().z,
            (h, e, x, y, z) -> new GrappleReanchorToEntityS2CPayload(h, e, new Vec3(x, y, z))
    );

    @NotNull
    @Override
    public Type<GrappleReanchorToEntityS2CPayload> type() {
        return PAYLOAD_TYPE;
    }
}
