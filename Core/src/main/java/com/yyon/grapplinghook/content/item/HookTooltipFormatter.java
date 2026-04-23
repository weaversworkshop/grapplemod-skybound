package com.yyon.grapplinghook.content.item;

import com.yyon.grapplinghook.client.ModKeys;
import com.yyon.grapplinghook.content.customization.PropertyDelta;
import com.yyon.grapplinghook.content.customization.data.HookCustomization;
import com.yyon.grapplinghook.content.customization.data.TemplateAuthor;
import com.yyon.grapplinghook.content.customization.type.AttachmentProperty;
import com.yyon.grapplinghook.content.customization.type.CustomizationProperty;
import com.yyon.grapplinghook.content.registry.internal.ModDataComponents;
import com.yyon.grapplinghook.util.TextUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;

import static com.yyon.grapplinghook.content.registry.CustomizationProperties.*;

@Environment(EnvType.CLIENT)
final class HookTooltipFormatter {

    private HookTooltipFormatter() {}

    static void format(ItemStack stack, HookCustomization custom, List<Component> tooltipComponents) {
        Options options = Minecraft.getInstance().options;

        boolean hasDelta = stack.has(ModDataComponents.CUSTOMIZATION_DELTA);

        if (stack.has(ModDataComponents.AUTHORED)) {
            TemplateAuthor metadata = stack.get(ModDataComponents.AUTHORED);
            Component author = metadata.author()
                    .copy()
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.UNDERLINE);

            tooltipComponents.add(Component.empty()
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.translatable("grapple_tooltip.template.author"))
                    .append(Component.literal(" "))
                    .append(author)
            );

            tooltipComponents.add(Component.literal(" "));
        }

        if (Screen.hasShiftDown() && !hasDelta) {
            appendControls(custom, tooltipComponents, options);
            return;
        }

        // Preview of Components like:
        // property: value
        // property: [/]
        //...
        if (Screen.hasControlDown() && !hasDelta) {
            appendPropertiesList(custom, tooltipComponents);
            return;
        }

        // Preview of changes:
        // unchanged_property: val (gray)
        // property: valBefore -> valAfter (green)
        // --removed_property: val_before -> val_after (default value)--   (all crossed out, red)
        if (hasDelta) {
            appendDeltaPreview(stack, custom, tooltipComponents);
            return;
        }

        appendAttachmentsList(custom, tooltipComponents);

        tooltipComponents.add(Component.translatable("grapple_tooltip.controls.hint").withStyle(
                ChatFormatting.ITALIC, ChatFormatting.GRAY
        ));
        tooltipComponents.add(Component.translatable("grapple_tooltip.configuration.hint").withStyle(
                ChatFormatting.ITALIC, ChatFormatting.GRAY
        ));
    }

    private static void appendControls(HookCustomization custom, List<Component> tooltipComponents, Options options) {
        tooltipComponents.add(Component.literal(""));
        tooltipComponents.add(Component.translatable("grappletooltip.controls.title").withStyle(
                ChatFormatting.GRAY, ChatFormatting.UNDERLINE
        ));

        if (custom.get(DOUBLE_HOOK_ATTACHED.get())) {
            if (!custom.get(DETACH_HOOK_ON_KEY_UP.get())) {
                tooltipComponents.add(TextUtils.keybinding("grappletooltip.throw_double_both.desc", ModKeys.THROW_HOOKS.get()));
                tooltipComponents.add(TextUtils.keybinding("grappletooltip.throw_double_off_hand.desc", ModKeys.THROW_OFF_HOOK));
                tooltipComponents.add(TextUtils.keybinding("grappletooltip.throw_double_main_hand.desc", ModKeys.THROW_MAIN_HOOK));
            } else {
                tooltipComponents.add(TextUtils.keybinding("grappletooltip.throw_double_both_hold.desc", ModKeys.THROW_HOOKS.get()));
                tooltipComponents.add(TextUtils.keybinding("grappletooltip.throw_double_off_hand_hold.desc", ModKeys.THROW_OFF_HOOK));
                tooltipComponents.add(TextUtils.keybinding("grappletooltip.throw_double_main_hand_hold.desc", ModKeys.THROW_MAIN_HOOK));
            }

        } else {
            if (!custom.get(DETACH_HOOK_ON_KEY_UP.get())) {
                tooltipComponents.add(TextUtils.keybinding("grappletooltip.throw.desc", ModKeys.THROW_HOOKS.get()));
                tooltipComponents.add(TextUtils.keybinding("grappletooltip.release.desc", ModKeys.THROW_HOOKS.get()));
            } else {
                tooltipComponents.add(TextUtils.keybinding("grappletooltip.throw_hold.desc", ModKeys.THROW_HOOKS.get()));
            }
        }

        tooltipComponents.add(TextUtils.keybinding("grappletooltip.swing.desc",
                options.keyUp, options.keyLeft, options.keyDown, options.keyRight
        ));

        tooltipComponents.add(TextUtils.keybinding("grappletooltip.jump.desc", ModKeys.DETACH.get()));
        tooltipComponents.add(TextUtils.keybinding("grappletooltip.slow.desc", ModKeys.DAMPEN_SWING.get()));

        tooltipComponents.add(Component.empty().withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)
                .append(ModKeys.CLIMB.get().getTranslatedKeyMessage()).append("+")
                .append(options.keyUp.getTranslatedKeyMessage())
                .append(" / ")
                .append(ModKeys.CLIMB_UP.getTranslatedKeyMessage())
                .append(" - ").append(Component.translatable("grappletooltip.climbup.desc"))
        );

        tooltipComponents.add(Component.empty().withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)
                .append(ModKeys.CLIMB.get().getTranslatedKeyMessage()).append("+")
                .append(options.keyDown.getTranslatedKeyMessage())
                .append(" / ")
                .append(ModKeys.CLIMB_DOWN.getTranslatedKeyMessage())
                .append(" - ").append(Component.translatable("grappletooltip.climbdown.desc"))
        );

        if (custom.get(ENDER_STAFF_ATTACHED.get())) {
            tooltipComponents.add(TextUtils.keybinding("grappletooltip.enderlaunch.desc", ModKeys.HOOK_ENDER_LAUNCH.get()));
        }

        if (custom.get(ROCKET_ATTACHED.get())) {
            tooltipComponents.add(TextUtils.keybinding("grappletooltip.rocket.desc", ModKeys.ROCKET.get()));
        }

        if (custom.get(MOTOR_ATTACHED.get())) {
            Component text = switch (custom.get(MOTOR_ACTIVATION.get())) {
                case WHEN_CROUCHING -> TextUtils.keybinding("grappletooltip.motoron.desc", ModKeys.TOGGLE_MOTOR.get());
                case WHEN_NOT_CROUCHING -> TextUtils.keybinding("grappletooltip.motoroff.desc", ModKeys.TOGGLE_MOTOR.get());
                default -> null;
            };

            if (text != null)
                tooltipComponents.add(text.copy().withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static void appendPropertiesList(HookCustomization custom, List<Component> tooltipComponents) {
        tooltipComponents.add(Component.translatable("grappletooltip.properties.title").withStyle(
                ChatFormatting.GRAY, ChatFormatting.UNDERLINE
        ));

        for (CustomizationProperty<?> property : custom.getPropertiesPresent()) {
            Component hintText = property.getDisplay().getValueHint(custom);
            if (hintText == null) continue;

            Component formatted = property.getDisplayName().copy().append(hintText).withStyle(ChatFormatting.DARK_GRAY);

            tooltipComponents.add(formatted);
        }
    }

    private static void appendDeltaPreview(ItemStack stack, HookCustomization custom, List<Component> tooltipComponents) {
        HookCustomization defaultCustom = new HookCustomization();
        HookCustomization prevCustom = stack.get(ModDataComponents.CUSTOMIZATION_DELTA);

        tooltipComponents.add(Component.translatable("grappletooltip.properties.delta.title").withStyle(
                ChatFormatting.GRAY, ChatFormatting.UNDERLINE
        ));

        for (CustomizationProperty<?> property : custom.getPropertyChanges(prevCustom)) {
            PropertyDelta delta = property.compareValues(property, prevCustom, custom);

            Component entry = switch (delta) {
                case SAME -> property.getDisplayName().copy()
                        .append(": ")
                        .append(property.getDisplay().getValueHint(custom))
                        .withStyle(ChatFormatting.DARK_GRAY);
                case CHANGED -> property.getDisplayName().copy()
                        .append(": ")
                        .append(property.getDisplay().getValueHint(prevCustom))
                        .append(Component.literal(" -> "))
                        .append(property.getDisplay().getValueHint(custom))
                        .withStyle(ChatFormatting.GREEN);
                case CHANGED_TO_DEFAULT -> property.getDisplayName().copy()
                        .append(": ")
                        .append(property.getDisplay().getValueHint(prevCustom))
                        .append(Component.literal(" -> "))
                        .append(property.getDisplay().getValueHint(defaultCustom))
                        .withStyle(ChatFormatting.RED, ChatFormatting.STRIKETHROUGH);
            };

            tooltipComponents.add(entry);
        }
    }

    private static void appendAttachmentsList(HookCustomization custom, List<Component> tooltipComponents) {
        HashMap<ResourceLocation, Component> attachmentTexts = new HashMap<>();

        custom.getPropertiesPresent().stream()
                .filter(p -> p instanceof AttachmentProperty)
                .map(p -> (AttachmentProperty) p)
                .forEach(attachment -> {
                    boolean isAttachmentShadowed = AttachmentProperty.isShadowed(custom, attachment);

                    // Some attachments are hidden by others (i.e, smart motor hides motor)
                    // Skip any names where its shadower is present and enabled.
                    if (isAttachmentShadowed) return;

                    Component formattedName = attachment.getDisplayName()
                            .copy()
                            .withStyle(ChatFormatting.DARK_GRAY);

                    attachmentTexts.put(attachment.getIdentifier(), formattedName);
                });

        if (!attachmentTexts.isEmpty()) {
            tooltipComponents.add(Component.translatable("grappletooltip.attachments.title").withStyle(
                    ChatFormatting.GRAY, ChatFormatting.BOLD, ChatFormatting.UNDERLINE
            ));

            tooltipComponents.add(Component.literal(""));
            tooltipComponents.addAll(attachmentTexts.values());
            tooltipComponents.add(Component.literal(""));
        }
    }
}
