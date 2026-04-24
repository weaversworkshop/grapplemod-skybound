package com.yyon.grapplinghook.content.item.smithing;

import com.yyon.grapplinghook.GrappleMod;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.SmithingTemplateItem;

import java.util.List;

public class GrapplingHookUpgradeTemplateItem extends SmithingTemplateItem {

    private static final ChatFormatting TITLE_FORMAT = ChatFormatting.GRAY;
    private static final ChatFormatting DESCRIPTION_FORMAT = ChatFormatting.BLUE;

    private static final String TRANSLATION_APPLIES_TO = Util.makeDescriptionId("item", GrappleMod.id("smithing_template.grappling_hook_upgrade.applies_to"));
    private static final String TRANSLATION_INGREDIENTS = Util.makeDescriptionId("item", GrappleMod.id("smithing_template.grappling_hook_upgrade.ingredients"));
    private static final String TRANSLATION_UPGRADE_NAME = Util.makeDescriptionId("upgrade", GrappleMod.id("grappling_hook_upgrade"));
    private static final String TRANSLATION_BASE_SLOT = Util.makeDescriptionId("item", GrappleMod.id("smithing_template.grappling_hook_upgrade.base_slot_description"));
    private static final String TRANSLATION_ADDITIONAL_SLOT = Util.makeDescriptionId("item", GrappleMod.id("smithing_template.grappling_hook_upgrade.additions_slot_description"));

    private static final ResourceLocation EMPTY_SLOT_HOOK = GrappleMod.id("items/hook");
    private static final ResourceLocation EMPTY_SLOT_UPGRADE = GrappleMod.id("items/hint/empty_slot_membrane");

    public GrapplingHookUpgradeTemplateItem() {
        super(
                Component.translatable(TRANSLATION_APPLIES_TO).withStyle(DESCRIPTION_FORMAT),
                Component.translatable(TRANSLATION_INGREDIENTS).withStyle(DESCRIPTION_FORMAT),
                Component.translatable(TRANSLATION_UPGRADE_NAME).withStyle(TITLE_FORMAT),
                Component.translatable(TRANSLATION_BASE_SLOT),
                Component.translatable(TRANSLATION_ADDITIONAL_SLOT),
                List.of(EMPTY_SLOT_HOOK),
                List.of(EMPTY_SLOT_UPGRADE)
        );
    }
}
