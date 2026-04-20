package com.yyon.grapplinghook.client.gui.screen.blueprint;

import net.minecraft.client.gui.components.AbstractWidget;

import java.util.List;

public class HelpView extends AbstractBlueprintView {

    public HelpView() {
        super(CurrentModifierView.HELP);
    }

    @Override
    public int create() {

        return 0;
    }

    @Override
    public void destroy(List<AbstractWidget> widgets) {

    }

    public int getContentsHeight() {
        return 0;
    }
}
