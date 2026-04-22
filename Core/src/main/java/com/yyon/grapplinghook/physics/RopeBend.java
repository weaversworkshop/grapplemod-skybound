package com.yyon.grapplinghook.physics;

import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One bend point on a rope. Carries a {@link AnchorSpace tag} indicating which
 * coordinate space the bend lives in, the bend's position in that native space
 * ({@link #nativePos}), and a refreshed world-space mirror ({@link #worldPos})
 * that the wrap/unwrap geometry operates on each tick.
 *
 * <p>For {@link AnchorSpace.World} bends the two positions are the same (no
 * transform needed). For {@link AnchorSpace.SubLevel} / {@link AnchorSpace.Contraption}
 * bends the caller is expected to refresh {@link #worldPos} every tick via the
 * owning integration's {@code plotToWorld} / {@code localToWorld} before running
 * wrap/unwrap math — otherwise the bend "lags" a tick behind its moving host.</p>
 *
 * <p>Endpoints (hook/player slots at indices {@code 0} and {@code size-1}) are
 * stored as {@link AnchorSpace.World} bends with {@code null} side Directions —
 * they have no wrap geometry, they just track the entity positions each tick.</p>
 *
 * <p>Mutable by design — rewriting {@link #worldPos} every tick avoids allocating
 * a new {@code RopeBend} per tick per bend.</p>
 */
public final class RopeBend {

    public final AnchorSpace space;
    public Vec worldPos;
    public final Vec nativePos;
    public final @Nullable Direction topSide;
    public final @Nullable Direction bottomSide;

    public RopeBend(AnchorSpace space, Vec worldPos, Vec nativePos,
                    @Nullable Direction topSide, @Nullable Direction bottomSide) {
        this.space = space;
        this.worldPos = worldPos;
        this.nativePos = nativePos;
        this.topSide = topSide;
        this.bottomSide = bottomSide;
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /** World-space bend — {@code worldPos} and {@code nativePos} are the same reference. */
    public static RopeBend world(Vec pos, @Nullable Direction topSide, @Nullable Direction bottomSide) {
        return new RopeBend(AnchorSpace.World.INSTANCE, pos, pos, topSide, bottomSide);
    }

    /** Sub-level bend — {@code nativePos} lives in the sub-level's plot space. */
    public static RopeBend subLevel(UUID subLevelId, Vec plotPos, Vec worldPos,
                                    @Nullable Direction topSide, @Nullable Direction bottomSide) {
        return new RopeBend(new AnchorSpace.SubLevel(subLevelId), worldPos, plotPos, topSide, bottomSide);
    }

    /** Contraption bend — {@code nativePos} lives in the contraption's local frame. */
    public static RopeBend contraption(int entityId, Vec localPos, Vec worldPos,
                                       @Nullable Direction topSide, @Nullable Direction bottomSide) {
        return new RopeBend(new AnchorSpace.Contraption(entityId), worldPos, localPos, topSide, bottomSide);
    }

    @Override
    public String toString() {
        return "RopeBend{space=" + space + ", world=" + worldPos + ", native=" + nativePos
                + ", top=" + topSide + ", bottom=" + bottomSide + "}";
    }
}
