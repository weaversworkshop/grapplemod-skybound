package com.yyon.grapplinghook.content.entity.grapplinghook;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.api.GrappleModServerEvents;
import com.yyon.grapplinghook.client.GrappleModClient;
import com.yyon.grapplinghook.client.api.GrappleModClientEvents;
import com.yyon.grapplinghook.config.GrappleModCommonConfig;
import com.yyon.grapplinghook.content.registry.internal.*;
import com.yyon.grapplinghook.content.customization.data.HookCustomization;
import com.yyon.grapplinghook.integration.ContraptionIntegration;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.network.NetworkManager;
import com.yyon.grapplinghook.network.clientbound.GrappleAttachS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleAttachHookS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleDetachS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleReanchorToEntityS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleReanchorToBlockS2CPayload;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import com.yyon.grapplinghook.physics.ServerHookEntityTracker;
import com.yyon.grapplinghook.physics.io.HookSnapshot;
import com.yyon.grapplinghook.physics.io.RopeSnapshot;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

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

	/**
	 * Partial-ticks value fed to {@link ContraptionIntegration} transforms. Using end-of-tick
	 * (1.0f) for now and relying on render interpolation for sub-tick smoothing. Exposed as
	 * a constant so we can try other values without code hunting.
	 */
	private static final float CONTRAPTION_PARTIAL_TICKS = 1.0f;

	public Entity shootingEntity = null;
	public int shootingEntityID;

	public Vec thisPos;

	private boolean isAttachedToMainHand = true;
	private boolean isFirstAttach = false;
	private boolean isAttachedToSurface;
	public Vec attachDirection = null;

	// Used for saving + loading hook state on world re-join.
	// if restoreCollision is true, the first tick of this hook entity
	// should force-attach the client.
	private BlockPos lastBlockCollision = null;
	private Direction lastBlockCollisionSide = null;
	private Vec lastSubCollisionPos = null;
	private boolean restoreCollision = false;


	public double pull;

	public double taut = 1;

	public boolean isInDoublePair = false;

	public double ropeLength;

	private final RopeSegmentHandler segmentHandler;

	private HookCustomization customization;

	// magnet attract
	public Vec prevPos = null;
	public boolean foundBlock = false;
	public boolean wasInAir = false;
	public BlockPos magnetBlock = null;

	// Entity lock
	private Entity attachedEntity = null;
	private int attachedEntityId = -1;
	/**
	 * When attached to a contraption, this is the hit point expressed in the contraption's
	 * local coordinate space. Non-null means "follow via {@link ContraptionIntegration}"
	 * instead of plain center-follow.
	 */
	private Vec3 attachedContraptionLocalOffset = null;
	/**
	 * Contraption-local key of the block the hook is anchored to. Stored alongside
	 * {@link #attachedContraptionLocalOffset} so that on disassembly we can locate the block
	 * in world-space without the rounding ambiguity of picking a cell from a face-boundary
	 * hit point. {@code null} if the hook was attached mid-flight to an already-moving
	 * contraption, where the captured block key isn't known.
	 */
	private BlockPos attachedContraptionLocalBlockPos = null;

	/**
	 * Consolidated attachment state (step 2 of refactor — shadows the legacy fields above).
	 * Maintained by {@link #recomputeAttachment()} which is called at the end of every
	 * mutation method. Once step 6 lands, this becomes the sole source of truth and the
	 * legacy fields are deleted.
	 */
	@Nullable private HookAttachment attachment = null;

	/** Client-side? instantiation. Creates a very basic entity for filling in details later.**/
	public GrapplinghookEntity(EntityType<? extends GrapplinghookEntity> type, Level world) {
		super(type, world);

		this.segmentHandler = new RopeSegmentHandler(this, Vec.positionVec(this), Vec.positionVec(this));
		this.customization = new HookCustomization();

		this.isAttachedToMainHand = true;
		this.isAttachedToSurface = false;

	}

	/** Server-side? instantiation. Used to spawn the entity & configure it correctly. */
	public GrapplinghookEntity(Level world, LivingEntity shooter, boolean isAttachedToMainHand, HookCustomization customization, boolean isInDoublePair) {
		super(ModEntities.GRAPPLE_HOOK.get(), shooter.position().x, shooter.position().y + shooter.getEyeHeight(), shooter.position().z, world);

		this.shootingEntity = shooter;
		this.shootingEntityID = this.shootingEntity.getId();

		this.isInDoublePair = isInDoublePair;
		
		Vec pos = Vec.positionVec(this.shootingEntity).add(new Vec(0, this.shootingEntity.getEyeHeight(), 0));

		this.segmentHandler = new RopeSegmentHandler(this, new Vec(pos), new Vec(pos));

		this.customization = customization;
		this.ropeLength = customization.get(MAX_ROPE_LENGTH.get());
		
		this.isAttachedToMainHand = isAttachedToMainHand;
		this.isAttachedToSurface = false;
	}

	/** Restore from state snapshot -- used when logging in to re-instantiate a player's hook. */
	public GrapplinghookEntity(HookSnapshot snapshot, HookCustomization volume, Entity shootingEntity, boolean isInPair) {
		super(ModEntities.GRAPPLE_HOOK.get(), snapshot.getX(), snapshot.getY(), snapshot.getZ(), shootingEntity.level());

		//todo: save pair details to HookSnapshot
		RopeSnapshot rope = snapshot.getRopeSnapshot();

		this.shootingEntity = shootingEntity;
		this.shootingEntityID = shootingEntity.getId();

		this.segmentHandler = new RopeSegmentHandler(this, shootingEntity, rope);

		this.customization = volume;
		this.ropeLength = rope.getRopeLength();
		this.isAttachedToMainHand = snapshot.isMainHook();
		this.isInDoublePair = isInPair;

		this.lastBlockCollision = snapshot.getLastBlockCollidedWith();
		this.lastSubCollisionPos = snapshot.getLastSubCollisionPos();
		this.lastBlockCollisionSide = snapshot.getLastBlockCollisionSide();
		this.restoreCollision = true;
		this.recomputeAttachment();

		//this.isAttachedToSurface = snapshot.isAttached();

		GrappleModServerEvents.HOOK_THROW.invoker().onHookThrown(this.shootingEntity, this);
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
		data.writeBoolean(this.restoreCollision);
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
		this.restoreCollision = data.readBoolean();
		this.recomputeAttachment();
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
		// While tethered to a mob, the client tracks the mob locally in tick().
		// Ignoring server position sync here prevents ~5Hz jitter from tracker updates
		// fighting our direct setPos.
		if (this.attachedEntity != null) return;
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
		return this.segmentHandler.getBoundingBox(Vec.positionVec(this), Vec.positionVec(this.shootingEntity).add(new Vec(0, this.shootingEntity.getEyeHeight(), 0)));
	}

	@NotNull
	@Override
	protected Item getDefaultItem() {
		return ModItems.GRAPPLING_HOOK.get();
	}

	@Override
	public void tick() {
		if (this.shootingEntityID == 0 || this.shootingEntity == null) { // removes ghost grappling hooks
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

		if (this.attachedEntityId != -1) {
			Entity e = this.level().getEntity(this.attachedEntityId);
			if (e != null) {
				this.attachedEntity = e;
				this.recomputeAttachment();
			}
		}

		if (this.isAttachedToSurface) {
			this.setDeltaMovement(0, 0, 0);
		}

		// Contraption entities typically return isPickable()==false, so vanilla
		// projectile raycasts filter them out entirely. We do our own AABB scan
		// against the ray segment for this tick and synthesize an EntityHitResult
		// so the normal onHit flow handles the attach.
		if (!this.isAttachedToSurface && !this.level().isClientSide) {
			Vec3 rayStart = this.position();
			Vec3 rayEnd = rayStart.add(this.getDeltaMovement());
			@Nullable EntityHitResult contraptionHit = GrappleModIntegrations
					.getContraptionIntegration()
					.findContraptionAlongRay(this.level(), rayStart, rayEnd);
			if (contraptionHit != null) {
				this.onHit(contraptionHit);
			}
		}

		super.tick();

		if(this.restoreCollision && !this.level().isClientSide) {
			this.serverAttach(
					this.getLastBlockCollision(),
					this.getLastSubCollisionPos(),
					this.getLastBlockCollisionSide(),
					true
			);

			this.restoreCollision = false;
			return;
		}

		if (this.attachedEntity != null) {
			if (!this.attachedEntity.isAlive()) {
				GrappleMod.LOGGER.warn("Attached entity has perished ...");
				if (!this.level().isClientSide && this.shootingEntityID != 0) {
					GrappleModUtils.sendToCorrectClient(
							new GrappleDetachS2CPayload(this.shootingEntityID),
							this.shootingEntityID,
							this.level()
					);
				}
				this.removeServer();
				return;
			}

			Vec target;
			if (this.attachedContraptionLocalOffset != null) {
				Vec3 worldPoint = GrappleModIntegrations.getContraptionIntegration().localToWorld(
						this.attachedEntity,
						this.attachedContraptionLocalOffset,
						CONTRAPTION_PARTIAL_TICKS
				);
				target = new Vec(worldPoint);
			} else {
				target = Vec.positionVec(this.attachedEntity)
						.add(new Vec(0, this.attachedEntity.getBbHeight() * 0.5, 0));
			}

			this.setPos(target.x, target.y, target.z);
			this.setDeltaMovement(this.attachedEntity.getDeltaMovement());
		}

		boolean hookIsDetached = !this.level().isClientSide &&
				                  this.shootingEntity != null &&
				                 !this.isAttachedToAnything();

		if(!hookIsDetached) return;

		this.handleHookPhysics();
	}

	public boolean isAttachedToAnything() {
		return this.isAttachedToSurface || this.attachedEntity != null;
	}

	@Override
	public boolean canUsePortal(boolean allowPassengers) {
		return false; // block portal travel else dear god.
	}

	@Override
	protected void onHit(HitResult hit) {
		if (this.level().isClientSide) return;

		if (this.isAttachedToSurface) {
			return;
		}

		if (this.shootingEntity == null || this.shootingEntityID == 0) {
			return;
		}

		if(!this.shootingEntity.isAlive()) {
			return;
		}

		// Give the entity time to restore the collision
		if(this.restoreCollision)
			return;

		// A sanity check - Gives the client side entity a bit more
		// time to spawn.
		if(this.tickCount < 1)
			return;

		if (hit == null)
			return;


		Vec vec3d = Vec.positionVec(this);
		Vec vec3d1 = vec3d.add(Vec.motionVec(this));

		if (hit instanceof EntityHitResult && !GrappleModCommonConfig.get().doHooksAffectEntities()) {
			this.onHit(GrappleModUtils.rayTraceBlocks(this, this.level(), vec3d, vec3d1));
			return;
		}

		BlockHitResult blockhit = hit instanceof BlockHitResult movingHit
				? movingHit
				: null;

		if (blockhit != null) {
			BlockPos blockpos = blockhit.getBlockPos();
			BlockState block = this.level().getBlockState(blockpos);

			if (block.is(ModTags.HOOK_BREAKS)) {
				this.level().destroyBlock(blockpos, true);
				this.onHit(GrappleModUtils.rayTraceBlocks(this, this.level(), vec3d, vec3d1));
				return;
			}
		}

		if (hit instanceof EntityHitResult entityHit) {
			Entity entity = entityHit.getEntity();

			if (entity == this.shootingEntity) {
				return;
			}

			// Respect config toggle
			if (!GrappleModCommonConfig.get().doHooksAffectEntities()) {
				this.onHit(GrappleModUtils.rayTraceBlocks(this, this.level(), Vec.positionVec(this), Vec.positionVec(this).add(Vec.motionVec(this))));
				return;
			}

			// Contraption path — per-block raycast + local-offset attach.
			// On a miss (ray passed through empty cell inside the contraption's AABB),
			// re-trace for blocks past the contraption so the hook keeps flying.
			ContraptionIntegration contraptionIntegration = GrappleModIntegrations.getContraptionIntegration();
			if (contraptionIntegration.isContraption(entity)) {
				Vec3 rayStart = new Vec3(vec3d.x, vec3d.y, vec3d.z);
				Vec3 rayEnd   = new Vec3(vec3d1.x, vec3d1.y, vec3d1.z);

				Vec3 precisePoint = contraptionIntegration.raycastContraption(
						entity, rayStart, rayEnd, CONTRAPTION_PARTIAL_TICKS);

				if (precisePoint != null) {
					Vec3 localOffset = contraptionIntegration.worldToLocal(
							entity, precisePoint, CONTRAPTION_PARTIAL_TICKS);

					this.attachedEntity = entity;
					this.attachedEntityId = entity.getId();
					this.attachedContraptionLocalOffset = localOffset;
					this.recomputeAttachment();

					this.serverAttach(null, new Vec(precisePoint), null, true);

					GrappleMod.LOGGER.warn(String.format(
							"Attached to contraption %d at local offset %s",
							this.attachedEntityId, localOffset));
					return;
				}

				// Miss inside the AABB — re-trace past the contraption for blocks.
				this.onHit(GrappleModUtils.rayTraceBlocks(this, this.level(), vec3d, vec3d1));
				return;
			}

			// Plain-entity attach path (mobs, etc.) — follow entity center.
			this.attachedEntity = entity;
			this.attachedEntityId = entity.getId();
			this.recomputeAttachment();

			Vec entityPos = Vec.positionVec(entity);
			Vec attachPos = new Vec(
					entityPos.x,
					entityPos.y + entity.getBbHeight() * 0.5,
					entityPos.z
			);

			this.serverAttach(null, attachPos, null, true);

			GrappleMod.LOGGER.warn(String.format("Attached to a new entity: %d", this.attachedEntityId));

		} else if (blockhit != null) {
			BlockPos blockpos = blockhit.getBlockPos();
			Vec vec3 = new Vec(hit.getLocation());

			this.serverAttach(blockpos, vec3, blockhit.getDirection());

		} else {
			GrappleMod.LOGGER.warn("Unknown collision type when handling hook hit? Not an Entity or a Block.");
		}
	}



	private void handleHookPhysics() {
		if (this.segmentHandler.hookPastBend(this.ropeLength)) {
			Vec farthest = this.segmentHandler.getFarthest();

			if(!this.level().isClientSide)
				this.serverAttach(this.segmentHandler.getBendBlock(1), farthest, null);
		}

		if (!this.customization.get(BLOCK_PHASE_ROPE.get())) {
			this.segmentHandler.update(Vec.positionVec(this), Vec.positionVec(this.shootingEntity).add(new Vec(0, this.shootingEntity.getEyeHeight(), 0)), this.ropeLength, true);

			if (this.customization.get(STICKY_ROPE.get())) {
				List<Vec> segments = this.segmentHandler.getSegments();

				if (segments.size() > 2) {
					int bendnumber = segments.size() - 2;
					Vec closest = segments.get(bendnumber);
					BlockPos blockpos = this.segmentHandler.getBendBlock(bendnumber);

					for (int i = 1; i <= bendnumber; i++)
						this.segmentHandler.removeSegment(1);

					if(!this.level().isClientSide)
						this.serverAttach(blockpos, closest, null);
				}
			}

		} else {
			this.segmentHandler.updatePos(Vec.positionVec(this), Vec.positionVec(this.shootingEntity).add(new Vec(0, this.shootingEntity.getEyeHeight(), 0)), this.ropeLength);
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

			this.setVelocityActually(motion.x, motion.y, motion.z);

			ropevec.mutableSetMagnitude(this.ropeLength - distToFarthest);
			Vec newpos = ropevec.add(farthest);

			this.setPos(newpos.x, newpos.y, newpos.z);
		}

		// magnet attraction

		boolean shouldAttactMagnet = this.customization.get(MAGNET_ATTACHED.get()) &&
				Vec.positionVec(this).sub(Vec.positionVec(this.shootingEntity)).length() >
						this.customization.get(MAGNET_RADIUS.get());

		if (shouldAttactMagnet) handleMagnetAttraction();
	}

	private void handleMagnetAttraction() {
		if (this.foundBlock) return;

		Vec playerpos = Vec.positionVec(this.shootingEntity);
		Vec pos = Vec.positionVec(this);

		if (this.magnetBlock == null && this.prevPos != null) {

			HashMap<BlockPos, Boolean> cachedPositions = new HashMap<>();
			Vec vector = pos.sub(this.prevPos);

			if (vector.length() > 0) {
				Vec normvector = vector.normalize();

				for (int i = 0; i < vector.length(); i++) {
					double dist = this.prevPos.sub(playerpos).length();
					int radius = (int) dist / 4;

					Optional<BlockPos> optFound = this.checkForMagnetTargetsNearby(this.prevPos, cachedPositions);

					if (optFound.isEmpty()) {
						this.wasInAir = true;
						this.prevPos.mutableAdd(normvector);
						continue;
					}

					BlockPos found = optFound.get();

					Vec distvec = new Vec(found.getX(), found.getY(), found.getZ());
					distvec.mutableSub(prevPos);
					if (distvec.length() < radius) {
						this.setPosRaw(prevPos.x, prevPos.y, prevPos.z);
						pos = this.prevPos;
						this.magnetBlock = found;

						break;
					}

					this.prevPos.mutableAdd(normvector);
				}
			}
		}

		if (magnetBlock != null) {
			BlockState blockstate = this.level().getBlockState(magnetBlock);
			VoxelShape BB = blockstate.getCollisionShape(this.level(), magnetBlock);

			Vec blockvec = new Vec(magnetBlock.getX() + (BB.max(Axis.X) + BB.min(Axis.X)) / 2, magnetBlock.getY() + (BB.max(Axis.Y) + BB.min(Axis.Y)) / 2, magnetBlock.getZ() + (BB.max(Axis.Z) + BB.min(Axis.Z)) / 2);
			Vec newvel = blockvec.sub(pos);

			double l = newvel.length();

			newvel.withMagnitude(this.getSpeed());

			this.setDeltaMovement(newvel.x, newvel.y, newvel.z);

			if (l < 0.2) {
				this.serverAttach(magnetBlock, blockvec, Direction.UP);
			}
		}

		prevPos = pos;
	}


	public void removeServer() {
		this.remove(RemovalReason.DISCARDED);
		this.shootingEntityID = 0;
	}

	public void shoot(Vec direction, double speed, float inaccuracy) {
		this.shoot(direction.getX(), direction.getY(), direction.getZ(), (float) speed, inaccuracy);
	}

	public void setVelocityActually(double x, double y, double z) {
		this.setDeltaMovement(x, y, z);

        if (this.xRotO == 0.0F && this.yRotO == 0.0F) {
            double f = Math.sqrt(x * x + z * z);
            this.setYRot((float)(Mth.atan2(x, z) * (180D / Math.PI)));
            this.setXRot((float)(Mth.atan2(y, f) * (180D / Math.PI)));
            this.yRotO = this.getYRot();
            this.xRotO = this.getXRot();
        }
	}


	public void serverAttach(BlockPos blockpos, Vec pos, Direction sideHit) {
		this.serverAttach(blockpos, pos, sideHit, false);
	}

	public void serverAttach(BlockPos blockpos, Vec pos, Direction sideHit, boolean force) {
		if(this.level().isClientSide)
			return;

		if (this.isAttachedToSurface)
			return;

		if (this.shootingEntity == null || this.shootingEntityID == 0)
			return;

		this.isAttachedToSurface = true;
		this.lastBlockCollision = blockpos;
		this.lastSubCollisionPos = pos;
		this.lastBlockCollisionSide = sideHit;
		this.recomputeAttachment();

		if (blockpos != null) {
			BlockState block = this.level().getBlockState(blockpos);

			if ((!force) && (!this.canAttachToBlock(block))) {
				this.playSound(SoundEvents.ANVIL_LAND, 0.7f, 1.8f);
				this.removeServer();
				return;
			}
		}

		Vec vec3 = Vec.positionVec(this);
		vec3.mutableAdd(Vec.motionVec(this));

		if (pos != null) {
            vec3 = pos;
            this.setPosRaw(vec3.x, vec3.y, vec3.z);
		}

		//west -x
		//north -z
		Vec curpos = Vec.positionVec(this);
		if(sideHit != null)
			switch (sideHit) {
				case DOWN  -> curpos.y -= 0.3f;
				case WEST  -> curpos.x -= 0.05f;
				case NORTH -> curpos.z -= 0.05f;
				case SOUTH -> curpos.z += 0.05f;
				case EAST  -> curpos.x += 0.05f;
				case UP    -> curpos.y += 0.05f;
			}
		curpos.applyAsPositionTo(this);

		this.setDeltaMovement(0, 0, 0);

        this.thisPos = Vec.positionVec(this);
		this.isFirstAttach = true;

		GrappleAttachS2CPayload.GrappleAttachTarget attachTarget;
		if (this.attachedEntity != null && this.attachedContraptionLocalOffset != null) {
			attachTarget = new GrappleAttachS2CPayload.GrappleAttachTarget.EntityOffset(
					this.attachedEntityId, this.attachedContraptionLocalOffset);
		} else if (this.attachedEntity != null) {
			attachTarget = new GrappleAttachS2CPayload.GrappleAttachTarget.Entity(this.attachedEntityId);
		} else {
			attachTarget = new GrappleAttachS2CPayload.GrappleAttachTarget.Block(blockpos);
		}

		GrappleAttachS2CPayload shootPacket = new GrappleAttachS2CPayload(
				this.getId(),
				this.position().toVector3f(),
				this.shootingEntityID,
				attachTarget,
				new RopeSnapshot(this.segmentHandler),
				this.customization
		);

		GrappleMod.LOGGER.info(shootPacket);

		GrappleModUtils.sendToCorrectClient(
				shootPacket,
				this.shootingEntityID,
				this.level()
		);

		GrappleAttachHookS2CPayload msg = new GrappleAttachHookS2CPayload(this.getId(), this.position().toVector3f());
		NetworkManager.packetToClient(msg, GrappleModUtils.getPlayersThatCanSeeChunkAt((ServerLevel) this.level(), new Vec(this.position())));

		GrappleModServerEvents.HOOK_ATTACH.invoker().onHookAttach(this.shootingEntity, this);
	}

	/**
	 * Switch this hook's anchor from a static block to a moving contraption that just absorbed
	 * that block. Preserves the original sub-block hit point as a contraption-local offset so
	 * the rope's visual anchor doesn't snap to the block's center on conversion.
	 *
	 * <p>Server-only. Sends a {@link GrappleReanchorToEntityS2CPayload} (not a full
	 * {@link GrappleAttachS2CPayload}) so the client's existing physics controller is
	 * left intact — a full re-attach would disable the old controller, which would fire
	 * {@code HaltCustomPhysicsC2SPayload} back and destroy the hook we just reanchored.</p>
	 */
	public void reattachToContraption(Entity contraption, Vec3 localOffset, @Nullable BlockPos localBlockPos) {
		if (this.level().isClientSide) return;
		if (!this.isAttachedToSurface) return;

		this.attachedEntity = contraption;
		this.attachedEntityId = contraption.getId();
		this.attachedContraptionLocalOffset = localOffset;
		this.attachedContraptionLocalBlockPos = localBlockPos;
		this.lastBlockCollision = null;
		this.lastBlockCollisionSide = null;
		this.lastSubCollisionPos = null;
		this.recomputeAttachment();

		// Intentionally a lightweight reanchor packet rather than a full GrappleAttachS2CPayload:
		// the full payload rebuilds the client-side physics controller, whose disable() path
		// fires HaltCustomPhysicsC2SPayload back to the server and kills this hook.
		GrappleReanchorToEntityS2CPayload packet = new GrappleReanchorToEntityS2CPayload(
				this.getId(), this.attachedEntityId, localOffset);

		GrappleModUtils.sendToCorrectClient(packet, this.shootingEntityID, this.level());
	}

	/**
	 * Called by contraption-integration compat modules when a contraption has just assembled.
	 * Migrates any active hook anchored to a block that the contraption captured onto the
	 * contraption itself, preserving sub-block hit precision.
	 */
	public static void onContraptionAssembled(Entity contraptionEntity) {
		ContraptionIntegration ci = GrappleModIntegrations.getContraptionIntegration();
		if (ci == null || !ci.isContraption(contraptionEntity)) return;

		Level contraptionLevel = contraptionEntity.level();
		if (contraptionLevel.isClientSide) return;

		for (GrapplinghookEntity hook : ServerHookEntityTracker.getAllTrackedHooks()) {
			if (hook == null || !hook.isAlive()) continue;
			if (hook.level() != contraptionLevel) continue;
			if (!hook.isAttachedToSurface) continue;
			if (hook.lastBlockCollision == null) continue;
			if (hook.attachedEntity != null) continue;

			BlockPos localKey = ci.getCapturedLocalPos(contraptionEntity, hook.lastBlockCollision);
			if (localKey == null) continue;

			Vec3 worldHit = hook.lastSubCollisionPos != null
					? hook.lastSubCollisionPos.toVec3d()
					: Vec3.atCenterOf(hook.lastBlockCollision);
			Vec3 localOffset = ci.worldToLocal(contraptionEntity, worldHit, CONTRAPTION_PARTIAL_TICKS);

			hook.reattachToContraption(contraptionEntity, localOffset, localKey);
		}
	}

	/**
	 * Maximum distance (in blocks) between the hook's world position and the nearest face of
	 * the candidate block for a disassembly-time block re-anchor to be considered "this is
	 * still visually the same anchor." Beyond this, the disassembly moved geometry enough
	 * that a silent re-anchor would look like a teleport, so we detach instead.
	 */
	private static final double DISASSEMBLY_REANCHOR_MAX_DIST = 1.47;

	/**
	 * Called by contraption-integration compat modules when a contraption is about to be
	 * removed because of disassembly (blocks being placed back into the world). For each
	 * hook anchored to this contraption, attempts a precise re-anchor to the block that
	 * just landed under it; if no suitable block is nearby, detaches the hook cleanly.
	 */
	public static void onContraptionDisassembled(Entity contraptionEntity) {
		ContraptionIntegration ci = GrappleModIntegrations.getContraptionIntegration();
		if (ci == null || !ci.isContraption(contraptionEntity)) return;

		Level level = contraptionEntity.level();
		if (level.isClientSide) return;

		for (GrapplinghookEntity hook : ServerHookEntityTracker.getAllTrackedHooks()) {
			if (hook == null || !hook.isAlive()) continue;
			if (hook.level() != level) continue;
			if (hook.attachedEntity != contraptionEntity) continue;

			BlockPos localBlock = hook.attachedContraptionLocalBlockPos;
			if (localBlock == null) {
				hook.detachFromContraption();
				continue;
			}

			Vec3 localCenter = new Vec3(localBlock.getX() + 0.5, localBlock.getY() + 0.5, localBlock.getZ() + 0.5);
			Vec3 worldCenter = ci.localToWorld(contraptionEntity, localCenter, CONTRAPTION_PARTIAL_TICKS);
			BlockPos candidate = BlockPos.containing(worldCenter);

			BlockState state = level.getBlockState(candidate);
			Vec3 hookPos = hook.position();
			double dist = distancePointToAabb(hookPos, new AABB(candidate));

			if (state.isAir() || dist > DISASSEMBLY_REANCHOR_MAX_DIST) {
				hook.detachFromContraption();
				continue;
			}

			hook.reattachToBlock(candidate, hookPos);
		}
	}

	private static double distancePointToAabb(Vec3 p, AABB box) {
		double dx = Math.max(Math.max(box.minX - p.x, 0), p.x - box.maxX);
		double dy = Math.max(Math.max(box.minY - p.y, 0), p.y - box.maxY);
		double dz = Math.max(Math.max(box.minZ - p.z, 0), p.z - box.maxZ);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Inverse of {@link #reattachToContraption}: switch a contraption-anchored hook onto a
	 * static block at {@code blockPos}. The hook's world position is pinned to {@code hookWorldPos}
	 * (the last known contraption-tracked position) so the visual anchor doesn't jump.
	 */
	public void reattachToBlock(BlockPos blockPos, Vec3 hookWorldPos) {
		if (this.level().isClientSide) return;

		this.attachedEntity = null;
		this.attachedEntityId = -1;
		this.attachedContraptionLocalOffset = null;
		this.attachedContraptionLocalBlockPos = null;

		this.lastBlockCollision = blockPos;
		this.lastSubCollisionPos = new Vec(hookWorldPos);
		this.lastBlockCollisionSide = null;
		this.isAttachedToSurface = true;
		this.recomputeAttachment();

		this.setPosRaw(hookWorldPos.x, hookWorldPos.y, hookWorldPos.z);
		this.setDeltaMovement(0, 0, 0);
		this.thisPos = new Vec(hookWorldPos);

		GrappleReanchorToBlockS2CPayload packet = new GrappleReanchorToBlockS2CPayload(
				this.getId(), blockPos, hookWorldPos);
		GrappleModUtils.sendToCorrectClient(packet, this.shootingEntityID, this.level());
	}

	/**
	 * Tell the shooter's client to detach this hook and clean up server state.
	 * Used when a contraption our hook was following disassembles without a suitable
	 * block underneath.
	 */
	public void detachFromContraption() {
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

	/** Client-side mirror of {@link #reattachToBlock}. Updates entity state without touching the controller. */
	public void clientReanchorToBlock(BlockPos blockPos, Vec3 hookWorldPos) {
		this.attachedEntity = null;
		this.attachedEntityId = -1;
		this.attachedContraptionLocalOffset = null;
		this.attachedContraptionLocalBlockPos = null;
		// Mirror the server-side block fields so recomputeAttachment() produces a Block variant
		// on the client too. Prior to the refactor the client never tracked these — harmless
		// because nothing read them client-side, but required once attachment() is authoritative.
		this.lastBlockCollision = blockPos;
		this.lastSubCollisionPos = new Vec(hookWorldPos);
		this.lastBlockCollisionSide = null;
		this.recomputeAttachment();
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

	// this mostly comes from packets
	public void setAttachPos(Vector3f attachPos) {
		float x = attachPos.x;
		float y = attachPos.y;
		float z = attachPos.z;
		this.setPosRaw(x, y, z);

		this.setDeltaMovement(0, 0, 0);
		this.isFirstAttach = true;
		this.isAttachedToSurface = true;
		this.restoreCollision = false;
        this.thisPos = new Vec(x, y, z);
		this.recomputeAttachment();
	}

	// used for magnet attraction

	/**
	 * Checks all the block positions within a certain "radius" (+- radius in each axis)
	 * around the center to see if the hook could successfully collide with them.
	 */
	public Optional<BlockPos> checkForMagnetTargetsNearby(Vec center, HashMap<BlockPos, Boolean> cachedPositions) {
    	int radius = (int) Math.floor(this.customization.get(MAGNET_RADIUS.get()));

    	BlockPos closestValidPos = null;
    	double closestDistance = 0;

		int pX = (int) center.x;
		int pY = (int) center.y;
		int pZ = (int) center.z;

    	for (int x = pX - radius; x <= pX + radius; x++) {
        	for (int y = pY - radius; y <= pY + radius; y++) {
            	for (int z = pZ - radius; z <= pZ + radius; z++) {

			    	BlockPos pos = new BlockPos(x, y, z);
					if (!this.checkIfCollidingWithBlock(pos, cachedPositions))
						continue;

					Vec distvec = new Vec(pos.getX(), pos.getY(), pos.getZ());
					distvec.mutableSub(center);

					double dist = distvec.length();
					if (closestValidPos == null || dist < closestDistance) {
						closestValidPos = pos;
						closestDistance = dist;
					}
				}
	    	}
    	}

		return Optional.ofNullable(closestValidPos);
	}
	// used for magnet attraction

	/**
	 * Checks if the hook has collided with a block, caching the check to the provided HashMap.
	 * Ensures that if there is a collision, the collided block matches the allowed / disallowed
	 * tags & gamerules.
	 */
	public boolean checkIfCollidingWithBlock(BlockPos pos, HashMap<BlockPos, Boolean> cachedPositions) {
		if(cachedPositions.containsKey(pos))
			return cachedPositions.get(pos);

		boolean canAttach = false;
		BlockState blockState = this.level().getBlockState(pos);

		if (this.canAttachToBlock(blockState) && !blockState.isAir()) {
			VoxelShape collider = blockState.getCollisionShape(this.level(), pos);

			if (!collider.isEmpty())
				canAttach = true;
		}

		cachedPositions.put(pos, canAttach);
		return canAttach;
	}

	private boolean canAttachToBlock(BlockState blockState) {
		// "Limited Hook" mode acts as a whitelist. Default behaviour uses a blacklist.
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

	public Vec getSurfaceAttachmentDirection() {
		return this.attachDirection;
	}

	public double getCurrentRopeLength() {
		return this.ropeLength;
	}

	public BlockPos getLastBlockCollision() {
		return this.lastBlockCollision;
	}

	public Vec getLastSubCollisionPos() {
		return this.lastSubCollisionPos;
	}

	public Direction getLastBlockCollisionSide() {
		return this.lastBlockCollisionSide;
	}

	public Entity getAttachedEntity() { return this.attachedEntity; }

	public int getAttachedEntityId() { return this.attachedEntityId; }

	public void setAttachedEntityIdClient(int id) {
		this.attachedEntityId = id;
		this.recomputeAttachment();
	}

	public void setAttachedEntityClient(Entity entity) {
		this.attachedEntity = entity;
		this.attachedEntityId = entity != null ? entity.getId() : -1;
		this.recomputeAttachment();
	}

	public void setAttachedContraptionLocalOffset(Vec3 offset) {
		this.attachedContraptionLocalOffset = offset;
		this.recomputeAttachment();
	}

	/**
	 * Shadow view of attachment state as a sealed sum type (refactor in progress).
	 * Kept in sync with the legacy fields by {@link #recomputeAttachment()}; once
	 * migration completes it becomes the sole source of truth.
	 */
	public @Nullable HookAttachment attachment() { return this.attachment; }

	/**
	 * Derive the consolidated {@link HookAttachment} from the current legacy field
	 * values. Called at the end of every mutation method during the step-wise
	 * refactor so reads can migrate to {@code attachment} independently of writes.
	 */
	private void recomputeAttachment() {
		if (this.attachedEntity != null || this.attachedEntityId != -1) {
			if (this.attachedContraptionLocalOffset != null) {
				this.attachment = this.attachedEntity != null
						? new HookAttachment.ContraptionBlock(
								this.attachedEntity,
								this.attachedContraptionLocalOffset,
								this.attachedContraptionLocalBlockPos)
						: HookAttachment.ContraptionBlock.fromId(
								this.attachedEntityId,
								this.attachedContraptionLocalOffset);
			} else {
				this.attachment = this.attachedEntity != null
						? new HookAttachment.Entity(this.attachedEntity)
						: HookAttachment.Entity.fromId(this.attachedEntityId);
			}
			return;
		}
		if (this.lastBlockCollision != null && this.lastSubCollisionPos != null) {
			this.attachment = new HookAttachment.Block(
					this.lastBlockCollision,
					this.lastSubCollisionPos.toVec3d(),
					this.lastBlockCollisionSide);
			return;
		}
		// isAttachedToSurface can be true client-side with no target fields populated
		// (spawn packet path); leave attachment null until a target-carrying packet lands.
		this.attachment = null;
	}
}
