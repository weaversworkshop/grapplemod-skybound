package com.yyon.grapplinghook.physics.attach;

import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.network.clientbound.GrappleAttachS2CPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.UUID;

public sealed interface HookAttachment
        permits HookAttachment.Block,
                HookAttachment.Entity,
                HookAttachment.ContraptionBlock,
                HookAttachment.SubLevelBlock {

    Vec3 worldHitPoint(float partialTicks);

    default HookAttachment refreshed(Level level) { return this; }

    GrappleAttachS2CPayload.GrappleAttachTarget toWireTarget();

    default @Nullable Direction ropeAnchorFace() { return null; }

    static Direction inferFace(BlockPos block, Vec3 hitPoint) {
        double dx = hitPoint.x - block.getX();
        double dy = hitPoint.y - block.getY();
        double dz = hitPoint.z - block.getZ();
        double bestDist = Double.MAX_VALUE;
        Direction best = Direction.UP;
        if (dx < bestDist)      { bestDist = dx;      best = Direction.WEST;  }
        if (1 - dx < bestDist)  { bestDist = 1 - dx;  best = Direction.EAST;  }
        if (dy < bestDist)      { bestDist = dy;      best = Direction.DOWN;  }
        if (1 - dy < bestDist)  { bestDist = 1 - dy;  best = Direction.UP;    }
        if (dz < bestDist)      { bestDist = dz;      best = Direction.NORTH; }
        if (1 - dz < bestDist)  {                     best = Direction.SOUTH; }
        return best;
    }

    record Block(BlockPos pos, Vec3 subHitPoint, @Nullable Direction sideHit)
            implements HookAttachment {
        @Override public Vec3 worldHitPoint(float partialTicks) { return subHitPoint; }
        @Override public GrappleAttachS2CPayload.GrappleAttachTarget toWireTarget() {
            return new GrappleAttachS2CPayload.GrappleAttachTarget.Block(pos);
        }
        @Override public @Nullable Direction ropeAnchorFace() {
            if (sideHit != null) return sideHit;
            return inferFace(pos, subHitPoint);
        }
    }

    record Entity(int entityId, WeakReference<net.minecraft.world.entity.Entity> resolved)
            implements HookAttachment {

        public Entity(net.minecraft.world.entity.Entity e) {
            this(e.getId(), new WeakReference<>(e));
        }

        public static Entity fromId(int id) {
            return new Entity(id, new WeakReference<>(null));
        }

        public @Nullable net.minecraft.world.entity.Entity entity() { return resolved.get(); }

        @Override public Vec3 worldHitPoint(float partialTicks) {
            var e = resolved.get();
            if (e == null) return Vec3.ZERO;
            return e.position().add(0, e.getBbHeight() * 0.5, 0);
        }

        @Override public HookAttachment refreshed(Level level) {
            var cached = resolved.get();
            if (cached != null && cached.isAlive()) return this;
            var fresh = level.getEntity(entityId);
            if (fresh == null) return this;
            return new Entity(entityId, new WeakReference<>(fresh));
        }

        @Override public GrappleAttachS2CPayload.GrappleAttachTarget toWireTarget() {
            return new GrappleAttachS2CPayload.GrappleAttachTarget.Entity(entityId);
        }
    }

    record ContraptionBlock(int entityId,
                            WeakReference<net.minecraft.world.entity.Entity> resolved,
                            Vec3 localOffset,
                            @Nullable BlockPos localBlockPos)
            implements HookAttachment {

        public ContraptionBlock(net.minecraft.world.entity.Entity e,
                                Vec3 localOffset,
                                @Nullable BlockPos localBlockPos) {
            this(e.getId(), new WeakReference<>(e), localOffset, localBlockPos);
        }

        public static ContraptionBlock fromId(int id, Vec3 localOffset) {
            return new ContraptionBlock(id, new WeakReference<>(null), localOffset, null);
        }

        public @Nullable net.minecraft.world.entity.Entity entity() { return resolved.get(); }

        @Override public Vec3 worldHitPoint(float partialTicks) {
            var e = resolved.get();
            if (e == null) return Vec3.ZERO;
            return GrappleModIntegrations.getContraptionIntegration()
                    .localToWorld(e, localOffset, partialTicks);
        }

        @Override public HookAttachment refreshed(Level level) {
            var cached = resolved.get();
            if (cached != null && cached.isAlive()) return this;
            var fresh = level.getEntity(entityId);
            if (fresh == null) return this;
            return new ContraptionBlock(entityId, new WeakReference<>(fresh), localOffset, localBlockPos);
        }

        @Override public GrappleAttachS2CPayload.GrappleAttachTarget toWireTarget() {
            return new GrappleAttachS2CPayload.GrappleAttachTarget.EntityOffset(entityId, localOffset);
        }

        @Override public @Nullable Direction ropeAnchorFace() {
            if (localBlockPos == null) return null;
            return inferFace(localBlockPos, localOffset);
        }
    }

    record SubLevelBlock(UUID subLevelId, BlockPos plotBlock, Vec3 plotHitPoint)
            implements HookAttachment {
        @Override public Vec3 worldHitPoint(float partialTicks) {
            return GrappleModIntegrations.getSubLevelIntegration()
                    .plotToWorld(subLevelId, plotHitPoint, partialTicks);
        }
        @Override public GrappleAttachS2CPayload.GrappleAttachTarget toWireTarget() {
            return new GrappleAttachS2CPayload.GrappleAttachTarget.SubLevel(
                    subLevelId, plotBlock, plotHitPoint);
        }

        @Override public @Nullable Direction ropeAnchorFace() {
            return inferFace(plotBlock, plotHitPoint);
        }
    }

    static HookAttachment fromWireTarget(
            GrappleAttachS2CPayload.GrappleAttachTarget target,
            Vec3 hookWorldPos,
            Level world) {
        return switch (target) {
            case GrappleAttachS2CPayload.GrappleAttachTarget.Block b ->
                    new Block(b.pos(), hookWorldPos, null);

            case GrappleAttachS2CPayload.GrappleAttachTarget.Entity e -> {
                var ent = world.getEntity(e.id());
                yield ent != null ? new Entity(ent) : Entity.fromId(e.id());
            }

            case GrappleAttachS2CPayload.GrappleAttachTarget.EntityOffset eo -> {
                var ent = world.getEntity(eo.id());
                yield ent != null
                        ? new ContraptionBlock(ent, eo.localOffset(), null)
                        : ContraptionBlock.fromId(eo.id(), eo.localOffset());
            }

            case GrappleAttachS2CPayload.GrappleAttachTarget.SubLevel sl ->
                    new SubLevelBlock(sl.subLevelId(), sl.plotBlock(), sl.plotHitPoint());
        };
    }
}
