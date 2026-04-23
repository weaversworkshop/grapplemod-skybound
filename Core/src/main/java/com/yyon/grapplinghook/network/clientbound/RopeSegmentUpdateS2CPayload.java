package com.yyon.grapplinghook.network.clientbound;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.network.S2CPayload;
import com.yyon.grapplinghook.physics.rope.AnchorSpace;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.NullableDirection;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/*
 * This file is part of GrappleMod.

    GrappleMod is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    GrappleMod is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with GrappleMod.  If not, see <http://www.gnu.org/licenses/>.
 */

public record RopeSegmentUpdateS2CPayload(int hookId, boolean shouldAdd, int index, Vec pos,
										  NullableDirection topFacing, NullableDirection bottomFacing,
										  AnchorSpace space) implements S2CPayload {
	public static final ResourceLocation IDENTIFIER = GrappleMod.id("rope_segment_update");
	public static final CustomPacketPayload.Type<RopeSegmentUpdateS2CPayload> PAYLOAD_TYPE = new Type<>(IDENTIFIER);

	public static final StreamCodec<RegistryFriendlyByteBuf, RopeSegmentUpdateS2CPayload> STREAM_CODEC = new StreamCodec<>() {
		@Override
		public RopeSegmentUpdateS2CPayload decode(RegistryFriendlyByteBuf buf) {
			int hookId = ByteBufCodecs.INT.decode(buf);
			boolean shouldAdd = ByteBufCodecs.BOOL.decode(buf);
			int index = ByteBufCodecs.INT.decode(buf);
			Vec pos = Vec.STREAM_CODEC.decode(buf);
			NullableDirection topFacing = GrappleModUtils.NULLABLE_DIRECTION_STREAM_CODEC.decode(buf);
			NullableDirection bottomFacing = GrappleModUtils.NULLABLE_DIRECTION_STREAM_CODEC.decode(buf);
			AnchorSpace space = AnchorSpace.STREAM_CODEC.decode(buf);
			return new RopeSegmentUpdateS2CPayload(hookId, shouldAdd, index, pos, topFacing, bottomFacing, space);
		}

		@Override
		public void encode(RegistryFriendlyByteBuf buf, RopeSegmentUpdateS2CPayload v) {
			ByteBufCodecs.INT.encode(buf, v.hookId());
			ByteBufCodecs.BOOL.encode(buf, v.shouldAdd());
			ByteBufCodecs.INT.encode(buf, v.index());
			Vec.STREAM_CODEC.encode(buf, v.pos());
			GrappleModUtils.NULLABLE_DIRECTION_STREAM_CODEC.encode(buf, v.topFacing());
			GrappleModUtils.NULLABLE_DIRECTION_STREAM_CODEC.encode(buf, v.bottomFacing());
			AnchorSpace.STREAM_CODEC.encode(buf, v.space());
		}
	};

	@NotNull
	@Override
	public Type<RopeSegmentUpdateS2CPayload> type() {
		return PAYLOAD_TYPE;
	}

}
