package net.minecraft.client.gui.components;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

/**
 * UI 预览器用的 {@code net.minecraft.client.gui.components.EditBox} 替身。
 *
 * <p>这是预览器里<b>唯一需要真正实现输入逻辑</b>的控件：搜索框打字会触发
 * {@code setResponder} 回调，进而让衣柜列表重新过滤。如果这里只是空壳，
 * 预览器就只能看静态界面，没法验证"搜索 → 过滤 → 空状态提示"这条链。
 *
 * <p>已实现：文本增删、光标位置、最大长度、占位提示（hint）、焦点、
 * 背景九宫格（{@code widget/text_field.png}，聚焦时换 highlighted）。
 */
public class EditBox implements Renderable, GuiEventListener, NarratableEntry, AlphaAware {

    /** 输入框底图。 */
    public static final Identifier TEXTURE = Identifier.withDefaultNamespace("widget/text_field");
    /** 输入框底图（聚焦态）。 */
    public static final Identifier TEXTURE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/text_field_highlighted");

    private static final int SRC_WIDTH = 200;
    private static final int SRC_HEIGHT = 20;
    private static final int BORDER = 1;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int CURSOR_COLOR = 0xFFD0D0D0;
    private static final int TEXT_INSET = 4;
    /** 鼠标左键编号（SDL3 1-based）。 */
    private static final int LEFT_BUTTON = 1;

    private final Font font;
    private int x;
    private int y;
    private int width;
    private int height;
    private String value = "";
    private String hint;
    private int maxLength = 32;
    private Consumer<String> responder;
    private boolean focused;
    private boolean visible = true;
    private float alpha = 1.0f;
    /** 光标位置（0..value.length()）。 */
    private int cursor;

    public EditBox(Font font, int x, int y, int width, int height, Component message) {
        this.font = font;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.hint = message == null ? "" : message.getString();
    }

    public EditBox(Font font, int x, int y, Component message) {
        this(font, x, y, 200, 20, message);
    }

    public EditBox(Font font, Component message) {
        this(font, 0, 0, 200, 20, message);
    }

    // ==================== 值 / 配置 ====================

    public void setResponder(Consumer<String> responder) {
        this.responder = responder;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = Math.max(0, maxLength);
    }

    public void setHint(Component hint) {
        this.hint = hint == null ? "" : hint.getString();
    }

    public void setValue(String value) {
        this.value = value == null ? "" : value;
        if (this.value.length() > maxLength) {
            this.value = this.value.substring(0, maxLength);
        }
        this.cursor = this.value.length();
    }

    public String getValue() {
        return value;
    }

    public void setX(int x) {
        this.x = x;
    }

    public void setY(int y) {
        this.y = y;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isEditable() {
        return true;
    }

    @Override
    public void setAlpha(float alpha) {
        this.alpha = alpha;
    }

    // ==================== 输入 ====================

    public void insertText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String next = value.substring(0, cursor) + text + value.substring(cursor);
        if (next.length() > maxLength) {
            next = next.substring(0, maxLength);
        }
        value = next;
        cursor = Math.min(cursor + text.length(), value.length());
        notifyResponder();
    }

    public void deleteChars(int count) {
        if (count <= 0) {
            return;
        }
        int from = Math.max(0, cursor - count);
        value = value.substring(0, from) + value.substring(cursor);
        cursor = from;
        notifyResponder();
    }

    public void moveCursorTo(int position, boolean select) {
        cursor = Math.max(0, Math.min(position, value.length()));
    }

    public void setCursorPosition(int position) {
        moveCursorTo(position, false);
    }

    public void moveCursorToStart(boolean select) {
        cursor = 0;
    }

    public void moveCursorToEnd(boolean select) {
        cursor = value.length();
    }

    private void notifyResponder() {
        if (responder != null) {
            responder.accept(value);
        }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!focused || !visible) {
            return false;
        }
        int cp = event.codepoint();
        if (cp < 32 || cp == 127) {
            return false;
        }
        insertText(new String(Character.toChars(cp)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!focused || !visible) {
            return false;
        }
        return switch (event.key()) {
            case 259 -> { // GLFW_KEY_BACKSPACE
                deleteChars(1);
                yield true;
            }
            case 261 -> { // GLFW_KEY_DELETE
                if (cursor < value.length()) {
                    value = value.substring(0, cursor) + value.substring(cursor + 1);
                    notifyResponder();
                }
                yield true;
            }
            case 263 -> { // GLFW_KEY_LEFT
                cursor = Math.max(0, cursor - 1);
                yield true;
            }
            case 262 -> { // GLFW_KEY_RIGHT
                cursor = Math.min(value.length(), cursor + 1);
                yield true;
            }
            case 268 -> { // GLFW_KEY_HOME
                cursor = 0;
                yield true;
            }
            case 269 -> { // GLFW_KEY_END
                cursor = value.length();
                yield true;
            }
            default -> false;
        };
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!visible) {
            return false;
        }
        // 左键编号 = 1（SDL3 1-based，与 AbstractWidget#isValidClickButton 一致）
        if (event.button() == LEFT_BUTTON && isMouseOver(event.x(), event.y())) {
            setFocused(true);
            return true;
        }
        setFocused(false);
        return false;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible
                && mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + height;
    }

    @Override
    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    // ==================== 渲染 ====================

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        nineSlice(context, focused ? TEXTURE_HIGHLIGHTED : TEXTURE, x, y, width, height);

        int textY = y + (height - 8) / 2;
        int textX = x + TEXT_INSET;
        int innerWidth = Math.max(0, width - TEXT_INSET * 2);
        if (value.isEmpty()) {
            if (hint != null && !hint.isEmpty()) {
                // hint 用样式色（SearchField 里指定了 #888888），与游戏内一致
                context.text(font, hint, textX, textY, withAlpha(0xFF888888, alpha), false);
            }
        } else {
            // 简化处理：文本超出宽度时从左边裁掉（游戏里是滚动显示光标附近）
            String shown = value;
            int cursorPixels = font.width(value.substring(0, Math.min(cursor, value.length())));
            if (cursorPixels > innerWidth) {
                int skip = 0;
                while (skip < shown.length() && font.width(shown.substring(skip)) > innerWidth) {
                    skip++;
                }
                shown = shown.substring(skip);
            } else if (font.width(shown) > innerWidth) {
                shown = font.plainSubstrByWidth(shown, innerWidth);
            }
            context.text(font, shown, textX, textY, withAlpha(TEXT_COLOR, alpha), false);
        }

        // 光标：聚焦时画一根 1px 竖线（游戏里是闪烁的，预览取"亮着"的那一帧）
        if (focused) {
            int cursorPixels = font.width(value.substring(0, Math.min(cursor, value.length())));
            int cursorX = textX + Math.min(cursorPixels, innerWidth);
            context.fill(cursorX, textY - 1, cursorX + 1, textY + 9, withAlpha(CURSOR_COLOR, alpha));
        }
    }

    private static void nineSlice(GuiGraphicsExtractor context, Identifier texture,
                                  int x, int y, int w, int h) {
        int borderX = Math.min(BORDER, w / 2);
        int borderY = Math.min(BORDER, h / 2);
        float u1 = (float) BORDER / SRC_WIDTH;
        float u2 = (float) (SRC_WIDTH - BORDER) / SRC_WIDTH;
        float v1 = (float) BORDER / SRC_HEIGHT;
        float v2 = (float) (SRC_HEIGHT - BORDER) / SRC_HEIGHT;

        int[] xSeg = {x, x + borderX, x + w - borderX, x + w};
        int[] ySeg = {y, y + borderY, y + h - borderY, y + h};
        float[] uSeg = {0f, u1, u2, 1f};
        float[] vSeg = {0f, v1, v2, 1f};

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if (xSeg[col + 1] <= xSeg[col] || ySeg[row + 1] <= ySeg[row]) {
                    continue;
                }
                context.blit(texture, xSeg[col], ySeg[row], xSeg[col + 1], ySeg[row + 1],
                        uSeg[col], vSeg[row], uSeg[col + 1], vSeg[row + 1]);
            }
        }
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) ((color >>> 24) * alpha) & 0xFF;
        return (a << 24) | (color & 0xFFFFFF);
    }

    @Override
    public void updateNarration(NarrationElementOutput output) {
    }

    @Override
    public NarrationPriority narrationPriority() {
        return focused ? NarrationPriority.FOCUSED : NarrationPriority.NONE;
    }
}
