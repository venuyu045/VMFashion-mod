package net.minecraft.client.gui.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AlphaAware;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * UI 预览器用的 {@code net.minecraft.client.gui.screens.Screen} 替身。
 *
 * <p>这是整个预览方案的"接缝"：{@code WardrobeScreen} 继承它，只用到它的
 * {@link #width}/{@link #height}/{@link #font}/{@link #minecraft} 字段、
 * {@link #addRenderableWidget}、{@link #extractRenderState}、{@link #fadeWidgets}
 * 和事件转发链。把这几件事实现对，生产代码就能原样跑起来。
 *
 * <p><b>与游戏内保持一致的语义：</b>
 * <ul>
 *   <li>{@code init(int, int)} 先清空控件再调子类 {@code init()} —— 所以界面 resize
 *       不会残留旧控件（游戏里换窗口大小就是这条路）；</li>
 *   <li>{@code extractRenderState} 遍历 renderables，按添加顺序绘制；</li>
 *   <li>鼠标/键盘事件按 children 顺序转发，第一个消费的命中；</li>
 *   <li>{@code fadeWidgets} 在打开动画期间给控件设 alpha。</li>
 * </ul>
 */
public class Screen extends AbstractContainerEventHandler implements Renderable {

    protected final Component title;
    protected final Minecraft minecraft;
    public int width;
    public int height;
    protected final Font font;

    private final List<GuiEventListener> children = new ArrayList<>();
    private final List<Renderable> renderables = new ArrayList<>();

    protected Screen(Component title) {
        this(Minecraft.ensureInstance(), Minecraft.getInstance().font, title);
    }

    protected Screen(Minecraft minecraft, Font font, Component title) {
        this.minecraft = minecraft;
        this.font = font;
        this.title = title;
    }

    public Component getTitle() {
        return title;
    }

    public Font getFont() {
        return font;
    }

    /** 初始化（游戏内会在窗口尺寸变化时重新调用）。 */
    public final void init(int width, int height) {
        this.width = width;
        this.height = height;
        this.clearWidgets();
        this.init();
    }

    /** 子类在这里建控件。 */
    protected void init() {
    }

    /** 换窗口大小：重新布局。 */
    public void resize(int width, int height) {
        init(width, height);
    }

    public <T extends GuiEventListener & Renderable & net.minecraft.client.gui.narration.NarratableEntry>
    T addRenderableWidget(T widget) {
        children.add(widget);
        renderables.add(widget);
        return widget;
    }

    public <T extends Renderable> T addRenderableOnly(T renderable) {
        renderables.add(renderable);
        return renderable;
    }

    public <T extends GuiEventListener & net.minecraft.client.gui.narration.NarratableEntry>
    T addWidget(T widget) {
        children.add(widget);
        return widget;
    }

    protected void removeWidget(GuiEventListener widget) {
        children.remove(widget);
        if (widget instanceof Renderable renderable) {
            renderables.remove(renderable);
        }
    }

    protected void clearWidgets() {
        children.clear();
        renderables.clear();
    }

    public void rebuildWidgets() {
        clearWidgets();
        init();
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return children;
    }

    /** 绘制所有控件（子类 {@code extractRenderState} 里调 {@code super} 就是走这里）。 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        for (Renderable renderable : renderables) {
            renderable.extractRenderState(context, mouseX, mouseY, delta);
        }
    }

    /** 打开动画：控件跟着面板一起淡入。 */
    protected void fadeWidgets(float alpha) {
        for (GuiEventListener child : children) {
            if (child instanceof AlphaAware aware) {
                aware.setAlpha(alpha);
            }
        }
    }

    public void onClose() {
        if (minecraft != null && minecraft.gui != null) {
            minecraft.gui.setScreen(null);
        }
    }

    public boolean shouldCloseOnEsc() {
        return true;
    }

    public boolean isPauseScreen() {
        return true;
    }

    public boolean isInGameUi() {
        return false;
    }

    public void tick() {
    }

    public void added() {
    }

    public void removed() {
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return true;
    }

    /** 无障碍朗读占位。 */
    public void updateNarrationState(net.minecraft.client.gui.narration.NarrationElementOutput output) {
    }

    public void updateNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
    }
}
