package com.yyon.grapplinghook.physics.persistence;

import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public sealed interface PersistedAttachment {

    String NBT_TYPE = "type";

    String TYPE_BLOCK = "block";
    String TYPE_SUBLEVEL = "sublevel";
    String TYPE_ENTITY = "entity";
    String TYPE_CONTRAPTION = "contraption";

    void writeNbt(CompoundTag tag);

    /** Returns null if the anchor cannot currently be resolved (chunk unloaded, entity missing, etc.). */
    @Nullable HookAttachment tryResolve(ServerLevel level);

    /** True if the missing resource might still appear soon (sub-level loading, entity streaming in). */
    boolean mayBecomeAvailableLater(ServerLevel level);

    static PersistedAttachment fromHook(HookAttachment src) {
        return switch (src) {
            case HookAttachment.Block b -> new Block(b.pos(), b.subHitPoint(), b.sideHit());
            case HookAttachment.SubLevelBlock sl -> new SubLevel(sl.subLevelId(), sl.plotBlock(), sl.plotHitPoint());
            case HookAttachment.Entity e -> {
                Entity ent = e.entity();
                yield ent != null ? new EntityAnchor(ent.getUUID()) : null;
            }
            case HookAttachment.ContraptionBlock c -> {
                Entity ent = c.entity();
                yield ent != null ? new ContraptionAnchor(ent.getUUID(), c.localOffset()) : null;
            }
        };
    }

    static PersistedAttachment readNbt(CompoundTag tag) {
        return switch (tag.getString(NBT_TYPE)) {
            case TYPE_BLOCK -> Block.read(tag);
            case TYPE_SUBLEVEL -> SubLevel.read(tag);
            case TYPE_ENTITY -> EntityAnchor.read(tag);
            case TYPE_CONTRAPTION -> ContraptionAnchor.read(tag);
            default -> null;
        };
    }

    record Block(BlockPos pos, Vec3 hitPoint, @Nullable Direction sideHit) implements PersistedAttachment {
        @Override public void writeNbt(CompoundTag tag) {
            tag.putString(NBT_TYPE, TYPE_BLOCK);
            tag.putInt("px", pos.getX()); tag.putInt("py", pos.getY()); tag.putInt("pz", pos.getZ());
            tag.putDouble("hx", hitPoint.x); tag.putDouble("hy", hitPoint.y); tag.putDouble("hz", hitPoint.z);
            if (sideHit != null) tag.putString("side", sideHit.getName());
        }
        static Block read(CompoundTag tag) {
            BlockPos p = new BlockPos(tag.getInt("px"), tag.getInt("py"), tag.getInt("pz"));
            Vec3 h = new Vec3(tag.getDouble("hx"), tag.getDouble("hy"), tag.getDouble("hz"));
            Direction s = tag.contains("side") ? Direction.byName(tag.getString("side")) : null;
            return new Block(p, h, s);
        }
        @Override public HookAttachment tryResolve(ServerLevel level) {
            if (!level.isLoaded(pos)) return null;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return null;
            return new HookAttachment.Block(pos, hitPoint, sideHit);
        }
        @Override public boolean mayBecomeAvailableLater(ServerLevel level) {
            return !level.isLoaded(pos);
        }
    }

    record SubLevel(UUID subLevelId, BlockPos plotBlock, Vec3 plotHitPoint) implements PersistedAttachment {
        @Override public void writeNbt(CompoundTag tag) {
            tag.putString(NBT_TYPE, TYPE_SUBLEVEL);
            tag.putUUID("uuid", subLevelId);
            tag.putInt("px", plotBlock.getX()); tag.putInt("py", plotBlock.getY()); tag.putInt("pz", plotBlock.getZ());
            tag.putDouble("hx", plotHitPoint.x); tag.putDouble("hy", plotHitPoint.y); tag.putDouble("hz", plotHitPoint.z);
        }
        static SubLevel read(CompoundTag tag) {
            UUID u = tag.getUUID("uuid");
            BlockPos p = new BlockPos(tag.getInt("px"), tag.getInt("py"), tag.getInt("pz"));
            Vec3 h = new Vec3(tag.getDouble("hx"), tag.getDouble("hy"), tag.getDouble("hz"));
            return new SubLevel(u, p, h);
        }
        @Override public HookAttachment tryResolve(ServerLevel level) {
            SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
            if (!sli.isSubLevelLoaded(subLevelId)) return null;
            if (!sli.isPlotBlockSolid(subLevelId, plotBlock)) return null;
            return new HookAttachment.SubLevelBlock(subLevelId, plotBlock, plotHitPoint);
        }
        @Override public boolean mayBecomeAvailableLater(ServerLevel level) {
            return !GrappleModIntegrations.getSubLevelIntegration().isSubLevelLoaded(subLevelId);
        }
    }

    record EntityAnchor(UUID entityUuid) implements PersistedAttachment {
        @Override public void writeNbt(CompoundTag tag) {
            tag.putString(NBT_TYPE, TYPE_ENTITY);
            tag.putUUID("uuid", entityUuid);
        }
        static EntityAnchor read(CompoundTag tag) { return new EntityAnchor(tag.getUUID("uuid")); }
        @Override public HookAttachment tryResolve(ServerLevel level) {
            Entity ent = level.getEntity(entityUuid);
            return (ent != null && ent.isAlive()) ? new HookAttachment.Entity(ent) : null;
        }
        @Override public boolean mayBecomeAvailableLater(ServerLevel level) {
            return level.getEntity(entityUuid) == null;
        }
    }

    record ContraptionAnchor(UUID entityUuid, Vec3 localOffset) implements PersistedAttachment {
        @Override public void writeNbt(CompoundTag tag) {
            tag.putString(NBT_TYPE, TYPE_CONTRAPTION);
            tag.putUUID("uuid", entityUuid);
            tag.putDouble("lx", localOffset.x); tag.putDouble("ly", localOffset.y); tag.putDouble("lz", localOffset.z);
        }
        static ContraptionAnchor read(CompoundTag tag) {
            return new ContraptionAnchor(tag.getUUID("uuid"),
                    new Vec3(tag.getDouble("lx"), tag.getDouble("ly"), tag.getDouble("lz")));
        }
        @Override public HookAttachment tryResolve(ServerLevel level) {
            Entity ent = level.getEntity(entityUuid);
            if (ent == null || !ent.isAlive()) return null;
            return new HookAttachment.ContraptionBlock(ent, localOffset, null);
        }
        @Override public boolean mayBecomeAvailableLater(ServerLevel level) {
            return level.getEntity(entityUuid) == null;
        }
    }
}
