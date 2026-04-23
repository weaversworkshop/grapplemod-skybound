package com.weaversworkshop.grapplemod.compat.sable;

import com.mojang.logging.LogUtils;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class SableSubLevelIntegration implements SubLevelIntegration {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<UUID, Tracked> tracked = new ConcurrentHashMap<>();

    private record Tracked(SubLevel subLevel, Level level) {}

    void trackSubLevel(UUID id, SubLevel subLevel, Level level) {
        Tracked prior = tracked.put(id, new Tracked(subLevel, level));
        if (prior == null) {
            LOGGER.info("[Grapple <-> Sable] NEW sub-level tracked: uuid={} level.isClient={} pose.pos={}",
                    id, level.isClientSide, subLevel.logicalPose().position());
        }
    }

    void untrackSubLevel(UUID id) {
        Tracked removed = tracked.remove(id);
        if (removed != null) {
            LOGGER.info("[Grapple <-> Sable] Sub-level untracked: uuid={} level.isClient={}",
                    id, removed.level.isClientSide);
        }
    }

    @Override
    public boolean isSubLevelLoaded(UUID subLevelId) {
        Tracked t = tracked.get(subLevelId);
        if (t == null) return false;
        if (t.subLevel.isRemoved()) return false;
        return true;
    }

    @Override
    public @Nullable UUID findSubLevelAlongRay(Vec3 rayStart, Vec3 rayEnd) {
        UUID closest = null;
        double closestDist = Double.POSITIVE_INFINITY;

        for (Map.Entry<UUID, Tracked> entry : tracked.entrySet()) {
            SubLevel sl = entry.getValue().subLevel;
            if (sl.isRemoved()) continue;

            BoundingBox3dc bounds = sl.boundingBox();
            if (bounds == null) continue;

            double[] tNear = rayAabbIntersect(rayStart, rayEnd,
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ());
            if (tNear == null) continue;

            double dist = tNear[0];
            if (dist < closestDist) {
                closestDist = dist;
                closest = entry.getKey();
            }
        }
        return closest;
    }

    private static double @Nullable [] rayAabbIntersect(Vec3 from, Vec3 to,
                                                         double minX, double minY, double minZ,
                                                         double maxX, double maxY, double maxZ) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double tmin = 0, tmax = 1;

        for (int axis = 0; axis < 3; axis++) {
            double o = axis == 0 ? from.x : axis == 1 ? from.y : from.z;
            double d = axis == 0 ? dx : axis == 1 ? dy : dz;
            double lo = axis == 0 ? minX : axis == 1 ? minY : minZ;
            double hi = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;

            if (Math.abs(d) < 1e-9) {
                if (o < lo || o > hi) return null;
            } else {
                double t1 = (lo - o) / d;
                double t2 = (hi - o) / d;
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) return null;
            }
        }
        return new double[]{tmin, tmax};
    }

    @Override
    public @Nullable Vec3 raycastSubLevel(UUID subLevelId, Vec3 rayStart, Vec3 rayEnd, float partialTicks) {
        Tracked t = tracked.get(subLevelId);
        if (t == null) {
            LOGGER.warn("[Grapple <-> Sable] raycastSubLevel for UNTRACKED uuid={}", subLevelId);
            return null;
        }

        Pose3dc pose = t.subLevel.logicalPose();
        Vec3 plotStart = pose.transformPositionInverse(rayStart);
        Vec3 plotEnd = pose.transformPositionInverse(rayEnd);

        BlockPos hit = voxelTraverse(t.subLevel, plotStart, plotEnd);
        if (hit == null) return null;

        double[] tRange = rayAabbIntersect(plotStart, plotEnd,
                hit.getX(), hit.getY(), hit.getZ(),
                hit.getX() + 1, hit.getY() + 1, hit.getZ() + 1);
        Vec3 plotEntry;
        if (tRange != null) {
            double tEnter = Math.max(0.0, tRange[0]);
            plotEntry = new Vec3(
                    plotStart.x + (plotEnd.x - plotStart.x) * tEnter,
                    plotStart.y + (plotEnd.y - plotStart.y) * tEnter,
                    plotStart.z + (plotEnd.z - plotStart.z) * tEnter);
        } else {
            plotEntry = new Vec3(hit.getX() + 0.5, hit.getY() + 0.5, hit.getZ() + 0.5);
        }
        return pose.transformPosition(plotEntry);
    }

    @Override
    public @Nullable SubLevelRaycastHit raycastSubLevelDetailed(UUID subLevelId, Vec3 rayStart, Vec3 rayEnd, float partialTicks) {
        Tracked t = tracked.get(subLevelId);
        if (t == null) return null;

        Pose3dc pose = t.subLevel.logicalPose();
        Vec3 plotStart = pose.transformPositionInverse(rayStart);
        Vec3 plotEnd = pose.transformPositionInverse(rayEnd);

        VoxelHit hit = voxelTraverseDetailed(t.subLevel, plotStart, plotEnd);
        if (hit == null) return null;

        Vec3 worldHit = pose.transformPosition(hit.plotHit);
        return new SubLevelRaycastHit(worldHit, hit.face, hit.plotHit, hit.pos);
    }

    private record VoxelHit(BlockPos pos, Direction face, Vec3 plotHit) {}

    private record ShapeHit(double t, Direction face, AABB box) {}

    private static @Nullable ShapeHit intersectShapeInVoxel(BlockState state, BlockPos probe, Vec3 from, Vec3 to) {
        var shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, probe);
        if (shape.isEmpty()) return null;
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double bestT = Double.POSITIVE_INFINITY;
        Direction bestFace = null;
        AABB bestBox = null;
        for (AABB local : shape.toAabbs()) {
            double minX = local.minX + probe.getX();
            double minY = local.minY + probe.getY();
            double minZ = local.minZ + probe.getZ();
            double maxX = local.maxX + probe.getX();
            double maxY = local.maxY + probe.getY();
            double maxZ = local.maxZ + probe.getZ();
            double tmin = 0, tmax = 1;
            Direction enterFace = null;
            boolean missed = false;
            for (int axis = 0; axis < 3; axis++) {
                double o = axis == 0 ? from.x : axis == 1 ? from.y : from.z;
                double d = axis == 0 ? dx : axis == 1 ? dy : dz;
                double lo = axis == 0 ? minX : axis == 1 ? minY : minZ;
                double hi = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
                if (Math.abs(d) < 1e-9) {
                    if (o < lo || o > hi) { missed = true; break; }
                } else {
                    double t1 = (lo - o) / d;
                    double t2 = (hi - o) / d;
                    Direction faceAtT1;
                    if (axis == 0) faceAtT1 = d > 0 ? Direction.WEST : Direction.EAST;
                    else if (axis == 1) faceAtT1 = d > 0 ? Direction.DOWN : Direction.UP;
                    else faceAtT1 = d > 0 ? Direction.NORTH : Direction.SOUTH;
                    if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; faceAtT1 = faceAtT1.getOpposite(); }
                    if (t1 > tmin) { tmin = t1; enterFace = faceAtT1; }
                    if (t2 < tmax) tmax = t2;
                    if (tmin > tmax) { missed = true; break; }
                }
            }
            if (missed) continue;
            if (tmin < 0) continue;
            if (tmin < bestT) {
                bestT = tmin;
                bestFace = enterFace;
                bestBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
            }
        }
        if (bestBox == null) return null;
        if (bestFace == null) {
            double adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
            if (adx >= ady && adx >= adz) bestFace = dx > 0 ? Direction.WEST : Direction.EAST;
            else if (ady >= adz) bestFace = dy > 0 ? Direction.DOWN : Direction.UP;
            else bestFace = dz > 0 ? Direction.NORTH : Direction.SOUTH;
        }
        return new ShapeHit(bestT, bestFace, bestBox);
    }

    @SuppressWarnings("unused")
    private static void diagnoseChunkColumn(SubLevel subLevel, Vec3 plotStart) {
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) return;
        int x = Mth.floor(plotStart.x), z = Mth.floor(plotStart.z);
        ChunkPos globalChunkPos = new ChunkPos(x >> 4, z >> 4);
        if (!plot.contains(globalChunkPos)) {
            LOGGER.info("[Grapple <-> Sable] DIAG column: start chunk {} not in plot", globalChunkPos);
            return;
        }
        LevelChunk chunk = plot.getChunk(plot.toLocal(globalChunkPos));
        if (chunk == null) {
            LOGGER.info("[Grapple <-> Sable] DIAG column: local chunk null", plot.toLocal(globalChunkPos));
            return;
        }
        LOGGER.info("[Grapple <-> Sable] DIAG column: chunk.getPos()={} minSection={} maxSection={}; scanning x={} z={} y=[-64,320] for non-air:",
                chunk.getPos(), chunk.getMinSection(), chunk.getMaxSection(), x, z);
        int found = 0;
        for (int y = -64; y < 320 && found < 20; y++) {
            BlockPos probe = new BlockPos(x, y, z);
            BlockState state = chunk.getBlockState(probe);
            if (!state.isAir()) {
                LOGGER.info("[Grapple <-> Sable]   y={} {}", y, state.getBlock());
                found++;
            }
        }
        if (found == 0) {
            LOGGER.info("[Grapple <-> Sable]   entire column is air at plot x={} z={} — blocks must be at a different (x,z). Scanning chunk's full block count:", x, z);
            int nonAirTotal = 0;
            int sampleX = -1, sampleY = -1, sampleZ = -1;
            outer:
            for (int lx = 0; lx < 16; lx++) for (int ly = chunk.getMinBuildHeight(); ly < chunk.getMaxBuildHeight(); ly++) for (int lz = 0; lz < 16; lz++) {
                int wx = (chunk.getPos().x << 4) + lx;
                int wz = (chunk.getPos().z << 4) + lz;
                BlockPos probe = new BlockPos(wx, ly, wz);
                if (!chunk.getBlockState(probe).isAir()) {
                    nonAirTotal++;
                    if (sampleX < 0) { sampleX = wx; sampleY = ly; sampleZ = wz; }
                    if (nonAirTotal >= 5000) break outer;
                }
            }
            LOGGER.info("[Grapple <-> Sable]   chunk non-air total={} firstSample=({}, {}, {})", nonAirTotal, sampleX, sampleY, sampleZ);

            if (sampleX >= 0) {
                Pose3dc pose = subLevel.logicalPose();
                var pp = pose.position();
                var pr = pose.rotationPoint();
                BoundingBox3dc bb = subLevel.boundingBox();
                LOGGER.info("[Grapple <-> Sable]   POSE pos=({}, {}, {}) rotPoint=({}, {}, {}) bbox=[{},{},{}]..[{},{},{}]",
                        pp.x(), pp.y(), pp.z(), pr.x(), pr.y(), pr.z(),
                        bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());

                LOGGER.info("[Grapple <-> Sable]   All non-air block world positions:");
                int logged = 0;
                outer2:
                for (int lx = 0; lx < 16 && logged < 30; lx++)
                    for (int ly = chunk.getMinBuildHeight(); ly < chunk.getMaxBuildHeight() && logged < 30; ly++)
                        for (int lz = 0; lz < 16 && logged < 30; lz++) {
                            int wx = (chunk.getPos().x << 4) + lx;
                            int wz = (chunk.getPos().z << 4) + lz;
                            BlockPos probe = new BlockPos(wx, ly, wz);
                            BlockState s = chunk.getBlockState(probe);
                            if (!s.isAir()) {
                                Vec3 plotC = new Vec3(wx + 0.5, ly + 0.5, wz + 0.5);
                                Vec3 worldC = pose.transformPosition(plotC);
                                LOGGER.info("[Grapple <-> Sable]     plot=({}, {}, {}) -> world=({}, {}, {}) block={}",
                                        wx, ly, wz, worldC.x, worldC.y, worldC.z, s.getBlock());
                                logged++;
                            }
                        }
            }
        }
    }

    private static @Nullable VoxelHit voxelTraverseDetailed(SubLevel subLevel, Vec3 from, Vec3 to) {
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) return null;

        int x = Mth.floor(from.x), y = Mth.floor(from.y), z = Mth.floor(from.z);
        int endX = Mth.floor(to.x), endY = Mth.floor(to.y), endZ = Mth.floor(to.z);

        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;

        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

        double tMaxX = stepX > 0 ? (x + 1 - from.x) / dx : stepX < 0 ? (from.x - x) / -dx : Double.POSITIVE_INFINITY;
        double tMaxY = stepY > 0 ? (y + 1 - from.y) / dy : stepY < 0 ? (from.y - y) / -dy : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ > 0 ? (z + 1 - from.z) / dz : stepZ < 0 ? (from.z - z) / -dz : Double.POSITIVE_INFINITY;

        for (int i = 0; i < 256; i++) {
            ChunkPos globalChunkPos = new ChunkPos(x >> 4, z >> 4);
            if (plot.contains(globalChunkPos)) {
                LevelChunk chunk = plot.getChunk(plot.toLocal(globalChunkPos));
                if (chunk != null) {
                    BlockPos probe = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(probe);
                    if (!state.isAir()) {
                        ShapeHit shapeHit = intersectShapeInVoxel(state, probe, from, to);
                        if (shapeHit != null) {
                            double tE = shapeHit.t;
                            Vec3 plotHit = new Vec3(
                                    from.x + (to.x - from.x) * tE,
                                    from.y + (to.y - from.y) * tE,
                                    from.z + (to.z - from.z) * tE);
                            return new VoxelHit(probe, shapeHit.face, plotHit);
                        }
                    }
                }
            }
            if (x == endX && y == endY && z == endZ) return null;

            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX; tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY; tMaxY += tDeltaY;
            } else {
                z += stepZ; tMaxZ += tDeltaZ;
            }
        }
        return null;
    }

    private static @Nullable BlockPos voxelTraverse(SubLevel subLevel, Vec3 from, Vec3 to) {
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) return null;

        int x = Mth.floor(from.x), y = Mth.floor(from.y), z = Mth.floor(from.z);
        int endX = Mth.floor(to.x), endY = Mth.floor(to.y), endZ = Mth.floor(to.z);

        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;

        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

        double tMaxX = stepX > 0 ? (x + 1 - from.x) / dx : stepX < 0 ? (from.x - x) / -dx : Double.POSITIVE_INFINITY;
        double tMaxY = stepY > 0 ? (y + 1 - from.y) / dy : stepY < 0 ? (from.y - y) / -dy : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ > 0 ? (z + 1 - from.z) / dz : stepZ < 0 ? (from.z - z) / -dz : Double.POSITIVE_INFINITY;

        for (int i = 0; i < 256; i++) {
            ChunkPos globalChunkPos = new ChunkPos(x >> 4, z >> 4);
            if (plot.contains(globalChunkPos)) {
                LevelChunk chunk = plot.getChunk(plot.toLocal(globalChunkPos));
                if (chunk != null) {
                    BlockPos probe = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(probe);
                    if (!state.isAir()
                            && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, probe).isEmpty()) {
                        return probe;
                    }
                }
            }
            if (x == endX && y == endY && z == endZ) return null;

            if (tMaxX < tMaxY && tMaxX < tMaxZ) { x += stepX; tMaxX += tDeltaX; }
            else if (tMaxY < tMaxZ)             { y += stepY; tMaxY += tDeltaY; }
            else                                 { z += stepZ; tMaxZ += tDeltaZ; }
        }
        return null;
    }

    @Override
    public Vec3 plotToWorld(UUID subLevelId, Vec3 plotPoint, float partialTicks) {
        Tracked t = tracked.get(subLevelId);
        if (t == null || t.subLevel.isRemoved()) {
            return plotPoint;
        }
        try {
            return t.subLevel.logicalPose().transformPosition(plotPoint);
        } catch (Throwable err) {
            LOGGER.warn("[Grapple <-> Sable] plotToWorld threw for uuid={} — sub-level may be in teardown; returning identity.",
                    subLevelId, err);
            return plotPoint;
        }
    }

    @Override
    public Vec3 worldToPlot(UUID subLevelId, Vec3 worldPoint, float partialTicks) {
        Tracked t = tracked.get(subLevelId);
        if (t == null || t.subLevel.isRemoved()) return worldPoint;
        try {
            return t.subLevel.logicalPose().transformPositionInverse(worldPoint);
        } catch (Throwable err) {
            LOGGER.warn("[Grapple <-> Sable] worldToPlot threw for uuid={}", subLevelId, err);
            return worldPoint;
        }
    }

    @Override
    public BlockPos worldToPlotBlock(UUID subLevelId, Vec3 worldPoint, float partialTicks) {
        return BlockPos.containing(worldToPlot(subLevelId, worldPoint, partialTicks));
    }

    @Override
    public @Nullable BlockPos getCapturedPlotPos(UUID subLevelId, BlockPos worldPos) {
        Tracked t = tracked.get(subLevelId);
        if (t == null) return null;

        Vec3 worldCentre = new Vec3(worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5);
        Vec3 plotPoint = t.subLevel.logicalPose().transformPositionInverse(worldCentre);
        BlockPos centre = BlockPos.containing(plotPoint);

        LevelPlot plot = t.subLevel.getPlot();
        if (plot == null) return null;

        BlockPos hit = probeNonAir(plot, centre);
        if (hit != null) return hit;

        for (int r = 1; r <= 1; r++) {
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos probe = centre.offset(dx, dy, dz);
                        BlockPos result = probeNonAir(plot, probe);
                        if (result != null) {
                            LOGGER.info("[Grapple <-> Sable] getCapturedPlotPos uuid={} worldPos={} plotCentre={} (air) — found neighbour at {} (offset {},{},{})",
                                    subLevelId, worldPos, centre, result, dx, dy, dz);
                            return result;
                        }
                    }
        }

        LOGGER.info("[Grapple <-> Sable] getCapturedPlotPos uuid={} worldPos={} → plotPoint={} plotCentre={} — all 27 cells air, migration will skip.",
                subLevelId, worldPos, plotPoint, centre);
        return null;
    }

    @Override
    public List<AABB> getPlotCollisionBoxes(UUID subLevelId, BlockPos plotBlock) {
        Tracked t = tracked.get(subLevelId);
        if (t == null || t.subLevel.isRemoved()) return List.of();
        LevelPlot plot = t.subLevel.getPlot();
        if (plot == null) return List.of();
        ChunkPos chunkPos = new ChunkPos(plotBlock.getX() >> 4, plotBlock.getZ() >> 4);
        if (!plot.contains(chunkPos)) return List.of();
        LevelChunk chunk = plot.getChunk(plot.toLocal(chunkPos));
        if (chunk == null) return List.of();
        BlockState state = chunk.getBlockState(plotBlock);
        if (state.isAir()) return List.of();
        var shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, plotBlock);
        if (shape.isEmpty()) return List.of();
        List<AABB> out = new ArrayList<>();
        for (AABB local : shape.toAabbs()) {
            out.add(local.move(plotBlock.getX(), plotBlock.getY(), plotBlock.getZ()));
        }
        return out;
    }

    @Override
    public boolean isPlotBlockSolid(UUID subLevelId, BlockPos plotBlock) {
        Tracked t = tracked.get(subLevelId);
        if (t == null || t.subLevel.isRemoved()) return false;
        LevelPlot plot = t.subLevel.getPlot();
        if (plot == null) return false;
        ChunkPos chunkPos = new ChunkPos(plotBlock.getX() >> 4, plotBlock.getZ() >> 4);
        if (!plot.contains(chunkPos)) return false;
        LevelChunk chunk = plot.getChunk(plot.toLocal(chunkPos));
        if (chunk == null) return false;
        BlockState state = chunk.getBlockState(plotBlock);
        if (state.isAir()) return false;
        return !state.getCollisionShape(EmptyBlockGetter.INSTANCE, plotBlock).isEmpty();
    }

    private static @Nullable BlockPos probeNonAir(LevelPlot plot, BlockPos probe) {
        ChunkPos globalChunkPos = new ChunkPos(probe.getX() >> 4, probe.getZ() >> 4);
        if (!plot.contains(globalChunkPos)) return null;
        LevelChunk chunk = plot.getChunk(plot.toLocal(globalChunkPos));
        if (chunk == null) return null;
        BlockState state = chunk.getBlockState(probe);
        return state.isAir() ? null : probe;
    }

    public @Nullable SubLevel getSubLevel(UUID subLevelId) {
        Tracked t = tracked.get(subLevelId);
        return t != null ? t.subLevel : null;
    }

    @Override
    public void forEachTrackedSubLevel(BiConsumer<UUID, AABB> visitor) {
        for (Map.Entry<UUID, Tracked> entry : tracked.entrySet()) {
            SubLevel sl = entry.getValue().subLevel;
            if (sl.isRemoved()) continue;
            BoundingBox3dc bb = sl.boundingBox();
            if (bb == null) continue;
            AABB box = new AABB(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
            visitor.accept(entry.getKey(), box);
        }
    }

    @Override
    public boolean anyTrackedSubLevelOverlaps(AABB probe) {
        for (Map.Entry<UUID, Tracked> entry : tracked.entrySet()) {
            SubLevel sl = entry.getValue().subLevel;
            if (sl.isRemoved()) continue;
            BoundingBox3dc bb = sl.boundingBox();
            if (bb == null) continue;
            if (probe.maxX < bb.minX() || probe.minX > bb.maxX()) continue;
            if (probe.maxY < bb.minY() || probe.minY > bb.maxY()) continue;
            if (probe.maxZ < bb.minZ() || probe.minZ > bb.maxZ()) continue;
            return true;
        }
        return false;
    }

    @Override
    public @Nullable UUID findSubLevelForPlotBlock(BlockPos plotPos) {
        ChunkPos chunkPos = new ChunkPos(plotPos.getX() >> 4, plotPos.getZ() >> 4);
        for (Map.Entry<UUID, Tracked> entry : tracked.entrySet()) {
            SubLevel sl = entry.getValue().subLevel;
            if (sl.isRemoved()) continue;
            LevelPlot plot = sl.getPlot();
            if (plot == null) continue;
            if (plot.contains(chunkPos)) return entry.getKey();
        }
        return null;
    }
}
