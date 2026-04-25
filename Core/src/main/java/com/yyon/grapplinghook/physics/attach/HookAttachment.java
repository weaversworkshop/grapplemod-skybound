package com.yyon.grapplinghook.physics.attach;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.content.entity.grapplinghook.HookAnchorMigration;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import com.yyon.grapplinghook.network.clientbound.GrappleAttachS2CPayload;
import com.yyon.grapplinghook.util.Vec;
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

    double SURFACE_OFFSET = 0.08;

    Vec3 worldHitPoint(float partialTicks);

    default Vec3 ropeAnchorPoint(float partialTicks) { return worldHitPoint(partialTicks); }

    default HookAttachment refreshed(Level level) { return this; }

    GrappleAttachS2CPayload.GrappleAttachTarget toWireTarget();

    default @Nullable Direction ropeAnchorFace() { return null; }

    /**
     * Keeps the hook pinned to this attachment each tick. Returns {@code false} if the follow
     * decided the hook must die (called {@code onAttachedEntityPerished}) and the caller should
     * stop ticking. Default: no-op (static-world attachments don't need per-tick pinning).
     */
    default boolean follow(GrapplinghookEntity hook, SubLevelIntegration sli) { return true; }

    /** The world entity this attachment follows (for {@link Entity} / {@link ContraptionBlock}), or null. */
    default @Nullable net.minecraft.world.entity.Entity hostEntity() { return null; }

    /** True if this attachment rides a moving host (entity / contraption / sub-level). */
    default boolean attachedToMovingBody() { return false; }

    default boolean rendersViaExplicitAnchor() { return false; }

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
        @Override public Vec3 ropeAnchorPoint(float partialTicks) {
            Direction face = ropeAnchorFace();
            if (face == null) return subHitPoint;
            return subHitPoint.add(
                    face.getStepX() * SURFACE_OFFSET,
                    face.getStepY() * SURFACE_OFFSET,
                    face.getStepZ() * SURFACE_OFFSET);
        }
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

        @Override public @Nullable net.minecraft.world.entity.Entity hostEntity() { return resolved.get(); }

        @Override public boolean attachedToMovingBody() { return true; }

        @Override public boolean follow(GrapplinghookEntity hook, SubLevelIntegration sli) {
            net.minecraft.world.entity.Entity e = resolved.get();
            if (e == null || !e.isAlive()) {
                hook.onAttachedEntityPerished();
                return false;
            }
            Vec target = Vec.positionVec(e).add(new Vec(0, e.getBbHeight() * 0.5, 0));
            hook.setPos(target.x, target.y, target.z);
            hook.setDeltaMovement(0, 0, 0);
            return true;
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

        @Override public Vec3 ropeAnchorPoint(float partialTicks) {
            var e = resolved.get();
            if (e == null) return Vec3.ZERO;
            Direction face = ropeAnchorFace();
            if (face == null) return worldHitPoint(partialTicks);
            Vec3 offsetLocal = localOffset.add(
                    face.getStepX() * SURFACE_OFFSET,
                    face.getStepY() * SURFACE_OFFSET,
                    face.getStepZ() * SURFACE_OFFSET);
            return GrappleModIntegrations.getContraptionIntegration()
                    .localToWorld(e, offsetLocal, partialTicks);
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

        @Override public @Nullable net.minecraft.world.entity.Entity hostEntity() { return resolved.get(); }

        @Override public boolean attachedToMovingBody() { return true; }

        @Override public boolean rendersViaExplicitAnchor() { return true; }

        @Override public boolean follow(GrapplinghookEntity hook, SubLevelIntegration sli) {
            net.minecraft.world.entity.Entity e = resolved.get();
            if (e == null || !e.isAlive()) {
                hook.onAttachedEntityPerished();
                return false;
            }
            Vec3 worldPoint = worldHitPoint(GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);
            hook.setPos(worldPoint.x, worldPoint.y, worldPoint.z);
            hook.setDeltaMovement(0, 0, 0);
            return true;
        }
    }

    record SubLevelBlock(UUID subLevelId, BlockPos plotBlock, Vec3 plotHitPoint)
            implements HookAttachment {
        @Override public Vec3 worldHitPoint(float partialTicks) {
            return GrappleModIntegrations.getSubLevelIntegration()
                    .plotToWorld(subLevelId, plotHitPoint, partialTicks);
        }

        @Override public Vec3 ropeAnchorPoint(float partialTicks) {
            Direction face = ropeAnchorFace();
            if (face == null) return worldHitPoint(partialTicks);
            Vec3 offsetPlot = plotHitPoint.add(
                    face.getStepX() * SURFACE_OFFSET,
                    face.getStepY() * SURFACE_OFFSET,
                    face.getStepZ() * SURFACE_OFFSET);
            return GrappleModIntegrations.getSubLevelIntegration()
                    .plotToWorld(subLevelId, offsetPlot, partialTicks);
        }
        @Override public GrappleAttachS2CPayload.GrappleAttachTarget toWireTarget() {
            return new GrappleAttachS2CPayload.GrappleAttachTarget.SubLevel(
                    subLevelId, plotBlock, plotHitPoint);
        }

        @Override public @Nullable Direction ropeAnchorFace() {
            return inferFace(plotBlock, plotHitPoint);
        }

        @Override public boolean attachedToMovingBody() { return true; }

        @Override public boolean follow(GrapplinghookEntity hook, SubLevelIntegration sli) {
            try {
                if (!sli.isSubLevelLoaded(subLevelId)) {
                    if (!hook.level().isClientSide) {
                        hook.onAttachedEntityPerished();
                        return false;
                    }
                    return true;
                }
                if (!hook.level().isClientSide && !sli.isPlotBlockSolid(subLevelId, plotBlock)) {
                    if (HookAnchorMigration.tryMigrate(hook, sli, this)) return false;
                    hook.onAttachedEntityPerished();
                    return false;
                }
                Vec3 worldPoint = worldHitPoint(GrapplinghookEntity.CONTRAPTION_PARTIAL_TICKS);
                hook.setPos(worldPoint.x, worldPoint.y, worldPoint.z);
                hook.setDeltaMovement(0, 0, 0);
                return true;
            } catch (Throwable err) {
                GrappleMod.LOGGER.error("[Grapple <-> Sable] Follow tick threw on side={} — detaching so we don't spin on this",
                        hook.level().isClientSide ? "CLIENT" : "SERVER", err);
                hook.onAttachedEntityPerished();
                return false;
            }
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
