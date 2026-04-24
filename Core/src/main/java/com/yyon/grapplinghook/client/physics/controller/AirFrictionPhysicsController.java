package com.yyon.grapplinghook.client.physics.controller;

import com.yyon.grapplinghook.config.GrappleModCommonConfig;
import com.yyon.grapplinghook.config.GrapplePropertyConfigLoader;
import com.yyon.grapplinghook.content.physics.PhysicsControllers;
import com.yyon.grapplinghook.content.customization.data.HookCustomization;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import static com.yyon.grapplinghook.content.registry.CustomizationProperties.ROCKET_ATTACHED;

public class AirFrictionPhysicsController extends GrapplingHookPhysicsController {

	public static final double DEG_90 = Math.PI / 2;

	private int ignoreGroundCounter = 0;
	private boolean wasRocket = false;
	private boolean firstTickSinceCreated = true;


	public AirFrictionPhysicsController(int grapplehookEntityId, int entityId, Level world, HookCustomization custom) {
		super(grapplehookEntityId, entityId, world, custom);
	}

	@Override
	public ResourceLocation getType() {
		return PhysicsControllers.AIR_FRICTION;
	}

	@Override
	public void updatePlayerPos() {
		LivingEntity entity = this.holder;

		if (entity == null) return;

		if (entity.getVehicle() != null) {
			this.disable();
			this.updateServerPos();
			return;
		}

		if(this.motion.y > 0)
			entity.resetFallDistance();

		if (entity instanceof LivingEntity e && e.onClimbable()) {
			this.disable();
		}

		boolean shouldCancel = GrappleModUtils.and(
				() -> !GrappleModCommonConfig.get().shouldOverrideMovementInAir(),
				() -> !entity.onGround(),
				() -> !this.wasRocket,
				() -> !this.firstTickSinceCreated
		);

		if (shouldCancel) {
			this.motion = Vec.motionVec(entity);
			this.disable();
			return;
		}

		if (!this.isControllerActive())
			return;

		if (this.ignoreGroundCounter <= 0) {
			this.normalGround(false);
			this.normalCollisions(false);
		}

		this.applyAirFriction();

		if (this.holder.isInWater() || this.holder.isInLava()) {
			this.disable();
			return;
		}

		boolean doesrocket = false;
		if (this.getCurrentCustomizations() != null) {
			if (this.getCurrentCustomizations().get(ROCKET_ATTACHED.get())) {
				Vec rocket = this.rocket(entity);
				this.motion.mutableAdd(rocket);
				if (rocket.length() > 0) {
					doesrocket = true;
				}
			}
		}

		double slowness = this.getSlownessFactor();
		double max_motion = GrappleModCommonConfig.get().getMaxStrafeSpeedInAir() * slowness;
		double accel = GrappleModCommonConfig.get().getStrafeAcceleration() * slowness;
		Vec motion_horizontal = motion.removeAlong(new Vec(0,1,0));
		double prev_motion = motion_horizontal.length();
		Vec new_motion_horizontal = motion_horizontal.add(this.playerMovement.withMagnitude(accel));
		double angle = motion_horizontal.angle(new_motion_horizontal);

		if (new_motion_horizontal.length() > max_motion && new_motion_horizontal.length() > prev_motion) {
			double newMaxMotion = max_motion;

			if (angle < DEG_90 && prev_motion > max_motion)
				newMaxMotion = prev_motion + ((max_motion - prev_motion) * (angle / (DEG_90)));

			new_motion_horizontal.mutableSetMagnitude(newMaxMotion);
		}

		motion.x = new_motion_horizontal.x;
		motion.z = new_motion_horizontal.z;

		if (entity instanceof LivingEntity entityLiving && entityLiving.isFallFlying()) {
			this.disable();
		}

		double g = GrapplePropertyConfigLoader.CONFIG.grappleGravity;
		Vec gravity = new Vec(0, -g, 0);

		this.motion.mutableAdd(gravity);


		Vec newMotion = this.motion;
		newMotion.applyAsMotionTo(entity);

		this.updateServerPos();

		if (entity.onGround()) {
			if (!doesrocket) {
				if (this.ignoreGroundCounter <= 0)
					this.disable();

			} else {
				this.motion = Vec.motionVec(entity);
			}
		}

		if (this.ignoreGroundCounter > 0)
			this.ignoreGroundCounter--;

		this.wasRocket = doesrocket;
		this.firstTickSinceCreated = false;
	}

	public void receiveEnderLaunch(double x, double y, double z) {
		super.receiveEnderLaunch(x, y, z);
		this.ignoreGroundCounter = 2;
	}
}
