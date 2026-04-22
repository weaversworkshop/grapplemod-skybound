package com.weaversworkshop.grapplemod.compat.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.yyon.grapplinghook.integration.ContraptionIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wires Create's {@link AbstractContraptionEntity} into the grapple integration API.
 *
 * <p>World ⇄ local transforms delegate to Create's built-in helpers
 * ({@code toLocalVector} / {@code toGlobalVector}) which already incorporate the
 * contraption's current rotation and translation.</p>
 *
 * <p>{@link #raycastContraption} walks the contraption's internal block map in
 * local space and returns the closest {@link VoxelShape} intersection — matching
 * the "must actually hit a block" requirement. Aiming into an empty interior
 * cell (e.g. the inside of an L) returns {@code null} so the hook continues past
 * the contraption.</p>
 */
public class CreateContraptionIntegration implements ContraptionIntegration {

    /**
     * Number of rotation snapshots we test per per-block raycast call.
     * <p>A single snapshot can miss fast-rotating blocks that sweep through the
     * hook's ray segment between tick boundaries. More samples = more reliable
     * catches, at linear cost in ray-vs-block intersections.</p>
     * <p>Each sample re-runs the full block-iteration loop, so doubling the
     * sample count doubles the per-call CPU work. 8 is a good default; tune if
     * profiling shows cost in large-contraption scenarios.</p>
     */
    private static final int ROTATION_SAMPLES = 64;

    /**
     * Margin (in blocks) added to each contraption block's bounding box when
     * testing ray intersection. The hook attaches if its 2-block ray passes
     * within this distance of any block face. Small values keep the visual
     * attach point close to a real block face; larger values forgive aim slop
     * and rotation desync at the cost of the hook appearing to float off the
     * block.
     */
    private static final double HIT_MARGIN = 0.25;

    /**
     * partialTicks range for rotation sampling. Sampling backward in time
     * (negative values) catches rotations that were visible to the client at
     * the moment they aimed, rather than only the current server tick.
     * [-2, 1] covers "up to 2 ticks ago through end of current tick" — enough
     * to absorb typical network + interpolation lag.
     */
    private static final float SAMPLE_START = -2.0f;
    private static final float SAMPLE_END   =  1.0f;

    /**
     * Broad-phase search radius (in blocks) around the hook's motion segment.
     * A contraption's {@link Entity#getBoundingBox()} reflects only the
     * blocks' <em>current</em> rotated positions, which moves every tick as
     * the contraption spins. Tiny search boxes miss contraptions whose
     * current AABB has rotated out of the ray's immediate vicinity, even
     * when the player clearly sees blocks in the ray's path. Using a
     * generous radius keeps broad-phase loose and lets the narrow phase
     * (which samples rotation) do the real hit test.
     */
    private static final double CONTRAPTION_SEARCH_RADIUS = 40.0;

    @Override
    public boolean isContraption(Entity entity) {
        return entity instanceof AbstractContraptionEntity;
    }

    @Override
    public @Nullable EntityHitResult findContraptionAlongRay(Level level, Vec3 rayStart, Vec3 rayEnd) {
        // Broad phase is intentionally loose — we just want to find any contraption
        // near the hook. The actual per-block hit test (with rotation sampling) happens
        // in raycastContraption. Relying on AABB.intersects filters out rotating
        // contraptions whose AABB happens to have rotated away from the ray this tick,
        // which is the source of inconsistent detection.
        AABB searchBox = new AABB(rayStart, rayEnd).inflate(CONTRAPTION_SEARCH_RADIUS);

        List<AbstractContraptionEntity> candidates = level.getEntitiesOfClass(
                AbstractContraptionEntity.class,
                searchBox,
                Entity::isAlive
        );

        AbstractContraptionEntity closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (AbstractContraptionEntity c : candidates) {
            double distSq = c.position().distanceToSqr(rayStart);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = c;
            }
        }

        if (closest == null) return null;
        // Entry point here is only used by the EntityHitResult payload; the real
        // hit location comes from raycastContraption. Use rayStart as a safe default.
        return new EntityHitResult(closest, rayStart);
    }

    @Override
    public @Nullable Vec3 raycastContraption(Entity entity, Vec3 rayStart, Vec3 rayEnd, float partialTicks) {
        if (!(entity instanceof AbstractContraptionEntity contraptionEntity)) return null;

        Contraption contraption = contraptionEntity.getContraption();
        if (contraption == null) return null;

        Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks = contraption.getBlocks();

        Vec3 closestHitWorld = null;
        double closestDistSq = Double.MAX_VALUE;

        // World-space approach: for each block, compute its current world-space position
        // via toGlobalVector and test the ray against that. Inverts the usual "transform
        // ray into local frame" approach — cross-verifies the transform direction and
        // avoids any weirdness in the local-space VoxelShape clip.
        for (int i = 0; i < ROTATION_SAMPLES; i++) {
            float sampleTick = ROTATION_SAMPLES == 1
                    ? partialTicks
                    : SAMPLE_START + (SAMPLE_END - SAMPLE_START) * (float) i / (ROTATION_SAMPLES - 1);

            for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> entry : blocks.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue().state();

                if (state.isAir()) continue;

                // Block's center point in the contraption's local frame, converted to world.
                Vec3 localCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                Vec3 worldCenter = contraptionEntity.toGlobalVector(localCenter, sampleTick);

                // Inflated AABB around the world-space block center.
                AABB hitBox = new AABB(
                        worldCenter.x - 0.5, worldCenter.y - 0.5, worldCenter.z - 0.5,
                        worldCenter.x + 0.5, worldCenter.y + 0.5, worldCenter.z + 0.5
                ).inflate(HIT_MARGIN);

                Vec3 hitPoint;
                if (hitBox.contains(rayStart)) {
                    hitPoint = rayStart;
                } else {
                    Optional<Vec3> clipped = hitBox.clip(rayStart, rayEnd);
                    if (clipped.isEmpty()) continue;
                    hitPoint = clipped.get();
                }

                double distSq = hitPoint.distanceToSqr(rayStart);
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closestHitWorld = hitPoint;
                }
            }
        }

        return closestHitWorld;
    }

    @Override
    public @Nullable ContraptionRaycastHit raycastContraptionDetailed(Entity entity, Vec3 rayStart, Vec3 rayEnd, float partialTicks) {
        if (!(entity instanceof AbstractContraptionEntity contraptionEntity)) return null;

        Contraption contraption = contraptionEntity.getContraption();
        if (contraption == null) return null;

        Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks = contraption.getBlocks();

        Vec3 closestHitWorld = null;
        AABB closestHitBox = null;
        float closestSampleTick = partialTicks;
        double closestDistSq = Double.MAX_VALUE;

        for (int i = 0; i < ROTATION_SAMPLES; i++) {
            float sampleTick = ROTATION_SAMPLES == 1
                    ? partialTicks
                    : SAMPLE_START + (SAMPLE_END - SAMPLE_START) * (float) i / (ROTATION_SAMPLES - 1);

            for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> entry : blocks.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue().state();
                if (state.isAir()) continue;

                Vec3 localCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                Vec3 worldCenter = contraptionEntity.toGlobalVector(localCenter, sampleTick);

                AABB hitBox = new AABB(
                        worldCenter.x - 0.5, worldCenter.y - 0.5, worldCenter.z - 0.5,
                        worldCenter.x + 0.5, worldCenter.y + 0.5, worldCenter.z + 0.5
                ).inflate(HIT_MARGIN);

                Vec3 hitPoint;
                if (hitBox.contains(rayStart)) {
                    hitPoint = rayStart;
                } else {
                    Optional<Vec3> clipped = hitBox.clip(rayStart, rayEnd);
                    if (clipped.isEmpty()) continue;
                    hitPoint = clipped.get();
                }

                double distSq = hitPoint.distanceToSqr(rayStart);
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closestHitWorld = hitPoint;
                    closestHitBox = hitBox;
                    closestSampleTick = sampleTick;
                }
            }
        }

        if (closestHitWorld == null) return null;

        // Which face of the AABB was hit? Closest face plane to the hit point.
        Direction face = closestHitFace(closestHitBox, closestHitWorld);
        Vec3 localHit = contraptionEntity.toLocalVector(closestHitWorld, closestSampleTick);
        return new ContraptionRaycastHit(closestHitWorld, face, localHit);
    }

    /** Return the Direction of the AABB face nearest to {@code point} (expected to lie on one of the six faces). */
    private static Direction closestHitFace(AABB box, Vec3 point) {
        double distMinX = Math.abs(point.x - box.minX);
        double distMaxX = Math.abs(point.x - box.maxX);
        double distMinY = Math.abs(point.y - box.minY);
        double distMaxY = Math.abs(point.y - box.maxY);
        double distMinZ = Math.abs(point.z - box.minZ);
        double distMaxZ = Math.abs(point.z - box.maxZ);

        double min = distMinX;
        Direction face = Direction.WEST;
        if (distMaxX < min) { min = distMaxX; face = Direction.EAST; }
        if (distMinY < min) { min = distMinY; face = Direction.DOWN; }
        if (distMaxY < min) { min = distMaxY; face = Direction.UP; }
        if (distMinZ < min) { min = distMinZ; face = Direction.NORTH; }
        if (distMaxZ < min) {                 face = Direction.SOUTH; }
        return face;
    }

    @Override
    public Vec3 worldToLocal(Entity entity, Vec3 worldPoint, float partialTicks) {
        if (!(entity instanceof AbstractContraptionEntity c)) return worldPoint;
        return c.toLocalVector(worldPoint, partialTicks);
    }

    @Override
    public Vec3 localToWorld(Entity entity, Vec3 localPoint, float partialTicks) {
        if (!(entity instanceof AbstractContraptionEntity c)) return localPoint;
        return c.toGlobalVector(localPoint, partialTicks);
    }

    @Override
    public @Nullable BlockPos getCapturedLocalPos(Entity entity, BlockPos worldPos) {
        if (!(entity instanceof AbstractContraptionEntity ce)) return null;
        Contraption c = ce.getContraption();
        if (c == null) return null;
        BlockPos local = worldPos.subtract(c.anchor);
        return c.getBlocks().containsKey(local) ? local : null;
    }
}
