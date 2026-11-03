package uipreview;

import net.minecraft.client.gui.font.McAsciiFont;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 把 {@link DrawCommand} 列表重放成 {@link BufferedImage} —— 预览器的"软件渲染后端"。
 *
 * <p>和 {@code tools/preview/SoftRasterizer}（3D 时装预览）是同一思路：<b>不用显卡、不启客户端</b>，
 * 纯 JDK 画图。区别在于这里是 2D：命令里的坐标、颜色、裁剪、文字位置全部来自
 * 真实 {@code Screen#extractRenderState}，渲染器只负责"照单画出来"。
 *
 * <p><b>文字为什么要分两套：</b>
 * <ul>
 *   <li>ASCII 用 {@link McAsciiFont} 的原始位图 —— 与游戏内<b>逐像素相同</b>，
 *       且宽度精确（布局全依赖它，差 1px 就会错位）；</li>
 *   <li>非 ASCII（中文 / emoji / 特殊符号）用系统 CJK 字体按 9px 步进绘制 ——
 *       游戏里这些字符走 unifont（16px 位图 × 0.5 缩放），宽度同样是 9px，
 *       用 AWT 渲染字形观感接近且清晰可读。</li>
 * </ul>
 *
 * <p><b>阴影</b>与游戏一致：先在 {@code (x+1, y+1)} 画一遍颜色除以 4 的暗色，
 * 再在 {@code (x, y)} 画原色。
 */
public final class Java2DRenderer {

    /** 放大倍数（1 = 逻辑像素 1:1）。预览图默认放大 2~3 倍方便看细节。 */
    private final int scale;

    /** CJK / 符号回退字体。找不到时退化成默认字体（仍然能出图，只是字形不同）。 */
    private final Font fallbackFont;

    public Java2DRenderer(int scale) {
        this.scale = Math.max(1, scale);
        this.fallbackFont = pickFallbackFont();
    }

    /**
     * 选一个能画中文的字体。
     *
     * <p>按常见 Linux 发行版的 Noto CJK / 文泉驿顺序找；都没有时用逻辑字体
     * {@code SansSerif}（JDK 会自动 fontconfig 回退，通常也能出中文）。
     */
    private Font pickFallbackFont() {
        String[] candidates = {
                "Noto Sans CJK SC", "Noto Sans CJK TC", "Noto Sans SC",
                "WenQuanYi Zen Hei", "WenQuanYi Micro Hei", "Source Han Sans SC",
                "Noto Serif CJK SC", "Droid Sans Fallback"
        };
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String candidate : candidates) {
            for (String name : available) {
                if (name.equalsIgnoreCase(candidate)) {
                    return new Font(name, Font.PLAIN, 9 * scale);
                }
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 9 * scale);
    }

    /** 实际用到的回退字体名（写进报告，方便判断中文渲染是否走了预期字体）。 */
    public String fallbackFontName() {
        return fallbackFont.getFamily();
    }

    /**
     * 渲染一帧。
     *
     * @param commands 录制到的绘制命令
     * @param width    逻辑宽（{@code Screen#width}）
     * @param height   逻辑高（{@code Screen#height}）
     * @return 放大 {@link #scale} 倍后的图片
     */
    public BufferedImage render(List<DrawCommand> commands, int width, int height) {
        BufferedImage image = new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            configure(g);
            // 背景：游戏里界面底下是"模糊的世界"，预览器没有世界可画，用中性深色兜底
            g.setColor(new Color(0xFF101018, true));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            for (DrawCommand command : commands) {
                applyClip(g, command.clip());
                switch (command) {
                    case DrawCommand.Fill fill -> drawFill(g, fill);
                    case DrawCommand.Gradient gradient -> drawGradient(g, gradient);
                    case DrawCommand.Blit blit -> drawBlit(g, blit);
                    case DrawCommand.Text text -> drawTextCommand(g, text);
                    case DrawCommand.Tooltip tooltip -> {
                        // tooltip 由 UiPreview 在所有命令之后单独画（它要盖在最上层）
                    }
                }
            }
            g.setClip(null);
            for (DrawCommand command : commands) {
                if (command instanceof DrawCommand.Tooltip tooltip) {
                    drawTooltip(g, tooltip);
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    // ==================== 基础图元 ====================

    private void applyClip(Graphics2D g, DrawCommand.Clip clip) {
        if (clip == null) {
            g.setClip(null);
        } else {
            g.setClip(clip.x0() * scale, clip.y0() * scale,
                    (clip.x1() - clip.x0()) * scale, (clip.y1() - clip.y0()) * scale);
        }
    }

    private void drawFill(Graphics2D g, DrawCommand.Fill fill) {
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new Color(fill.color(), true));
        g.fillRect(fill.x0() * scale, fill.y0() * scale,
                (fill.x1() - fill.x0()) * scale, (fill.y1() - fill.y0()) * scale);
    }

    private void drawGradient(Graphics2D g, DrawCommand.Gradient gradient) {
        g.setComposite(AlphaComposite.SrcOver);
        g.setPaint(new java.awt.GradientPaint(
                gradient.x0() * scale, gradient.y0() * scale, new Color(gradient.colorTop(), true),
                gradient.x0() * scale, gradient.y1() * scale, new Color(gradient.colorBottom(), true)));
        g.fillRect(gradient.x0() * scale, gradient.y0() * scale,
                (gradient.x1() - gradient.x0()) * scale, (gradient.y1() - gradient.y0()) * scale);
    }

    private void drawBlit(Graphics2D g, DrawCommand.Blit blit) {
        BufferedImage texture = PreviewTextures.get(blit.texture());
        int dx0 = blit.x0() * scale;
        int dy0 = blit.y0() * scale;
        int dw = (blit.x1() - blit.x0()) * scale;
        int dh = (blit.y1() - blit.y0()) * scale;
        if (texture == null) {
            drawMissingTexture(g, dx0, dy0, dw, dh);
            return;
        }
        int sx0 = Math.round(blit.u0() * texture.getWidth());
        int sy0 = Math.round(blit.v0() * texture.getHeight());
        int sx1 = Math.round(blit.u1() * texture.getWidth());
        int sy1 = Math.round(blit.v1() * texture.getHeight());
        if (sx1 <= sx0 || sy1 <= sy0) {
            return;
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(texture, dx0, dy0, dx0 + dw, dy0 + dh, sx0, sy0, sx1, sy1, null);
    }

    /** 缺失纹理：洋红/黑棋盘格（与游戏内的缺失贴图同款，便于一眼发现）。 */
    private void drawMissingTexture(Graphics2D g, int x, int y, int w, int h) {
        int cell = Math.max(2, scale * 2);
        g.setComposite(AlphaComposite.SrcOver);
        for (int cy = 0; cy < h; cy += cell) {
            for (int cx = 0; cx < w; cx += cell) {
                boolean magenta = ((cx / cell) + (cy / cell)) % 2 == 0;
                g.setColor(magenta ? new Color(0xFFFF00FF, true) : new Color(0xFF000000, true));
                g.fillRect(x + cx, y + cy, Math.min(cell, w - cx), Math.min(cell, h - cy));
            }
        }
    }

    // ==================== 文本 ====================

    private void drawTextCommand(Graphics2D g, DrawCommand.Text text) {
        if (text.wrapWidth() > 0) {
            List<String> lines = net.minecraft.client.gui.TextLayout.wrap(
                    text.text(), text.wrapWidth(), SharedFont.INSTANCE);
            int lineY = text.y();
            for (String line : lines) {
                drawTextLine(g, line, text.x(), lineY, text.color(), text.shadow());
                lineY += SharedFont.INSTANCE.lineHeight;
            }
            return;
        }
        int x = text.x();
        if (text.centered()) {
            x = text.x() - SharedFont.INSTANCE.width(text.text()) / 2;
        }
        drawTextLine(g, text.text(), x, text.y(), text.color(), text.shadow());
    }

    private void drawTextLine(Graphics2D g, String line, int x, int y, int color, boolean shadow) {
        if (shadow) {
            drawTextRaw(g, line, x + 1, y + 1, shadowColor(color));
        }
        drawTextRaw(g, line, x, y, color);
    }

    /** 与游戏一致的阴影色：RGB 各取高 6 位后右移 2（即除以 4），保留 alpha。 */
    private static int shadowColor(int color) {
        return ((color & 0xFCFCFC) >> 2) | (color & 0xFF000000);
    }

    /**
     * 逐字符绘制：ASCII 走内嵌位图（与游戏逐像素一致），其余走 AWT 字体。
     *
     * <p>{@code y} 是文本<b>顶部</b>坐标（游戏内 {@code drawString} 的语义），
     * ASCII 位图从第 0 行开始画；AWT 字形用 ascent 对齐到同一基线。
     */
    private void drawTextRaw(Graphics2D g, String text, int x, int y, int color) {
        g.setComposite(AlphaComposite.SrcOver);
        int penX = x;
        int current = color;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            // 原版格式码：§ 后面跟一个字符。游戏里由 Font 解析成颜色/样式，
            // 预览器不解析的话会原样画出 "§a开启" 这种乱码（设置界面的开关文案就是）。
            if ((c == '§' || c == '\u00a7') && i + 1 < text.length()) {
                Integer mapped = LEGACY_COLORS.get(Character.toLowerCase(text.charAt(i + 1)));
                if (mapped != null) {
                    current = 0xFF000000 | mapped;
                } else if (Character.toLowerCase(text.charAt(i + 1)) == 'r') {
                    current = color;
                }
                // 其余格式码（l 粗体 / n 下划线 / o 斜体 / m 删除线 / k 乱码）预览器不实现，
                // 但必须跳过，否则 § 和字母都会被画出来
                i += 2;
                continue;
            }
            int cp = text.codePointAt(i);
            Color awtColor = new Color(current, true);
            g.setColor(awtColor);
            if (cp < 256 && McAsciiFont.ADVANCE[cp] > 0) {
                drawAsciiGlyph(g, cp, penX, y, awtColor);
                penX += McAsciiFont.ADVANCE[cp];
            } else if (cp < 32) {
                // 控制字符不画
            } else {
                drawFallbackGlyph(g, cp, penX, y, awtColor);
                penX += 9;
            }
            i += Character.charCount(cp);
        }
    }

    /** 原版 16 色（§0–§f）。 */
    private static final java.util.Map<Character, Integer> LEGACY_COLORS = java.util.Map.ofEntries(
            java.util.Map.entry('0', 0x000000), java.util.Map.entry('1', 0x0000AA),
            java.util.Map.entry('2', 0x00AA00), java.util.Map.entry('3', 0x00AAAA),
            java.util.Map.entry('4', 0xAA0000), java.util.Map.entry('5', 0xAA00AA),
            java.util.Map.entry('6', 0xFFAA00), java.util.Map.entry('7', 0xAAAAAA),
            java.util.Map.entry('8', 0x555555), java.util.Map.entry('9', 0x5555FF),
            java.util.Map.entry('a', 0x55FF55), java.util.Map.entry('b', 0x55FFFF),
            java.util.Map.entry('c', 0xFF5555), java.util.Map.entry('d', 0xFF55FF),
            java.util.Map.entry('e', 0xFFFF55), java.util.Map.entry('f', 0xFFFFFF));

    /** 画一个 ASCII 字形：把 8×8 位图按 scale 放大成方块。 */
    private void drawAsciiGlyph(Graphics2D g, int codepoint, int x, int y, Color color) {
        g.setColor(color);
        for (int row = 0; row < McAsciiFont.ROWS; row++) {
            int bits = McAsciiFont.row(codepoint, row);
            if (bits == 0) {
                continue;
            }
            for (int col = 0; col < McAsciiFont.CELL; col++) {
                if ((bits & (0x80 >> col)) == 0) {
                    continue;
                }
                g.fillRect((x + col) * scale, (y + row) * scale, scale, scale);
            }
        }
    }

    /**
     * 画一个非 ASCII 字形（中文 / emoji / 特殊符号），步进固定 9px。
     *
     * <p><b>为什么不能直接用 {@code drawString} + ascent 对齐：</b>
     * AWT 字体的 ascent 通常比 MC 的 8px 字形高得多（18px 的 Noto CJK ascent ≈ 17px，
     * 而行距只有 10px），直接按 ascent 摆基线会让字形整体下沉、相邻两行<b>视觉重叠</b>。
     * 这里改用 {@link TextLayout} 取字形自身的视觉边界（{@code getBounds()}），
     * 把边界<b>顶边</b>对齐到 {@code y}、左边对齐到 {@code x} —— 与游戏内
     * "文字顶边 = y" 的语义一致，也就消除了行间压字。
     *
     * <p>字形高度超过 8px 时按比例压到 8px 以内（游戏内 unifont 同样是 16px 位图缩到 8px）。
     */
    private void drawFallbackGlyph(Graphics2D g, int codepoint, int x, int y, Color color) {
        String glyph = new String(Character.toChars(codepoint));
        g.setFont(fallbackFont);
        g.setColor(color);
        FontRenderContext frc = g.getFontRenderContext();
        TextLayout layout = new TextLayout(glyph, fallbackFont, frc);
        Rectangle2D bounds = layout.getBounds();
        if (bounds.isEmpty()) {
            // 字体里没有这个字形（emoji 等）：画一个空心方框提示"预览器缺字形"，
            // 而不是静默留白 —— 这样开发者能分辨"界面漏字"和"预览器缺字体"。
            drawMissingGlyphBox(g, x, y, color);
            return;
        }
        double maxHeight = 8.0 * scale;
        double factor = bounds.getHeight() > maxHeight ? maxHeight / bounds.getHeight() : 1.0;
        AffineTransform previous = g.getTransform();
        try {
            // 平移：让字形左边界落在 x、顶边落在 y
            double tx = x * scale - bounds.getX();
            double ty = y * scale - bounds.getY();
            if (factor != 1.0) {
                g.translate(tx, ty);
                g.scale(factor, factor);
                layout.draw(g, 0f, 0f);
            } else {
                layout.draw(g, (float) tx, (float) ty);
            }
        } finally {
            g.setTransform(previous);
        }
    }

    /** 缺字形提示：1px 空心方框，尺寸取 8×8（与字符格一致）。 */
    private void drawMissingGlyphBox(Graphics2D g, int x, int y, Color color) {
        int px = x * scale;
        int py = y * scale;
        int size = 7 * scale;
        g.setColor(color);
        g.fillRect(px, py, size, scale);
        g.fillRect(px, py + size - scale, size, scale);
        g.fillRect(px, py, scale, size);
        g.fillRect(px + size - scale, py, scale, size);
    }

    // ==================== Tooltip ====================

    private void drawTooltip(Graphics2D g, DrawCommand.Tooltip tooltip) {
        String text = tooltip.text().getString();
        if (text.isEmpty()) {
            return;
        }
        int padding = 3;
        int textWidth = SharedFont.INSTANCE.width(text);
        int width = textWidth + padding * 2;
        int height = SharedFont.INSTANCE.lineHeight + padding * 2 - 2;
        int x = tooltip.x() + 12;
        int y = tooltip.y() - 12;
        g.setClip(null);
        g.setComposite(AlphaComposite.SrcOver);
        // 游戏内 tooltip：深紫外框 + 内层渐变底
        g.setColor(new Color(0xF0100010, true));
        g.fillRect(x * scale - scale, y * scale - scale, width * scale + 2 * scale, height * scale + 2 * scale);
        g.setColor(new Color(0xFF2D2D44, true));
        g.fillRect(x * scale, y * scale, width * scale, height * scale);
        drawTextLine(g, text, x + padding, y + padding, 0xFFFFFFFF, false);
    }

    /** 供渲染器做宽度/折行计算用的字体单例（与 {@code Minecraft.getInstance().font} 等价）。 */
    public static final class SharedFont {
        public static final net.minecraft.client.gui.Font INSTANCE = new net.minecraft.client.gui.Font();

        private SharedFont() {
        }
    }
}
