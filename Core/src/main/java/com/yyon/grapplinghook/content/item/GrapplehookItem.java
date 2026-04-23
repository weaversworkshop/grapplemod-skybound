package com.yyon.grapplinghook.content.item;

import com.yyon.grapplinghook.api.GrappleModServerEvents;
import com.yyon.grapplinghook.client.GrappleModClient;
import com.yyon.grapplinghook.client.ModKeys;
import com.yyon.grapplinghook.config.GrapplePropertyConfigLoader;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.content.item.type.ICustomizationApplicable;
import com.yyon.grapplinghook.content.item.type.IDropHandling;
import com.yyon.grapplinghook.content.item.type.IGlobalKeyObserver;
import com.yyon.grapplinghook.content.registry.internal.ModDataComponents;
import com.yyon.grapplinghook.content.customization.data.HookCustomization;
import com.yyon.grapplinghook.content.customization.data.TemplateAuthor;
import com.yyon.grapplinghook.network.NetworkManager;
import com.yyon.grapplinghook.network.clientbound.DetachSingleHookS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleDetachS2CPayload;
import com.yyon.grapplinghook.network.serverbound.KeypressC2SPayload;
import com.yyon.grapplinghook.physics.ServerHookEntityTracker;
import com.yyon.grapplinghook.physics.raycast.MultiSpaceRaycaster;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.Vec;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;

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

public class GrapplehookItem extends Item implements IGlobalKeyObserver, IDropHandling, ICustomizationApplicable {

	public static final int DURABILITY = 500; // as of 1.21.1, this should be changed with data components rather than the config.

	public static HashMap<Entity, GrapplinghookEntity> grapplehookEntitiesOffHand = new HashMap<>();
	public static HashMap<Entity, GrapplinghookEntity> grapplehookEntitiesMainHand = new HashMap<>();

	//todo: left/right hand --> main & off hand.

	public GrapplehookItem() {
		super(
				new Properties()
						.stacksTo(1)
						.durability(DURABILITY)
						.component(ModDataComponents.CUSTOMIZABLE, new HookCustomization())
		);
	}


	@Override
	public boolean isValidRepairItem(ItemStack stack, ItemStack repair) {
        if (repair != null && repair.getItem().equals(Items.LEATHER)) return true;
        return super.isValidRepairItem(stack, repair);
	}

	@Override
	public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
		return true;
	}

	// previously: onBlockStartBreak
	@Override
	public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity miningEntity) {
		return true;
	}

	@Override
	public boolean canAttackBlock(BlockState p_195938_1_, Level p_195938_2_, BlockPos p_195938_3_, Player p_195938_4_) {
		return false;
	}

	@Override
	public void onCustomKeyDown(ItemStack stack, Player player, Keys key, boolean isMainHand) {
		if (player.level().isClientSide) {
			if (key == Keys.LAUNCHER) {
				if (this.getCustomizationsOrDefault(stack).get(ENDER_STAFF_ATTACHED.get()))
					GrappleModClient.get().launchPlayer(player);

			} else if (key == Keys.THROW_OFF_HAND || key == Keys.THROW_MAIN_HAND || key == Keys.THROW_BOTH_HOOKS) {
				NetworkManager.packetToServer(new KeypressC2SPayload(key, true));

			} else if (key == Keys.ROCKET) {
				HookCustomization custom = this.getCustomizationsOrDefault(stack);
				if (custom.get(ROCKET_ATTACHED.get()))
					GrappleModClient.get().startRocket(player, custom);
			}

			return;
		}

		HookCustomization custom = this.getCustomizationsOrDefault(stack);

		boolean isEitherSingleHandThrowKeyDown = key == Keys.THROW_OFF_HAND || key == Keys.THROW_MAIN_HAND;

		if (key == Keys.THROW_BOTH_HOOKS || (!custom.get(DOUBLE_HOOK_ATTACHED.get()) && isEitherSingleHandThrowKeyDown)) {
			throwBoth(stack, player.level(), player, isMainHand);
			return;
		}

		if(!isEitherSingleHandThrowKeyDown) return;

		boolean isOffHand = key == Keys.THROW_OFF_HAND;

		GrapplinghookEntity hook = isOffHand
				? getHookEntityOffHand(player)
				: getHookEntityMainHand(player);

		if (hook != null) {
			if(isOffHand) detachOffHand(player);
			else detachMainHand(player);
			return;
		}

		stack.hurtAndBreak(1, player, GrappleModUtils.currentHand(isMainHand));
		if (stack.getCount() <= 0) return;

		boolean threw = isOffHand
				? throwOffHand(stack, player.level(), player, false)
				: throwMainHand(stack, player.level(), player, false);

		if (!threw) return;

		player.level().playSound(null, player.position().x, player.position().y, player.position().z, SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1.0F, 1.0F / (player.getRandom().nextFloat() * 0.4F + 1.2F) + 2.0F * 0.5F);
	}
	
	@Override
	public void onCustomKeyUp(ItemStack stack, Player player, Keys key, boolean isMainHand) {
		if (player.level().isClientSide) {
			if (key == Keys.THROW_OFF_HAND || key == Keys.THROW_MAIN_HAND || key == Keys.THROW_BOTH_HOOKS) {
				NetworkManager.packetToServer(new KeypressC2SPayload(key, false));
			}

			return;
		}

		HookCustomization custom = this.getCustomizationsOrDefault(stack);

		if (custom.get(DETACH_HOOK_ON_KEY_UP.get())) {
			GrapplinghookEntity hookLeft = getHookEntityOffHand(player);
			GrapplinghookEntity hookRight = getHookEntityMainHand(player);

			if (key == Keys.THROW_BOTH_HOOKS) {
				detachBoth(player);
			} else if (key == Keys.THROW_OFF_HAND) {
				if (hookLeft != null) detachOffHand(player);
			} else if (key == Keys.THROW_MAIN_HAND) {
				if (hookRight != null) detachMainHand(player);
			}
		}
	}

	@Override
	public void onDroppedByPlayer(ItemStack stack, Player player) {
		if(!stack.has(ModDataComponents.FORCE_HOOK_DISPLAY))
			return;

		int id = player.getId();
		GrappleModUtils.sendToCorrectClient(new GrappleDetachS2CPayload(id), id, player.level());

		if (grapplehookEntitiesOffHand.containsKey(player)) {
			GrapplinghookEntity hookOffHand = grapplehookEntitiesOffHand.get(player);
			setHookEntityOffHand(player, null);
			if (hookOffHand != null) {
				hookOffHand.removeServer();
			}
		}

		if (grapplehookEntitiesMainHand.containsKey(player)) {
			GrapplinghookEntity hookMainHand = grapplehookEntitiesMainHand.get(player);
			setHookEntityOffHand(player, null);
			if (hookMainHand != null) {
				hookMainHand.removeServer();
			}
		}
	}

	@Override
	@Environment(EnvType.CLIENT)
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
		HookTooltipFormatter.format(stack, this.getCustomizationsOrDefault(stack), tooltipComponents);
	}

	@NotNull
	@Override
	public Component getName(ItemStack stack) {
		if(!stack.has(ModDataComponents.AUTHORED))
			return super.getName(stack);

		TemplateAuthor metadata = stack.get(ModDataComponents.AUTHORED);

		return metadata.isNameEmpty()
				? super.getName(stack)
				: metadata.templateDisplayName();
	}

	@Override
	public boolean shouldAllowQuickOverwrite() {
		return true;
	}

	@Override
	public Component getOverwriteMessage() {
		return Component.translatable("feedback.grapplemod.modifier.applied_configuration");
	}

	@Override
	public SoundEvent getOverwriteSoundEffect() {
		return SoundEvents.VILLAGER_WORK_TOOLSMITH;
	}

	@Override
	public void applyCustomizations(ItemStack stack, HookCustomization custom) {
		stack.remove(ModDataComponents.AUTHORED);
		stack.set(ModDataComponents.CUSTOMIZABLE, custom);
	}

	public Vec calculateThrowDirectionVector(Vec angleVec) {
		float velx = -Mth.sin((float) angleVec.getYaw() * 0.017453292F) * Mth.cos((float) angleVec.getPitch() * 0.017453292F);
		float vely = -Mth.sin((float) angleVec.getPitch() * 0.017453292F);
		float velz = Mth.cos((float) angleVec.getYaw() * 0.017453292F) * Mth.cos((float) angleVec.getPitch() * 0.017453292F);

		return new Vec(velx, vely, velz);
	}

	public double calculateExtraSpeedFromAngles(LivingEntity holder, Vec directionVec) {
		return Math.max(0.0D, Vec.motionVec(holder).distanceAlong(directionVec));
	}

	public void throwBoth(ItemStack stack, Level worldIn, Player entityLiving, boolean isMainHand) {
		if (this.hasHookEntity(entityLiving)) {
			this.detachBoth(entityLiving);
			return;
		}

		stack.hurtAndBreak(1, entityLiving, GrappleModUtils.currentHand(isMainHand));
		if (stack.getCount() <= 0)
			return;

		HookCustomization custom = this.getCustomizationsOrDefault(stack);
		double doubleAngle = this.getDoubleHookAngle(entityLiving, custom);
		boolean shouldThrowBothHands = custom.get(DOUBLE_HOOK_ATTACHED.get()) && doubleAngle != 0;

		if (shouldThrowBothHands)
            this.throwOffHand(stack, worldIn, entityLiving, shouldThrowBothHands);

		this.throwMainHand(stack, worldIn, entityLiving, shouldThrowBothHands);

		entityLiving.level().playSound(null, entityLiving.position().x, entityLiving.position().y, entityLiving.position().z, SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1.0F, 1.0F / (worldIn.random.nextFloat() * 0.4F + 1.2F) + 2.0F * 0.5F);
	}

	public boolean throwOffHand(ItemStack stack, Level worldIn, Player entityLiving, boolean isDoublePair) {
    	HookCustomization custom = this.getCustomizationsOrDefault(stack);

		int handAdjustment = entityLiving.getMainArm() == HumanoidArm.RIGHT ? -1 : 1; // flip based on off-hand
		double angle = this.getDoubleHookAngle(entityLiving, custom) * handAdjustment;
		double verticalAngle = this.getSingleHookAngle(entityLiving, custom);

		Vec initialAngle = Vec.fromAngles(Math.toRadians(angle), Math.toRadians(verticalAngle));
		Vec anglevec = applyHolderRotation(initialAngle, entityLiving);
	  	Vec direction = this.calculateThrowDirectionVector(anglevec);
	  	double extraSpeed = this.calculateExtraSpeedFromAngles(entityLiving, direction);

		GrapplinghookEntity hookEntity = this.createGrapplehookEntity(stack, worldIn, entityLiving, false, isDoublePair);
        hookEntity.shoot(direction, hookEntity.getSpeed() + extraSpeed, 0.0F);
        
		worldIn.addFreshEntity(hookEntity);
		setHookEntityOffHand(entityLiving, hookEntity);
		return true;
	}
	
	public boolean throwMainHand(ItemStack stack, Level worldIn, Player entityLiving, boolean isDoublePair) {
	    HookCustomization custom = this.getCustomizationsOrDefault(stack);

		int handAdjustment = entityLiving.getMainArm() == HumanoidArm.RIGHT ? 1 : -1; // flip based on main-hand
		double angle = this.getDoubleHookAngle(entityLiving, custom) * handAdjustment;
  		double verticalAngle = this.getSingleHookAngle(entityLiving, custom);

		boolean isDoubleHook = custom.get(DOUBLE_HOOK_ATTACHED.get()) && angle > 0;

		Vec initialAngle = isDoubleHook
				? Vec.fromAngles(Math.toRadians(angle), Math.toRadians(verticalAngle))
				: new Vec(0,0,1).rotatePitch(Math.toRadians(verticalAngle));

		Vec anglevec = applyHolderRotation(initialAngle, entityLiving);
		Vec direction = this.calculateThrowDirectionVector(anglevec);
		double extraSpeed = this.calculateExtraSpeedFromAngles(entityLiving, direction);

		GrapplinghookEntity hookEntity = this.createGrapplehookEntity(stack, worldIn, entityLiving, true, isDoublePair);

		hookEntity.shoot(direction, hookEntity.getSpeed() + extraSpeed, 0.0F);

		worldIn.addFreshEntity(hookEntity);
		setHookEntityMainHand(entityLiving, hookEntity);

		return true;
	}
	
	public void detachBoth(LivingEntity thrower) {
		GrapplinghookEntity hookOffHand = getHookEntityOffHand(thrower);
		GrapplinghookEntity hookMainHand = getHookEntityMainHand(thrower);

		tryFlingAttachedEntity(thrower, hookOffHand);
		tryFlingAttachedEntity(thrower, hookMainHand);

		setHookEntityOffHand(thrower, null);
		setHookEntityMainHand(thrower, null);

		if (hookOffHand != null) hookOffHand.removeServer();
		if (hookMainHand != null) hookMainHand.removeServer();

		int id = thrower.getId();
		GrappleModServerEvents.HOOK_RETRACT.invoker().onHookRetracted(thrower);
		GrappleModUtils.sendToCorrectClient(new GrappleDetachS2CPayload(id), thrower.getId(), thrower.level());
	}

	public void detachOffHand(LivingEntity thrower) {

		GrapplinghookEntity hookOffHand = getHookEntityOffHand(thrower);
		tryFlingAttachedEntity(thrower, hookOffHand);
		setHookEntityOffHand(thrower, null);

		if (hookOffHand != null) hookOffHand.removeServer();

		int id = thrower.getId();
		GrappleModServerEvents.HOOK_RETRACT.invoker().onHookRetracted(thrower);
		
		// remove controller if no hook is attached
		if (getHookEntityMainHand(thrower) == null) {
			GrappleModUtils.sendToCorrectClient(new GrappleDetachS2CPayload(id), id, thrower.level());
		} else {
			GrappleModUtils.sendToCorrectClient(new DetachSingleHookS2CPayload(id, hookOffHand.getId()), id, thrower.level());
		}
	}
	
	public void detachMainHand(LivingEntity thrower) {
		GrapplinghookEntity hookMainHand = getHookEntityMainHand(thrower);
		tryFlingAttachedEntity(thrower, hookMainHand);
		setHookEntityMainHand(thrower, null);

		if (hookMainHand != null) hookMainHand.removeServer();
		
		int id = thrower.getId();

		GrappleModServerEvents.HOOK_RETRACT.invoker().onHookRetracted(thrower);

		// remove controller if no hook is attached
		if (getHookEntityOffHand(thrower) == null) {
			GrappleModUtils.sendToCorrectClient(new GrappleDetachS2CPayload(id), id, thrower.level());
		} else {
			GrappleModUtils.sendToCorrectClient(new DetachSingleHookS2CPayload(id, hookMainHand.getId()), id, thrower.level());
		}
	}
	
	private void tryFlingAttachedEntity(LivingEntity thrower, GrapplinghookEntity hook) {
		if (hook == null) return;
		if (!thrower.isCrouching()) return;
		if (thrower.level().isClientSide) return;

		Entity attached = hook.attachedWorldEntity();
		if (!(attached instanceof LivingEntity mob)) return;

		Vec3 mobPos = mob.position();
		Vec3 playerPos = thrower.position().add(0, thrower.getEyeHeight(), 0);
		Vec3 pullVec = playerPos.subtract(mobPos);
		double flingDist = playerPos.subtract(mobPos).length();
		if (flingDist < 1.0E-3) return;

		Vec3 dir = pullVec.scale(1.0 / flingDist);

		double verticalBias = Math.sin(Math.toRadians(GrapplePropertyConfigLoader.CONFIG.flingVerticalAngle));
		double biasScale = Math.max(0.0, 1.0 - Math.abs(dir.y));
		Vec3 biasedDir = new Vec3(dir.x, dir.y + verticalBias * biasScale, dir.z).normalize();

		double magnitude = Math.sqrt(flingDist) * GrapplePropertyConfigLoader.CONFIG.flingBasePower;
		Vec3 velocity = biasedDir.scale(magnitude);

		mob.setDeltaMovement(velocity);
		mob.hurtMarked = true;
	}

	public GrapplinghookEntity createGrapplehookEntity(ItemStack stack, Level worldIn, LivingEntity entityLiving, boolean isMainHand, boolean isDoublePair) {
		GrapplinghookEntity hookEntity = new GrapplinghookEntity(worldIn, entityLiving, isMainHand, this.getCustomizationsOrDefault(stack), isDoublePair);
		ServerHookEntityTracker.addGrappleEntity(entityLiving, hookEntity);
		return hookEntity;
	}

	public void applyTemplateMetadata(ItemStack stack, TemplateAuthor template) {
		if(template != null) {
			stack.set(ModDataComponents.AUTHORED, template);
		} else {
			stack.remove(ModDataComponents.AUTHORED);
		}
	}

	public static Vec applyHolderRotation(Vec angleVec, LivingEntity holder) {
		Vec newVec = angleVec.rotatePitch(Math.toRadians(-holder.getViewXRot(1.0F)));
		return newVec.rotateYaw(Math.toRadians(holder.getViewYRot(1.0F)));
	}


	public void setHookEntityOffHand(Entity entity, GrapplinghookEntity hookEntity) {
		GrapplehookItem.grapplehookEntitiesOffHand.put(entity, hookEntity);
	}
	public void setHookEntityMainHand(Entity entity, GrapplinghookEntity hookEntity) {
		GrapplehookItem.grapplehookEntitiesMainHand.put(entity, hookEntity);
	}


	public boolean hasHookEntity(Entity entity) {
		GrapplinghookEntity offHook = getHookEntityOffHand(entity);
		GrapplinghookEntity mainHook = getHookEntityMainHand(entity);
		return (offHook != null) || (mainHook != null);
	}

	public GrapplinghookEntity getHookEntityOffHand(Entity entity) {
		if (!GrapplehookItem.grapplehookEntitiesOffHand.containsKey(entity)) return null;

		GrapplinghookEntity hookEntity = GrapplehookItem.grapplehookEntitiesOffHand.get(entity);
		if (hookEntity != null && hookEntity.isAlive())
			return hookEntity;

		return null;
	}

	public GrapplinghookEntity getHookEntityMainHand(Entity entity) {
		if (!GrapplehookItem.grapplehookEntitiesMainHand.containsKey(entity)) return null;

		GrapplinghookEntity hookEntity = GrapplehookItem.grapplehookEntitiesMainHand.get(entity);
		if (hookEntity != null && hookEntity.isAlive())
			return hookEntity;

		return null;
	}

	public double getSingleHookAngle(LivingEntity entity, HookCustomization custom) {
		return entity.isCrouching()
				? custom.get(HOOK_THROW_ANGLE_ON_SNEAK.get())
				: custom.get(HOOK_THROW_ANGLE.get());
	}

	public double getDoubleHookAngle(LivingEntity entity, HookCustomization custom) {
		return entity.isCrouching()
				? custom.get(DOUBLE_HOOK_ANGLE_ON_SNEAK.get())
				: custom.get(DOUBLE_HOOK_ANGLE.get());
	}

	public HookCustomization getCustomizationsOrDefault(ItemStack stack) {
		return stack.getOrDefault(ModDataComponents.CUSTOMIZABLE, new HookCustomization());
	}

	public boolean shouldDisplayAsHookOnly(ItemStack stack) {
		return stack.has(ModDataComponents.FORCE_HOOK_DISPLAY);
	}
}
