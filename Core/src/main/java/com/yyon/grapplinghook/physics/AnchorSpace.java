package com.yyon.grapplinghook.physics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * Identifies which coordinate space a rope bend lives in. Used by
 * {@link RopeBend} to route per-tick transforms (plot→world, contraption-local→world)
 * back to the right integration SPI. Core stays mod-agnostic — variants carry only
 * primitive identifiers; resolution happens via
 * {@code SubLevelIntegration.plotToWorld} / {@code ContraptionIntegration.localToWorld}.
 *
 * <p>Wire-stable tags — tag byte {@code 0} is reserved for {@link World} so a rope
 * snapshot saved before multi-space support loads with all bends as world-space
 * (see {@code RopeSnapshot} NBT read path).</p>
 */
public sealed interface AnchorSpace
        permits AnchorSpace.World, AnchorSpace.SubLevel, AnchorSpace.Contraption {

    /** Wire-stable discriminator — explicit tag bytes so reordering variants is safe. */
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

    // ------------------------------------------------------------------
    // Variants
    // ------------------------------------------------------------------

    /** Static world-space anchor — the classic grapple bend. */
    record World() implements AnchorSpace {
        public static final World INSTANCE = new World();
        @Override public Kind kind() { return Kind.WORLD; }
    }

    /** Bend on a Sable sub-level; native coords live in that sublevel's plot space. */
    record SubLevel(UUID subLevelId) implements AnchorSpace {
        @Override public Kind kind() { return Kind.SUBLEVEL; }
    }

    /** Bend on a Create contraption; native coords live in that contraption's local frame. */
    record Contraption(int entityId) implements AnchorSpace {
        @Override public Kind kind() { return Kind.CONTRAPTION; }
    }

    // ------------------------------------------------------------------
    // Wire codec
    // ------------------------------------------------------------------

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
                case World w -> { /* tag-only */ }
                case SubLevel sl -> {
                    buf.writeLong(sl.subLevelId().getMostSignificantBits());
                    buf.writeLong(sl.subLevelId().getLeastSignificantBits());
                }
                case Contraption c -> buf.writeVarInt(c.entityId());
            }
        }
    };

    // ------------------------------------------------------------------
    // NBT helpers
    // ------------------------------------------------------------------

    String NBT_KIND = "kind";
    String NBT_SUBLEVEL_UUID_MSB = "uuid_msb";
    String NBT_SUBLEVEL_UUID_LSB = "uuid_lsb";
    String NBT_CONTRAPTION_ID = "entity_id";

    /**
     * Reconstruct an {@code AnchorSpace} from its NBT form. Returns {@link World}
     * if {@code tag} is {@code null} or lacks a {@link #NBT_KIND} entry — that's the
     * v1-save compatibility path (a pre-multi-space rope has no space tags, so every
     * bend loads as world-space).
     */
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

    /** Write this anchor space into {@code tag}. Safe to read back with {@link #readFromNbt}. */
    default void writeToNbt(CompoundTag tag) {
        tag.putInt(NBT_KIND, kind().tag());
        switch (this) {
            case World w -> { /* no payload */ }
            case SubLevel sl -> {
                tag.putLong(NBT_SUBLEVEL_UUID_MSB, sl.subLevelId().getMostSignificantBits());
                tag.putLong(NBT_SUBLEVEL_UUID_LSB, sl.subLevelId().getLeastSignificantBits());
            }
            case Contraption c -> tag.putInt(NBT_CONTRAPTION_ID, c.entityId());
        }
    }
}
