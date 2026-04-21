package com.weaversworkshop.grapplemod.compat.sable;

import com.mojang.logging.LogUtils;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sable-backed {@link SubLevelIntegration}. Keeps an internal
 * {@code UUID → (SubLevel, Level)} cache, refreshed by the tick poll in
 * {@link SableCompatModule}. This means the SPI surface itself stays level-free —
 * Core can call {@link #plotToWorld} without knowing which Minecraft level the
 * sub-level lives in.
 */
public class SableSubLevelIntegration implements SubLevelIntegration {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Concurrent because tick-poll writes and physics-thread reads can interleave. */
    private final Map<UUID, Tracked> tracked = new ConcurrentHashMap<>();

    private record Tracked(SubLevel subLevel, Level level) {}

    /** Called by {@link SableCompatModule} each tick for every live sub-level. */
    void trackSubLevel(UUID id, SubLevel subLevel, Level level) {
        Tracked prior = tracked.put(id, new Tracked(subLevel, level));
        if (prior == null) {
            LOGGER.info("[Grapple <-> Sable] NEW sub-level tracked: uuid={} level.isClient={} pose.pos={}",
                    id, level.isClientSide, subLevel.logicalPose().position());
        }
    }

    /** Called by {@link SableCompatModule} when a UUID disappears from the container. */
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
        // Sable marks the SubLevel removed during disassembly; treat it as unloaded
        // even if our tick-poll hasn't yet observed the UUID disappear from the container.
        if (t.subLevel.isRemoved()) return false;
        return true;
    }

    @Override
    public @Nullable UUID findSubLevelAlongRay(Vec3 rayStart, Vec3 rayEnd) {
        UUID closest = null;
        double closestDist = Double.POSITIVE_INFINITY;

        for (Map.Entry<UUID, Tracked> entry : tracked.entrySet()) {
            SubLevel sl = entry.getValue().subLevel;

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
        if (closest != null) {
            LOGGER.info("[Grapple <-> Sable] findSubLevelAlongRay hit uuid={} ray={}->{} tNear={}",
                    closest, rayStart, rayEnd, closestDist);
        }
        return closest;
    }

    /**
     * Slab-method ray/AABB test returning [entry-t, exit-t] along the ray, or {@code null}
     * if no intersection in [0,1]. Standard; nothing Sable-specific.
     */
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
        LOGGER.info("[Grapple <-> Sable] raycastSubLevel uuid={} rayWorld={}->{} rayPlot={}->{} hit={} pose.pos={}",
                subLevelId, rayStart, rayEnd, plotStart, plotEnd, hit, pose.position());
        if (hit == null) {
            // DIAGNOSTIC: if the ray missed, scan the column at the starting (x,z) to
            // find where the ship's non-air blocks actually live in plot space.
            diagnoseChunkColumn(t.subLevel, plotStart);
            return null;
        }

        Vec3 hitCentre = new Vec3(hit.getX() + 0.5, hit.getY() + 0.5, hit.getZ() + 0.5);
        Vec3 worldHit = pose.transformPosition(hitCentre);
        LOGGER.info("[Grapple <-> Sable] raycastSubLevel result: plotCentre={} worldHit={}", hitCentre, worldHit);
        return worldHit;
    }

    /** One-shot diagnostic: scan a 16-block tall column at the ray's entry (x,z) and log every non-air Y. */
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

            // Pose sanity check: dump the raw pose fields, then forward-transform the
            // sample block's plot position to see what apparent-world position Sable
            // reports for it. That world position should be inside sl.boundingBox().
            if (sampleX >= 0) {
                Pose3dc pose = subLevel.logicalPose();
                var pp = pose.position();
                var pr = pose.rotationPoint();
                BoundingBox3dc bb = subLevel.boundingBox();
                LOGGER.info("[Grapple <-> Sable]   POSE pos=({}, {}, {}) rotPoint=({}, {}, {}) bbox=[{},{},{}]..[{},{},{}]",
                        pp.x(), pp.y(), pp.z(), pr.x(), pr.y(), pr.z(),
                        bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());

                // Log ALL non-air blocks' world positions so we can see where the ship actually is visually.
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

    /**
     * Amanatides-Woo voxel traversal through the sub-level's block grid in plot space.
     * Returns the first solid {@link BlockPos}, or {@code null} on miss.
     *
     * <p>Reads blocks via {@link LevelPlot#getChunk(ChunkPos)} directly — emphatically
     * NOT via {@code EmbeddedPlotLevelAccessor.getBlockState}, which delegates back to
     * {@code Level.getBlockState} and triggers vanilla chunk generation at plot coords
     * (which live in the 20-million range and blow up the chunk generator). The
     * {@link LevelPlot#contains(ChunkPos)} guard short-circuits any probe outside the
     * plot's allocated region.</p>
     */
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
            // contains(ChunkPos) takes GLOBAL chunk coords; getChunk(ChunkPos) takes
            // LOCAL (indexed within this plot). toLocal does the subtraction.
            ChunkPos globalChunkPos = new ChunkPos(x >> 4, z >> 4);
            if (plot.contains(globalChunkPos)) {
                ChunkPos localChunkPos = plot.toLocal(globalChunkPos);
                LevelChunk chunk = plot.getChunk(localChunkPos);
                if (chunk != null) {
                    BlockPos probe = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(probe);
                    boolean collidable = !state.isAir()
                            && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, probe).isEmpty();
                    LOGGER.info("[Grapple <-> Sable] voxelTraverse probe i={} pos=({}, {}, {}) global={} local={} state={} collidable={}",
                            i, x, y, z, globalChunkPos, localChunkPos, state.getBlock(), collidable);
                    if (collidable) return probe;
                } else {
                    LOGGER.info("[Grapple <-> Sable] voxelTraverse probe i={} global={} local={} chunk NULL (unloaded)",
                            i, globalChunkPos, plot.toLocal(globalChunkPos));
                }
            } else {
                LOGGER.info("[Grapple <-> Sable] voxelTraverse probe i={} global={} OUTSIDE plot", i, globalChunkPos);
            }
            if (x == endX && y == endY && z == endZ) return null;

            if (tMaxX < tMaxY && tMaxX < tMaxZ) { x += stepX; tMaxX += tDeltaX; }
            else if (tMaxY < tMaxZ)             { y += stepY; tMaxY += tDeltaY; }
            else                                 { z += stepZ; tMaxZ += tDeltaZ; }

            if (tMaxX > 1 && tMaxY > 1 && tMaxZ > 1) return null;
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

        // Project the world block's centre into plot space and check whether Sable has
        // a real block there. This is a best-effort: freshly-captured assembly state
        // should map the world block's centre cleanly onto a plot block.
        Vec3 worldCentre = new Vec3(worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5);
        Vec3 plotPoint = t.subLevel.logicalPose().transformPositionInverse(worldCentre);
        BlockPos plotBlock = BlockPos.containing(plotPoint);

        LevelPlot plot = t.subLevel.getPlot();
        if (plot == null) return null;
        ChunkPos globalChunkPos = new ChunkPos(plotBlock.getX() >> 4, plotBlock.getZ() >> 4);
        if (!plot.contains(globalChunkPos)) return null;
        LevelChunk chunk = plot.getChunk(plot.toLocal(globalChunkPos));
        if (chunk == null) return null;
        BlockState state = chunk.getBlockState(plotBlock);
        if (state.isAir()) return null;

        return plotBlock;
    }

    /** Exposes the tracked {@link Level} for a UUID — used by the mixin to look up pose. */
    public @Nullable SubLevel getSubLevel(UUID subLevelId) {
        Tracked t = tracked.get(subLevelId);
        return t != null ? t.subLevel : null;
    }

    @Override
    public @Nullable UUID findSubLevelForPlotBlock(BlockPos plotPos) {
        ChunkPos chunkPos = new ChunkPos(plotPos.getX() >> 4, plotPos.getZ() >> 4);
        for (Map.Entry<UUID, Tracked> entry : tracked.entrySet()) {
            LevelPlot plot = entry.getValue().subLevel.getPlot();
            if (plot == null) continue;
            if (plot.contains(chunkPos)) return entry.getKey();
        }
        return null;
    }
}
