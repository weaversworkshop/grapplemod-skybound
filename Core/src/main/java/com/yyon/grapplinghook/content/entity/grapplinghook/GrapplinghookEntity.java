package com.yyon.grapplinghook.content.entity.grapplinghook;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.api.GrappleModServerEvents;
import com.yyon.grapplinghook.config.GrappleModCommonConfig;
import com.yyon.grapplinghook.client.GrappleModClient;
import com.yyon.grapplinghook.client.api.GrappleModClientEvents;
import com.yyon.grapplinghook.content.registry.internal.*;
import com.yyon.grapplinghook.content.customization.data.HookCustomization;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import com.yyon.grapplinghook.network.NetworkManager;
import com.yyon.grapplinghook.network.clientbound.GrappleAttachS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleAttachHookS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleDetachS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleReanchorToEntityS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleReanchorToBlockS2CPayload;
import com.yyon.grapplinghook.physics.rope.AnchorSpace;
import com.yyon.grapplinghook.physics.rope.RopeBend;
import com.yyon.grapplinghook.physics.rope.RopeSegmentHandler;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import com.yyon.grapplinghook.physics.io.RopeSnapshot;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;

import static com.yyon.grapplinghook.content.registry.CustomizationProperties.*;

/*
 * This file is part of GrappleMod.

    GrappleMod is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    GrappleMod is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with GrappleMod.  If not, see <http://www.gnu.org/licenses/>.
 */

public class GrapplinghookEntity extends ThrowableItemProjectile implements IExtendedSpawnPacketEntity {

	public static final float CONTRAPTION_PARTIAL_TICKS = 1.0f;

	public Entity shootingEntity = null;
	public int shootingEntityID;

	public Vec thisPos;

	private boolean isAttachedToMainHand = true;
	private boolean isFirstAttach = false;
	private boolean isAttachedToSurface;
	public Vec attachDirection = null;

	public double pull;

	public double taut = 1;

	public boolean isInDoublePair = false;

	public double ropeLength;

	private final RopeSegmentHandler segmentHandler;

	private HookCustomization customization;

	public Vec prevPos = null;
	public boolean foundBlock = false;
	public boolean wasInAir = false;
	public BlockPos magnetBlock = null;

	@Nullable private HookAttachment attachment = null;

	private int cutCount = 0;
	private int lastCutTick = -10000;

	public GrapplinghookEntity(EntityType<? extends GrapplinghookEntity> type, Level world) {
		super(type, world);

		this.segmentHandler = new RopeSegmentHandler(this, Vec.positionVec(this), Vec.positionVec(this));
		this.customization = new HookCustomization();

		this.isAttachedToMainHand = true;
		this.isAttachedToSurface = false;

	}

	public GrapplinghookEntity(Level world, LivingEntity shooter, boolean isAttachedToMainHand, HookCustomization customization, boolean isInDoublePair) {
		super(ModEntities.GRAPPLE_HOOK.get(), shooter.position().x, shooter.position().y + shooter.getEyeHeight(), shooter.position().z, world);

		this.shootingEntity = shooter;
		this.shootingEntityID = this.shootingEntity.getId();

		this.isInDoublePair = isInDoublePair;
		
		this.isAttachedToMainHand = isAttachedToMainHand;
		Vec pos = this.getRopeOriginAtHolder();

		this.segmentHandler = new RopeSegmentHandler(this, new Vec(pos), new Vec(pos));

		this.customization = customization;
		this.ropeLength = customization.get(MAX_ROPE_LENGTH.get());

		this.isAttachedToSurface = false;
	}

	@Override
    public void writeSpawnData(FriendlyByteBuf data) {
	    data.writeInt(this.shootingEntity != null ? this.shootingEntity.getId() : 0);
	    data.writeBoolean(this.isAttachedToMainHand);
	    data.writeBoolean(this.isInDoublePair);
		data.writeBoolean(this.isAttachedToSurface);
	    if (this.customization == null) {
	    	GrappleMod.LOGGER.warn("error: customization null");
	    }
	    this.customization.writeToBuf(data);
    }

	@Override
    public void readSpawnData(FriendlyByteBuf data) {
    	this.shootingEntityID = data.readInt();
	    this.shootingEntity = this.level().getEntity(this.shootingEntityID);
	    this.isAttachedToMainHand = data.readBoolean();
	    this.isInDoublePair = data.readBoolean();
		this.isAttachedToSurface = data.readBoolean();
	    this.customization = new HookCustomization();
	    this.customization.readFromBuf(data);
    }

	@Override
	@NotNull
	public ItemStack getItem() {
        return new ItemStack(this.getDefaultItem());
	}

	@Override
	@NotNull
	public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity entity) {
		return new ClientboundAddEntityPacket(this, entity);
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double p_70112_1_) {
		return true;
	}

	@Override
	public boolean shouldRender(double p_145770_1_, double p_145770_3_, double p_145770_5_) {
		return true;
	}

	@Override
	public void lerpTo(double x, double y, double z, float yRot, float xRot, int lerpSteps) {
		if (this.isAttachedToMovingBody()) return;
		super.lerpTo(x, y, z, yRot, xRot, lerpSteps);
	}

	@Override
	protected double getDefaultGravity() {
		if (this.isAttachedToAnything())
			return 0.0F;

		return this.customization.get(HOOK_GRAVITY_MULTIPLIER.get()).floatValue() * 0.1F;
	}

	@NotNull
	@Override
	public AABB getBoundingBoxForCulling() {
		if (this.shootingEntity == null) {
			return super.getBoundingBoxForCulling();
		}
		return this.segmentHandler.getBoundingBox(Vec.positionVec(this), this.getRopeOriginAtHolder());
	}

	@NotNull
	@Override
	protected Item getDefaultItem() {
		return ModItems.GRAPPLING_HOOK.get();
	}

	@Override
	public void tick() {
		if (this.shootingEntityID == 0 || this.shootingEntity == null) {
			this.discard();
			return;
		}

		if (!this.shootingEntity.isAlive()) {
			this.discard();
			return;
		}

		if (this.isFirstAttach) {
			this.setDeltaMovement(0, 0, 0);
			this.isFirstAttach = false;
			super.setPos(this.thisPos.x, this.thisPos.y, this.thisPos.z);
		}

		if (this.attachment != null) {
			HookAttachment refreshed = this.attachment.refreshed(this.level());
			if (refreshed != this.attachment) {
				this.attachment = refreshed;
			}
		}

		if (this.attachment != null) {
			this.setDeltaMovement(0, 0, 0);
		}

		SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
		if (HookFlightController.runPreTickScans(this, sli)) {
			HookFlightController.manualProjectileStep(this);
		} else {
			super.tick();
		}

		if (this.attachment != null && !this.attachment.follow(this, sli)) return;

		boolean hookIsDetached = !this.level().isClientSide &&
				                  this.shootingEntity != null &&
				                 !this.isAttachedToAnything();

		if(!hookIsDetached) return;

		this.handleHookPhysics();
	}

	public boolean isAttachedToAnything() {
		return this.attachment != null;
	}

	public void onAttachedEntityPerished() {
		GrappleMod.LOGGER.warn("Attached entity has perished ...");
		if (!this.level().isClientSide && this.shootingEntityID != 0) {
			GrappleModUtils.sendToCorrectClient(
					new GrappleDetachS2CPayload(this.shootingEntityID),
					this.shootingEntityID,
					this.level()
			);
		}
		this.removeServer();
	}

	@Override
	public boolean canUsePortal(boolean allowPassengers) {
		return false;
	}

	@Override
	protected void onHit(HitResult hit) {
		HookHitDispatcher.dispatch(this, hit);
	}

	@Override
	public boolean isPickable() {
		return GrappleModCommonConfig.get().isHookCuttingEnabled();
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand) {
		if (this.level().isClientSide) {
			return player.getItemInHand(hand).getItem() == Items.SHEARS
					? InteractionResult.SUCCESS
					: InteractionResult.PASS;
		}

		GrappleModCommonConfig config = GrappleModCommonConfig.get();
		if (!config.isHookCuttingEnabled()) return InteractionResult.PASS;

		ItemStack weapon = player.getItemInHand(hand);
		if (weapon.getItem() != Items.SHEARS) return InteractionResult.PASS;

		int now = this.tickCount;
		if (now - this.lastCutTick < config.getHookCutCooldownTicks()) return InteractionResult.CONSUME;
		this.lastCutTick = now;

		this.cutCount++;
		this.playSound(SoundEvents.SHEEP_SHEAR, 0.8f, 1.2f);
		weapon.hurtAndBreak(5, player, LivingEntity.getSlotForHand(hand));

		if (this.cutCount >= config.getHookCutsRequired()) {
			this.releaseAndRemove();
		}
		return InteractionResult.CONSUME;
	}



	private void handleHookPhysics() {
		if (this.segmentHandler.hookPastBend(this.ropeLength) && !this.level().isClientSide) {
			RopeBend farthestBend = this.segmentHandler.getBends().get(1);
			Vec farthest = farthestBend.worldPos;
			HookAttachment newAnchor = switch (farthestBend.space) {
				case AnchorSpace.World w -> new HookAttachment.Block(
						this.segmentHandler.getBendBlock(1), farthest.toVec3d(), farthestBend.bottomSide);
				case AnchorSpace.Contraption c -> {
					Entity host = this.level().getEntity(c.entityId());
					yield host != null
							? new HookAttachment.ContraptionBlock(host, farthestBend.nativePos.toVec3d(), null)
							: null;
				}
				case AnchorSpace.SubLevel sl -> {
					SubLevelIntegration sliForAnchor = GrappleModIntegrations.getSubLevelIntegration();
					if (!sliForAnchor.isSubLevelLoaded(sl.subLevelId())) yield null;
					Vec3 plotPos = farthestBend.nativePos.toVec3d();
					if (farthestBend.bottomSide != null) {
						Direction f = farthestBend.bottomSide;
						plotPos = plotPos.subtract(f.getStepX() * 0.2, f.getStepY() * 0.2, f.getStepZ() * 0.2);
					}
					BlockPos plotBlock = BlockPos.containing(plotPos);
					yield new HookAttachment.SubLevelBlock(sl.subLevelId(), plotBlock, farthestBend.nativePos.toVec3d());
				}
			};
			if (newAnchor != null) {
				this.serverAttach(newAnchor, false);
			}
		}

		Vec hookPos = this.getRopeAnchorHookPos();
		Vec playerPos = this.getRopeOriginAtHolder();

		boolean skipRopeWrap = this.customization.get(BLOCK_PHASE_ROPE.get());

		if (!skipRopeWrap) {
			this.segmentHandler.update(hookPos, playerPos, this.ropeLength, true);

			if (this.customization.get(STICKY_ROPE.get())) {
				List<Vec> segments = this.segmentHandler.getSegments();

				if (segments.size() > 2) {
					int bendnumber = segments.size() - 2;
					Vec closest = segments.get(bendnumber);
					BlockPos blockpos = this.segmentHandler.getBendBlock(bendnumber);

					for (int i = 1; i <= bendnumber; i++)
						this.segmentHandler.removeSegment(1);

					if(!this.level().isClientSide)
						this.serverAttach(
								new HookAttachment.Block(blockpos, closest.toVec3d(), null),
								false);
				}
			}

		} else {
			this.segmentHandler.updatePos(hookPos, playerPos, this.ropeLength);
		}

		Vec farthest = this.segmentHandler.getFarthest();
		double distToFarthest = this.segmentHandler.getDistToFarthest();

		Vec ropevec = Vec.positionVec(this).sub(farthest);
		double d = ropevec.length();

		if (d + distToFarthest > this.ropeLength) {
			Vec motion = Vec.motionVec(this);

			if (motion.dot(ropevec) > 0) {
				motion = motion.removeAlong(ropevec);
			}

			this.setVelocity(motion.x, motion.y, motion.z);

			ropevec.mutableSetMagnitude(this.ropeLength - distToFarthest);
			Vec newpos = ropevec.add(farthest);

			this.setPos(newpos.x, newpos.y, newpos.z);
		}

		boolean shouldAttactMagnet = this.customization.get(MAGNET_ATTACHED.get()) &&
				Vec.positionVec(this).sub(Vec.positionVec(this.shootingEntity)).length() >
						this.customization.get(MAGNET_RADIUS.get());

		if (shouldAttactMagnet) HookMagnetBehavior.handleMagnetAttraction(this);
	}


	public void removeServer() {
		this.setAttachment(null);
		this.remove(RemovalReason.DISCARDED);
		this.shootingEntityID = 0;
	}

	public void shoot(Vec direction, double speed, float inaccuracy) {
		this.shoot(direction.getX(), direction.getY(), direction.getZ(), (float) speed, inaccuracy);
	}

	public void setVelocity(double x, double y, double z) {
		this.setDeltaMovement(x, y, z);

        if (this.xRotO == 0.0F && this.yRotO == 0.0F) {
            double f = Math.sqrt(x * x + z * z);
            this.setYRot((float)(Mth.atan2(x, z) * (180D / Math.PI)));
            this.setXRot((float)(Mth.atan2(y, f) * (180D / Math.PI)));
            this.yRotO = this.getYRot();
            this.xRotO = this.getXRot();
        }
	}


	public void serverAttach(HookAttachment target, boolean force) {
		if (this.level().isClientSide) return;
		if (this.attachment != null) return;
		if (this.shootingEntity == null || this.shootingEntityID == 0) return;

		if (target instanceof HookAttachment.Block block && !force) {
			BlockState blockState = this.level().getBlockState(block.pos());
			if (!this.canAttachToBlock(blockState)) {
				this.playSound(SoundEvents.ANVIL_LAND, 0.7f, 1.8f);
				this.removeServer();
				return;
			}
		}

		this.setAttachment(target);

		Vec3 anchor = target.worldHitPoint(CONTRAPTION_PARTIAL_TICKS);
		this.setPosRaw(anchor.x, anchor.y, anchor.z);

		Vec curpos = Vec.positionVec(this);
		if (target instanceof HookAttachment.Block block && block.sideHit() != null) {
			switch (block.sideHit()) {
				case DOWN  -> curpos.y -= 0.3f;
				case WEST  -> curpos.x -= 0.05f;
				case NORTH -> curpos.z -= 0.05f;
				case SOUTH -> curpos.z += 0.05f;
				case EAST  -> curpos.x += 0.05f;
				case UP    -> curpos.y += 0.05f;
			}
		}
		curpos.applyAsPositionTo(this);

		this.setDeltaMovement(0, 0, 0);
		this.thisPos = Vec.positionVec(this);
		this.isFirstAttach = true;

		GrappleAttachS2CPayload shootPacket = new GrappleAttachS2CPayload(
				this.getId(),
				this.position().toVector3f(),
				this.shootingEntityID,
				target.toWireTarget(),
				new RopeSnapshot(this.segmentHandler),
				this.customization
		);

		GrappleModUtils.sendToCorrectClient(shootPacket, this.shootingEntityID, this.level());

		GrappleAttachHookS2CPayload msg = new GrappleAttachHookS2CPayload(this.getId(), this.position().toVector3f());
		NetworkManager.packetToClient(msg, GrappleModUtils.getPlayersThatCanSeeChunkAt((ServerLevel) this.level(), new Vec(this.position())));

		GrappleModServerEvents.HOOK_ATTACH.invoker().onHookAttach(this.shootingEntity, this);
	}

	public void reattachToContraption(Entity contraption, Vec3 localOffset, @Nullable BlockPos localBlockPos) {
		if (this.level().isClientSide) return;
		if (this.attachment == null) return;

		this.setAttachment(new HookAttachment.ContraptionBlock(contraption, localOffset, localBlockPos));

		GrappleReanchorToEntityS2CPayload packet = new GrappleReanchorToEntityS2CPayload(
				this.getId(), contraption.getId(), localOffset);

		GrappleModUtils.sendToCorrectClient(packet, this.shootingEntityID, this.level());
	}


	public void reattachToSubLevel(UUID subLevelId, BlockPos plotBlock, Vec3 plotHitPoint) {
		if (this.level().isClientSide) return;
		if (this.attachment == null) return;

		this.setAttachment(new HookAttachment.SubLevelBlock(subLevelId, plotBlock, plotHitPoint));

		Vec3 anchor = this.attachment.worldHitPoint(CONTRAPTION_PARTIAL_TICKS);
		this.setPosRaw(anchor.x, anchor.y, anchor.z);
		this.setDeltaMovement(0, 0, 0);
		this.thisPos = Vec.positionVec(this);
		this.isFirstAttach = true;

		GrappleAttachS2CPayload packet = new GrappleAttachS2CPayload(
				this.getId(),
				this.position().toVector3f(),
				this.shootingEntityID,
				this.attachment.toWireTarget(),
				new RopeSnapshot(this.segmentHandler),
				this.customization
		);
		GrappleModUtils.sendToCorrectClient(packet, this.shootingEntityID, this.level());
	}

public void reattachToBlock(BlockPos blockPos, Vec3 hookWorldPos) {
		if (this.level().isClientSide) return;

		this.setAttachment(new HookAttachment.Block(blockPos, hookWorldPos, null));

		this.setPosRaw(hookWorldPos.x, hookWorldPos.y, hookWorldPos.z);
		this.setDeltaMovement(0, 0, 0);
		this.thisPos = new Vec(hookWorldPos);

		GrappleReanchorToBlockS2CPayload packet = new GrappleReanchorToBlockS2CPayload(
				this.getId(), blockPos, hookWorldPos);
		GrappleModUtils.sendToCorrectClient(packet, this.shootingEntityID, this.level());
	}

	public void releaseAndRemove() {
		if (this.level().isClientSide) return;
		if (this.shootingEntityID != 0) {
			GrappleModUtils.sendToCorrectClient(
					new GrappleDetachS2CPayload(this.shootingEntityID),
					this.shootingEntityID,
					this.level()
			);
		}
		this.removeServer();
	}

	public void clientReanchorToBlock(BlockPos blockPos, Vec3 hookWorldPos) {
		this.setAttachment(new HookAttachment.Block(blockPos, hookWorldPos, null));
		this.setPosRaw(hookWorldPos.x, hookWorldPos.y, hookWorldPos.z);
		this.setDeltaMovement(0, 0, 0);
		this.thisPos = new Vec(hookWorldPos);
	}

	public void clientAttach(Vector3f attachPos) {
		this.setAttachPos(attachPos);

		if (this.shootingEntity instanceof Player) {
			GrappleModClient.get().resetLauncherTime(this.shootingEntityID);
		}

		GrappleModClientEvents.HOOK_ATTACH.invoker().onHookAttach(this.shootingEntity, this);
	}

	public void setAttachPos(Vector3f attachPos) {
		float x = attachPos.x;
		float y = attachPos.y;
		float z = attachPos.z;
		this.setPosRaw(x, y, z);

		this.setDeltaMovement(0, 0, 0);
		this.isFirstAttach = true;
		this.isAttachedToSurface = true;
        this.thisPos = new Vec(x, y, z);
	}

	boolean canAttachToBlock(BlockState blockState) {
		return this.level().getGameRules().getBoolean(ModGamerules.USE_LIMITED_HOOK)
				? blockState.is(ModTags.LIMITED_HOOK_ALLOWED)
				: !blockState.is(ModTags.HOOK_DISALLOWED);
	}

	public double getSpeed() {
		return this.customization.get(HOOK_THROW_SPEED.get());
	}

	public HookCustomization getCurrentCustomizations() {
		return this.customization;
	}

	public RopeSegmentHandler getSegmentHandler() {
		return this.segmentHandler;
	}

	public boolean isAttachedToSurface() {
		return this.isAttachedToSurface;
	}

	public boolean isHeldInMainHand() {
		return this.isAttachedToMainHand;
	}

	public Vec getRopeOriginAtHolder() {
		Entity shooter = this.shootingEntity;
		if (shooter == null) return Vec.positionVec(this);
		return Vec.positionVec(shooter).add(new Vec(0, shooter.getEyeHeight(), 0));
	}

	public Vec getRopeAnchorHookPos() {
		if (this.attachment == null) return Vec.positionVec(this);
		Vec3 anchor = this.attachment.ropeAnchorPoint(CONTRAPTION_PARTIAL_TICKS);
		return new Vec(anchor.x, anchor.y, anchor.z);
	}

	public Vec getSurfaceAttachmentDirection() {
		return this.attachDirection;
	}

	public double getCurrentRopeLength() {
		return this.ropeLength;
	}

	public @Nullable Entity attachedWorldEntity() {
		return this.attachment != null ? this.attachment.hostEntity() : null;
	}

	public boolean isAttachedToMovingBody() {
		return this.attachment != null && this.attachment.attachedToMovingBody();
	}

	public @Nullable HookAttachment attachment() { return this.attachment; }

	public void setAttachmentClient(@Nullable HookAttachment next) {
		this.setAttachment(next);
	}

	private void setAttachment(@Nullable HookAttachment next) {
		this.attachment = next;
		this.isAttachedToSurface = (next != null);
	}
}
