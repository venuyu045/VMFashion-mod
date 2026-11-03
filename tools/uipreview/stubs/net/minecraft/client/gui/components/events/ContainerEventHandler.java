package net.minecraft.client.gui.components.events;

import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;

/**
 * UI 预览器用的 {@code ContainerEventHandler} 精简替身。
 *
 * <p>默认实现与游戏内一致：<b>按 children 顺序转发事件，第一个消费掉的即命中</b>，
 * 并把焦点移过去。生产代码（如 {@code WardrobeScreen#mouseClicked}）先调
 * {@code super.mouseClicked(...)} 让控件（搜索框 / 按钮）先处理，正是依赖这条链。
 */
public interface ContainerEventHandler extends GuiEventListener {

    List<? extends GuiEventListener> children();

    default boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (GuiEventListener child : children()) {
            if (child.mouseClicked(event, doubleClick)) {
                setFocused(child);
                return true;
            }
        }
        return false;
    }

    default boolean mouseReleased(MouseButtonEvent event) {
        for (GuiEventListener child : children()) {
            if (child.mouseReleased(event)) {
                return true;
            }
        }
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (GuiEventListener child : children()) {
            if (child.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
                return true;
            }
        }
        return false;
    }

    default boolean keyPressed(KeyEvent event) {
        for (GuiEventListener child : children()) {
            if (child.keyPressed(event)) {
                return true;
            }
        }
        return false;
    }

    default boolean charTyped(CharacterEvent event) {
        for (GuiEventListener child : children()) {
            if (child.charTyped(event)) {
                return true;
            }
        }
        return false;
    }

    boolean isDragging();

    void setDragging(boolean dragging);

    GuiEventListener getFocused();

    void setFocused(GuiEventListener focused);

    @Override
    default void setFocused(boolean focused) {
    }
}
