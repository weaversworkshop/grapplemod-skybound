package com.yyon.grapplinghook.content.entity.grapplinghook;

import com.yyon.grapplinghook.content.customization.data.HookCustomization;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Optional;

import static com.yyon.grapplinghook.content.registry.CustomizationProperties.*;

final class HookMagnetBehavior {

    private HookMagnetBehavior() {}

    static void handleMagnetAttraction(GrapplinghookEntity hook) {
        if (hook.foundBlock) return;

        Vec playerpos = Vec.positionVec(hook.shootingEntity);
        Vec pos = Vec.positionVec(hook);

        if (hook.magnetBlock == null && hook.prevPos != null) {

            HashMap<BlockPos, Boolean> cachedPositions = new HashMap<>();
            Vec vector = pos.sub(hook.prevPos);

            if (vector.length() > 0) {
                Vec normvector = vector.normalize();

                for (int i = 0; i < vector.length(); i++) {
                    double dist = hook.prevPos.sub(playerpos).length();
                    int radius = (int) dist / 4;

                    Optional<BlockPos> optFound = checkForMagnetTargetsNearby(hook, hook.prevPos, cachedPositions);

                    if (optFound.isEmpty()) {
                        hook.wasInAir = true;
                        hook.prevPos.mutableAdd(normvector);
                        continue;
                    }

                    BlockPos found = optFound.get();

                    Vec distvec = new Vec(found.getX(), found.getY(), found.getZ());
                    distvec.mutableSub(hook.prevPos);
                    if (distvec.length() < radius) {
                        hook.setPosRaw(hook.prevPos.x, hook.prevPos.y, hook.prevPos.z);
                        pos = hook.prevPos;
                        hook.magnetBlock = found;

                        break;
                    }

                    hook.prevPos.mutableAdd(normvector);
                }
            }
        }

        if (hook.magnetBlock != null) {
            BlockState blockstate = hook.level().getBlockState(hook.magnetBlock);
            VoxelShape BB = blockstate.getCollisionShape(hook.level(), hook.magnetBlock);

            Vec blockvec = new Vec(hook.magnetBlock.getX() + (BB.max(Axis.X) + BB.min(Axis.X)) / 2, hook.magnetBlock.getY() + (BB.max(Axis.Y) + BB.min(Axis.Y)) / 2, hook.magnetBlock.getZ() + (BB.max(Axis.Z) + BB.min(Axis.Z)) / 2);
            Vec newvel = blockvec.sub(pos);

            double l = newvel.length();

            newvel.withMagnitude(hook.getSpeed());

            hook.setDeltaMovement(newvel.x, newvel.y, newvel.z);

            if (l < 0.2) {
                hook.serverAttach(
                        new HookAttachment.Block(hook.magnetBlock, blockvec.toVec3d(), Direction.UP),
                        false);
            }
        }

        hook.prevPos = pos;
    }

    static Optional<BlockPos> checkForMagnetTargetsNearby(GrapplinghookEntity hook, Vec center, HashMap<BlockPos, Boolean> cachedPositions) {
        HookCustomization customization = hook.getCurrentCustomizations();
        int radius = (int) Math.floor(customization.get(MAGNET_RADIUS.get()));

        BlockPos closestValidPos = null;
        double closestDistance = 0;

        int pX = (int) center.x;
        int pY = (int) center.y;
        int pZ = (int) center.z;

        for (int x = pX - radius; x <= pX + radius; x++) {
            for (int y = pY - radius; y <= pY + radius; y++) {
                for (int z = pZ - radius; z <= pZ + radius; z++) {

                    BlockPos pos = new BlockPos(x, y, z);
                    if (!checkIfCollidingWithBlock(hook, pos, cachedPositions))
                        continue;

                    Vec distvec = new Vec(pos.getX(), pos.getY(), pos.getZ());
                    distvec.mutableSub(center);

                    double dist = distvec.length();
                    if (closestValidPos == null || dist < closestDistance) {
                        closestValidPos = pos;
                        closestDistance = dist;
                    }
                }
            }
        }

        return Optional.ofNullable(closestValidPos);
    }

    static boolean checkIfCollidingWithBlock(GrapplinghookEntity hook, BlockPos pos, HashMap<BlockPos, Boolean> cachedPositions) {
        if (cachedPositions.containsKey(pos))
            return cachedPositions.get(pos);

        boolean canAttach = false;
        Level level = hook.level();
        BlockState blockState = level.getBlockState(pos);

        if (hook.canAttachToBlock(blockState) && !blockState.isAir()) {
            VoxelShape collider = blockState.getCollisionShape(level, pos);

            if (!collider.isEmpty())
                canAttach = true;
        }

        cachedPositions.put(pos, canAttach);
        return canAttach;
    }
}
