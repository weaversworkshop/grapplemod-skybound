package com.yyon.grapplinghook.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/*
public record ...S2CPayload() implements S2CPayload {
	public static final ResourceLocation IDENTIFIER = GrappleMod.id();
	public static final CustomPacketPayload.Type<> PAYLOAD_TYPE = new Type<>(IDENTIFIER);

	public static final StreamCodec<RegistryFriendlyByteBuf, > STREAM_CODEC = StreamCodec.composite(

	);

	@NotNull
	@Override
	public Type<> type() {
		return PAYLOAD_TYPE;
	}

	@Override
	public void process(ClientPlayNetworking.Context ctx) {

	}
}
 */
/**
 * Server-to-client payload marker. Processing lives in client-only
 * {@code com.yyon.grapplinghook.client.network.ClientNetworkReceivers} —
 * payload classes themselves must stay clean of client-only references so
 * Fabric's transformer can load them on dedicated servers.
 */
public interface S2CPayload extends CustomPacketPayload {

}
