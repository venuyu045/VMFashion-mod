package net.minecraft.client.gui.components.events;

import net.minecraft.client.gui.components.TabOrderedElement;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * UI 预览器用的 {@code GuiEventListener} 精简替身。
 *
 * <p>方法集与游戏内对齐（鼠标/键盘/字符输入），这样生产代码里的
 * {@code super.mouseClicked(...)} 转发链在预览器里走的是同一条路径。
 */
public interface GuiEventListener extends TabOrderedElement {

    default void mouseMoved(double mouseX, double mouseY) {
    }

    default boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    default boolean mouseReleased(MouseButtonEvent event) {
        return false;
    }

    default boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return false;
    }

    default boolean keyPressed(KeyEvent event) {
        return false;
    }

    default boolean keyReleased(KeyEvent event) {
        return false;
    }

    default boolean charTyped(CharacterEvent event) {
        return false;
    }

    default boolean isMouseOver(double mouseX, double mouseY) {
        return false;
    }

    void setFocused(boolean focused);

    boolean isFocused();
}
