package com.yyon.grapplinghook.physics;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.api.GrappleModServerEvents;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Handles server-side tracking and aggregation of grappling hook
 */
public class ServerHookEntityTracker {

	private static final HashMap<Integer, HashSet<GrapplinghookEntity>> allGrapplehookEntities = new HashMap<>();


	public static void checkOwnerIsNotHookElseWarn(Entity entity) {
		if(!(entity instanceof GrapplinghookEntity)) return;

		// If someone needs to throw a hook from a hook, what the hell are you doing???
		// Submit a PR explaining yourself if this is a problem.
		GrappleMod.LOGGER.warn(new Throwable(
				"A mod checks if a hook has other hooks attached to it. This is probably not right."
		));
	}


	/**
	 * Adds a grappling hook entity to be tracked
	 * @param hookEntity the entity instance of the hook thrown
	 */
	public static void addGrappleEntity(Entity thrower, GrapplinghookEntity hookEntity) {
		int id = thrower.getId();
		if (!allGrapplehookEntities.containsKey(id))
			allGrapplehookEntities.put(id, new HashSet<>());

		allGrapplehookEntities.get(id).add(hookEntity);
		GrappleModServerEvents.HOOK_THROW.invoker().onHookThrown(thrower, hookEntity);
	}

	/**
	 * Adds a grappling hook entity to be tracked.
	 * @param ownerEntity the thrower of the hook
	 */
	public static void removeAllHooksFor(Entity ownerEntity) {
		ServerHookEntityTracker.checkOwnerIsNotHookElseWarn(ownerEntity);
		ServerHookEntityTracker.removeAllHooksFor(ownerEntity.getId());
	}

	/**
	 * Adds a grappling hook entity to be tracked.
	 * @param ownerId the hookId of the hook thrower
	 */
	public static void removeAllHooksFor(int ownerId) {
		if (!allGrapplehookEntities.containsKey(ownerId)) {
			allGrapplehookEntities.put(ownerId, new HashSet<>());
			return;
		}

		for (GrapplinghookEntity hookEntity : allGrapplehookEntities.get(ownerId)) {
			if (hookEntity == null) continue;
			if(!hookEntity.isAlive()) continue;

			hookEntity.removeServer();
		}

		allGrapplehookEntities.put(ownerId, new HashSet<>());
	}
	
	public static void handleGrappleEndFromClient(int ownerId, Level world, Set<Integer> hookEntityIds) {
		
		for (int hookEntityId : hookEntityIds) {
	      	Entity grapple = world.getEntity(hookEntityId);
	  		if (grapple instanceof GrapplinghookEntity) {
	  			((GrapplinghookEntity) grapple).removeServer();
	  		}
		}
  		
  		Entity entity = world.getEntity(ownerId);
  		if (entity != null) entity.fallDistance = 0;

  		
  		ServerHookEntityTracker.removeAllHooksFor(ownerId);
	}

	public static Set<GrapplinghookEntity> getHooksThrownBy(Entity ownerEntity) {
		ServerHookEntityTracker.checkOwnerIsNotHookElseWarn(ownerEntity);
		return ServerHookEntityTracker.getHooksThrownBy(ownerEntity.getId());
	}

	public static Set<GrapplinghookEntity> getHooksThrownBy(int ownerId) {
		Set<GrapplinghookEntity> hookEntities = allGrapplehookEntities.get(ownerId);
		return hookEntities != null
				? Collections.unmodifiableSet(hookEntities)
				: new HashSet<>();
	}

	/** Snapshot of every tracked hook across all owners. Snapshot, not a live view, so callers can mutate. */
	public static List<GrapplinghookEntity> getAllTrackedHooks() {
		List<GrapplinghookEntity> all = new ArrayList<>();
		for (HashSet<GrapplinghookEntity> bucket : allGrapplehookEntities.values()) {
			all.addAll(bucket);
		}
		return all;
	}

	public static boolean isAttachedToHooks(Entity ownerEntity) {
		ServerHookEntityTracker.checkOwnerIsNotHookElseWarn(ownerEntity);
		return ServerHookEntityTracker.isAttachedToHooks(ownerEntity.getId());
	}

	public static boolean isAttachedToHooks(int ownerId) {
		Set<GrapplinghookEntity> hookEntities = allGrapplehookEntities.get(ownerId);
		return hookEntities != null && !hookEntities.isEmpty();
	}
}
