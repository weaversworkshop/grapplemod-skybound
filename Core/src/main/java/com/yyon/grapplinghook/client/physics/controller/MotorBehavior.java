package com.yyon.grapplinghook.client.physics.controller;

import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.world.entity.Entity;

import static com.yyon.grapplinghook.content.registry.CustomizationProperties.*;

final class MotorBehavior {

    private MotorBehavior() {}

    static void apply(GrapplingHookPhysicsController controller,
                      Vec playerPos, Vec facing, Entity entity, Vec gravity, boolean close) {
        boolean dopull = true;

        // if only one rope is pulling and not oneropepull, disable motor
        if (controller.custom.get(DOUBLE_HOOK_ATTACHED.get()) && controller.grapplehookEntities.size() == 1) {
            boolean isdouble = true;
            for (GrapplinghookEntity hookEntity : controller.grapplehookEntities) {
                if (!hookEntity.isInDoublePair) {
                    isdouble = false;
                    break;
                }
            }

            if (isdouble && !controller.custom.get(SINGLE_ROPE_PULL.get())) {
                dopull = false;
            }
        }

        Vec totalPull = new Vec(0, 0, 0);

        double accel = controller.custom.get(MOTOR_ACCELERATION.get()) / controller.grapplehookEntities.size();

        double minabssidewayspull = 999;

        boolean firstpull = true;
        boolean pullispositive = true;
        boolean pullissameway = true;

        // set all motors to maximum pull and precalculate some stuff for smart motor / smart double motor
        for (GrapplinghookEntity hookEntity : controller.grapplehookEntities) {
            Vec hookPos = Vec.positionVec(hookEntity);
            Vec anchor = hookEntity.getSegmentHandler().getClosest(hookPos);
            Vec spherevec = playerPos.sub(anchor);
            Vec pull = spherevec.scale(-1);

            hookEntity.pull = accel;

            totalPull.mutableAdd(pull.withMagnitude(accel));

            pull.mutableSetMagnitude(hookEntity.pull);

            // precalculate some stuff for smart double motor
            // For smart double motor: the motors should pull left and right equally
            // one side will be less able to pull to its side due to the angle
            // therefore the other side should slow down in order to match and have both sides pull left/right equally
            // the amount each should pull (the lesser of the two) is minabssidewayspull
            if (pull.dot(facing) > 0 || controller.custom.get(MOTOR_WORKS_BACKWARDS.get())) {
                if (controller.custom.get(SMART_MOTOR.get()) && controller.grapplehookEntities.size() > 1) {
                    Vec facingxy = new Vec(facing.x, 0, facing.z);
                    Vec facingside = facingxy.cross(new Vec(0, 1, 0)).normalize();
                    Vec sideways = pull.project(facingside);
                    Vec currentsideways = controller.motion.project(facingside);
                    sideways.mutableAdd(currentsideways);
                    double sidewayspull = sideways.dot(facingside);

                    if (Math.abs(sidewayspull) < minabssidewayspull) {
                        minabssidewayspull = Math.abs(sidewayspull);
                    }

                    if (firstpull) {
                        firstpull = false;
                        pullispositive = (sidewayspull >= 0);
                    } else {
                        if (pullispositive != (sidewayspull >= 0)) {
                            pullissameway = false;
                        }
                    }
                }

            }
        }

        // Smart double motor - calculate the speed each motor should pull at
        if (controller.custom.get(DOUBLE_SMART_MOTOR.get()) && controller.grapplehookEntities.size() > 1) {
            totalPull = new Vec(0, 0, 0);

            for (GrapplinghookEntity hookEntity : controller.grapplehookEntities) {
                Vec hookPos = Vec.positionVec(hookEntity);
                Vec anchor = hookEntity.getSegmentHandler().getClosest(hookPos);
                Vec spherevec = playerPos.sub(anchor);
                Vec pull = spherevec.scale(-1);
                pull.mutableSetMagnitude(hookEntity.pull);

                if (pull.dot(facing) > 0 || controller.custom.get(MOTOR_WORKS_BACKWARDS.get())) {
                    Vec facingxy = new Vec(facing.x, 0, facing.z);
                    Vec facingside = facingxy.cross(new Vec(0, 1, 0)).normalize();
                    Vec sideways = pull.project(facingside);
                    Vec currentsideways = controller.motion.project(facingside);
                    sideways.mutableAdd(currentsideways);
                    double sidewayspull = sideways.dot(facingside);

                    if (pullissameway) {
                        // only 1 rope pulls
                        if (Math.abs(sidewayspull) > minabssidewayspull + 0.05) {
                            hookEntity.pull = 0;
                        }
                    } else {
                        hookEntity.pull = hookEntity.pull * minabssidewayspull / Math.abs(sidewayspull);
                    }
                    totalPull.mutableAdd(pull.withMagnitude(hookEntity.pull));
                } else {
                    if (hookEntity.isInDoublePair) {
                        if (!controller.custom.get(SINGLE_ROPE_PULL.get())) {
                            dopull = false;
                        }
                    }
                }
            }
        }

        // smart motor - angle of motion = angle facing
        // match angle (the ratio of pulling upwards to pulling sideways)
        // between the motion (after pulling and gravity) vector and the facing vector
        // if double hooks, all hooks are scaled by the same amount (to prevent pulling to the left/right)
        double pullmult = 1;
        if (controller.custom.get(SMART_MOTOR.get()) && totalPull.y > 0 && !(controller.onGroundTimer > 0 || entity.onGround())) {
            Vec pullxzvector = new Vec(totalPull.x, 0, totalPull.z);
            double pullxz = pullxzvector.length();
            double motionxz = controller.motion.project(pullxzvector).dot(pullxzvector.normalize());
            double facingxz = facing.project(pullxzvector).dot(pullxzvector.normalize());

            pullmult = (facingxz * (controller.motion.y + gravity.y) - motionxz * facing.y) / (facing.y * pullxz - facingxz * totalPull.y);

            if ((facing.y * pullxz - facingxz * totalPull.y) == 0) {
                // division by zero
                pullmult = 9999;
            }

            double pulll = pullmult * totalPull.length();

            if (pulll > controller.custom.get(MOTOR_ACCELERATION.get())) {
                pulll = controller.custom.get(MOTOR_ACCELERATION.get());
            }

            if (pulll < 0) {
                pulll = 0;
            }

            pullmult = pulll / totalPull.length();
        }

        // Prevent motor from moving too fast (motormaxspeed)
        if (controller.motion.dot(totalPull) > 0) {
            if (controller.motion.project(totalPull).length() + totalPull.scale(pullmult).length() > controller.custom.get(MAX_MOTOR_SPEED.get())) {
                pullmult = Math.max(0, (controller.custom.get(MAX_MOTOR_SPEED.get()) - controller.motion.project(totalPull).length()) / totalPull.length());
            }
        }

        // sideways dampener
        if (controller.custom.get(MOTOR_DAMPENER.get()) && totalPull.length() != 0) {
            controller.motion = controller.dampenMotion(controller.motion, totalPull);
        }

        // actually pull with the motor
        if (dopull) {
            for (GrapplinghookEntity hookEntity : controller.grapplehookEntities) {
                Vec hookPos = Vec.positionVec(hookEntity);
                Vec anchor = hookEntity.getSegmentHandler().getClosest(hookPos);
                Vec spherevec = playerPos.sub(anchor);
                Vec pull = spherevec.scale(-1);
                pull.mutableSetMagnitude(hookEntity.pull * pullmult);

                if (pull.dot(facing) > 0 || controller.custom.get(MOTOR_WORKS_BACKWARDS.get())) {
                    if (hookEntity.pull > 0) {
                        controller.motion.mutableAdd(pull);
                    }
                }
            }
        }

        // if player is at the destination, slow down
        if (close && !(controller.grapplehookEntities.size() > 1)) {
            if (entity.horizontalCollision || entity.verticalCollision || entity.onGround()) {
                controller.motion.mutableScale(0.6);
            }
        }
    }
}
