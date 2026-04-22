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
	/**
	 * Spectator-visible "this hook is frozen" flag. Replicated in the spawn packet so new
	 * observers render the hook as stationary without receiving the full attachment target
	 * (which is owner-only). Kept in sync with {@code attachment != null} by
	 * {@link #setAttachment}.
	 */
	private boolean isAttachedToSurface;
	public Vec attachDirection = null;

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

	/**
	 * Authoritative attachment state — what the hook is anchored to, as a sealed sum type.
	 * {@code null} means the hook is in flight or detached.
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
		
		this.isAttachedToMainHand = isAttachedToMainHand;   // set early so getRopeOriginAtHolder sees the right hand side
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
		// While tethered to a moving body (mob, contraption, or sub-level), the client tracks
		// the anchor locally in tick(). Ignoring server position sync here prevents ~5Hz
		// jitter from tracker updates fighting our direct setPos.
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
		if (this.shootingEntityID == 0 || this.shootingEntity == null) { // removes ghost grappling hooks
			GrappleMod.LOGGER.info("[HookDbg] tick: discarding ghost (no shooter) hookId={} side={}", this.getId(), this.level().isClientSide ? "C" : "S");
			this.discard();
			return;
		}

		if (!this.shootingEntity.isAlive()) {
			GrappleMod.LOGGER.info("[HookDbg] tick: discarding — shooter not alive hookId={} side={}", this.getId(), this.level().isClientSide ? "C" : "S");
			this.discard();
			return;
		}

		if (!this.level().isClientSide) {
			GrappleMod.LOGGER.info("[HookDbg] tick#{} hookId={} pos={} delta={} attachment={} bends={}",
					this.tickCount, this.getId(), this.position(), this.getDeltaMovement(),
					this.attachment == null ? "null" : this.attachment.getClass().getSimpleName(),
					this.segmentHandler == null ? "n/a" : this.segmentHandler.getBends().size());
		}

		if (this.isFirstAttach) {
			this.setDeltaMovement(0, 0, 0);
			this.isFirstAttach = false;
			super.setPos(this.thisPos.x, this.thisPos.y, this.thisPos.z);
		}

		// Re-resolve any cached entity handle on the current attachment.
		if (this.attachment != null) {
			HookAttachment refreshed = this.attachment.refreshed(this.level());
			if (refreshed != this.attachment) {
				this.attachment = refreshed;
			}
		}

		if (this.attachment != null) {
			this.setDeltaMovement(0, 0, 0);
		}

		// Contraption entities typically return isPickable()==false, so vanilla
		// projectile raycasts filter them out entirely. We do our own AABB scan
		// against the ray segment for this tick and synthesize an EntityHitResult
		// so the normal onHit flow handles the attach.
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

		// Sub-level broad-phase scan + near-sublevel guard, combined.
		//
		// Sable's ProjectileUtilMixin patches vanilla projectile collision to look
		// for hits inside sub-levels' plot-space block storage. Any time the hook's
		// per-tick move ray passes through a tracked sub-level's apparent-world AABB,
		// that patched path runs — and when it runs, it can pull plot-coord
		// BlockGetter state, triggering chunk generation at ~20M coords and hanging
		// the server for several seconds. Same underlying bug documented in
		// project_sable_rope_raytrace_hang.md / project_sable_projectile_flight_hang.md.
		//
		// The geometric trigger is **ray vs sub-level AABB**, not hook-bbox vs AABB —
		// so we use {@link SubLevelIntegration#findSubLevelAlongRay} (same ray test
		// Sable effectively uses) as the gate, not a swept-bbox overlap. The hook's
		// hitbox is only ~0.25 m, so a swept-bbox check can miss cases where the
		// ray actually crosses the AABB.
		//
		// If the ray hits a sub-level AABB:
		//   (a) server-side: try our own plot-aware block raycast. Hit → attach.
		//       Miss (near-miss through an air gap) → let manualProjectileStep
		//       advance the hook, never running super.tick().
		//   (b) client-side: just bypass super.tick() too; client hook physics
		//       re-syncs to the server via the existing tracking packets.
		SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
		UUID nearSubLevel = null;
		if (this.attachment == null) {
			Vec3 rayStart = this.position();
			Vec3 rayEnd = rayStart.add(this.getDeltaMovement());
			nearSubLevel = sli.findSubLevelAlongRay(rayStart, rayEnd);

			if (nearSubLevel != null && !this.level().isClientSide) {
				// Use the detailed raycast so we get the hit block directly from
				// the plot-space DDA. The non-detailed path derives plotBlock via
				// BlockPos.containing(worldToPlot(entryPoint)), which for hits on
				// +X/+Y/+Z faces rounds to the adjacent block on the wrong side of
				// the face (entry point sits exactly on the integer boundary). A
				// wrong plotBlock propagates to HookAttachment.ropeAnchorFace's
				// inferFace, flipping the anchor offset inward — causing the rope
				// endpoint to sit inside a solid plot block and corrupting every
				// subsequent rope raycast. Most visible when the player is inside
				// the sub-level AABB (close to the hit block).
				SubLevelIntegration.SubLevelRaycastHit detailedHit = sli.raycastSubLevelDetailed(
						nearSubLevel, rayStart, rayEnd, CONTRAPTION_PARTIAL_TICKS);
				if (detailedHit != null) {
					this.serverAttach(
							new HookAttachment.SubLevelBlock(nearSubLevel, detailedHit.plotBlock(), detailedHit.plotHit()),
							true);
					this.setDeltaMovement(0, 0, 0);
					nearSubLevel = null; // attached — no bypass needed
				}
			}
		}

		if (nearSubLevel != null) {
			this.manualProjectileStep();
		} else {
			super.tick();
		}

		// Dispatch follow behavior on the attachment variant.
		switch (this.attachment) {
			case null -> { /* unattached: no follow */ }

			case HookAttachment.Block ignored -> { /* static block: no follow */ }

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
						// Server is authoritative for detach. On the client, the sub-level
						// may just not have propagated through Sable's network layer yet
						// (seen right after a block→sub-level migration — Sable itself
						// logs "Received a sub-level movement packet for a non-existent
						// sub-level" at the same moment). Detaching the client hook in
						// that window orphans it while the server keeps following.
						if (!this.level().isClientSide) {
							this.onAttachedEntityPerished();
							return;
						}
						break;
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

	/**
	 * Minimal in-flight projectile step used when the hook is near a Sable sub-level's
	 * apparent AABB — replaces {@code super.tick()} so we don't call
	 * {@link ProjectileUtil#getHitResultOnMoveVector}, which Sable patches to transform
	 * the ray through plot space. That patched path can walk millions of voxels when a
	 * large sublevel is nearby, hanging the server for 10+ seconds on a near-miss shot.
	 *
	 * <p>Block-hit detection is already handled by the sub-level broad-phase scan
	 * above (bounded voxel traversal in plot space) plus, outside sub-levels, the
	 * vanilla static-world blocks won't matter on a near-miss because by definition
	 * the hook is over air. Entity hits still need to be detected — we use
	 * {@link ProjectileUtil#getEntityHitResult}, which Sable does NOT patch.</p>
	 *
	 * <p>This intentionally skips things like fire burning, water bubble particles,
	 * and the despawn timer that {@code Projectile.tick} handles. Those are nice-to-have
	 * cosmetic ticks, not load-bearing for grapple mechanics during the ~20 ticks a
	 * hook typically spends flying past a sub-level AABB.</p>
	 */
	private void manualProjectileStep() {
		Vec3 delta = this.getDeltaMovement();
		Vec3 start = this.position();
		Vec3 end = start.add(delta);

		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
				this.level(), this, start, end,
				this.getBoundingBox().expandTowards(delta).inflate(1.0),
				e -> !e.isSpectator() && e.isAlive() && e.isPickable());

		// World-block collision. Critical when the player is *inside* a sub-level's
		// apparent AABB (e.g., standing on a Sable ship) — every tick the outer
		// tick() routes us here (findSubLevelAlongRay returns non-null because
		// rayStart is inside the AABB), and without this the hook silently phases
		// through world walls/floors while near the ship. We use our DDA
		// (GrappleModUtils.rayTraceBlocks) which bypasses Sable's BlockGetter.clip
		// mixin, so we can safely check world blocks even inside a tracked AABB.
		BlockHitResult blockHit = GrappleModUtils.rayTraceBlocks(this, this.level(), new Vec(start), new Vec(end));

		// Pick the closer of the two (if both present), apply via onHit.
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
			if (this.attachment != null) return; // onHit may have attached — stop here
		}

		this.setPos(end.x, end.y, end.z);

		float drag = this.isInWater() ? 0.8F : 0.99F;
		double gravity = this.getGravity();
		this.setDeltaMovement(delta.x * drag, (delta.y - gravity) * drag, delta.z * drag);
	}

	/** Extracted from tick() — handle the "attached entity is gone" branch. */
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
		return false; // block portal travel else dear god.
	}

	@Override
	protected void onHit(HitResult hit) {
		if (this.level().isClientSide) return;

		GrappleMod.LOGGER.info("[HookDbg] onHit entered hookId={} hitType={} loc={} attachment={}",
				this.getId(),
				hit == null ? "null" : hit.getType(),
				hit == null ? "null" : hit.getLocation(),
				this.attachment == null ? "null" : this.attachment.getClass().getSimpleName());

		if (this.attachment != null) {
			return;
		}

		if (this.shootingEntity == null || this.shootingEntityID == 0) {
			return;
		}

		if(!this.shootingEntity.isAlive()) {
			return;
		}

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

				// Miss inside the AABB — re-trace past the contraption for blocks.
				this.onHit(GrappleModUtils.rayTraceBlocks(this, this.level(), vec3d, vec3d1));
				return;
			}

			// Plain-entity attach path (mobs, etc.) — follow entity center.
			this.serverAttach(new HookAttachment.Entity(entity), true);

			GrappleMod.LOGGER.debug("Attached to a new entity: {}", entity.getId());

		} else if (blockhit != null) {
			BlockPos blockpos = blockhit.getBlockPos();
			Vec3 hitPoint = hit.getLocation();

			// Sable's ProjectileUtilMixin patches the vanilla projectile raycast so it
			// hits blocks in the far-away plot region. When that fires, the BlockHitResult
			// carries plot-coord BlockPos / hitPoint (X or Z > 10M), NOT main-world coords.
			// Detect that here and route it through SubLevelBlock so the rope anchors
			// correctly via the sub-level's pose transform each tick.
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
			GrappleMod.LOGGER.info("[HookDbg] hookPastBend fired hookId={} distToFarthest={} ropeLen={}",
					this.getId(), this.segmentHandler.getDistToFarthest(), this.ropeLength);
			// Rope has exhausted its length past the first bend — collapse the hook
			// onto that bend as a new anchor. The attachment type has to match the
			// bend's host so the new anchor tracks correctly: a WORLD bend becomes
			// a static Block attach (v1 behavior), a CONTRAPTION bend becomes a
			// ContraptionBlock attach that rides the contraption, a SUBLEVEL bend
			// becomes a SubLevelBlock attach that rides the sub-level pose.
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
					// Approximate the hit plot-block by nudging the bend's native
					// position back along its stored face normal (undo the face
					// offset that insertSubLevelBend applied). Good enough for the
					// re-anchor: the rope's hook endpoint is re-offset outward by
					// ropeAnchorFace on the new attachment.
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

		// Hook-side rope endpoint: offset outward from the attach face when the hook
		// is stuck in a block so the first rope raycast doesn't start inside the
		// VoxelShape. See getRopeAnchorHookPos for the rationale.
		Vec hookPos = this.getRopeAnchorHookPos();
		// Use the holder's hand position (not eye) as the rope's player-endpoint so
		// wrap/unwrap math operates on the same line the renderer draws. Keeps
		// rope bends aligned with the visible rope even when the player's eye and
		// hand diverge (e.g. near walls, looking up/down, different body yaw).
		Vec playerPos = this.getRopeOriginAtHolder();

		// Phase 3: rope wrap now goes through MultiSpaceRaycaster, which routes
		// SUBLEVEL spans to the Sable integration's plot-space voxel walker
		// (bypassing the BlockGetter.clip hang entirely). The only remaining
		// reason to skip wrap is the player's explicit BLOCK_PHASE_ROPE toggle.
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

			if (!this.level().isClientSide) {
				GrappleMod.LOGGER.info("[HookDbg] rope-yank hookId={} d={} distToFarthest={} ropeLen={} farthest={} oldPos={} newPos={}",
						this.getId(), d, distToFarthest, this.ropeLength, farthest, this.position(), newpos);
			}
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
				this.serverAttach(
						new HookAttachment.Block(magnetBlock, blockvec.toVec3d(), Direction.UP),
						false);
			}
		}

		prevPos = pos;
	}


	public void removeServer() {
		// Any detach path funnels through here — clearing attachment keeps reads consistent
		// for the brief window between remove() and GC, and mirrors the attach-funnel via
		// setAttachment(...) that every attach path now uses.
		StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
		GrappleMod.LOGGER.info("[HookDbg] removeServer hookId={} pos={} attachment={} caller={}.{}:{}",
				this.getId(), this.position(),
				this.attachment == null ? "null" : this.attachment.getClass().getSimpleName(),
				caller.getClassName().substring(caller.getClassName().lastIndexOf('.') + 1),
				caller.getMethodName(), caller.getLineNumber());
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


	/**
	 * Commit a server-side attachment. Updates the consolidated {@link HookAttachment} state,
	 * snaps the hook entity to the anchor, freezes velocity, and notifies the client.
	 *
	 * @param target the attachment to commit; block variants trigger the canAttach sanity
	 *               check unless {@code force} is true
	 * @param force  bypass the {@link #canAttachToBlock} check for Block targets
	 */
	public void serverAttach(HookAttachment target, boolean force) {
		if (this.level().isClientSide) return;
		GrappleMod.LOGGER.info("[HookDbg] serverAttach request hookId={} target={} force={} currentAttachment={}",
				this.getId(),
				target.getClass().getSimpleName(),
				force,
				this.attachment == null ? "null" : this.attachment.getClass().getSimpleName());
		if (this.attachment != null) {
			GrappleMod.LOGGER.info("[HookDbg]   -> rejected: already attached");
			return;
		}
		if (this.shootingEntity == null || this.shootingEntityID == 0) {
			GrappleMod.LOGGER.info("[HookDbg]   -> rejected: no shooter");
			return;
		}

		// Block-state sanity check only applies when the target is a static block.
		if (target instanceof HookAttachment.Block block && !force) {
			BlockState blockState = this.level().getBlockState(block.pos());
			if (!this.canAttachToBlock(blockState)) {
				GrappleMod.LOGGER.info("[HookDbg]   -> canAttachToBlock REJECTED at {} state={} — removeServer",
						block.pos(), blockState);
				this.playSound(SoundEvents.ANVIL_LAND, 0.7f, 1.8f);
				this.removeServer();
				return;
			}
		}
		GrappleMod.LOGGER.info("[HookDbg]   -> attaching hookId={} target={}", this.getId(), target);

		this.setAttachment(target);

		// Snap hook position to the anchor's current world point.
		Vec3 anchor = target.worldHitPoint(CONTRAPTION_PARTIAL_TICKS);
		this.setPosRaw(anchor.x, anchor.y, anchor.z);

		// Small face-nudge so the hook isn't flush with the block face it attached to.
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
		if (this.attachment == null) return;

		this.setAttachment(new HookAttachment.ContraptionBlock(contraption, localOffset, localBlockPos));

		// Intentionally a lightweight reanchor packet rather than a full GrappleAttachS2CPayload:
		// the full payload rebuilds the client-side physics controller, whose disable() path
		// fires HaltCustomPhysicsC2SPayload back to the server and kills this hook.
		GrappleReanchorToEntityS2CPayload packet = new GrappleReanchorToEntityS2CPayload(
				this.getId(), contraption.getId(), localOffset);

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
			if (!(hook.attachment instanceof HookAttachment.Block block)) continue;

			BlockPos localKey = ci.getCapturedLocalPos(contraptionEntity, block.pos());
			if (localKey == null) continue;

			Vec3 localOffset = ci.worldToLocal(contraptionEntity, block.subHitPoint(), CONTRAPTION_PARTIAL_TICKS);

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
			if (!(hook.attachment instanceof HookAttachment.ContraptionBlock cb)) continue;
			if (cb.entity() != contraptionEntity) continue;

			BlockPos localBlock = cb.localBlockPos();
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

	/**
	 * Called by the Sable compat module when it observes a new sub-level UUID appear
	 * (e.g. a {@code PhysicsAssemblerBlockEntity} just converted a structure). Migrates
	 * any active hook anchored to a block that the sub-level absorbed onto the
	 * sub-level itself, preserving sub-block hit precision.
	 */
	public static void onSubLevelAssembled(UUID subLevelId, Level level) {
		SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();
		if (!sli.isSubLevelLoaded(subLevelId)) return;
		if (level.isClientSide) return;

		for (GrapplinghookEntity hook : ServerHookEntityTracker.getAllTrackedHooks()) {
			if (hook == null || !hook.isAlive()) continue;
			if (hook.level() != level) continue;
			if (!(hook.attachment instanceof HookAttachment.Block block)) continue;

			BlockPos plotBlock = sli.getCapturedPlotPos(subLevelId, block.pos());
			if (plotBlock == null) {
				GrappleMod.LOGGER.info("[Grapple <-> Sable] onSubLevelAssembled uuid={} hookId={} worldBlock={} — getCapturedPlotPos returned null; leaving hook on static block.",
						subLevelId, hook.getId(), block.pos());
				continue;
			}

			Vec3 plotHit = sli.worldToPlot(subLevelId, block.subHitPoint(), CONTRAPTION_PARTIAL_TICKS);
			GrappleMod.LOGGER.info("[Grapple <-> Sable] onSubLevelAssembled uuid={} hookId={} migrating Block→SubLevelBlock: worldBlock={} → plotBlock={} plotHit={}",
					subLevelId, hook.getId(), block.pos(), plotBlock, plotHit);
			hook.reattachToSubLevel(subLevelId, plotBlock, plotHit);
		}
	}

	/**
	 * Called by the Sable compat module when a tracked sub-level UUID disappears
	 * (disassembly, unload, etc.). For each hook anchored to this sub-level, attempts
	 * a precise re-anchor to the world block that the anchored plot block just landed
	 * at; if no suitable block is present, detaches the hook cleanly.
	 */
	public static void onSubLevelDisassembled(UUID subLevelId, Level level) {
		if (level.isClientSide) return;
		SubLevelIntegration sli = GrappleModIntegrations.getSubLevelIntegration();

		for (GrapplinghookEntity hook : ServerHookEntityTracker.getAllTrackedHooks()) {
			try {
				if (hook == null || !hook.isAlive()) continue;
				if (hook.level() != level) continue;
				if (!(hook.attachment instanceof HookAttachment.SubLevelBlock slb)) continue;
				if (!slb.subLevelId().equals(subLevelId)) continue;

				BlockPos plotBlock = slb.plotBlock();
				Vec3 plotCenter = new Vec3(plotBlock.getX() + 0.5, plotBlock.getY() + 0.5, plotBlock.getZ() + 0.5);
				Vec3 worldCenter = sli.plotToWorld(subLevelId, plotCenter, CONTRAPTION_PARTIAL_TICKS);
				BlockPos candidate = BlockPos.containing(worldCenter);

				BlockState state = level.getBlockState(candidate);
				Vec3 hookPos = hook.position();
				double dist = distancePointToAabb(hookPos, new AABB(candidate));

				if (state.isAir() || dist > DISASSEMBLY_REANCHOR_MAX_DIST) {
					hook.detachFromContraption();
					continue;
				}

				hook.reattachToBlock(candidate, hookPos);
			} catch (Throwable err) {
				// Don't let one broken reattach take down the rest of the loop or the server.
				GrappleMod.LOGGER.error("[Grapple <-> Sable] onSubLevelDisassembled: reattach for hook {} failed; detaching as fallback",
						hook != null ? hook.getId() : "null", err);
				if (hook != null && hook.isAlive()) {
					try { hook.detachFromContraption(); } catch (Throwable ignored) {}
				}
			}
		}
	}

	/**
	 * Server-side: switch this hook's anchor from a static block onto a sub-level that
	 * just absorbed it. Mirror of {@link #reattachToContraption} but keyed by UUID.
	 */
	public void reattachToSubLevel(UUID subLevelId, BlockPos plotBlock, Vec3 plotHitPoint) {
		if (this.level().isClientSide) return;
		if (this.attachment == null) return;

		this.setAttachment(new HookAttachment.SubLevelBlock(subLevelId, plotBlock, plotHitPoint));

		// Full re-attach packet: no lightweight reanchor payload exists for sub-levels
		// yet, and the client needs the UUID/plotBlock/plotHitPoint to rebuild the
		// attachment. Safe here because this path runs off an assembly event, not from
		// the client-side physics controller's shutdown sequence.
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

		this.setAttachment(new HookAttachment.Block(blockPos, hookWorldPos, null));

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

	// this mostly comes from packets
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

	/**
	 * World-space point where the rope meets the holder — the holder's eye
	 * position. Used as the player-endpoint for rope wrap/unwrap physics.
	 *
	 * <p>This was briefly computed from the holder's hand position to match the
	 * renderer's visible rope line. Hand-based placement aligns bend positions
	 * with rendered geometry more precisely (~fraction of a block), but the
	 * hand sits at roughly torso height — often inside the apparent AABB of a
	 * sub-level the player is standing on. Rope segments adjacent to a hand
	 * endpoint inside a ship's bbox unavoidably cross ship blocks, producing
	 * cascading bend insertions that were hard to prevent without breaking
	 * legitimate wraps. Eye sits above the block the player is standing on,
	 * which sidesteps the whole class of issues. v1 used eye for years with
	 * no reports of bend-alignment problems; the tiny visual discrepancy
	 * against the rendered hand-based rope is negligible in practice.</p>
	 *
	 * <p>Method name kept for continuity with call sites. Falls back to the
	 * hook's own position if the holder has been cleared (e.g. shooter
	 * disconnected) — callers of rope updates shouldn't be running in that
	 * state, but the guard avoids a NullPointer.</p>
	 */
	public Vec getRopeOriginAtHolder() {
		Entity shooter = this.shootingEntity;
		if (shooter == null) return Vec.positionVec(this);
		return Vec.positionVec(shooter).add(new Vec(0, shooter.getEyeHeight(), 0));
	}

	/**
	 * The hook's world position as the rope sees it — same as {@link Vec#positionVec}
	 * for an in-flight or entity-attached hook, but nudged outward from the attach
	 * face by {@link #ROPE_ANCHOR_FACE_OFFSET} when the hook is stuck on a block
	 * (world block, Create contraption block, or Sable sub-level block). The
	 * hit-point world coord lives exactly on the block face, so any pose drift or
	 * numerical slop can land the rope's starting raycast a hair inside the solid
	 * VoxelShape — which {@link com.yyon.grapplinghook.physics.raycast.MultiSpaceRaycaster}
	 * then reports as an immediate hit against the same block the hook is in, and
	 * the rope visually clips through. Pushing the rope's starting point outward
	 * along the attach face avoids that. The hook entity itself still sits at the
	 * exact hit point, so visually it remains lodged in the block.
	 */
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

	/** Same outward offset used for non-endpoint rope bends (see {@code CONTRAPTION_BEND_OFFSET}). */
	private static final double ROPE_ANCHOR_FACE_OFFSET = 0.08;

	public Vec getSurfaceAttachmentDirection() {
		return this.attachDirection;
	}

	public double getCurrentRopeLength() {
		return this.ropeLength;
	}

	/**
	 * The world entity this hook is anchored to, if any. Covers both plain-entity attaches
	 * (mobs, boats) and contraption attaches — anything where the rope follows a moving
	 * world object. Returns {@code null} for block attaches and unattached hooks.
	 */
	public @Nullable Entity attachedWorldEntity() {
		return switch (this.attachment) {
			case HookAttachment.Entity e -> e.entity();
			case HookAttachment.ContraptionBlock cb -> cb.entity();
			case null, default -> null;
		};
	}

	/**
	 * True iff the hook's current anchor is a moving body (plain entity, Create
	 * contraption, or Sable sub-level) — anything whose world-space position
	 * changes between ticks. Callers that previously special-cased the two
	 * entity-backed variants should use this so {@link HookAttachment.SubLevelBlock}
	 * also suppresses server position-sync lerp and similar static-target
	 * fast-paths.
	 */
	public boolean isAttachedToMovingBody() {
		return this.attachedWorldEntity() != null
				|| this.attachment instanceof HookAttachment.SubLevelBlock;
	}

	/** The authoritative attachment state. {@code null} if the hook is in flight or detached. */
	public @Nullable HookAttachment attachment() { return this.attachment; }

	/** Client-side mutation point for attachment state. Wraps the private {@link #setAttachment}. */
	public void setAttachmentClient(@Nullable HookAttachment next) {
		this.setAttachment(next);
	}

	/**
	 * Sole write path for {@link #attachment}. Also updates the {@link #isAttachedToSurface}
	 * spawn-packet replication flag so spectator clients continue to see the hook as frozen
	 * or in-flight without receiving the full variant.
	 */
	private void setAttachment(@Nullable HookAttachment next) {
		this.attachment = next;
		this.isAttachedToSurface = (next != null);
	}
}
