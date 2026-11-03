package net.minecraft.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import uipreview.DrawCommand;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * UI 预览器用的 {@code GuiGraphicsExtractor} 替身 —— <b>整个预览器的核心</b>。
 *
 * <p>游戏里的这个类会把绘制命令写进 {@code GuiRenderState}，交给渲染线程用 GPU 执行。
 * 这里换成写进一个 {@link #commands()} 列表，再由 {@code uipreview.Java2DRenderer}
 * 用纯 CPU 重放成图片。因为 {@code Screen#extractRenderState} 的调用约定完全没变，
 * 所以<b>生产代码一行都不用改</b>就能在无显卡环境里出图。
 *
 * <p>已实现的语义（与游戏内一致的部分）：
 * <ul>
 *   <li>{@code fill} / {@code outline} / {@code blit} 的坐标与颜色；</li>
 *   <li>{@code enableScissor} / {@code disableScissor} 的<b>栈</b>语义 —— 内层裁剪与外层求交；</li>
 *   <li>{@code text} 优先使用 {@link Component} 自带样式色（游戏内行为）；</li>
 *   <li>{@code text} 默认带阴影（游戏内 {@code text(font, text, x, y, color)} 就是带阴影的）。</li>
 * </ul>
 */
public class GuiGraphicsExtractor {

    private final List<DrawCommand> commands = new ArrayList<>();
    private final Deque<DrawCommand.Clip> scissorStack = new ArrayDeque<>();
    private final int guiWidth;
    private final int guiHeight;
    private DrawCommand.Tooltip pendingTooltip;

    /**
     * @param guiWidth  逻辑宽度（= 窗口像素宽 / GUI 缩放，即 {@code Screen#width}）
     * @param guiHeight 逻辑高度
     */
    public GuiGraphicsExtractor(int guiWidth, int guiHeight) {
        this.guiWidth = guiWidth;
        this.guiHeight = guiHeight;
    }

    public int guiWidth() {
        return guiWidth;
    }

    public int guiHeight() {
        return guiHeight;
    }

    /** 录制下来的全部命令，按调用顺序。 */
    public List<DrawCommand> commands() {
        return commands;
    }

    /** 本帧待显示的 tooltip（{@code setTooltipForNextFrame} 写入），没有则 {@code null}。 */
    public DrawCommand.Tooltip pendingTooltip() {
        return pendingTooltip;
    }

    /** 清空本帧状态（每帧渲染前由预览器调用）。 */
    public void reset() {
        commands.clear();
        scissorStack.clear();
        pendingTooltip = null;
    }

    // ==================== 裁剪 ====================

    private DrawCommand.Clip currentClip() {
        return scissorStack.peek();
    }

    public void enableScissor(int x0, int y0, int x1, int y1) {
        int nx0 = Math.min(x0, x1);
        int ny0 = Math.min(y0, y1);
        int nx1 = Math.max(x0, x1);
        int ny1 = Math.max(y0, y1);
        DrawCommand.Clip parent = currentClip();
        if (parent != null) {
            // 内层裁剪与父裁剪求交（游戏内 scissor 是绝对坐标，直接覆盖；这里按可见结果求交更稳）
            nx0 = Math.max(nx0, parent.x0());
            ny0 = Math.max(ny0, parent.y0());
            nx1 = Math.min(nx1, parent.x1());
            ny1 = Math.min(ny1, parent.y1());
        }
        scissorStack.push(new DrawCommand.Clip(nx0, ny0, Math.max(nx0, nx1), Math.max(ny0, ny1)));
    }

    public void disableScissor() {
        if (!scissorStack.isEmpty()) {
            scissorStack.pop();
        }
    }

    public boolean containsPointInScissor(int x, int y) {
        DrawCommand.Clip clip = currentClip();
        return clip == null || clip.contains(x, y);
    }

    // ==================== 矩形 ====================

    public void fill(int x0, int y0, int x1, int y1, int color) {
        if (x0 == x1 || y0 == y1) {
            return;
        }
        commands.add(new DrawCommand.Fill(Math.min(x0, x1), Math.min(y0, y1),
                Math.max(x0, x1), Math.max(y0, y1), color, currentClip()));
    }

    public void fillGradient(int x0, int y0, int x1, int y1, int colorTop, int colorBottom) {
        if (x0 == x1 || y0 == y1) {
            return;
        }
        commands.add(new DrawCommand.Gradient(Math.min(x0, x1), Math.min(y0, y1),
                Math.max(x0, x1), Math.max(y0, y1), colorTop, colorBottom, currentClip()));
    }

    public void horizontalLine(int x0, int x1, int y, int color) {
        fill(x0, y, x1, y + 1, color);
    }

    public void verticalLine(int x, int y0, int y1, int color) {
        fill(x, y0, x + 1, y1, color);
    }

    /**
     * 画 1px 边框。
     *
     * <p><b>参数是 (x, y, width, height)，不是 (x0, y0, x1, y1)</b> —— 这一点与 {@link #fill}
     * 不同，是核对真实调用点确认的：界面里写的是
     * {@code outline(panelX, panelY, panelWidth, panelHeight, color)}、
     * {@code outline(tab.x(), tab.y(), tab.width(), tab.height(), color)}。
     * 早先按 (x0,y0,x1,y1) 实现会让所有边框向右下角溢出（面板边框跑到屏幕外、
     * tab 边框画成一条斜线），是预览器最容易踩的语义坑之一。
     */
    public void outline(int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int x1 = x + width;
        int y1 = y + height;
        fill(x, y, x1, y + 1, color);
        fill(x, y1 - 1, x1, y1, color);
        fill(x, y + 1, x + 1, y1 - 1, color);
        fill(x1 - 1, y + 1, x1, y1 - 1, color);
    }

    // ==================== 贴图 ====================

    public void blit(Identifier texture, int x0, int y0, int x1, int y1,
                     float u0, float v0, float u1, float v1) {
        commands.add(new DrawCommand.Blit(texture, x0, y0, x1, y1, u0, v0, u1, v1, currentClip()));
    }

    // ==================== 文本 ====================

    public void text(Font font, String text, int x, int y, int color) {
        text(font, text, x, y, color, true);
    }

    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        if (text == null || text.isEmpty()) {
            return;
        }
        commands.add(new DrawCommand.Text(text, x, y, color, shadow, false, 0, currentClip()));
    }

    public void text(Font font, Component text, int x, int y, int color) {
        text(font, text, x, y, color, true);
    }

    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (text == null) {
            return;
        }
        String plain = text.getString();
        if (plain.isEmpty()) {
            return;
        }
        // 游戏内：Component 自带的样式色优先于调用方传入的颜色
        Integer styleColor = text.getStyle() == null ? null : text.getStyle().getColor();
        int resolved = styleColor == null ? color : (0xFF000000 | (styleColor & 0xFFFFFF));
        commands.add(new DrawCommand.Text(plain, x, y, resolved, shadow, false, 0, currentClip()));
    }

    public void centeredText(Font font, Component text, int centerX, int y, int color) {
        if (text == null) {
            return;
        }
        String plain = text.getString();
        if (plain.isEmpty()) {
            return;
        }
        Integer styleColor = text.getStyle() == null ? null : text.getStyle().getColor();
        int resolved = styleColor == null ? color : (0xFF000000 | (styleColor & 0xFFFFFF));
        commands.add(new DrawCommand.Text(plain, centerX, y, resolved, true, true, 0, currentClip()));
    }

    public void centeredText(Font font, String text, int centerX, int y, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        commands.add(new DrawCommand.Text(text, centerX, y, color, true, true, 0, currentClip()));
    }

    /**
     * 按宽度折行绘制。
     *
     * @return 实际占用高度（像素）—— 游戏内返回同样的值，调用方常拿它做后续布局
     */
    public int textWithWordWrap(Font font, FormattedText text, int x, int y, int maxWidth, int color) {
        if (text == null) {
            return font.lineHeight;
        }
        String plain = text.getString();
        if (plain.isEmpty()) {
            return font.lineHeight;
        }
        commands.add(new DrawCommand.Text(plain, x, y, color, true, false, maxWidth, currentClip()));
        return TextLayout.wrap(plain, maxWidth, font).size() * font.lineHeight;
    }

    // ==================== Tooltip ====================

    public void setTooltipForNextFrame(Component text, int mouseX, int mouseY) {
        if (text == null) {
            return;
        }
        pendingTooltip = new DrawCommand.Tooltip(text, mouseX, mouseY, currentClip());
    }
}
