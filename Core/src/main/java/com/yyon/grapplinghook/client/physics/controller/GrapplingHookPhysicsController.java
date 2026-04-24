package com.yyon.grapplinghook.client.physics.controller;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.client.GrappleModClient;
import com.yyon.grapplinghook.client.ModKeys;
import com.yyon.grapplinghook.config.GrappleModClientConfig;
import com.yyon.grapplinghook.config.GrappleModCommonConfig;
import com.yyon.grapplinghook.config.GrapplePropertyConfigLoader;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.physics.attach.HookAttachment;
import com.yyon.grapplinghook.physics.rope.RopeSegmentHandler;
import com.yyon.grapplinghook.content.physics.PhysicsControllers;
import com.yyon.grapplinghook.content.customization.data.HookCustomization;
import com.yyon.grapplinghook.network.NetworkManager;
import com.yyon.grapplinghook.network.serverbound.HaltCustomPhysicsC2SPayload;
import com.yyon.grapplinghook.network.serverbound.PhysicsUpdateC2SPayload;
import com.yyon.grapplinghook.network.serverbound.PlayerMovementC2SPayload;
import com.yyon.grapplinghook.physics.PlayerPhysicsFrame;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashSet;

import static com.yyon.grapplinghook.content.registry.CustomizationProperties.*;


public class GrapplingHookPhysicsController {

	public int holderId;
	public Level world;
	public LivingEntity holder;

	private int lastTickRan = -1;
	private int duplicates = 0;
	
	final HashSet<GrapplinghookEntity> grapplehookEntities = new HashSet<>();
	private final HashSet<Integer> grapplehookEntityIds = new HashSet<>();

	public boolean ownsHook(int hookId) { return this.grapplehookEntityIds.contains(hookId); }

	private boolean isControllerActive = true;
	
	protected Vec motion;
	
	protected double playerForward = 0;
	protected double playerStrafe = 0;
	protected boolean playerJump = false;
	protected boolean playerSneak = false;
	protected Vec playerMovementUnrotated = new Vec(0,0,0);
	protected Vec playerMovement = new Vec(0,0,0);

	protected int onGroundTimer;
	protected int maxOnGroundTimer = 3;

	protected double maxLen;

	protected double playerMovementMult = 0;

private boolean rocketKeyDown = false;
	private double rocketProgression;

	HookCustomization custom;
	
	public GrapplingHookPhysicsController(int grapplehookEntityId, int holderId, Level world, HookCustomization custom) {
		this.holderId = holderId;
		this.world = world;
		this.custom = custom;
		
		if (this.custom != null) {
			this.playerMovementMult = this.custom.get(MOVE_SPEED_MULTIPLIER.get());
			this.maxLen = custom.get(MAX_ROPE_LENGTH.get());
		}

		Entity holderFromWorld = world.getEntity(holderId);

		if(holderFromWorld == null) {
			GrappleMod.LOGGER.warn("GrapplingHookPhysicsController is missing an expected holder entity! Report this to 'GrappleMod: Restitched'");
			this.disable(true);
			return;
		}

		if(!(holderFromWorld instanceof LivingEntity holderAsLiving)) {
			GrappleMod.LOGGER.warn("GrapplingHookPhysicsController is tied to a holder entity hookId that is not a Living Entity! Holders are meant to be players!");
			this.disable(true);
			return;
		}

		this.holder = holderAsLiving;

		// This could happen if a player is killed before their hook actually lands.
		if(!this.holder.isAlive()) {
			this.disable(true);
			return;
		}

		this.motion = Vec.motionVec(this.holder);
		
		// undo friction
		Vec newmotion = new Vec(holder.position().x - holder.xOld, holder.position().y - holder.yOld, holder.position().z - holder.zOld);
		if (newmotion.x/motion.x < 2 && motion.x/newmotion.x < 2 && newmotion.y/motion.y < 2 && motion.y/newmotion.y < 2 && newmotion.z/motion.z < 2 && motion.z/newmotion.z < 2) {
			this.motion = newmotion;
		}

		this.onGroundTimer = 0;

		if (grapplehookEntityId != -1) {
			Entity grapplehookEntity = world.getEntity(grapplehookEntityId);
			if (grapplehookEntity != null && grapplehookEntity.isAlive() && grapplehookEntity instanceof GrapplinghookEntity grapple) {
				this.addHookEntity(grapple);

			} else {
				GrappleMod.LOGGER.warn("GrapplingHookPhysicsController is missing an expected hook entity!");
				this.disable();
			}
		}
		
		if (custom != null && custom.get(ROCKET_ATTACHED.get())) {
			GrappleModClient.get().updateRocketRegen(custom.get(ROCKET_FUEL_DEPLETION_RATIO.get()), custom.get(ROCKET_REFUEL_RATIO.get()));
		}
	}

	public ResourceLocation getType() {
		return PhysicsControllers.GRAPPLING_HOOK;
	}

	public void disable() {
		this.disable(false);
	}

	public void disable(boolean stopPropagation) {
		// Error'ed controllers should just be removed with no extra
		// conntrollers applied - they should be 'disabled' already.

		boolean wasAlreadyDisabled = !this.isControllerActive;
		this.isControllerActive = false;

		Player clientPlayer = Minecraft.getInstance().player;
		boolean isEntityClientPlayer = this.holder == clientPlayer;

		// Reset local copy of "Server Physics"
		if(this.holder instanceof Player player) {
			GrappleMod.get().getServerPhysicsObserver().receiveNewFrame(player, new PlayerPhysicsFrame());
		}

		// Not null & player
		// Reset server-side physics tracking.
		if(isEntityClientPlayer && !wasAlreadyDisabled) {
			NetworkManager.packetToServer(new PhysicsUpdateC2SPayload());
		}


		if (GrappleModClient.get().getClientControllerManager().unregisterController(this.holderId) == null)
			return;

		if (this.getType() == PhysicsControllers.AIR_FRICTION)
			return;

		NetworkManager.packetToServer(new HaltCustomPhysicsC2SPayload(this.holderId, this.grapplehookEntityIds));

		if(this.holder instanceof LocalPlayer p) {
			PlayerInfo playerInfo = p.connection.getPlayerInfo(p.getUUID());

			if(playerInfo != null && playerInfo.getGameMode() == GameType.SPECTATOR) return;
		}

		if(!stopPropagation && !wasAlreadyDisabled) {
			GrappleModClient.get()
					.getClientControllerManager()
					.createControl(PhysicsControllers.AIR_FRICTION, -1, this.holderId, this.holder.level(), null, this.custom);
		}
	}
	
	
	public void doClientTick() {
		if (!this.isControllerActive) {
			this.disable();
			return;
		}

		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();

		if(server != null) {
			int serverTick = server.getTickCount();

			if(serverTick == lastTickRan) this.duplicates++;
			else this.duplicates = 0;

			this.lastTickRan = serverTick;
		}

		if (this.holder == null || !this.holder.isAlive()) {
			this.disable();
		} else {
			this.updatePlayerPos();
			this.transmitServerPhysicsUpdate();
		}
	}

	public void transmitServerPhysicsUpdate() {
		if(!this.isControllerActive)
			return;

		Player clientPlayer = Minecraft.getInstance().player;

		if(this.holder == null)
			return;

		if(this.holder != clientPlayer)
			return;

		PlayerPhysicsFrame frame = new PlayerPhysicsFrame()
				.setPhysicsControllerType(this.getType())
				.setSpeed(this.motion.length())
				.setUsingRocket(this.rocketKeyDown);

		GrappleMod.get().getServerPhysicsObserver().receiveNewFrame(clientPlayer, frame);
		NetworkManager.packetToServer(new PhysicsUpdateC2SPayload(frame));
	}
		
	public void receivePlayerMovementMessage(float strafe, float forward, boolean sneak) {
		this.playerForward = forward;
		this.playerStrafe = strafe;
		this.playerSneak = sneak;
		this.playerMovementUnrotated = new Vec(strafe, 0, forward);
		this.playerMovement = playerMovementUnrotated.rotateYaw((float) (this.holder.getYRot() * (Math.PI / 180.0)));
	}
	
	public void updatePlayerPos() {
		Entity entity = this.holder;
		
		if (!this.isControllerActive) return;
		if(entity == null) return;

		entity.resetFallDistance();

		if (entity.getVehicle() != null) {
			this.disable();
			this.updateServerPos();
			return;
		}

		this.normalGround(false);
		this.normalCollisions(false);
		this.applyAirFriction();

		Vec playerPos = Vec.positionVec(entity).add(new Vec(0, entity.getEyeHeight(), 0));
		Vec additionalMotion = new Vec(0,0,0);

		double g = GrapplePropertyConfigLoader.CONFIG.grappleGravity;
		Vec gravity = new Vec(0, -g, 0);

		this.motion.mutableAdd(gravity);

		// is motor active?
		boolean motor = false;
		if (this.custom.get(MOTOR_ATTACHED.get())) {
			boolean isActive = this.custom.get(MOTOR_ACTIVATION.get())
					                      .meetsActivationCondition(ModKeys.TOGGLE_MOTOR.get());
			if(isActive) motor = true;
		}


		Vec averagemotiontowards = new Vec(0, 0, 0);
		double minSphereVecDist = 99999;
		double jumpSpeed = 0;
		boolean close = false;
		boolean doJump = false;
		boolean isClimbing = false;

		for (GrapplinghookEntity hookEntity : this.grapplehookEntities) {
			Vec hookPos = Vec.positionVec(hookEntity);
			Vec ropeHookPos = hookEntity.getRopeAnchorHookPos();
			Vec ropeEndpoint = hookEntity.getRopeOriginAtHolder();
			RopeSegmentHandler segmentHandler = hookEntity.getSegmentHandler();

			boolean skipRopeWrap = this.custom.get(BLOCK_PHASE_ROPE.get());
			if (skipRopeWrap) {
				segmentHandler.updatePos(ropeHookPos, ropeEndpoint, hookEntity.ropeLength);
			} else {
				segmentHandler.update(ropeHookPos, ropeEndpoint, hookEntity.ropeLength, false);
			}

			// vectors along rope
			Vec anchor = segmentHandler.getClosest(hookPos);
			double distToAnchor = segmentHandler.getDistToAnchor();
			double remainingLength = motor
					? Math.max(this.custom.get(MAX_ROPE_LENGTH.get()), hookEntity.ropeLength) - distToAnchor
					: hookEntity.ropeLength - distToAnchor;

			Vec oldspherevec = playerPos.sub(anchor);
			Vec spherevec = oldspherevec.withMagnitude(remainingLength);
			Vec spherechange = spherevec.sub(oldspherevec);

			if (spherevec.length() < minSphereVecDist) {minSphereVecDist = spherevec.length();}

			averagemotiontowards.mutableAdd(spherevec.withMagnitude(-1));

			if (motor) {
				hookEntity.ropeLength = distToAnchor + oldspherevec.length();
			}

			// snap to rope length
			if (oldspherevec.length() >= remainingLength) {
				if (oldspherevec.length() - remainingLength > GrappleModCommonConfig.get().getRopeSnapBuffer()) {
					// if rope is too long, the rope snaps
					this.disable();
					this.updateServerPos();
					return;
				} else {
					additionalMotion = spherechange.scale(0.8f);
				}
			}

			double playerToAnchorDist = oldspherevec.length();

			this.applyCalculatedTaut(playerToAnchorDist, hookEntity);

			// handle keyboard input (jumping and climbing)
			if (entity instanceof Player player) {
				boolean detachKeyDown = ModKeys.DETACH.get().isDown();
				boolean isJumping = detachKeyDown && !this.playerJump;
				this.playerJump = detachKeyDown;

				if (isJumping && this.onGroundTimer >= 0) {
					// jumping
					long timer = GrappleModClient.get().getTimeSinceLastRopeJump(this.holder.level());
					if (timer > GrappleModCommonConfig.get().getRopeJumpCooldown()) {
						doJump = true;
						jumpSpeed = this.getJumpPower(player, spherevec, hookEntity);
					}
				}

				if (ModKeys.DAMPEN_SWING.get().isDown()) {
					// slow down
					Vec motiontorwards = spherevec.withMagnitude(-0.1);
					motiontorwards = new Vec(motiontorwards.x, 0, motiontorwards.z);

					if (this.motion.dot(motiontorwards) < 0)
						this.motion.mutableAdd(motiontorwards);

					Vec newmotion = this.dampenMotion(this.motion, motiontorwards);
					this.motion = new Vec(newmotion.x, this.motion.y, newmotion.z);

				}

				if ((ModKeys.CLIMB.get().isDown() || ModKeys.CLIMB_UP.isDown() || ModKeys.CLIMB_DOWN.isDown()) && !motor) {
					Vec climbMotion = anchor.y != playerPos.y
							? this.calculateClimbingMotion(hookEntity, playerToAnchorDist, distToAnchor, spherevec)
							: new Vec(0, 0, 0);

					isClimbing = true;
					additionalMotion.mutableAdd(climbMotion);
				}
			}

			if (playerToAnchorDist + distToAnchor < 2) {
				close = true;
			}

			// swing along max rope length
			if (anchor.sub(playerPos.add(motion)).length() > remainingLength) { // moving away
				this.motion = this.motion.removeAlong(spherevec);
			}
		}

		averagemotiontowards.mutableSetMagnitude(1);

		Vec facing = new Vec(entity.getLookAngle()).normalize();

		// Motor
		if (motor)
			MotorBehavior.apply(this, playerPos, facing, entity, gravity, close);

		// forcefield - does not go through this path if via ForcefieldPhysicsController
		if (this.custom.get(FORCEFIELD_ATTACHED.get())) {
			Vec blockPush = RepelField.checkRepel(playerPos, entity.level());
			blockPush.mutableScale(this.custom.get(FORCEFIELD_FORCE.get()))
					 .mutableScale(0.5D)
					 .mutableMultiply(0.5D, 2.0D, 0.5D);

			this.motion.mutableAdd(blockPush);
		}

		// rocket
		if (this.custom.get(ROCKET_ATTACHED.get())) {
			this.motion.mutableAdd(this.rocket(entity));
		}

		// WASD movement
		if (!doJump && !isClimbing) {
			this.applyPlayerMovement();
		}

		// jump
		if (doJump) {
			double maxJumpPower = GrappleModCommonConfig.get().getRopeJumpPower();
			jumpSpeed = Mth.clamp(jumpSpeed, 0.0D, maxJumpPower);

			this.doJump(entity, jumpSpeed, averagemotiontowards, minSphereVecDist);
			GrappleModClient.get().resetRopeJumpTime(this.holder.level());
			return;
		}

		// now to actually apply everything to the player
		Vec newmotion = motion.add(additionalMotion);

		if (Double.isNaN(newmotion.x) || Double.isNaN(newmotion.y) || Double.isNaN(newmotion.z)) {
			newmotion = new Vec(0, 0, 0);
			this.motion = new Vec(0, 0, 0);
			GrappleMod.LOGGER.warn("error: motion is NaN");
		}

		entity.setDeltaMovement(newmotion.x, newmotion.y, newmotion.z);

		this.updateServerPos();
	}

	private Vec calculateClimbingMotion(GrapplinghookEntity hook, double dist, double distToAnchor, Vec spherevec) {
		// climb up/down rope
		double climbDelta = 0;

		if (ModKeys.CLIMB.get().isDown()) {
			climbDelta = this.playerForward;

			if (GrappleModClient.get().isMovingSlowly(this.holder))
				climbDelta /= 0.3D;

			climbDelta = Mth.clamp(climbDelta, -1.0D, 1.0D);

		}
		else if (ModKeys.CLIMB_UP.isDown()) { climbDelta = 1.0D; }
		else if (ModKeys.CLIMB_DOWN.isDown()) { climbDelta = -1.0D; }


		if (climbDelta == 0) return new Vec(0, 0, 0);

		double climbSpeed = GrappleModCommonConfig.get().getClimbSpeed();

		if (dist + distToAnchor >= this.maxLen && climbDelta <= 0 && this.maxLen != 0)
			return new Vec(0, 0, 0);

		hook.ropeLength = dist + distToAnchor;
		hook.ropeLength -= climbDelta * climbSpeed;

		if (hook.ropeLength < distToAnchor) {
			hook.ropeLength = dist + distToAnchor;
		}

		Vec up = new Vec(0,1,0);
		Vec additionalVerticalMovement = spherevec.withMagnitude(-climbDelta * climbSpeed).project(up);

		if(additionalVerticalMovement.y > 0)
			additionalVerticalMovement.mutableScale(0.66f);

		return additionalVerticalMovement;
	}

	public void applyCalculatedTaut(double dist, GrapplinghookEntity hookEntity) {
		if (hookEntity == null) return;

		hookEntity.taut = dist < hookEntity.ropeLength
				? Math.max(0, 1 - ((hookEntity.ropeLength - dist) / 5))
				: 1.0d;
	}

	public void normalCollisions(boolean sliding) {

		// stop if collided with object
		if (this.holder.horizontalCollision) {
			if (this.holder.getDeltaMovement().x == 0) {
				if (!sliding || this.tryStepUp(new Vec(this.motion.x, 0, 0))) {
					this.motion.x = 0;
				}
			}

			if (this.holder.getDeltaMovement().z == 0) {
				if (!sliding || this.tryStepUp(new Vec(0, 0, this.motion.z))) {
					this.motion.z = 0;
				}
			}
		}
		
		if (sliding && !this.holder.horizontalCollision) {
			if (holder.position().x - holder.xOld == 0) {
				this.motion.x = 0;
			}
			if (holder.position().z - holder.zOld == 0) {
				this.motion.z = 0;
			}
		}
		
		if (this.holder.verticalCollision) {
			if (this.holder.onGround()) {
				if (!sliding && Minecraft.getInstance().options.keyJump.isDown()) {
					this.motion.y = holder.getDeltaMovement().y;
				} else {
					if (this.motion.y < 0) {
						this.motion.y = 0;
					}
				}

			} else {
				if (this.motion.y > 0 && holder.yOld == holder.position().y) {
					this.motion.y = 0;
				}
			}
		}
	}

	public boolean tryStepUp(Vec collisionMotion) {
		if (collisionMotion.length() == 0)
			return false;

		Vec moveOffset = collisionMotion.withMagnitude(0.05).add(0, holder.maxUpStep() + 0.01, 0);
		Iterable<VoxelShape> collisions = this.holder.level().getCollisions(this.holder, this.holder.getBoundingBox().move(moveOffset.x, moveOffset.y, moveOffset.z));

		if (collisions.iterator().hasNext()) return true;

		if (this.holder.onGround()) {
			this.holder.horizontalCollision = false;
			return false;
		}

		Vec pos = Vec.positionVec(holder);
		pos.mutableAdd(moveOffset);
		pos.applyAsPositionTo(holder);
		this.holder.xOld = pos.x;
		this.holder.yOld = pos.y;
		this.holder.zOld = pos.z;

		return false;
	}

	public void normalGround(boolean sliding) {
		if (this.holder.onGround()) {
			this.onGroundTimer = this.maxOnGroundTimer;

		} else if (this.onGroundTimer > 0) {
			this.onGroundTimer--;
		}

		boolean touchingGround = this.holder.onGround() || this.onGroundTimer > 0;

		if (touchingGround && !sliding) {
			this.motion = Vec.motionVec(this.holder);
			Options options = Minecraft.getInstance().options;

			if (options.keyJump.isDown())
				this.motion.y += 0.05;
		}
	}

	private double getJumpPower(Entity player, double jumppower) {
		double maxjump = GrappleModCommonConfig.get().getRopeJumpPower();
		if (this.onGroundTimer > 0) { // on ground: jump normally
			this.onGroundTimer = 20;
			return 0;
		}
		if (player.onGround()) {
			jumppower = 0;
		}
		if (player.horizontalCollision || player.verticalCollision) {
			jumppower = maxjump;
		}
		if (jumppower < 0) {
			jumppower = 0;
		}
		
		return jumppower;
	}
	
	public void doJump(Entity player, double jumppower, Vec averagemotiontowards, double min_spherevec_dist) {
		if (jumppower > 0) {
			if (GrappleModCommonConfig.get().shouldJumpAtAngleFromRope() && min_spherevec_dist > 1) {
				motion.mutableAdd(averagemotiontowards.withMagnitude(jumppower));
			} else {
				if (jumppower > player.getDeltaMovement().y + jumppower) {
					motion.y = jumppower;
				} else {
					motion.y += jumppower;
				}
			}
			this.motion.applyAsMotionTo(player);
		}
		
		this.disable();
		this.updateServerPos();
	}
	
	public double getJumpPower(Entity player, Vec spherevec, GrapplinghookEntity hookEntity) {
		double maxjump = GrappleModCommonConfig.get().getRopeJumpPower();
		Vec jump = new Vec(0, maxjump, 0);

		boolean useRopeAngleAsJump = GrappleModCommonConfig.get().shouldJumpAtAngleFromRope() && spherevec != null;

		if (useRopeAngleAsJump) {
			jump = jump.project(spherevec);
		}

		double jumppower = jump.y;
		
		if (spherevec != null && spherevec.y > 0) {
			jumppower = 0;
		}

		if ((hookEntity != null) && hookEntity.ropeLength < 1 && (player.position().y < hookEntity.position().y)) {
			jumppower = maxjump;
		}

		jumppower = this.getJumpPower(player, jumppower);


		double current_speed = useRopeAngleAsJump
				? -this.motion.distanceAlong(spherevec)
				: this.motion.y;

		if (current_speed > 0)
			jumppower = jumppower - current_speed;

		if (jumppower < 0)
			jumppower = 0;

		return jumppower;
	}

	public Vec dampenMotion(Vec motion, Vec forward) {
		Vec newmotion = motion.project(forward);
		double dampening = 0.05;
		return newmotion.scale(dampening).add(motion.scale(1-dampening));
	}
	
	public void updateServerPos() {
		this.limitVelocity();
		NetworkManager.packetToServer(new PlayerMovementC2SPayload(this.holderId, this.holder.position().toVector3f(), this.holder.getDeltaMovement().toVector3f()));
	}

	public void limitVelocity() {
//		final double MAX_MOTION = GrapplePropertyConfigLoader.CONFIG.maxAirspeed;
//		if (MAX_MOTION > 0 && this.motion.length() > MAX_MOTION) {
//			GrappleMod.LOGGER.warn(String.format("Speed limited to %f from %f", MAX_MOTION, this.motion.length()));
//			this.motion.mutableSetMagnitude(MAX_MOTION);
//		} else {
//			GrappleMod.LOGGER.warn(String.format("Speed unlimited as %f", this.motion.length()));
//		}

		final double MAX_VERTICAL = GrapplePropertyConfigLoader.CONFIG.maxVerticalAirspeed;
		final double MAX_HORIZONTAL = GrapplePropertyConfigLoader.CONFIG.maxHorizontalAirspeed;

		Vec horizontal = motion.removeAlong(new Vec(0, 1, 0));
		double vertical = motion.y;

		if (MAX_HORIZONTAL > 0 && horizontal.length() > MAX_HORIZONTAL) {
			horizontal.mutableSetMagnitude(MAX_HORIZONTAL);
		}

		if (MAX_VERTICAL > 0 && vertical > MAX_VERTICAL) {
			vertical = MAX_VERTICAL;
		}

		motion.x = horizontal.x;
		motion.y = vertical;
		motion.z = horizontal.z;
	}
	
	// Vector stuff:
	
	public void receiveGrappleDetach() {
		this.disable();
	}

	public void receiveEnderLaunch(double x, double y, double z) {
		this.motion.mutableAdd(x, y, z);
		this.motion.applyAsMotionTo(this.holder);
	}
	
	public void applyAirFriction() {
		double dragforce = 1 / 200F;
		if (this.holder.isInWater() || this.holder.isInLava()) {
			dragforce = 1 / 4F;
		}
		
		double vel = this.motion.length();
		dragforce = vel * dragforce;
		
		Vec airfric = new Vec(this.motion.x, this.motion.y, this.motion.z);
		airfric.mutableSetMagnitude(-dragforce);
		this.motion.mutableAdd(airfric);
	}
	
	public void applyPlayerMovement() {
		Vec additionalMotion = this.playerMovement.withMagnitude(0.015 + this.motion.length() * 0.01)
											      .scale(this.playerMovementMult);
		this.motion.mutableAdd(additionalMotion);
	}

	public void addHookEntity(GrapplinghookEntity hookEntity) {
		this.grapplehookEntities.add(hookEntity);
		hookEntity.ropeLength = hookEntity.getSegmentHandler().getDist(Vec.positionVec(hookEntity), Vec.positionVec(holder).add(new Vec(0, holder.getEyeHeight(), 0)));
		this.grapplehookEntityIds.add(hookEntity.getId());
	}

	
public void receiveGrappleDetachHook(int hookid) {
		if (this.grapplehookEntityIds.contains(hookid)) {
			this.grapplehookEntityIds.remove(hookid);

		} else {
			GrappleMod.LOGGER.warn("Error: controller received hook detach, but hook hookId not in grapplehookEntityIds");
		}
		
		GrapplinghookEntity hookToRemove = null;
		for (GrapplinghookEntity hookEntity : this.grapplehookEntities) {
			if (hookEntity.getId() == hookid) {
				hookToRemove = hookEntity;
				break;
			}
		}
		
		if (hookToRemove != null) {
			this.grapplehookEntities.remove(hookToRemove);
		} else {
			GrappleMod.LOGGER.warn("Error: controller received hook detach, but hook entity not in grapplehookEntities");
		}
	}

	public Vec rocket(Entity entity) {
		Options options = Minecraft.getInstance().options;

		boolean overrideKey = this.areControlsOverridenByEquipment();
		boolean isRocketActivated = ModKeys.ROCKET.get().isDown() || (overrideKey && options.keyUse.isDown());

		if (!isRocketActivated) {
			this.rocketKeyDown = false;
			this.rocketProgression = 0F;
			return new Vec(0,0,0);
		}

		this.rocketProgression = GrappleModClient.get().getRocketFunctioning();
		double rocket_force = this.custom.get(ROCKET_FORCE.get()) * 0.225 * this.rocketProgression;
		double yaw = entity.getYRot();
		double pitch = this.custom.get(ROCKET_ANGLE.get()) - entity.getXRot();

		Vec force = new Vec(0, 0, rocket_force);
		force = force.rotatePitch(Math.toRadians(pitch));
		force = force.rotateYaw(Math.toRadians(yaw));

		this.rocketKeyDown = true;
		return force;
	}

	public void resetRocketProgression() {
		this.rocketKeyDown = true;
		this.rocketProgression = 1.0F;
	}

	public double getRocketProgression() {
		return this.rocketProgression;
	}

	public HookCustomization getCurrentCustomizations() {
		return this.custom;
	}

	public void overrideCustomizations(HookCustomization volume) {
		this.custom = volume;
	}

	public boolean isRocketKeyDown() {
		return this.rocketKeyDown;
	}

	public boolean isControllerActive() {
		return this.isControllerActive;
	}

	public Vec getCopyOfMotion() {
		return new Vec(this.motion);
	}

	public int getDuplicates() {
		return this.duplicates;
	}

	public boolean areControlsOverridenByEquipment() {
		if(this.custom == null) return false;
		return this.custom.get(IS_EQUIPMENT_OVERRIDE.get());
	}
}
