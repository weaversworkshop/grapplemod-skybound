package com.yyon.grapplinghook.physics.raycast;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class PlotSpaceEdgeWrap {

    public static final double EDGE_OFFSET = 0.08;

    public record Result(Vec3 plotBendPos, Direction wrapFace) {}

    private PlotSpaceEdgeWrap() {}

    public static @Nullable Result findWrap(BlockPos block, Direction face, Vec3 plotHit, Vec3 plotRayEnd) {
        double nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();

        double dx = plotRayEnd.x - plotHit.x;
        double dy = plotRayEnd.y - plotHit.y;
        double dz = plotRayEnd.z - plotHit.z;
        double dotN = dx * nx + dy * ny + dz * nz;
        double flatX = dx - dotN * nx;
        double flatY = dy - dotN * ny;
        double flatZ = dz - dotN * nz;
        double flatLen = Math.sqrt(flatX * flatX + flatY * flatY + flatZ * flatZ);
        if (flatLen < 1e-6) return null;
        flatX /= flatLen; flatY /= flatLen; flatZ /= flatLen;

        Axis faceAxis = face.getAxis();
        Direction bestWrap = null;
        double bestDot = -Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            if (d.getAxis() == faceAxis) continue;
            double dot = flatX * d.getStepX() + flatY * d.getStepY() + flatZ * d.getStepZ();
            if (dot > bestDot) { bestDot = dot; bestWrap = d; }
        }
        if (bestWrap == null) return null;

        double wx = bestWrap.getStepX(), wy = bestWrap.getStepY(), wz = bestWrap.getStepZ();
        double midX = block.getX() + 0.5 + 0.5 * nx + 0.5 * wx;
        double midY = block.getY() + 0.5 + 0.5 * ny + 0.5 * wy;
        double midZ = block.getZ() + 0.5 + 0.5 * nz + 0.5 * wz;

        Axis edgeAxis = thirdAxis(faceAxis, bestWrap.getAxis());
        double ex = edgeAxis == Axis.X ? 1 : 0;
        double ey = edgeAxis == Axis.Y ? 1 : 0;
        double ez = edgeAxis == Axis.Z ? 1 : 0;

        double rx = plotRayEnd.x - plotHit.x;
        double ry = plotRayEnd.y - plotHit.y;
        double rz = plotRayEnd.z - plotHit.z;
        double a11 = ex * ex + ey * ey + ez * ez;
        double a12 = -(ex * rx + ey * ry + ez * rz);
        double a21 = ex * rx + ey * ry + ez * rz;
        double a22 = -(rx * rx + ry * ry + rz * rz);
        double b1 = (plotHit.x - midX) * ex + (plotHit.y - midY) * ey + (plotHit.z - midZ) * ez;
        double b2 = (plotHit.x - midX) * rx + (plotHit.y - midY) * ry + (plotHit.z - midZ) * rz;
        double det = a11 * a22 - a12 * a21;
        double tEdge;
        if (Math.abs(det) < 1e-9) {
            tEdge = 0.0;
        } else {
            tEdge = (b1 * a22 - b2 * a12) / det;
            if (tEdge < -0.5) tEdge = -0.5;
            if (tEdge >  0.5) tEdge =  0.5;
        }

        double edgeX = midX + ex * tEdge;
        double edgeY = midY + ey * tEdge;
        double edgeZ = midZ + ez * tEdge;

        Vec3 bend = new Vec3(
                edgeX + nx * EDGE_OFFSET + wx * EDGE_OFFSET,
                edgeY + ny * EDGE_OFFSET + wy * EDGE_OFFSET,
                edgeZ + nz * EDGE_OFFSET + wz * EDGE_OFFSET);
        return new Result(bend, bestWrap);
    }

    private static Axis thirdAxis(Axis a, Axis b) {
        if (a == b) return Axis.X;
        if (a != Axis.X && b != Axis.X) return Axis.X;
        if (a != Axis.Y && b != Axis.Y) return Axis.Y;
        return Axis.Z;
    }
}
