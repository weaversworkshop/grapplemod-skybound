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

public class CreateContraptionIntegration implements ContraptionIntegration {

    private static final int ROTATION_SAMPLES = 64;

    private static final double HIT_MARGIN = 0.08;

    private static final float SAMPLE_START = -2.0f;
    private static final float SAMPLE_END   =  1.0f;

    private static final double CONTRAPTION_SEARCH_RADIUS = 40.0;

    @Override
    public boolean isContraption(Entity entity) {
        return entity instanceof AbstractContraptionEntity;
    }

    @Override
    public @Nullable EntityHitResult findContraptionAlongRay(Level level, Vec3 rayStart, Vec3 rayEnd) {
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

        Direction face = closestHitFace(closestHitBox, closestHitWorld);
        Vec3 localHit = contraptionEntity.toLocalVector(closestHitWorld, closestSampleTick);
        return new ContraptionRaycastHit(closestHitWorld, face, localHit);
    }

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
        Vec3 rotated = c.toGlobalVector(localPoint, partialTicks);
        double lerpX = net.minecraft.util.Mth.lerp((double) partialTicks, c.xOld, c.getX());
        double lerpY = net.minecraft.util.Mth.lerp((double) partialTicks, c.yOld, c.getY());
        double lerpZ = net.minecraft.util.Mth.lerp((double) partialTicks, c.zOld, c.getZ());
        return rotated.add(lerpX - c.getX(), lerpY - c.getY(), lerpZ - c.getZ());
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
