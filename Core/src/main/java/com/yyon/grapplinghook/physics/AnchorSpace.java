package com.yyon.grapplinghook.physics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

public sealed interface AnchorSpace
        permits AnchorSpace.World, AnchorSpace.SubLevel, AnchorSpace.Contraption {

    enum Kind {
        WORLD((byte) 0),
        SUBLEVEL((byte) 1),
        CONTRAPTION((byte) 2);

        private final byte tag;
        Kind(byte tag) { this.tag = tag; }
        public byte tag() { return tag; }

        public static Kind fromTag(byte t) {
            for (Kind k : values()) {
                if (k.tag == t) return k;
            }
            throw new IllegalStateException("Unknown AnchorSpace tag: " + t);
        }
    }

    Kind kind();

    record World() implements AnchorSpace {
        public static final World INSTANCE = new World();
        @Override public Kind kind() { return Kind.WORLD; }
    }

    record SubLevel(UUID subLevelId) implements AnchorSpace {
        @Override public Kind kind() { return Kind.SUBLEVEL; }
    }

    record Contraption(int entityId) implements AnchorSpace {
        @Override public Kind kind() { return Kind.CONTRAPTION; }
    }

    StreamCodec<RegistryFriendlyByteBuf, AnchorSpace> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AnchorSpace decode(RegistryFriendlyByteBuf buf) {
            Kind kind = Kind.fromTag(buf.readByte());
            return switch (kind) {
                case WORLD -> World.INSTANCE;
                case SUBLEVEL -> {
                    long msb = buf.readLong();
                    long lsb = buf.readLong();
                    yield new SubLevel(new UUID(msb, lsb));
                }
                case CONTRAPTION -> new Contraption(buf.readVarInt());
            };
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AnchorSpace value) {
            buf.writeByte(value.kind().tag());
            switch (value) {
                case World w -> {}
                case SubLevel sl -> {
                    buf.writeLong(sl.subLevelId().getMostSignificantBits());
                    buf.writeLong(sl.subLevelId().getLeastSignificantBits());
                }
                case Contraption c -> buf.writeVarInt(c.entityId());
            }
        }
    };

    String NBT_KIND = "kind";
    String NBT_SUBLEVEL_UUID_MSB = "uuid_msb";
    String NBT_SUBLEVEL_UUID_LSB = "uuid_lsb";
    String NBT_CONTRAPTION_ID = "entity_id";

    static AnchorSpace readFromNbt(CompoundTag tag) {
        if (tag == null || !tag.contains(NBT_KIND)) return World.INSTANCE;
        Kind kind = Kind.fromTag((byte) tag.getInt(NBT_KIND));
        return switch (kind) {
            case WORLD -> World.INSTANCE;
            case SUBLEVEL -> new SubLevel(new UUID(
                    tag.getLong(NBT_SUBLEVEL_UUID_MSB),
                    tag.getLong(NBT_SUBLEVEL_UUID_LSB)));
            case CONTRAPTION -> new Contraption(tag.getInt(NBT_CONTRAPTION_ID));
        };
    }

    default void writeToNbt(CompoundTag tag) {
        tag.putInt(NBT_KIND, kind().tag());
        switch (this) {
            case World w -> {}
            case SubLevel sl -> {
                tag.putLong(NBT_SUBLEVEL_UUID_MSB, sl.subLevelId().getMostSignificantBits());
                tag.putLong(NBT_SUBLEVEL_UUID_LSB, sl.subLevelId().getLeastSignificantBits());
            }
            case Contraption c -> tag.putInt(NBT_CONTRAPTION_ID, c.entityId());
        }
    }
}
