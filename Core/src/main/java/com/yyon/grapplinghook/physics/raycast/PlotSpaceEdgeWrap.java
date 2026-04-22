package com.yyon.grapplinghook.physics.raycast;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Edge-aware wrap bend placement for sub-level collision hits.
 *
 * <p>Sub-level blocks live in plot-space on an axis-aligned unit grid (the
 * compat module stores them that way regardless of the host's world-space
 * pose). When a rope hits a sub-level, placing the bend directly on the hit
 * face's center is insufficient — for pole-like shapes the rope needs to
 * wrap <em>around the edge</em> of the block, not anchor on its face. This
 * helper mirrors the WORLD-space logic in {@link WrapEdgeFinder} but
 * simplified for unit-cube plot blocks: picks the wrap edge whose outward
 * direction most aligns with the rope's continuation, then places the bend
 * on that edge with outward offsets along both shared faces.</p>
 *
 * <p>Purely geometric — no {@code Level} / {@code VoxelShape} access, safe
 * to run from any thread. Operates entirely in plot-space coordinates;
 * callers are responsible for transforming the result back to world-space
 * via {@link com.yyon.grapplinghook.integration.SubLevelIntegration#plotToWorld}.</p>
 */
public final class PlotSpaceEdgeWrap {

    /** Outward offset from each of the two edge-adjacent faces where the bend sits. */
    public static final double EDGE_OFFSET = 0.08;

    public record Result(Vec3 plotBendPos, Direction wrapFace) {}

    private PlotSpaceEdgeWrap() {}

    /**
     * @param block      plot-space block pos of the hit block.
     * @param face       hit face (outward normal) in plot-space.
     * @param plotHit    plot-space hit point (on the face).
     * @param plotRayEnd plot-space ray endpoint past the hit (i.e., where the
     *                   rope is trying to reach on the other side of the block).
     * @return the wrap bend (plot-space position + the wrap face), or
     *         {@code null} for a degenerate ray that produces no usable
     *         direction in the face plane.
     */
    public static @Nullable Result findWrap(BlockPos block, Direction face, Vec3 plotHit, Vec3 plotRayEnd) {
        double nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();

        // Project plotRayEnd onto the hit-face plane.
        double dx = plotRayEnd.x - plotHit.x;
        double dy = plotRayEnd.y - plotHit.y;
        double dz = plotRayEnd.z - plotHit.z;
        double dotN = dx * nx + dy * ny + dz * nz;
        // 2D direction from hit point to projected ray end (in the face plane).
        double flatX = dx - dotN * nx;
        double flatY = dy - dotN * ny;
        double flatZ = dz - dotN * nz;
        double flatLen = Math.sqrt(flatX * flatX + flatY * flatY + flatZ * flatZ);
        if (flatLen < 1e-6) return null;
        flatX /= flatLen; flatY /= flatLen; flatZ /= flatLen;

        // Pick the perpendicular-to-face direction whose step most aligns with flat.
        Axis faceAxis = face.getAxis();
        Direction bestWrap = null;
        double bestDot = -Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            if (d.getAxis() == faceAxis) continue;
            double dot = flatX * d.getStepX() + flatY * d.getStepY() + flatZ * d.getStepZ();
            if (dot > bestDot) { bestDot = dot; bestWrap = d; }
        }
        if (bestWrap == null) return null;

        // The wrap edge is the edge of the hit face shared with bestWrap's face.
        // Its midpoint: block center + 0.5*hit-face-normal + 0.5*wrap-face-normal.
        double wx = bestWrap.getStepX(), wy = bestWrap.getStepY(), wz = bestWrap.getStepZ();
        double midX = block.getX() + 0.5 + 0.5 * nx + 0.5 * wx;
        double midY = block.getY() + 0.5 + 0.5 * ny + 0.5 * wy;
        double midZ = block.getZ() + 0.5 + 0.5 * nz + 0.5 * wz;

        // Edge axis: perpendicular to both face axis and wrap-face axis.
        Axis edgeAxis = thirdAxis(faceAxis, bestWrap.getAxis());
        double ex = edgeAxis == Axis.X ? 1 : 0;
        double ey = edgeAxis == Axis.Y ? 1 : 0;
        double ez = edgeAxis == Axis.Z ? 1 : 0;

        // Closest parameter t along the edge (axis-aligned, length 1, centered at mid)
        // to the original ray line (plotHit → plotRayEnd). Edge parameterised t ∈ [-0.5, 0.5].
        double rx = plotRayEnd.x - plotHit.x;
        double ry = plotRayEnd.y - plotHit.y;
        double rz = plotRayEnd.z - plotHit.z;
        double a11 = ex * ex + ey * ey + ez * ez;       // = 1 for axis-aligned unit edge
        double a12 = -(ex * rx + ey * ry + ez * rz);
        double a21 = ex * rx + ey * ry + ez * rz;
        double a22 = -(rx * rx + ry * ry + rz * rz);
        double b1 = (plotHit.x - midX) * ex + (plotHit.y - midY) * ey + (plotHit.z - midZ) * ez;
        double b2 = (plotHit.x - midX) * rx + (plotHit.y - midY) * ry + (plotHit.z - midZ) * rz;
        double det = a11 * a22 - a12 * a21;
        double tEdge;
        if (Math.abs(det) < 1e-9) {
            tEdge = 0.0;  // ray parallel to edge — use midpoint
        } else {
            tEdge = (b1 * a22 - b2 * a12) / det;
            if (tEdge < -0.5) tEdge = -0.5;
            if (tEdge >  0.5) tEdge =  0.5;
        }

        double edgeX = midX + ex * tEdge;
        double edgeY = midY + ey * tEdge;
        double edgeZ = midZ + ez * tEdge;

        // Offset outward along both adjacent faces.
        Vec3 bend = new Vec3(
                edgeX + nx * EDGE_OFFSET + wx * EDGE_OFFSET,
                edgeY + ny * EDGE_OFFSET + wy * EDGE_OFFSET,
                edgeZ + nz * EDGE_OFFSET + wz * EDGE_OFFSET);
        return new Result(bend, bestWrap);
    }

    private static Axis thirdAxis(Axis a, Axis b) {
        if (a == b) return Axis.X;  // degenerate; shouldn't happen
        if (a != Axis.X && b != Axis.X) return Axis.X;
        if (a != Axis.Y && b != Axis.Y) return Axis.Y;
        return Axis.Z;
    }
}
