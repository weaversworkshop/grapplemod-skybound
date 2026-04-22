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

/**
 * Geometry helper for the surface-walking rope wrap algorithm. Given a raycast
 * that hit a block, find which edge of the hit face the rope should wrap around,
 * and where on that edge the bend goes.
 *
 * <p>Operates on {@link VoxelShape}s decomposed into {@link AABB}s — which covers
 * every vanilla partial block (stairs, slabs, walls, fences, buttons, etc.) and
 * any modded block whose {@code getCollisionShape} returns an
 * AABB-union shape. Rotation around a sub-level pose is handled by the caller:
 * the ray is transformed into the shape's native space before calling this class,
 * and the result is transformed back.</p>
 *
 * <p>Pure functions — no state, trivially testable.</p>
 */
public final class WrapEdgeFinder {

    /**
     * Outward offset per face applied when placing the bend point. The bend sits at
     * the wrap edge but offset {@code BEND_OFFSET} along each of the two face normals
     * — effectively treating the block as slightly larger than its real hitbox so the
     * rope doesn't catch on raw edges as the player swings past. The diagonal distance
     * from the real corner is {@code BEND_OFFSET * √2}. Observed sticking at 0.05
     * (≈7% past corner); 0.08 gives ~11% which feels smoother without the rope
     * visibly floating off the block.
     */
    private static final double BEND_OFFSET = 0.08;

    /** Probe distance for silhouette check (must be > 0 and small compared to 1 block). */
    private static final double SILHOUETTE_PROBE = 0.01;

    /**
     * Clearance required between a candidate bend point and any neighboring block's
     * collision shape. If a neighbor is within this distance, the bend would be at a
     * "pinch" where two blocks touch at a corner or share an edge — physically the
     * rope can't occupy that space. Rejected bend → next best wrap face is tried.
     *
     * <p>Should be slightly larger than {@link #BEND_OFFSET} so bends near a shared
     * corner land inside the exclusion zone, while bends on a fully-exposed edge
     * (offset {@code BEND_OFFSET} into air) don't.</p>
     */
    private static final double PINCH_CLEARANCE = 0.12;

    private WrapEdgeFinder() {}

    /** Result of {@link #findWrap}. */
    public record WrapResult(Vec bendPoint, Direction hitFace, Direction wrapFace) {}

    /**
     * Find where the rope should wrap after a raycast hit.
     *
     * @param level        world / level for collision lookup
     * @param blockPos     hit block's position
     * @param hitPoint     exact world-space point where the ray struck the surface
     * @param hitFace      direction of the hit face (ray came from {@code -hitFace} side)
     * @param rayEnd       the ray's destination point {@code B} — determines which edge the rope bends around
     * @return the wrap result, or {@code null} if no valid wrap edge found (e.g. the hit face is the target — rope runs along it)
     */
    public static @Nullable WrapResult findWrap(BlockGetter level, BlockPos blockPos, Vec3 hitPoint,
                                                Direction hitFace, Vec3 rayEnd) {
        BlockState state = level.getBlockState(blockPos);
        VoxelShape shape = state.getCollisionShape(level, blockPos);
        if (shape.isEmpty()) return null;

        // Translate the shape's local AABBs into world space (VoxelShape.toAabbs returns 0..1 local).
        List<AABB> worldBoxes = new ArrayList<>();
        for (AABB local : shape.toAabbs()) {
            worldBoxes.add(local.move(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
        }

        AABB hitBox = findBoxContainingHit(worldBoxes, hitPoint, hitFace);
        if (hitBox == null) return null;

        // Iterate wrap-face candidates in descending score order. For each, compute
        // the bend point and accept the first whose bend isn't too close to a
        // neighboring block — this catches the "pinch" case where two blocks touch
        // at a corner or share an edge, and the top-ranked face would place the
        // bend at or inside the other block's space.
        List<Direction> rankedCandidates = rankedWrapFaces(level, blockPos, hitBox, hitFace, hitPoint, rayEnd, worldBoxes);
        for (Direction wrapFace : rankedCandidates) {
            Vec bendPoint = computeBendPoint(hitBox, hitFace, wrapFace, hitPoint, rayEnd);
            if (isBendNearPinch(level, blockPos, bendPoint)) continue;
            return new WrapResult(bendPoint, hitFace, wrapFace);
        }
        return null;
    }

    /**
     * Pick the AABB within {@code boxes} whose {@code hitFace} surface contains {@code hitPoint}.
     * Uses a small tolerance because ray/AABB math returns hit points that can be epsilon off-plane.
     */
    static @Nullable AABB findBoxContainingHit(List<AABB> boxes, Vec3 hitPoint, Direction hitFace) {
        double tolerance = 1e-4;
        for (AABB box : boxes) {
            double planeCoord = facePlaneCoord(box, hitFace);
            double pointCoord = axisValue(hitPoint, hitFace.getAxis());
            if (Math.abs(pointCoord - planeCoord) > tolerance) continue;
            // Point also has to lie within the box's other two dimensions.
            if (pointWithinFace(box, hitFace, hitPoint, tolerance)) return box;
        }
        return null;
    }

    /**
     * Rank the 4 perpendicular faces of {@code hitBox} by how much the rope should
     * prefer wrapping them, highest score first. A face qualifies if:
     * <ul>
     *   <li>Its outward direction in the hit-face plane has positive alignment with
     *       {@code rayEnd - hitPoint} (i.e., the ray is trying to exit on this side), and</li>
     *   <li>Its shared edge with {@code hitFace} is a true silhouette edge (stepping
     *       outward lands in air, not inside the same VoxelShape or a neighbor block).</li>
     * </ul>
     * <p>{@link #findWrap} iterates the returned list and accepts the first candidate
     * whose resulting bend point isn't near a pinch — so a lower-ranked face can win
     * if the top pick would create a pinched bend.</p>
     */
    static List<Direction> rankedWrapFaces(BlockGetter level, BlockPos blockPos, AABB hitBox,
                                           Direction hitFace, Vec3 hitPoint, Vec3 rayEnd,
                                           List<AABB> worldBoxes) {
        Direction.Axis hitAxis = hitFace.getAxis();
        Vec3 toEnd = rayEnd.subtract(hitPoint);

        record Scored(Direction dir, double score) {}
        List<Scored> scored = new ArrayList<>(4);

        for (Direction candidate : Direction.values()) {
            if (candidate.getAxis() == hitAxis) continue; // perpendicular only

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

    /**
     * Silhouette check: is the edge between {@code hitFace} and {@code candidate} on
     * {@code hitBox} interior — i.e., covered by a solid face on the other side? True
     * if a point just outside the edge (along {@code candidate}'s outward direction,
     * in the hit-face plane) lies inside any solid block: another AABB of the hit
     * block's own VoxelShape, OR a neighboring world block's shape. The user's spec:
     * "the rope should only pass over faces that are not in contact with any other
     * block" — the neighbor-block probe enforces that.
     */
    static boolean isEdgeInterior(BlockGetter level, BlockPos blockPos, AABB hitBox,
                                  Direction hitFace, Direction candidate, List<AABB> worldBoxes) {
        // Build the edge midpoint: fixed on hitFace's plane and candidate's plane,
        // centered along the remaining perpendicular axis.
        Direction.Axis hitAxis = hitFace.getAxis();
        Direction.Axis candAxis = candidate.getAxis();
        Direction.Axis edgeAxis = remainingAxis(hitAxis, candAxis);

        double[] xyz = new double[3];
        writeAxis(xyz, hitAxis, facePlaneCoord(hitBox, hitFace));
        writeAxis(xyz, candAxis, facePlaneCoord(hitBox, candidate));
        writeAxis(xyz, edgeAxis, (axisMin(hitBox, edgeAxis) + axisMax(hitBox, edgeAxis)) * 0.5);

        // Step outward along candidate's normal.
        Vec3 probe = new Vec3(
                xyz[0] + candidate.getStepX() * SILHOUETTE_PROBE,
                xyz[1] + candidate.getStepY() * SILHOUETTE_PROBE,
                xyz[2] + candidate.getStepZ() * SILHOUETTE_PROBE);

        // (a) Same-shape check — another AABB of the hit block's VoxelShape.
        for (AABB other : worldBoxes) {
            if (other == hitBox) continue;
            if (other.contains(probe)) return true;
        }

        // (b) Neighbor-block check — a different block whose collision shape covers
        // the probe point. Skip if the probe still falls in the hit block's own pos
        // (same-shape check above already handled it).
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

    /**
     * Pinch-point check: is the bend point within {@link #PINCH_CLEARANCE} of any
     * neighboring block's collision shape? True if placing the bend here would force
     * the rope into the corner/edge where two blocks touch — physically impossible
     * and visually ugly (rope appears to pass through the meeting point).
     *
     * <p>Probes an inflated AABB around the bend point and tests intersection with
     * neighboring blocks' collision shapes. Only checks the (up to 27) block
     * positions that the inflated AABB spans, not the owner block's own position.</p>
     */
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

    /**
     * Compute the bend point on the edge shared by {@code hitFace} and {@code wrapFace}
     * of {@code hitBox}, biased toward the closest point on that edge to the line
     * {@code hitPoint → rayEnd}, and pushed slightly off both face planes so the
     * rope doesn't clip into either.
     */
    static Vec computeBendPoint(AABB hitBox, Direction hitFace, Direction wrapFace,
                                Vec3 hitPoint, Vec3 rayEnd) {
        Direction.Axis hitAxis = hitFace.getAxis();
        Direction.Axis wrapAxis = wrapFace.getAxis();
        Direction.Axis edgeAxis = remainingAxis(hitAxis, wrapAxis);

        // Edge = {hitAxis: hitPlane, wrapAxis: wrapPlane, edgeAxis: [edgeLo..edgeHi]}.
        // Along edgeAxis we pick the hit point's own coord, clamped — that's the
        // point on the edge closest to the incoming ray, which is the bend spot.
        double hitPlane = facePlaneCoord(hitBox, hitFace);
        double wrapPlane = facePlaneCoord(hitBox, wrapFace);
        double edgeCoord = clamp(
                axisValue(hitPoint, edgeAxis),
                axisMin(hitBox, edgeAxis),
                axisMax(hitBox, edgeAxis));

        // Assemble the 3D point by placing each axis from the right source.
        double[] xyz = new double[3];
        writeAxis(xyz, hitAxis, hitPlane);
        writeAxis(xyz, wrapAxis, wrapPlane);
        writeAxis(xyz, edgeAxis, edgeCoord);

        // Push outward from both faces so the bend point sits slightly off each plane
        // and the rope doesn't clip into the block — matches the legacy algorithm's
        // BEND_OFFSET convention.
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

    // ------------------------------------------------------------------
    // Small geometry utilities
    // ------------------------------------------------------------------

    /** World-space coord of the plane containing {@code face} on {@code box}. */
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
