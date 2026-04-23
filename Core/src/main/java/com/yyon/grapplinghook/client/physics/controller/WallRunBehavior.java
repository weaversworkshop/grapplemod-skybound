package com.yyon.grapplinghook.client.physics.controller;

import com.yyon.grapplinghook.client.GrappleModClient;
import com.yyon.grapplinghook.client.ModKeys;
import com.yyon.grapplinghook.config.GrappleModClientConfig;
import com.yyon.grapplinghook.util.EnchantmentValues;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

final class WallRunBehavior {

    private final GrapplingHookPhysicsController controller;

    private boolean isOnWall = false;
    private Vec wallDirection = null;
    private BlockHitResult wallrunRaytraceResult = null;
    private int ticksSinceLastWallrunSoundEffect = 0;

    WallRunBehavior(GrapplingHookPhysicsController controller) {
        this.controller = controller;
    }

    boolean isWallRunning() {
        double currentSpeed = Math.sqrt(Math.pow(controller.motion.x, 2) + Math.pow(controller.motion.z, 2));
        if (currentSpeed <= EnchantmentValues.MIN_WALLRUN_SPEED) {
            this.isOnWall = false;
            return false;
        }

        if (this.isOnWall) {
            GrappleModClient.get().setWallrunTicks(GrappleModClient.get().getWallrunTicks() + 1);
        }

        if (GrappleModClient.get().getWallrunTicks() < EnchantmentValues.MAX_WALLRUN_TIME) {
            if (!(controller.playerSneak)) {
                // continue wallrun
                if (this.isOnWall && !controller.holder.onGround() && controller.holder.horizontalCollision) {
                    return !controller.holder.onClimbable();
                }

                // start wallrun
                if (GrappleModClient.get().isWallRunning(controller.holder, controller.motion)) {
                    this.isOnWall = true;
                    return true;
                }
            }

            this.isOnWall = false;
        }

        if (GrappleModClient.get().getWallrunTicks() > 0 && (controller.holder.onGround() || (!controller.holder.horizontalCollision && !this.wallNearby(0.2)))) {
            this.ticksSinceLastWallrunSoundEffect = 0;
        }

        return false;
    }

    boolean apply() {
        boolean isWallRunning = this.isWallRunning();

        if (controller.playerJump) {
            if (isWallRunning)
                return false;

            controller.playerJump = false;
        }

        if (isWallRunning && !ModKeys.DETACH.get().isDown()) {

            Vec wallSide = this.getWallDirection();

            if (wallSide != null)
                this.wallDirection = wallSide;

            if (this.wallDirection == null)
                return false;

            if (!controller.playerJump)
                controller.motion.y = 0;

            // drag
            double dragForce = EnchantmentValues.WALLRUN_DRAG;
            double speed = controller.motion.length();

            if (dragForce > speed)
                dragForce = speed;

            Vec wallFriction = new Vec(controller.motion);
            if (wallSide != null)
                wallFriction.removeAlong(wallSide);

            wallFriction.mutableSetMagnitude(-dragForce);
            controller.motion.mutableAdd(wallFriction);
            this.ticksSinceLastWallrunSoundEffect++;

            double wallRunningSoundTime = GrappleModClientConfig.get().getWallrunVolume();
            double wallRunningMaxSpeed = EnchantmentValues.MAX_WALLRUN_SPEED;
            double timeLimit = speed != 0
                    ? wallRunningSoundTime * 20 * wallRunningMaxSpeed / speed
                    : -1;

            if (timeLimit < 0 || this.ticksSinceLastWallrunSoundEffect > timeLimit) {
                if (this.wallrunRaytraceResult != null) {
                    BlockPos blockpos = this.wallrunRaytraceResult.getBlockPos();

                    BlockState blockState = controller.holder.level().getBlockState(blockpos);
                    SoundType soundtype = blockState.getSoundType();

                    controller.holder.playSound(soundtype.getStepSound(), soundtype.getVolume() * 0.30F * GrappleModClientConfig.get().getWallrunVolume(), soundtype.getPitch());
                    this.ticksSinceLastWallrunSoundEffect = 0;
                }
            }
        }

        // jump
        boolean isDetachRequested = ModKeys.DETACH.get().isDown();
        boolean shouldJump = isDetachRequested && this.isOnWall && !controller.playerJump;
        controller.playerJump = isDetachRequested && this.isOnWall;

        if (shouldJump && isWallRunning) {
            GrappleModClient.get().setWallrunTicks(0);
            Vec jump = new Vec(0, EnchantmentValues.WALLRUN_JUMP_UP_FORCE, 0);

            if (this.wallDirection != null) {
                double wallJumpSide = EnchantmentValues.WALLRUN_JUMP_SIDE_FORCE;
                Vec wallDir = this.wallDirection.scale(-wallJumpSide);
                jump.mutableAdd(wallDir);
            }

            controller.motion.mutableAdd(jump);

            isWallRunning = false;

            GrappleModClient.get().playWallrunJumpSound();
        }

        return isWallRunning;
    }

    Vec pressAgainstWall() {
        if (this.wallDirection != null) {
            return this.wallDirection.withMagnitude(0.05);
        }
        return new Vec(0, 0, 0);
    }

    Vec getWallDirection() {
        Vec tryfirst = new Vec(0, 0, 0);
        Vec trysecond = new Vec(0, 0, 0);

        if (Math.abs(controller.motion.x) > Math.abs(controller.motion.z)) {
            tryfirst.x = (controller.motion.x > 0) ? 1 : -1;
            trysecond.z = (controller.motion.z > 0) ? 1 : -1;
        } else {
            tryfirst.z = (controller.motion.z > 0) ? 1 : -1;
            trysecond.x = (controller.motion.x > 0) ? 1 : -1;
        }

        return getNearbyWall(tryfirst, trysecond, 0.05);
    }

    private Vec getNearbyWall(Vec tryFirst, Vec trySecond, double extra) {
        float entityCollisionWidth = controller.holder.getBbWidth();

        Vec[] directions = new Vec[] {
                tryFirst,
                trySecond,
                tryFirst.scale(-1),
                trySecond.scale(-1)
        };

        for (Vec direction : directions) {
            Vec collisionRayLength = direction.withMagnitude(entityCollisionWidth / 2 + extra);
            BlockHitResult raytraceresult = GrappleModUtils.rayTraceBlocks(
                    controller.holder,
                    controller.holder.level(),
                    Vec.positionVec(controller.holder),
                    Vec.positionVec(controller.holder).add(collisionRayLength)
            );

            if (raytraceresult != null) {
                this.wallrunRaytraceResult = raytraceresult;
                return direction;
            }
        }

        return null;
    }

    private boolean wallNearby(double dist) {
        float entitywidth = controller.holder.getBbWidth();
        Vec v1 = new Vec(entitywidth / 2 + dist, 0, 0);
        Vec v2 = new Vec(0, 0, entitywidth / 2 + dist);

        for (int i = 0; i < 4; i++) {
            Vec corner1 = getCorner(i, v1, v2);
            Vec corner2 = getCorner((i + 1) % 4, v1, v2);

            BlockHitResult raytraceresult = GrappleModUtils.rayTraceBlocks(controller.holder, controller.holder.level(), Vec.positionVec(controller.holder).add(corner1), Vec.positionVec(controller.holder).add(corner2));
            if (raytraceresult != null) {
                return true;
            }
        }

        return false;
    }

    private static Vec getCorner(int cornernum, Vec facing, Vec sideways) {
        Vec corner = new Vec(0, 0, 0);
        if (cornernum / 2 == 0) {
            corner.mutableAdd(facing);
        } else {
            corner.mutableAdd(facing.scale(-1));
        }

        if (cornernum % 2 == 0) {
            corner.mutableAdd(sideways);
        } else {
            corner.mutableAdd(sideways.scale(-1));
        }
        return corner;
    }
}
