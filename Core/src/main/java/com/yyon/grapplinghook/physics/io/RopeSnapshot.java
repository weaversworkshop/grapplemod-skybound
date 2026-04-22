package com.yyon.grapplinghook.physics.io;

import com.yyon.grapplinghook.content.entity.grapplinghook.RopeSegmentHandler;
import com.yyon.grapplinghook.physics.AnchorSpace;
import com.yyon.grapplinghook.physics.RopeBend;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.NullableDirection;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.*;
import java.util.stream.Collectors;

public class RopeSnapshot {

    public static final StreamCodec<RegistryFriendlyByteBuf, RopeSnapshot> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, Vec.STREAM_CODEC),
            RopeSnapshot::internalSegments,
            ByteBufCodecs.collection(ArrayList::new, GrappleModUtils.NULLABLE_DIRECTION_STREAM_CODEC),
            RopeSnapshot::internalTops,
            ByteBufCodecs.collection(ArrayList::new, GrappleModUtils.NULLABLE_DIRECTION_STREAM_CODEC),
            RopeSnapshot::internalBottoms,
            ByteBufCodecs.collection(ArrayList::new, AnchorSpace.STREAM_CODEC),
            RopeSnapshot::internalSpaces,
            ByteBufCodecs.DOUBLE,
            RopeSnapshot::getRopeLength,

            RopeSnapshot::fromWire
    );

    private static final String NBT_SEGMENTS_LIST = "segments";
    private static final String NBT_ROPE_LENGTH = "rope_length";

    private static final String NBT_TOP = "top";
    private static final String NBT_BOTTOM = "bottom";
    private static final String NBT_POS = "pos";
    private static final String NBT_SPACE = "space";

    /**
     * Backing store is a list of {@link RopeBend}s — each carries its own
     * {@link AnchorSpace}. Projection accessors ({@link #getSegments},
     * {@link #getTopSides}, {@link #getBottomSides}) expose the legacy
     * parallel-list view for callers that still speak world-space.
     */
    private final List<RopeBend> bends;
    private final double ropeLength;

    /**
     * Wire reconstruction — assembles a snapshot from the four parallel lists
     * transmitted by {@link #STREAM_CODEC}. The space list must line up 1:1 with
     * the other three.
     */
    private static RopeSnapshot fromWire(List<Vec> segments,
                                         List<NullableDirection> topSides,
                                         List<NullableDirection> bottomSides,
                                         List<AnchorSpace> spaces,
                                         double ropeLength) {
        List<RopeBend> bends = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            AnchorSpace space = i < spaces.size() ? spaces.get(i) : AnchorSpace.World.INSTANCE;
            // Wire carries world-space positions directly. For non-WORLD bends we
            // currently treat nativePos == worldPos; the next tick will refresh
            // worldPos via the integration. This matches the convention used in
            // RopeSegmentHandler.actuallyAddSegment when receiving a bend from the
            // network — the server's already-resolved worldPos is the source of truth
            // until the client runs its first tick-refresh.
            Vec pos = segments.get(i);
            bends.add(new RopeBend(space, pos, pos, topSides.get(i).toVanilla(), bottomSides.get(i).toVanilla()));
        }
        return new RopeSnapshot(bends, ropeLength);
    }

    public RopeSnapshot(List<RopeBend> bends, double ropeLength) {
        this.bends = new ArrayList<>(bends);
        this.ropeLength = ropeLength;
    }

    public RopeSnapshot(RopeSegmentHandler segmentHandler) {
        this.bends = new ArrayList<>(segmentHandler.getBends());
        this.ropeLength = segmentHandler.getCurrentRopeLength();
    }

    /**
     * Load from NBT. Entries missing the {@link #NBT_SPACE} compound default to
     * {@link AnchorSpace.World} — this is the v1-save compatibility path.
     */
    public RopeSnapshot(CompoundTag nbt) {
        this.bends = new ArrayList<>();

        this.ropeLength = nbt.getDouble(NBT_ROPE_LENGTH);
        ListTag segmentsTag = nbt.getList(NBT_SEGMENTS_LIST, Tag.TAG_COMPOUND);

        for (int i = 0; i < segmentsTag.size(); i++) {
            CompoundTag entry = segmentsTag.getCompound(i);

            ListTag posTag = entry.getList(NBT_POS, Tag.TAG_DOUBLE);
            Vec pos = new Vec(posTag);

            String topSide = entry.getString(NBT_TOP);
            String bottomSide = entry.getString(NBT_BOTTOM);
            Direction topSideDir = !topSide.equalsIgnoreCase("null")
                    ? Direction.byName(topSide)
                    : null;
            Direction bottomSideDir = !bottomSide.equalsIgnoreCase("null")
                    ? Direction.byName(bottomSide)
                    : null;

            AnchorSpace space = entry.contains(NBT_SPACE)
                    ? AnchorSpace.readFromNbt(entry.getCompound(NBT_SPACE))
                    : AnchorSpace.World.INSTANCE;

            // NBT stores the world-space position even for non-WORLD bends. On first
            // tick after load the handler will refresh worldPos via the integration,
            // so starting with nativePos == worldPos is acceptable; a bend whose host
            // has drifted since save will snap into place on the first refresh.
            this.bends.add(new RopeBend(space, pos, pos, topSideDir, bottomSideDir));
        }
    }


    public CompoundTag toNBT() {
        CompoundTag snapshotTag = new CompoundTag();
        ListTag segmentsTag = new ListTag();

        for (RopeBend bend : this.bends) {
            CompoundTag entry = new CompoundTag();
            entry.put(NBT_POS, bend.worldPos.toNBT());

            String topVal = bend.topSide != null ? bend.topSide.getName() : "null";
            String bottomVal = bend.bottomSide != null ? bend.bottomSide.getName() : "null";
            entry.putString(NBT_TOP, topVal);
            entry.putString(NBT_BOTTOM, bottomVal);

            // Only write the space compound for non-WORLD bends — keeps NBT footprint
            // identical to v1 for ropes that never touched a foreign space, and makes
            // debugging easier because saved files still look like "vanilla rope" NBT.
            if (!(bend.space instanceof AnchorSpace.World)) {
                CompoundTag spaceTag = new CompoundTag();
                bend.space.writeToNbt(spaceTag);
                entry.put(NBT_SPACE, spaceTag);
            }

            segmentsTag.add(entry);
        }

        snapshotTag.put(NBT_SEGMENTS_LIST, segmentsTag);
        snapshotTag.putDouble(NBT_ROPE_LENGTH, this.ropeLength);

        return snapshotTag;
    }

    // ------------------------------------------------------------------
    // Wire-codec projections — kept as private helpers for the composite codec
    // ------------------------------------------------------------------

    public ArrayList<Vec> internalSegments() {
        ArrayList<Vec> out = new ArrayList<>(this.bends.size());
        for (RopeBend bend : this.bends) out.add(bend.worldPos);
        return out;
    }

    public List<NullableDirection> internalTops() {
        return this.bends.stream()
                .map(b -> NullableDirection.fromVanilla(b.topSide))
                .collect(Collectors.toList());
    }

    public List<NullableDirection> internalBottoms() {
        return this.bends.stream()
                .map(b -> NullableDirection.fromVanilla(b.bottomSide))
                .collect(Collectors.toList());
    }

    public List<AnchorSpace> internalSpaces() {
        return this.bends.stream()
                .map(b -> b.space)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // External projections — legacy parallel-list view for callers that still
    // speak the old three-list API (the segment handler's constructor, tests, etc.)
    // ------------------------------------------------------------------

    public List<Vec> getSegments() {
        return internalSegments();
    }

    public List<Direction> getTopSides() {
        return this.bends.stream()
                .map(b -> b.topSide)
                .collect(Collectors.toUnmodifiableList());
    }

    public List<Direction> getBottomSides() {
        return this.bends.stream()
                .map(b -> b.bottomSide)
                .collect(Collectors.toUnmodifiableList());
    }

    /** The authoritative view — used by {@link RopeSegmentHandler#loadFromSnapshot}. */
    public List<RopeBend> getBends() {
        return Collections.unmodifiableList(this.bends);
    }

    public double getRopeLength() {
        return this.ropeLength;
    }


    @Override
    public String toString() {
        return "[ RopeSnapshot, %sx bends, ropeLen=%s ]: %s".formatted(
                this.bends.size(), this.ropeLength, this.bends);
    }
}
