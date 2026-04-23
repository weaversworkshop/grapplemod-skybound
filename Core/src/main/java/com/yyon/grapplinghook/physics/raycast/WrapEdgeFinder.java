package com.yyon.grapplinghook.physics.raycast;

import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class WrapEdgeFinder {

    private static final double BEND_OFFSET = 0.08;

    private static final double SILHOUETTE_PROBE = 0.01;

    private static final double PINCH_CLEARANCE = 0.12;

    private WrapEdgeFinder() {}

    public record WrapResult(Vec bendPoint, Direction hitFace, Direction wrapFace) {}

    public static @Nullable WrapResult findWrap(BlockGetter level, BlockPos blockPos, Vec3 hitPoint,
                                                Direction hitFace, Vec3 rayEnd) {
        BlockState state = level.getBlockState(blockPos);
        VoxelShape shape = state.getCollisionShape(level, blockPos);
        if (shape.isEmpty()) return null;

        List<AABB> worldBoxes = new ArrayList<>();
        for (AABB local : shape.toAabbs()) {
            worldBoxes.add(local.move(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
        }

        AABB hitBox = findBoxContainingHit(worldBoxes, hitPoint, hitFace);
        if (hitBox == null) return null;

        List<Direction> rankedCandidates = rankedWrapFaces(level, blockPos, hitBox, hitFace, hitPoint, rayEnd, worldBoxes);
        for (Direction wrapFace : rankedCandidates) {
            Vec bendPoint = computeBendPoint(hitBox, hitFace, wrapFace, hitPoint, rayEnd);
            if (isBendNearPinch(level, blockPos, bendPoint)) continue;
            return new WrapResult(bendPoint, hitFace, wrapFace);
        }
        return null;
    }

    static @Nullable AABB findBoxContainingHit(List<AABB> boxes, Vec3 hitPoint, Direction hitFace) {
        double tolerance = 1e-4;
        for (AABB box : boxes) {
            double planeCoord = facePlaneCoord(box, hitFace);
            double pointCoord = axisValue(hitPoint, hitFace.getAxis());
            if (Math.abs(pointCoord - planeCoord) > tolerance) continue;
            if (pointWithinFace(box, hitFace, hitPoint, tolerance)) return box;
        }
        return null;
    }

    static List<Direction> rankedWrapFaces(BlockGetter level, BlockPos blockPos, AABB hitBox,
                                           Direction hitFace, Vec3 hitPoint, Vec3 rayEnd,
                                           List<AABB> worldBoxes) {
        Direction.Axis hitAxis = hitFace.getAxis();
        Vec3 toEnd = rayEnd.subtract(hitPoint);

        record Scored(Direction dir, double score) {}
        List<Scored> scored = new ArrayList<>(4);

        for (Direction candidate : Direction.values()) {
            if (candidate.getAxis() == hitAxis) continue;

            double score = switch (candidate) {
                case NORTH -> -toEnd.z;
                case SOUTH -> toEnd.z;
                case WEST -> -toEnd.x;
                case EAST -> toEnd.x;
                case UP -> toEnd.y;
                case DOWN -> -toEnd.y;
            };

            if (score <= 0) continue;
            if (isEdgeInterior(level, blockPos, hitBox, hitFace, candidate, worldBoxes)) continue;

            scored.add(new Scored(candidate, score));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        List<Direction> out = new ArrayList<>(scored.size());
        for (Scored s : scored) out.add(s.dir);
        return out;
    }

    static boolean isEdgeInterior(BlockGetter level, BlockPos blockPos, AABB hitBox,
                                  Direction hitFace, Direction candidate, List<AABB> worldBoxes) {
        Direction.Axis hitAxis = hitFace.getAxis();
        Direction.Axis candAxis = candidate.getAxis();
        Direction.Axis edgeAxis = remainingAxis(hitAxis, candAxis);

        double[] xyz = new double[3];
        writeAxis(xyz, hitAxis, facePlaneCoord(hitBox, hitFace));
        writeAxis(xyz, candAxis, facePlaneCoord(hitBox, candidate));
        writeAxis(xyz, edgeAxis, (axisMin(hitBox, edgeAxis) + axisMax(hitBox, edgeAxis)) * 0.5);

        Vec3 probe = new Vec3(
                xyz[0] + candidate.getStepX() * SILHOUETTE_PROBE,
                xyz[1] + candidate.getStepY() * SILHOUETTE_PROBE,
                xyz[2] + candidate.getStepZ() * SILHOUETTE_PROBE);

        for (AABB other : worldBoxes) {
            if (other == hitBox) continue;
            if (other.contains(probe)) return true;
        }

        BlockPos probeBlockPos = BlockPos.containing(probe);
        if (!probeBlockPos.equals(blockPos)) {
            BlockState neighborState = level.getBlockState(probeBlockPos);
            VoxelShape neighborShape = neighborState.getCollisionShape(level, probeBlockPos);
            if (!neighborShape.isEmpty()) {
                int nx = probeBlockPos.getX();
                int ny = probeBlockPos.getY();
                int nz = probeBlockPos.getZ();
                for (AABB neighborAabb : neighborShape.toAabbs()) {
                    if (neighborAabb.move(nx, ny, nz).contains(probe)) return true;
                }
            }
        }

        return false;
    }

    static boolean isBendNearPinch(BlockGetter level, BlockPos ourPos, Vec bendPoint) {
        AABB probe = new AABB(
                bendPoint.x - PINCH_CLEARANCE, bendPoint.y - PINCH_CLEARANCE, bendPoint.z - PINCH_CLEARANCE,
                bendPoint.x + PINCH_CLEARANCE, bendPoint.y + PINCH_CLEARANCE, bendPoint.z + PINCH_CLEARANCE);

        int xMin = (int) Math.floor(probe.minX);
        int yMin = (int) Math.floor(probe.minY);
        int zMin = (int) Math.floor(probe.minZ);
        int xMax = (int) Math.floor(probe.maxX);
        int yMax = (int) Math.floor(probe.maxY);
        int zMax = (int) Math.floor(probe.maxZ);

        for (int x = xMin; x <= xMax; x++) {
            for (int y = yMin; y <= yMax; y++) {
                for (int z = zMin; z <= zMax; z++) {
                    if (x == ourPos.getX() && y == ourPos.getY() && z == ourPos.getZ()) continue;
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    VoxelShape shape = state.getCollisionShape(level, new BlockPos(x, y, z));
                    if (shape.isEmpty()) continue;
                    for (AABB local : shape.toAabbs()) {
                        if (local.move(x, y, z).intersects(probe)) return true;
                    }
                }
            }
        }
        return false;
    }

    static Vec computeBendPoint(AABB hitBox, Direction hitFace, Direction wrapFace,
                                Vec3 hitPoint, Vec3 rayEnd) {
        Direction.Axis hitAxis = hitFace.getAxis();
        Direction.Axis wrapAxis = wrapFace.getAxis();
        Direction.Axis edgeAxis = remainingAxis(hitAxis, wrapAxis);

        double hitPlane = facePlaneCoord(hitBox, hitFace);
        double wrapPlane = facePlaneCoord(hitBox, wrapFace);
        double edgeCoord = clamp(
                axisValue(hitPoint, edgeAxis),
                axisMin(hitBox, edgeAxis),
                axisMax(hitBox, edgeAxis));

        double[] xyz = new double[3];
        writeAxis(xyz, hitAxis, hitPlane);
        writeAxis(xyz, wrapAxis, wrapPlane);
        writeAxis(xyz, edgeAxis, edgeCoord);

        xyz[0] += (hitFace.getStepX() + wrapFace.getStepX()) * BEND_OFFSET;
        xyz[1] += (hitFace.getStepY() + wrapFace.getStepY()) * BEND_OFFSET;
        xyz[2] += (hitFace.getStepZ() + wrapFace.getStepZ()) * BEND_OFFSET;

        return new Vec(xyz[0], xyz[1], xyz[2]);
    }

    private static void writeAxis(double[] xyz, Direction.Axis axis, double value) {
        switch (axis) {
            case X -> xyz[0] = value;
            case Y -> xyz[1] = value;
            case Z -> xyz[2] = value;
        }
    }

    static double facePlaneCoord(AABB box, Direction face) {
        return switch (face) {
            case DOWN -> box.minY;
            case UP -> box.maxY;
            case NORTH -> box.minZ;
            case SOUTH -> box.maxZ;
            case WEST -> box.minX;
            case EAST -> box.maxX;
        };
    }

    static boolean pointWithinFace(AABB box, Direction face, Vec3 p, double tolerance) {
        return switch (face.getAxis()) {
            case X -> p.y >= box.minY - tolerance && p.y <= box.maxY + tolerance
                   && p.z >= box.minZ - tolerance && p.z <= box.maxZ + tolerance;
            case Y -> p.x >= box.minX - tolerance && p.x <= box.maxX + tolerance
                   && p.z >= box.minZ - tolerance && p.z <= box.maxZ + tolerance;
            case Z -> p.x >= box.minX - tolerance && p.x <= box.maxX + tolerance
                   && p.y >= box.minY - tolerance && p.y <= box.maxY + tolerance;
        };
    }

    static double axisValue(Vec3 v, Direction.Axis axis) {
        return switch (axis) { case X -> v.x; case Y -> v.y; case Z -> v.z; };
    }

    static double axisMin(AABB box, Direction.Axis axis) {
        return switch (axis) { case X -> box.minX; case Y -> box.minY; case Z -> box.minZ; };
    }

    static double axisMax(AABB box, Direction.Axis axis) {
        return switch (axis) { case X -> box.maxX; case Y -> box.maxY; case Z -> box.maxZ; };
    }

    static Direction.Axis remainingAxis(Direction.Axis a, Direction.Axis b) {
        if (a == Direction.Axis.X) return b == Direction.Axis.Y ? Direction.Axis.Z : Direction.Axis.Y;
        if (a == Direction.Axis.Y) return b == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        return b == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

}
