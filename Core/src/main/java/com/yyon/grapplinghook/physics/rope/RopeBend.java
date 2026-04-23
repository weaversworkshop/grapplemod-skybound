package com.yyon.grapplinghook.physics.rope;

import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

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

    public static RopeBend world(Vec pos, @Nullable Direction topSide, @Nullable Direction bottomSide) {
        return new RopeBend(AnchorSpace.World.INSTANCE, pos, pos, topSide, bottomSide);
    }

    public static RopeBend subLevel(UUID subLevelId, Vec plotPos, Vec worldPos,
                                    @Nullable Direction topSide, @Nullable Direction bottomSide) {
        return new RopeBend(new AnchorSpace.SubLevel(subLevelId), worldPos, plotPos, topSide, bottomSide);
    }

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
