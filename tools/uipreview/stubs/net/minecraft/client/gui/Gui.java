package net.minecraft.client.gui;

import net.minecraft.client.gui.screens.Screen;

/**
 * UI 预览器用的 {@code net.minecraft.client.gui.Gui} 替身。
 *
 * <p>只保留界面栈最小语义：{@link #setScreen} 记录当前界面，
 * {@link #screen()} 供预览器读取（比如点了"关闭"之后判断界面是否被关掉）。
 */
public class Gui {

    private Screen screen;

    public void setScreen(Screen screen) {
        this.screen = screen;
    }

    public Screen screen() {
        return screen;
    }

    public void tick() {
    }
}
