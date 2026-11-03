package net.minecraft.client.gui.components;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * UI 预览器用的 {@code net.minecraft.client.gui.components.Button} 替身。
 *
 * <p><b>外观走真实精灵图</b>：背景用游戏内 {@code widget/button.png} 的九宫格
 * （200×20，border 3），悬停换成 {@code button_highlighted.png}、禁用换成
 * {@code button_disabled.png}，文字颜色与游戏一致（可用 {@code #FFFFFF} / 禁用 {@code #A0A0A0}）。
 * 这样预览图上按钮的圆角、边框、高光都和游戏里一样，而不是一个自己瞎画的灰块。
 *
 * <p>布局语义也与游戏一致：{@code bounds} 决定左上角与宽高，文字在按钮内<b>居中</b>
 * （水平居中 + 垂直居中偏移 {@code (height - 8) / 2}）。
 */
public class Button implements Renderable, GuiEventListener, NarratableEntry, AlphaAware {

    /** 按钮底图（正常态）。 */
    public static final Identifier TEXTURE = Identifier.withDefaultNamespace("widget/button");
    /** 按钮底图（悬停态）。 */
    public static final Identifier TEXTURE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/button_highlighted");
    /** 按钮底图（禁用态）。 */
    public static final Identifier TEXTURE_DISABLED = Identifier.withDefaultNamespace("widget/button_disabled");

    /** 源图尺寸（九宫格切分依据）。 */
    private static final int SRC_WIDTH = 200;
    private static final int SRC_HEIGHT = 20;
    private static final int BORDER = 3;

    /** 鼠标左键编号（SDL3 1-based，见 {@code AbstractWidget#isValidClickButton}）。 */
    private static final int LEFT_BUTTON = 1;

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR_DISABLED = 0xFFA0A0A0;

    private final OnPress onPress;
    private Component message;
    private int x;
    private int y;
    private int width;
    private int height;
    /** 是否可用（禁用后变灰且不响应点击）。 */
    public boolean active = true;
    /** 是否可见（不可见时不绘制也不响应）。 */
    public boolean visible = true;
    private boolean focused;
    private float alpha = 1.0f;

    private Button(int x, int y, int width, int height, Component message, OnPress onPress) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.message = message;
        this.onPress = onPress;
    }

    public static Builder builder(Component message, OnPress onPress) {
        return new Builder(message, onPress);
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Component getMessage() {
        return message;
    }

    public void setMessage(Component message) {
        this.message = message;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public void setAlpha(float alpha) {
        this.alpha = alpha;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible
                && mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + height;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!visible || !active) {
            return false;
        }
        // MC 26.3 的鼠标按钮编号来自 SDL3，是 1-based（左 1 / 中 2 / 右 3），
        // 不是 GLFW 的 0/2/1。原版 AbstractWidget#isValidClickButton 的字节码就是
        // `button() == 1`（已用 javap 核对），这里必须一致，否则预览里点不动按钮。
        if (event.button() != LEFT_BUTTON) {
            return false;
        }
        if (!isMouseOver(event.x(), event.y())) {
            return false;
        }
        onPress.onPress(this);
        return true;
    }

    public void onPress(InputWithModifiers input) {
        onPress.onPress(this);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        boolean hovered = active && isMouseOver(mouseX, mouseY);
        Identifier texture = !active ? TEXTURE_DISABLED : (hovered ? TEXTURE_HIGHLIGHTED : TEXTURE);
        nineSlice(context, texture, x, y, width, height);

        Font font = Minecraft.getInstance().font;
        int textColor = active ? TEXT_COLOR : TEXT_COLOR_DISABLED;
        int textX = x + width / 2;
        int textY = y + (height - 8) / 2;
        context.centeredText(font, message, textX, textY, withAlpha(textColor, alpha));
    }

    /** 按九宫格把 200×20 的源图铺到 {@code w × h} 的目标矩形上。 */
    private static void nineSlice(GuiGraphicsExtractor context, Identifier texture,
                                  int x, int y, int w, int h) {
        // 目标端的边框厚度不能超过目标尺寸的一半，否则小按钮会画歪
        int borderX = Math.min(BORDER, w / 2);
        int borderY = Math.min(BORDER, h / 2);
        float u0 = 0f;
        float u1 = (float) BORDER / SRC_WIDTH;
        float u2 = (float) (SRC_WIDTH - BORDER) / SRC_WIDTH;
        float u3 = 1f;
        float v0 = 0f;
        float v1 = (float) BORDER / SRC_HEIGHT;
        float v2 = (float) (SRC_HEIGHT - BORDER) / SRC_HEIGHT;
        float v3 = 1f;

        int xs0 = x;
        int xs1 = x + borderX;
        int xs2 = x + w - borderX;
        int xs3 = x + w;
        int ys0 = y;
        int ys1 = y + borderY;
        int ys2 = y + h - borderY;
        int ys3 = y + h;

        int[] xSeg = {xs0, xs1, xs2, xs3};
        int[] ySeg = {ys0, ys1, ys2, ys3};
        float[] uSeg = {u0, u1, u2, u3};
        float[] vSeg = {v0, v1, v2, v3};

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int dx0 = xSeg[col];
                int dx1 = xSeg[col + 1];
                int dy0 = ySeg[row];
                int dy1 = ySeg[row + 1];
                if (dx1 <= dx0 || dy1 <= dy0) {
                    continue;
                }
                context.blit(texture, dx0, dy0, dx1, dy1,
                        uSeg[col], vSeg[row], uSeg[col + 1], vSeg[row + 1]);
            }
        }
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) ((color >>> 24) * alpha) & 0xFF;
        return (a << 24) | (color & 0xFFFFFF);
    }

    @Override
    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    public void updateNarration(NarrationElementOutput output) {
    }

    @Override
    public NarrationPriority narrationPriority() {
        return NarrationPriority.NONE;
    }

    /** 按钮点击回调。 */
    public interface OnPress {
        void onPress(Button button);
    }

    /** 按钮构造器（{@code Button.builder(...).bounds(...).build()} 与游戏内同一套写法）。 */
    public static class Builder {
        private final Component message;
        private final OnPress onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        Builder(Component message, OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public Button build() {
            return new Button(x, y, width, height, message, onPress);
        }
    }
}
