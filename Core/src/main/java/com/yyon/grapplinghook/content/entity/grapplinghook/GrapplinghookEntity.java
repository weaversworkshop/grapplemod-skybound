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
import com.yyon.grapplinghook.integration.SubLevelIntegration;
import com.yyon.grapplinghook.network.NetworkManager;
import com.yyon.grapplinghook.network.clientbound.GrappleAttachS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleAttachHookS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleDetachS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleReanchorToEntityS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleReanchorToBlockS2CPayload;
import com.yyon.grapplinghook.physics.AnchorSpace;
import com.yyon.grapplinghook.physics.RopeBend;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import com.yyon.grapplinghook.physics.ServerHookEntityTracker;
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
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
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

		if (this.attachment == null && !this.level().isClientSide) {
			Vec3 rayStart = this.position();
			Vec3 rayEnd = rayStart.add(this.getDeltaMovement());
			@Nullable EntityHitResult contraptionHit = GrappleModIntegrations
					.getContraptionIntegration()
					.findContraptionAlongRay(this.level(), rayStart, rayEnd);
			if (contraptionHit != null) {
				this.onHit(contraptionHit);
			}
		}

		// Sable's ProjectileUtilMixin patches vanilla projectile collision; if its ray crosses a tracked sub-level AABB it can hang the server.
		SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
		boolean anySubLevelCrossed = false;
		if (this.attachment == null) {
			Vec3 rayStart = this.position();
			Vec3 rayEnd = rayStart.add(this.getDeltaMovement());

			UUID[] bestUuid = { null };
			SubLevelIntegration.SubLevelRaycastHit[] bestHit = { null };
			double[] bestDistSq = { Double.POSITIVE_INFINITY };
			boolean[] crossedRef = { false };
			boolean isServer = !this.level().isClientSide;

			sli.forEachTrackedSubLevel((uuid, aabb) -> {
				if (!aabb.clip(rayStart, rayEnd).isPresent()) return;
				crossedRef[0] = true;
				if (!isServer) return;
				SubLevelIntegration.SubLevelRaycastHit hit = sli.raycastSubLevelDetailed(
						uuid, rayStart, rayEnd, CONTRAPTION_PARTIAL_TICKS);
				if (hit == null) return;
				double distSq = hit.worldHit().distanceToSqr(rayStart);
				if (distSq < bestDistSq[0]) {
					bestDistSq[0] = distSq;
					bestUuid[0] = uuid;
					bestHit[0] = hit;
				}
			});

			anySubLevelCrossed = crossedRef[0];

			if (bestHit[0] != null) {
				this.serverAttach(
						new HookAttachment.SubLevelBlock(bestUuid[0], bestHit[0].plotBlock(), bestHit[0].plotHit()),
						true);
				this.setDeltaMovement(0, 0, 0);
				anySubLevelCrossed = false;
			}
		}

		if (anySubLevelCrossed) {
			this.manualProjectileStep();
		} else {
			super.tick();
		}

		switch (this.attachment) {
			case null -> {}

			case HookAttachment.Block ignored -> {}

			case HookAttachment.Entity entityAttach -> {
				Entity e = entityAttach.entity();
				if (e == null || !e.isAlive()) {
					this.onAttachedEntityPerished();
					return;
				}
				Vec target = Vec.positionVec(e).add(new Vec(0, e.getBbHeight() * 0.5, 0));
				this.setPos(target.x, target.y, target.z);
				this.setDeltaMovement(e.getDeltaMovement());
			}

			case HookAttachment.ContraptionBlock cb -> {
				Entity e = cb.entity();
				if (e == null || !e.isAlive()) {
					this.onAttachedEntityPerished();
					return;
				}
				Vec3 worldPoint = cb.worldHitPoint(CONTRAPTION_PARTIAL_TICKS);
				this.setPos(worldPoint.x, worldPoint.y, worldPoint.z);
				this.setDeltaMovement(e.getDeltaMovement());
			}

			case HookAttachment.SubLevelBlock slb -> {
				try {
					if (!sli.isSubLevelLoaded(slb.subLevelId())) {
						if (!this.level().isClientSide) {
							this.onAttachedEntityPerished();
							return;
						}
						break;
					}
					if (!this.level().isClientSide && !sli.isPlotBlockSolid(slb.subLevelId(), slb.plotBlock())) {
						if (this.tryMigrateLostSubLevelAnchor(sli, slb)) return;
						this.onAttachedEntityPerished();
						return;
					}
					Vec3 worldPoint = slb.worldHitPoint(CONTRAPTION_PARTIAL_TICKS);
					this.setPos(worldPoint.x, worldPoint.y, worldPoint.z);
					this.setDeltaMovement(0, 0, 0);
				} catch (Throwable err) {
					GrappleMod.LOGGER.error("[Grapple <-> Sable] Follow tick threw on side={} — detaching so we don't spin on this",
							this.level().isClientSide ? "CLIENT" : "SERVER", err);
					this.onAttachedEntityPerished();
					return;
				}
			}
		}

		boolean hookIsDetached = !this.level().isClientSide &&
				                  this.shootingEntity != null &&
				                 !this.isAttachedToAnything();

		if(!hookIsDetached) return;

		this.handleHookPhysics();
	}

	public boolean isAttachedToAnything() {
		return this.attachment != null;
	}

	private void manualProjectileStep() {
		Vec3 delta = this.getDeltaMovement();
		Vec3 start = this.position();
		Vec3 end = start.add(delta);

		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
				this.level(), this, start, end,
				this.getBoundingBox().expandTowards(delta).inflate(1.0),
				e -> !e.isSpectator() && e.isAlive() && e.isPickable());

		BlockHitResult blockHit = GrappleModUtils.rayTraceBlocks(this, this.level(), new Vec(start), new Vec(end));

		HitResult hit = null;
		if (entityHit != null && blockHit != null) {
			double entityDistSq = entityHit.getLocation().distanceToSqr(start);
			double blockDistSq = blockHit.getLocation().distanceToSqr(start);
			hit = entityDistSq < blockDistSq ? entityHit : blockHit;
		} else if (entityHit != null) {
			hit = entityHit;
		} else if (blockHit != null) {
			hit = blockHit;
		}

		if (hit != null) {
			this.onHit(hit);
			if (this.isRemoved()) return;
			if (this.attachment != null) return;
		}

		this.setPos(end.x, end.y, end.z);

		float drag = this.isInWater() ? 0.8F : 0.99F;
		double gravity = this.getGravity();
		this.setDeltaMovement(delta.x * drag, (delta.y - gravity) * drag, delta.z * drag);
	}

	private void onAttachedEntityPerished() {
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
		if (this.level().isClientSide) return;

		if (this.attachment != null ||
			this.shootingEntity == null || this.shootingEntityID == 0 || !this.shootingEntity.isAlive() ||
			this.tickCount < 1 ||
			hit == null
		) {
			return;
		}

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

			if (!GrappleModCommonConfig.get().doHooksAffectEntities()) {
				this.onHit(GrappleModUtils.rayTraceBlocks(this, this.level(), Vec.positionVec(this), Vec.positionVec(this).add(Vec.motionVec(this))));
				return;
			}

			ContraptionIntegration contraptionIntegration = GrappleModIntegrations.getContraptionIntegration();
			if (contraptionIntegration.isContraption(entity)) {
				Vec3 rayStart = new Vec3(vec3d.x, vec3d.y, vec3d.z);
				Vec3 rayEnd   = new Vec3(vec3d1.x, vec3d1.y, vec3d1.z);

				Vec3 precisePoint = contraptionIntegration.raycastContraption(
						entity, rayStart, rayEnd, CONTRAPTION_PARTIAL_TICKS);

				if (precisePoint != null) {
					Vec3 localOffset = contraptionIntegration.worldToLocal(
							entity, precisePoint, CONTRAPTION_PARTIAL_TICKS);
					Vec3 backToWorld = contraptionIntegration.localToWorld(
							entity, localOffset, CONTRAPTION_PARTIAL_TICKS);
					GrappleMod.LOGGER.info(
							"[Grapple] CREATE-path attach: entity={} id={} entity.pos={} precisePointWorld={} localOffset={} localToWorld(localOffset)={}",
							entity.getClass().getSimpleName(), entity.getId(), entity.position(),
							precisePoint, localOffset, backToWorld);

					this.serverAttach(
							new HookAttachment.ContraptionBlock(entity, localOffset, null),
							true);
					return;
				}

				this.onHit(GrappleModUtils.rayTraceBlocks(this, this.level(), vec3d, vec3d1));
				return;
			}

			this.serverAttach(new HookAttachment.Entity(entity), true);

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
					this.serverAttach(
							new HookAttachment.SubLevelBlock(subLevelId, blockpos, hitPoint),
							true);
					return;
				}
				GrappleMod.LOGGER.warn("[Grapple <-> Sable] Plot-coord BlockHitResult but no sub-level claims block {} — falling back to plain Block attach (rope may misrender)",
						blockpos);
			}

			this.serverAttach(
					new HookAttachment.Block(blockpos, hitPoint, blockhit.getDirection()),
					false);

		} else {
			GrappleMod.LOGGER.warn("Unknown collision type when handling hook hit? Not an Entity or a Block.");
		}
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

			this.setVelocityActually(motion.x, motion.y, motion.z);

			ropevec.mutableSetMagnitude(this.ropeLength - distToFarthest);
			Vec newpos = ropevec.add(farthest);

			this.setPos(newpos.x, newpos.y, newpos.z);
		}

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
				this.serverAttach(
						new HookAttachment.Block(magnetBlock, blockvec.toVec3d(), Direction.UP),
						false);
			}
		}

		prevPos = pos;
	}


	public void removeServer() {
		this.setAttachment(null);
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

	public static final double DISASSEMBLY_REANCHOR_MAX_DIST = 1.47;

private boolean tryMigrateLostSubLevelAnchor(SubLevelIntegration sli, HookAttachment.SubLevelBlock slb) {
		Vec3 lastWorldPos;
		try {
			lastWorldPos = slb.worldHitPoint(CONTRAPTION_PARTIAL_TICKS);
		} catch (Throwable ignored) {
			lastWorldPos = this.position();
		}
		BlockPos worldCandidate = BlockPos.containing(lastWorldPos);

		UUID[] winner = { null };
		BlockPos[] winnerPlotBlock = { null };
		final Vec3 probePoint = lastWorldPos;
		sli.forEachTrackedSubLevel((uuid, aabb) -> {
			if (winner[0] != null) return;
			if (uuid.equals(slb.subLevelId())) return;
			if (!aabb.contains(probePoint)) return;
			BlockPos plotBlock = sli.worldToPlotBlock(uuid, probePoint, CONTRAPTION_PARTIAL_TICKS);
			if (sli.isPlotBlockSolid(uuid, plotBlock)) {
				winner[0] = uuid;
				winnerPlotBlock[0] = plotBlock;
			}
		});

		if (winner[0] != null) {
			Vec3 newPlotHit = sli.worldToPlot(winner[0], lastWorldPos, CONTRAPTION_PARTIAL_TICKS);
			GrappleMod.LOGGER.info("[Grapple <-> Sable] Sub-level anchor migrated hookId={} {} → {} (plotBlock {})",
					this.getId(), slb.subLevelId(), winner[0], winnerPlotBlock[0]);
			this.reattachToSubLevel(winner[0], winnerPlotBlock[0], newPlotHit);
			return true;
		}

		BlockState worldState = this.level().getBlockState(worldCandidate);
		double dist = distancePointToAabb(this.position(), new AABB(worldCandidate));
		if (!worldState.isAir() && dist <= DISASSEMBLY_REANCHOR_MAX_DIST) {
			GrappleMod.LOGGER.info("[Grapple <-> Sable] Sub-level anchor lost, falling back to world block for hookId={} at {}",
					this.getId(), worldCandidate);
			this.reattachToBlock(worldCandidate, lastWorldPos);
			return true;
		}
		return false;
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

	public static double distancePointToAabb(Vec3 p, AABB box) {
		double dx = Math.max(Math.max(box.minX - p.x, 0), p.x - box.maxX);
		double dy = Math.max(Math.max(box.minY - p.y, 0), p.y - box.maxY);
		double dz = Math.max(Math.max(box.minZ - p.z, 0), p.z - box.maxZ);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
		Vec pos = Vec.positionVec(this);
		if (this.attachment == null) return pos;
		Direction face = this.attachment.ropeAnchorFace();
		if (face == null) return pos;
		return pos.add(new Vec(
				face.getStepX() * ROPE_ANCHOR_FACE_OFFSET,
				face.getStepY() * ROPE_ANCHOR_FACE_OFFSET,
				face.getStepZ() * ROPE_ANCHOR_FACE_OFFSET));
	}

	private static final double ROPE_ANCHOR_FACE_OFFSET = 0.08;

	public Vec getSurfaceAttachmentDirection() {
		return this.attachDirection;
	}

	public double getCurrentRopeLength() {
		return this.ropeLength;
	}

	public @Nullable Entity attachedWorldEntity() {
		return switch (this.attachment) {
			case HookAttachment.Entity e -> e.entity();
			case HookAttachment.ContraptionBlock cb -> cb.entity();
			case null, default -> null;
		};
	}

	public boolean isAttachedToMovingBody() {
		return this.attachedWorldEntity() != null
				|| this.attachment instanceof HookAttachment.SubLevelBlock;
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
