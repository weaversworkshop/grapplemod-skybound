package com.yyon.grapplinghook.content.registry.internal;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.content.recipe.smithing.HookUpgradeSmithingRecipe;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeSerializer;

public final class ModRecipeSerializers {

    public static final RecipeSerializer<HookUpgradeSmithingRecipe> HOOK_UPGRADE = new HookUpgradeSmithingRecipe.Serializer();

    public static void registerAll() {
        Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, GrappleMod.id("hook_upgrade"), HOOK_UPGRADE);
    }

    private ModRecipeSerializers() {}
}
