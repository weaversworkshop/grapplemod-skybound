package com.yyon.grapplinghook.client.gui.view;

import net.minecraft.client.gui.components.AbstractWidget;

import java.util.List;

public class BlankView extends SwitchableScreenView {

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
