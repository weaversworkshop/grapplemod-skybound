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

/**
 * Closed-world description of what a {@code GrapplinghookEntity} is anchored to.
 * A {@code null} {@code HookAttachment} means the hook is in flight or detached;
 * otherwise the concrete variant tells every consumer (tick follow, save, render,
 * network) exactly which path applies — replacing the previous scatter of eight
 * nullable fields on the entity.
 *
 * <p>Wire-format alignment: {@link Block}, {@link Entity}, and {@link ContraptionBlock}
 * map 1:1 onto {@link GrappleAttachS2CPayload.GrappleAttachTarget#Block Block} /
 * {@code Entity} / {@code EntityOffset}. {@link SubLevelBlock} is a server-side
 * variant for the future Sable compat module — on the wire it collapses to an
 * {@code EntityOffset}-like payload; clients see a sub-level the same way they
 * see a Create contraption.
 */
public sealed interface HookAttachment
        permits HookAttachment.Block,
                HookAttachment.Entity,
                HookAttachment.ContraptionBlock,
                HookAttachment.SubLevelBlock {

    /**
     * World-space point the rope anchors to at the given partial-tick interpolation.
     * Time-invariant for {@link Block}; follows the underlying object for the others.
     */
    Vec3 worldHitPoint(float partialTicks);

    /**
     * Re-resolves any cached entity handle against {@code level}. No-op for block variants.
     * Returns a fresh attachment instance if the handle was refreshed, or {@code this} otherwise.
     */
    default HookAttachment refreshed(Level level) { return this; }

    /**
     * Lower this attachment to its wire representation. Used by the server when sending
     * a full {@link GrappleAttachS2CPayload}. {@link SubLevelBlock} throws until the
     * Sable compat module provides a transport.
     */
    GrappleAttachS2CPayload.GrappleAttachTarget toWireTarget();

    /**
     * Outward face direction at the hook's anchor point, if the attachment sits on
     * a block face — used to nudge the rope's hook-end endpoint outward so the
     * first rope raycast doesn't start inside a solid block. Returns {@code null}
     * for attachments that have no block face (plain {@link Entity}, or a
     * {@link ContraptionBlock} that was attached mid-flight without a known
     * local block cell).
     */
    default @Nullable Direction ropeAnchorFace() { return null; }

    /**
     * Face-inference helper: given a block position and a hit point in the block's
     * local coordinate system (the hit point's floored coords equal {@code block}),
     * return the face the hit is on. Picks whichever of the six block faces the
     * hit is closest to — correct for points on or near the surface, degenerate
     * to {@link Direction#UP} for mid-block hits.
     */
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

    // ------------------------------------------------------------------
    // Variants
    // ------------------------------------------------------------------

    /** Static world-space block anchor — the classic grapple target. */
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

    /**
     * Plain-entity anchor (mobs, boats, minecarts). Follows the entity's centre.
     * The weak ref avoids retaining a dead entity; {@link #refreshed(Level)} re-looks-up by ID.
     */
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

    /**
     * Create contraption anchor — follows a contraption-local offset via the active
     * {@code ContraptionIntegration}. {@code localBlockPos} is nullable because
     * mid-flight contraption attaches don't know which local cell was captured;
     * when non-null it enables precise block re-anchoring on disassembly.
     */
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

    /**
     * Sable sub-level anchor (Create: Aeronautics ships, etc.). Sub-levels are keyed
     * by UUID (persistent across save/load, unlike entity IDs); blocks live in a
     * far-away plot region that the Sable compat module projects to apparent
     * world-space each tick via {@link SubLevelIntegration#plotToWorld}. When no
     * Sable compat is installed, {@link SubLevelIntegration} is a no-op and the
     * Sable attach paths never fire, so this variant never appears at runtime.
     */
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
            // Face inferred in plot-space coords; for translation-only poses (the
            // common Aeronautics case) this is identical to world-space. Rotated
            // sub-levels are covered by project_v2_rope_rotation_limitation.md.
            return inferFace(plotBlock, plotHitPoint);
        }
    }

    // ------------------------------------------------------------------
    // Wire reconstruction
    // ------------------------------------------------------------------

    /**
     * Reconstruct an attachment from a received wire target. The top-level {@code hookWorldPos}
     * from the containing {@link GrappleAttachS2CPayload} is used as {@code subHitPoint} for
     * {@link Block} variants, since the wire {@code Block} target carries only the block pos.
     */
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
