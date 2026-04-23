package com.yyon.grapplinghook.physics.rope;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.integration.ContraptionIntegration;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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

    /** True for a coordinate space that never moves (the static world). */
    default boolean isStatic() { return false; }

    /**
     * Convert a world-space position to this space's native coordinates.
     * Default returns {@code world} unchanged (for {@link World}). Non-world implementations
     * return {@code null} when the conversion can't be done right now (integration not loaded,
     * host gone) so callers can preserve the previous native value instead of clobbering it.
     */
    default Vec3 worldToNative(Vec3 world, float partialTicks, Level level) {
        return world;
    }

    /**
     * Refresh {@code bend.worldPos} based on {@code bend.nativePos} for this space.
     * Returns {@code true} if the bend should be kept; {@code false} if it should be dropped
     * (host gone, or world-pos jumped implausibly far).
     */
    default boolean refreshBendWorld(RopeBend bend, Level level) { return true; }

    record World() implements AnchorSpace {
        public static final World INSTANCE = new World();
        @Override public Kind kind() { return Kind.WORLD; }
        @Override public boolean isStatic() { return true; }
    }

    record SubLevel(UUID subLevelId) implements AnchorSpace {
        @Override public Kind kind() { return Kind.SUBLEVEL; }

        @Override
        public Vec3 worldToNative(Vec3 world, float partialTicks, Level level) {
            SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
            if (!sli.isSubLevelLoaded(subLevelId)) return null;
            return sli.worldToPlot(subLevelId, world, partialTicks);
        }

        @Override
        public boolean refreshBendWorld(RopeBend bend, Level level) {
            SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
            if (!sli.isSubLevelLoaded(subLevelId)) return false;
            Vec3 newWorld = sli.plotToWorld(subLevelId, bend.nativePos.toVec3d(), GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);
            double jumpSq = newWorld.distanceToSqr(bend.worldPos.toVec3d());
            if (jumpSq > 64 * 64) {
                GrappleMod.LOGGER.warn("[Grapple] Sub-level plotToWorld returned a position {}m from the previous bend world pos; dropping bend. uuid={} native={} oldWorld={} newWorld={}",
                        Math.sqrt(jumpSq), subLevelId, bend.nativePos, bend.worldPos, newWorld);
                return false;
            }
            bend.worldPos = new Vec(newWorld.x, newWorld.y, newWorld.z);
            return true;
        }
    }

    record Contraption(int entityId) implements AnchorSpace {
        @Override public Kind kind() { return Kind.CONTRAPTION; }

        @Override
        public Vec3 worldToNative(Vec3 world, float partialTicks, Level level) {
            Entity host = level.getEntity(entityId);
            if (host == null || !host.isAlive()) return null;
            ContraptionIntegration ci = GrappleModIntegrations.getContraptionIntegration();
            return ci.worldToLocal(host, world, partialTicks);
        }

        @Override
        public boolean refreshBendWorld(RopeBend bend, Level level) {
            Entity host = level.getEntity(entityId);
            if (host == null || !host.isAlive()) return false;
            Vec3 newWorld = GrappleModIntegrations.getContraptionIntegration()
                    .localToWorld(host, bend.nativePos.toVec3d(), GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);
            double jumpSq = newWorld.distanceToSqr(bend.worldPos.toVec3d());
            if (jumpSq > 64 * 64) {
                GrappleMod.LOGGER.warn("[Grapple] Contraption localToWorld returned a position {}m from the previous bend world pos; dropping bend. entityId={} class={} native={} oldWorld={} newWorld={}",
                        Math.sqrt(jumpSq), entityId, host.getClass().getName(),
                        bend.nativePos, bend.worldPos, newWorld);
                return false;
            }
            bend.worldPos = new Vec(newWorld.x, newWorld.y, newWorld.z);
            return true;
        }
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
