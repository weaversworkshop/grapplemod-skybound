package com.yyon.grapplinghook.content.entity.grapplinghook;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.config.GrappleModCommonConfig;
import com.yyon.grapplinghook.content.registry.internal.ModTags;
import com.yyon.grapplinghook.integration.ContraptionIntegration;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

final class HookHitDispatcher {

    private HookHitDispatcher() {}

    static void dispatch(GrapplinghookEntity hook, HitResult hit) {
        if (hook.level().isClientSide) return;

        if (hook.attachment() != null ||
            hook.shootingEntity == null || hook.shootingEntityID == 0 || !hook.shootingEntity.isAlive() ||
            hook.tickCount < 1 ||
            hit == null
        ) {
            return;
        }

        Vec vec3d = Vec.positionVec(hook);
        Vec vec3d1 = vec3d.add(Vec.motionVec(hook));

        if (hit instanceof EntityHitResult && !GrappleModCommonConfig.get().doHooksAffectEntities()) {
            hook.onHit(GrappleModUtils.rayTraceBlocks(hook, hook.level(), vec3d, vec3d1));
            return;
        }

        BlockHitResult blockhit = hit instanceof BlockHitResult movingHit
                ? movingHit
                : null;

        if (blockhit != null) {
            BlockPos blockpos = blockhit.getBlockPos();
            BlockState block = hook.level().getBlockState(blockpos);

            if (block.is(ModTags.HOOK_BREAKS)) {
                hook.level().destroyBlock(blockpos, true);
                hook.onHit(GrappleModUtils.rayTraceBlocks(hook, hook.level(), vec3d, vec3d1));
                return;
            }
        }

        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();

            if (entity == hook.shootingEntity) {
                return;
            }

            if (!GrappleModCommonConfig.get().doHooksAffectEntities()) {
                hook.onHit(GrappleModUtils.rayTraceBlocks(hook, hook.level(), Vec.positionVec(hook), Vec.positionVec(hook).add(Vec.motionVec(hook))));
                return;
            }

            ContraptionIntegration contraptionIntegration = GrappleModIntegrations.getContraptionIntegration();
            if (contraptionIntegration.isContraption(entity)) {
                Vec3 rayStart = new Vec3(vec3d.x, vec3d.y, vec3d.z);
                Vec3 rayEnd   = new Vec3(vec3d1.x, vec3d1.y, vec3d1.z);

                Vec3 precisePoint = contraptionIntegration.raycastContraption(
                        entity, rayStart, rayEnd, GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);

                if (precisePoint != null) {
                    Vec3 localOffset = contraptionIntegration.worldToLocal(
                            entity, precisePoint, GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);
                    Vec3 backToWorld = contraptionIntegration.localToWorld(
                            entity, localOffset, GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);
                    GrappleMod.LOGGER.info(
                            "[Grapple] CREATE-path attach: entity={} id={} entity.pos={} precisePointWorld={} localOffset={} localToWorld(localOffset)={}",
                            entity.getClass().getSimpleName(), entity.getId(), entity.position(),
                            precisePoint, localOffset, backToWorld);

                    hook.serverAttach(
                            new HookAttachment.ContraptionBlock(entity, localOffset, null),
                            true);
                    return;
                }

                hook.onHit(GrappleModUtils.rayTraceBlocks(hook, hook.level(), vec3d, vec3d1));
                return;
            }

            hook.serverAttach(new HookAttachment.Entity(entity), true);

            GrappleMod.LOGGER.debug("Attached to a new entity: {}", entity.getId());

        } else if (blockhit != null) {
            BlockPos blockpos = blockhit.getBlockPos();
            Vec3 hitPoint = hit.getLocation();

            boolean looksLikePlotCoord = Math.abs(blockpos.getX()) > 10_000_000
                    || Math.abs(blockpos.getZ()) > 10_000_000;
            if (looksLikePlotCoord) {
                SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
                UUID subLevelId = sli.findSubLevelForPlotBlock(blockpos);
                if (subLevelId != null) {
                    hook.serverAttach(
                            new HookAttachment.SubLevelBlock(subLevelId, blockpos, hitPoint),
                            true);
                    return;
                }
                GrappleMod.LOGGER.warn("[Grapple <-> Sable] Plot-coord BlockHitResult but no sub-level claims block {} — falling back to plain Block attach (rope may misrender)",
                        blockpos);
            }

            hook.serverAttach(
                    new HookAttachment.Block(blockpos, hitPoint, blockhit.getDirection()),
                    false);

        } else {
            GrappleMod.LOGGER.warn("Unknown collision type when handling hook hit? Not an Entity or a Block.");
        }
    }
}
