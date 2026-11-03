package net.minecraft.client.gui.components;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** UI 预览器用的 {@code Renderable} 精简替身 —— 与游戏内一样只有一个方法。 */
public interface Renderable {
    void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta);
}
