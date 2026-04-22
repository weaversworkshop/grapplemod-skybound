package com.yyon.grapplinghook.util;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.network.NetworkManager;
import com.yyon.grapplinghook.network.S2CPayload;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class GrappleModUtils {

	public static final StreamCodec<ByteBuf, NullableDirection> NULLABLE_DIRECTION_STREAM_CODEC = ByteBufCodecs.idMapper(id -> NullableDirection.values()[id], NullableDirection::ordinal);

	public static EquipmentSlot currentHand(boolean isMainHand) {
		return  isMainHand ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
	}

	public static boolean hasArmourAbility(LivingEntity target, DataComponentType<?> ability) {
		for (ItemStack stack : target.getArmorSlots()) {
			if (stack == null) continue;

			if(EnchantmentHelper.has(stack, ability))
				return true;
		}

		return false;
	}

	public static void sendToCorrectClient(S2CPayload message, int playerid, Level w) {
		Entity entity = w.getEntity(playerid);
		if (entity instanceof ServerPlayer player) {
			NetworkManager.packetToClient(message, player);
			return;
		}

		GrappleMod.LOGGER.warn("ERROR! couldn't find player");
	}

	/**
	 * World-space block raycast for rope wrap / hook collision. Walks voxels via
	 * Amanatides-Woo DDA and per-voxel {@link VoxelShape#clip} instead of routing
	 * through {@link Level#clip}, because Sable patches {@code BlockGetter.clip}
	 * (its mixin transforms any ray that crosses a sub-level's apparent AABB into
	 * plot-space and walks millions of voxels there — see
	 * project_sable_rope_raytrace_hang.md). Calling {@link Level#getBlockState}
	 * directly bypasses the mixin entirely.
	 *
	 * <p>Semantically equivalent to {@code Level.clip(ClipContext.Block.COLLIDER,
	 * ClipContext.Fluid.NONE)} for the shapes grapplemod cares about — air skip,
	 * per-block collision shape, precise sub-voxel face hit. The {@code entity}
	 * parameter is preserved for API compatibility but unused: COLLIDER +
	 * non-fluid clipping never consults it in vanilla either.</p>
	 *
	 * @return the hit on a solid block face, or {@code null} on miss.
	 */
	@SuppressWarnings("unused")
	public static BlockHitResult rayTraceBlocks(Entity entity, Level world, Vec from, Vec to) {
		Vec3 start = from.toVec3d();
		Vec3 end = to.toVec3d();
		double dx = end.x - start.x;
		double dy = end.y - start.y;
		double dz = end.z - start.z;
		if (dx == 0 && dy == 0 && dz == 0) return null;

		int x = Mth.floor(start.x), y = Mth.floor(start.y), z = Mth.floor(start.z);
		int endX = Mth.floor(end.x), endY = Mth.floor(end.y), endZ = Mth.floor(end.z);

		int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
		int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
		int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;

		double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
		double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
		double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

		double tMaxX = stepX > 0 ? (x + 1 - start.x) / dx : stepX < 0 ? (start.x - x) / -dx : Double.POSITIVE_INFINITY;
		double tMaxY = stepY > 0 ? (y + 1 - start.y) / dy : stepY < 0 ? (start.y - y) / -dy : Double.POSITIVE_INFINITY;
		double tMaxZ = stepZ > 0 ? (z + 1 - start.z) / dz : stepZ < 0 ? (start.z - z) / -dz : Double.POSITIVE_INFINITY;

		// Safety cap: rope segments are bounded by ropeLength (<100 blocks), so 1024
		// voxels is far more than any legitimate raycast could need. Prevents an
		// infinite loop on a degenerate ray.
		//
		// Loop structure: check current voxel, return null if it's the end voxel
		// (we've visited every voxel the ray passes through), otherwise step. Do NOT
		// early-exit on "all tMax > 1" — that check fires after a step that lands
		// us *in* the end voxel, which preempts the next iteration's check of that
		// voxel. For a grapple attached to a block, the rope-end ray's end voxel is
		// typically the attach block itself; missing it means wrap corner-hunt sees
		// no hit and bends never form. The endX/endY/endZ termination catches valid
		// rays correctly; 1024 iter cap catches degenerate geometry.
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		for (int i = 0; i < 1024; i++) {
			probe.set(x, y, z);
			BlockState state = world.getBlockState(probe);
			if (!state.isAir()) {
				VoxelShape shape = state.getCollisionShape(world, probe);
				if (!shape.isEmpty()) {
					BlockHitResult hit = shape.clip(start, end, probe);
					if (hit != null) return hit;
				}
			}
			if (x == endX && y == endY && z == endZ) return null;

			if (tMaxX < tMaxY && tMaxX < tMaxZ) { x += stepX; tMaxX += tDeltaX; }
			else if (tMaxY < tMaxZ)             { y += stepY; tMaxY += tDeltaY; }
			else                                 { z += stepZ; tMaxZ += tDeltaZ; }
		}
		return null;
	}

	@SafeVarargs
	public static boolean and(Supplier<Boolean>... conditions) {
		boolean failed = Arrays.stream(conditions).anyMatch(bool -> !bool.get());
		return !failed;
	}

	public static boolean and(List<Supplier<Boolean>> conditions) {
		boolean failed = conditions.stream().anyMatch(bool -> !bool.get());
		return !failed;
	}

	public static synchronized ServerPlayer[] getPlayersThatCanSeeChunkAt(ServerLevel level, Vec point) {
		ChunkPos chunk = level.getChunkAt(BlockPos.containing(point.toVec3d())).getPos();
		return PlayerLookup.tracking(level, chunk).toArray(new ServerPlayer[0]);
	}

	public static void registerPack(String id, Component displayName, ModContainer container, ResourcePackActivationType activationType) {
		ResourceManagerHelper.registerBuiltinResourcePack(GrappleMod.id(id), container, displayName, activationType);
	}

}
