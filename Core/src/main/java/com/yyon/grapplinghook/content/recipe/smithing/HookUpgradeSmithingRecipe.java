package com.yyon.grapplinghook.content.recipe.smithing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yyon.grapplinghook.content.customization.data.HookCustomization;
import com.yyon.grapplinghook.content.customization.type.CustomizationProperty;
import com.yyon.grapplinghook.content.item.GrapplehookItem;
import com.yyon.grapplinghook.content.registry.internal.ModItems;
import com.yyon.grapplinghook.content.registry.internal.ModRecipeSerializers;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.Level;

import java.util.List;

public class HookUpgradeSmithingRecipe implements SmithingRecipe {

    private final Ingredient template;
    private final Ingredient addition;
    private final List<CustomizationProperty<?>> applies;
    private final List<CustomizationProperty<?>> excludesIfAny;

    public HookUpgradeSmithingRecipe(Ingredient template, Ingredient addition,
                                     List<CustomizationProperty<?>> applies,
                                     List<CustomizationProperty<?>> excludesIfAny) {
        this.template = template;
        this.addition = addition;
        this.applies = List.copyOf(applies);
        this.excludesIfAny = List.copyOf(excludesIfAny);
    }

    public Ingredient template() { return template; }
    public Ingredient addition() { return addition; }
    public List<CustomizationProperty<?>> applies() { return applies; }
    public List<CustomizationProperty<?>> excludesIfAny() { return excludesIfAny; }

    @Override
    public boolean isTemplateIngredient(ItemStack stack) {
        return this.template.test(stack);
    }

    @Override
    public boolean isBaseIngredient(ItemStack stack) {
        return stack.is(ModItems.GRAPPLING_HOOK.get());
    }

    @Override
    public boolean isAdditionIngredient(ItemStack stack) {
        return this.addition.test(stack);
    }

    @Override
    public boolean matches(SmithingRecipeInput input, Level level) {
        if (!this.template.test(input.template())) return false;
        if (!this.isBaseIngredient(input.base())) return false;
        if (!this.addition.test(input.addition())) return false;

        GrapplehookItem hook = ModItems.GRAPPLING_HOOK.get();
        HookCustomization custom = hook.getCustomizationsOrDefault(input.base());
        for (CustomizationProperty<?> blocker : this.excludesIfAny) {
            if (Boolean.TRUE.equals(custom.get(blocker))) return false;
        }
        return true;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ItemStack assemble(SmithingRecipeInput input, HolderLookup.Provider registries) {
        ItemStack base = input.base().copyWithCount(1);
        GrapplehookItem hook = ModItems.GRAPPLING_HOOK.get();
        HookCustomization custom = HookCustomization.copyAllFrom(hook.getCustomizationsOrDefault(base));
        for (CustomizationProperty<?> property : this.applies) {
            ((HookCustomization) custom).set((CustomizationProperty) property, Boolean.TRUE);
        }
        hook.applyCustomizations(base, custom);
        return base;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return ModItems.GRAPPLING_HOOK.get().getDefaultInstance();
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.HOOK_UPGRADE;
    }

    @Override
    public boolean isIncomplete() {
        return this.template.isEmpty() || this.addition.isEmpty();
    }

    public static class Serializer implements RecipeSerializer<HookUpgradeSmithingRecipe> {

        private static final Codec<List<CustomizationProperty<?>>> PROPERTY_LIST_CODEC = CustomizationProperty.KEY_CODEC.listOf();

        private static final MapCodec<HookUpgradeSmithingRecipe> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
                Ingredient.CODEC.fieldOf("template").forGetter(HookUpgradeSmithingRecipe::template),
                Ingredient.CODEC.fieldOf("addition").forGetter(HookUpgradeSmithingRecipe::addition),
                PROPERTY_LIST_CODEC.fieldOf("applies").forGetter(HookUpgradeSmithingRecipe::applies),
                PROPERTY_LIST_CODEC.optionalFieldOf("excludes_if_any", List.of()).forGetter(HookUpgradeSmithingRecipe::excludesIfAny)
        ).apply(inst, HookUpgradeSmithingRecipe::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, List<CustomizationProperty<?>>> PROPERTY_LIST_STREAM_CODEC =
                ByteBufCodecs.fromCodecWithRegistries(PROPERTY_LIST_CODEC);

        private static final StreamCodec<RegistryFriendlyByteBuf, HookUpgradeSmithingRecipe> STREAM_CODEC =
                StreamCodec.composite(
                        Ingredient.CONTENTS_STREAM_CODEC, HookUpgradeSmithingRecipe::template,
                        Ingredient.CONTENTS_STREAM_CODEC, HookUpgradeSmithingRecipe::addition,
                        PROPERTY_LIST_STREAM_CODEC, HookUpgradeSmithingRecipe::applies,
                        PROPERTY_LIST_STREAM_CODEC, HookUpgradeSmithingRecipe::excludesIfAny,
                        HookUpgradeSmithingRecipe::new
                );

        @Override
        public MapCodec<HookUpgradeSmithingRecipe> codec() { return CODEC; }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, HookUpgradeSmithingRecipe> streamCodec() { return STREAM_CODEC; }
    }
}
