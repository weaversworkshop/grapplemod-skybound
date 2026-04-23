package com.yyon.grapplinghook.client.physics.controller;

import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

final class RepelField {

    private static final double REPEL_MAX_PUSH = 0.3;

    private RepelField() {}

    static Vec checkRepel(Vec p, Level w) {
        Vec centerOfMass = p.add(0.0, 0.75, 0.0);
        Vec repelForce = new Vec(0, 0, 0);

        double t = (1.0 + Math.sqrt(5.0)) / 2.0;

        BlockPos pos = BlockPos.containing(p.x, p.y, p.z);

        if (hasBlock(pos, w)) {
            repelForce.mutableAdd(0, 1, 0);

        } else {
            repelForce.mutableAdd(castRepelForceRay(centerOfMass, new Vec(-1,  t,  0), w));
            repelForce.mutableAdd(castRepelForceRay(centerOfMass, new Vec( 1,  t,  0), w));
            repelForce.mutableAdd(castRepelForceRay(centerOfMass, new Vec(-1, -t,  0), w));
            repelForce.mutableAdd(castRepelForceRay(centerOfMass, new Vec( 1, -t,  0), w));

            repelForce.mutableAdd(castRepelForceRay(centerOfMass, new Vec( 0,  1,  t), w));
            repelForce.mutableAdd(castRepelForceRay(centerOfMass, new Vec( 0, -1,  t), w));
            repelForce.mutableAdd(castRepelForceRay(centerOfMass, new Vec( 0, -1, -t), w));
            repelForce.mutableAdd(castRepelForceRay(centerOfMass, new Vec( 0,  1, -t), w));

            repelForce.mutableAdd(castRepelForceRay(centerOfMass, new Vec( t,  0, -1), w));
            repelForce.mutableAdd(castRepelForceRay(centerOfMass, new Vec( t,  0,  1), w));
            repelForce.mutableAdd(castRepelForceRay(centerOfMass, new Vec(-t,  0, -1), w));
            repelForce.mutableAdd(castRepelForceRay(centerOfMass, new Vec(-t,  0,  1), w));
        }

        if (repelForce.length() > REPEL_MAX_PUSH) {
            repelForce.mutableSetMagnitude(REPEL_MAX_PUSH);
        }

        return repelForce;
    }

    private static Vec castRepelForceRay(Vec origin, Vec direction, Level w) {
        for (double i = 0.5; i < 10; i += 0.5) {
            Vec v2 = direction.withMagnitude(i);
            BlockPos pos = BlockPos.containing(origin.x + v2.x, origin.y + v2.y, origin.z + v2.z);

            if (!hasBlock(pos, w))
                continue;

            Vec v3 = new Vec(pos)
                    .mutableSub(origin)
                    .add(0.5D, 0.5D, 0.5D);

            return v3.mutableSetMagnitude(-1 / Math.pow(v3.length(), 2));
        }

        return new Vec(0, 0, 0);
    }

    private static boolean hasBlock(BlockPos pos, Level w) {
        return !w.getBlockState(pos).isAir();
    }
}
