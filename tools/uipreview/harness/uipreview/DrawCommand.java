package uipreview;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 一次 GUI 绘制命令 —— 这是预览器的"中间表示"。
 *
 * <p>Minecraft 26.x 的 GUI 是<b>提取式</b>渲染：{@code Screen#extractRenderState} 拿到的
 * {@code GuiGraphicsExtractor} 并不立刻画像素，只是把"要画什么"写进渲染状态，真正上屏由
 * 渲染线程按状态列表执行。UI 预览器正是利用这一点：把 {@code GuiGraphicsExtractor}
 * 换成一个只做记录的替身，产出一串 {@link DrawCommand}，再用 Java2D 重放成 PNG。
 *
 * <p>因此预览图里的<b>坐标、颜色、裁剪、文字位置全部来自真实生产代码</b>，
 * 预览器只负责"把命令画出来"，不参与任何布局计算。
 *
 * <p>{@link Clip} 是命令发生时生效的裁剪矩形（{@code enableScissor} 的栈顶），
 * 渲染时直接用 Java2D 的 clip 实现。
 */
public sealed interface DrawCommand {

    /** 命令生效时的裁剪矩形；{@code null} 表示不裁剪。 */
    Clip clip();

    /** 实心矩形（{@code GuiGraphicsExtractor#fill}）。 */
    record Fill(int x0, int y0, int x1, int y1, int color, Clip clip) implements DrawCommand {
    }

    /** 渐变矩形（{@code fillGradient}），从上到下。 */
    record Gradient(int x0, int y0, int x1, int y1, int colorTop, int colorBottom, Clip clip)
            implements DrawCommand {
    }

    /** 贴图绘制（{@code blit}）。{@code u0/v0/u1/v1} 是 0..1 的纹理坐标。 */
    record Blit(Identifier texture, int x0, int y0, int x1, int y1,
                float u0, float v0, float u1, float v1, Clip clip) implements DrawCommand {
    }

    /**
     * 文本绘制。
     *
     * @param wrapWidth 大于 0 表示这是 {@code textWithWordWrap}，渲染时要按宽度折行
     * @param centered  是否为 {@code centeredText}（{@code x} 是中心点）
     * @param shadow    是否带 1px 右下阴影（游戏内默认 true）
     */
    record Text(String text, int x, int y, int color, boolean shadow, boolean centered,
                int wrapWidth, Clip clip) implements DrawCommand {
    }

    /** 待显示的 tooltip（{@code setTooltipForNextFrame}），预览时画成悬停提示框。 */
    record Tooltip(Component text, int x, int y, Clip clip) implements DrawCommand {
    }

    /** 裁剪矩形。 */
    record Clip(int x0, int y0, int x1, int y1) {
        public boolean contains(int x, int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }
}
