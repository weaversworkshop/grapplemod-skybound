package com.yyon.grapplinghook.physics.rope;

import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.network.NetworkManager;
import com.yyon.grapplinghook.network.clientbound.RopeSegmentUpdateS2CPayload;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.NullableDirection;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

final class RopeBroadcaster {

    private RopeBroadcaster() {}

    static void broadcastAdd(GrapplinghookEntity hook, Level world, int index, RopeBend bend) {
        if (world.isClientSide) return;
        RopeSegmentUpdateS2CPayload msg = new RopeSegmentUpdateS2CPayload(
                hook.getId(), true, index,
                bend.worldPos,
                NullableDirection.fromVanilla(bend.topSide),
                NullableDirection.fromVanilla(bend.bottomSide),
                bend.space);
        send(hook, world, msg);
    }

    static void broadcastRemove(GrapplinghookEntity hook, Level world, int index) {
        if (world.isClientSide) return;
        RopeSegmentUpdateS2CPayload msg = new RopeSegmentUpdateS2CPayload(
                hook.getId(), false, index,
                new Vec(0, 0, 0), NullableDirection.DOWN, NullableDirection.DOWN,
                AnchorSpace.World.INSTANCE);
        send(hook, world, msg);
    }

    private static void send(GrapplinghookEntity hook, Level world, RopeSegmentUpdateS2CPayload msg) {
        Vec playerpoint = Vec.positionVec(hook.shootingEntity);
        NetworkManager.packetToClient(msg, GrappleModUtils.getPlayersThatCanSeeChunkAt((ServerLevel) world, playerpoint));
    }
}
