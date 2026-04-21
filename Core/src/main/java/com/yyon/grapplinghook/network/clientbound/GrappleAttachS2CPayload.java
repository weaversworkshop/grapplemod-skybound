package com.yyon.grapplinghook.network.clientbound;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.content.customization.data.HookCustomization;
import com.yyon.grapplinghook.network.S2CPayload;
import com.yyon.grapplinghook.network.codec.Vec3StreamCodec;
import com.yyon.grapplinghook.physics.io.RopeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

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



public record GrappleAttachS2CPayload(int hookId, Vector3f hookPos, int holderId, GrappleAttachTarget attachTarget, RopeSnapshot ropeState, HookCustomization customization) implements S2CPayload {

    /**
     * Wire-stable discriminator. Each variant picks an explicit byte tag so that
     * reordering the enum or inserting new kinds does not shift existing tags.
     */
    public enum AttachTargetKind {
        BLOCK((byte) 0),
        ENTITY((byte) 1),
        CONTRAPTION((byte) 2);

        private final byte tag;
        AttachTargetKind(byte tag) { this.tag = tag; }
        public byte tag() { return tag; }

        public static AttachTargetKind fromTag(byte t) {
            for (AttachTargetKind k : values()) {
                if (k.tag == t) return k;
            }
            throw new IllegalStateException("Unknown GrappleAttachTarget tag: " + t);
        }
    }

    public sealed interface GrappleAttachTarget
            permits GrappleAttachTarget.Block, GrappleAttachTarget.Entity, GrappleAttachTarget.EntityOffset {

        AttachTargetKind kind();

        StreamCodec<RegistryFriendlyByteBuf, GrappleAttachTarget> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public GrappleAttachTarget decode(RegistryFriendlyByteBuf buf) {
                AttachTargetKind kind = AttachTargetKind.fromTag(buf.readByte());
                return switch (kind) {
                    case BLOCK -> new Block(BlockPos.STREAM_CODEC.decode(buf));
                    case ENTITY -> new Entity(buf.readVarInt());
                    case CONTRAPTION -> {
                        int id = buf.readVarInt();
                        Vec3 offset = Vec3StreamCodec.INSTANCE.decode(buf);
                        yield new EntityOffset(id, offset);
                    }
                };
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, GrappleAttachTarget value) {
                buf.writeByte(value.kind().tag());
                switch (value) {
                    case Block b -> BlockPos.STREAM_CODEC.encode(buf, b.pos());
                    case Entity e -> buf.writeVarInt(e.id());
                    case EntityOffset eo -> {
                        buf.writeVarInt(eo.id());
                        Vec3StreamCodec.INSTANCE.encode(buf, eo.localOffset());
                    }
                }
            }
        };

        record Block(BlockPos pos) implements GrappleAttachTarget {
            @Override public AttachTargetKind kind() { return AttachTargetKind.BLOCK; }
        }
        record Entity(int id) implements GrappleAttachTarget {
            @Override public AttachTargetKind kind() { return AttachTargetKind.ENTITY; }
        }
        record EntityOffset(int id, Vec3 localOffset) implements GrappleAttachTarget {
            @Override public AttachTargetKind kind() { return AttachTargetKind.CONTRAPTION; }
        }
    }

    public static final ResourceLocation IDENTIFIER = GrappleMod.id("grapple_attach");
    public static final CustomPacketPayload.Type<GrappleAttachS2CPayload> PAYLOAD_TYPE = new Type<>(IDENTIFIER);

    public static final StreamCodec<RegistryFriendlyByteBuf, GrappleAttachS2CPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT,
            GrappleAttachS2CPayload::hookId,
            ByteBufCodecs.VECTOR3F,
            GrappleAttachS2CPayload::hookPos,
            ByteBufCodecs.INT,
            GrappleAttachS2CPayload::holderId,
            GrappleAttachTarget.STREAM_CODEC,
            GrappleAttachS2CPayload::attachTarget,
            RopeSnapshot.STREAM_CODEC,
            GrappleAttachS2CPayload::ropeState,
            HookCustomization.STREAM_CODEC,
            GrappleAttachS2CPayload::customization,

            GrappleAttachS2CPayload::new
    );

    @NotNull
    @Override
    public Type<GrappleAttachS2CPayload> type() {
        return PAYLOAD_TYPE;
    }

}
