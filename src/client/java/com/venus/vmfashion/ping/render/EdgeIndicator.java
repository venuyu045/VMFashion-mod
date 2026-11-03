package com.venus.vmfashion.ping.render;

import com.venus.vmfashion.config.PingSettings;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 屏幕边缘方向指示器：标点在屏幕外（或跑到相机背后）时，在屏幕边缘画一个箭头 + 距离。
 *
 * <p>为什么需要它：标点最常用的场景是"队友在视野外标了个点"，如果只在屏幕内画标记，
 * 玩家根本不知道该往哪转头。上游 Ping Wheel 用 {@code DirectionIndicatorRenderer} 做同一件事，
 * 这里照抄思路并补了三点：
 * <ol>
 *   <li><b>相机背后</b>的标点：投影坐标是镜像的，方向向量要取反，否则箭头指向反方向；</li>
 *   <li><b>堆叠错开</b>：同一方向有多个标点时，箭头沿边缘切线错开（见
 *       {@link EdgeIndicatorMath#assignSlots}），而不是叠成一坨；</li>
 *   <li><b>距离数字</b>：箭头内侧跟一行距离，玩家不用打开地图就知道还有多远。</li>
 * </ol>
 *
 * <p>几何计算全部在纯逻辑类 {@link EdgeIndicatorMath} 里（可单测），这里只负责画。
 * 安全区是屏幕四边各内缩 {@link PingSettings.UI#EDGE_INDICATOR_MARGIN} 像素 ——
 * 避开热键栏、血量、聊天栏。
 *
 * <p>合订版适配：UI 常量从原 ping {@code ModConfig.UI} 迁到 {@link PingSettings.UI}（值不变），
 * 包名改为 {@code com.venus.vmfashion.ping.render}，绘制逻辑不变。
 */
public final class EdgeIndicator {

    /** 箭头三角形的半径（像素）。 */
    private static final int ARROW_RADIUS = PingSettings.UI.EDGE_INDICATOR_SIZE / 2 + 1;
    /** 相邻堆叠箭头之间的间距（像素）。 */
    private static final float STACK_SPACING = PingSettings.UI.EDGE_INDICATOR_SIZE + 8f;
    /** 箭头往屏幕中心方向的偏移（避免箭头一半压在边框外）。 */
    private static final float INWARD_OFFSET = 12f;
    /** 距离文字相对箭头的内缩（像素）。 */
    private static final float LABEL_INWARD = 26f;
    /** 箭头底衬色（半透明黑，保证在亮背景上也看得清）。 */
    private static final int BACKDROP = 0x66000000;

    /** 安全区边界（每次渲染前由 {@link #prepare} 更新）。 */
    private static float safeLeft;
    private static float safeTop;
    private static float safeRight;
    private static float safeBottom;

    private EdgeIndicator() {
    }

    /**
     * 每帧调用一次：按当前视口算出安全区。
     *
     * @param width  视口宽（GUI 逻辑像素）
     * @param height 视口高
     */
    public static void prepare(int width, int height) {
        int margin = PingSettings.UI.EDGE_INDICATOR_MARGIN;
        // 窗口特别小（或 GUI 缩放很大）时自动缩小边距，否则安全区会翻过来
        margin = Math.min(margin, Math.max(2, Math.min(width, height) / 8));
        safeLeft = margin;
        safeTop = margin;
        safeRight = Math.max(safeLeft + 1, width - margin);
        safeBottom = Math.max(safeTop + 1, height - margin);
    }

    /** 安全区左边界的 x（单测/调试用）。 */
    public static float safeLeft() {
        return safeLeft;
    }

    /** 安全区上边界的 y。 */
    public static float safeTop() {
        return safeTop;
    }

    /** 安全区右边界的 x。 */
    public static float safeRight() {
        return safeRight;
    }

    /** 安全区下边界的 y。 */
    public static float safeBottom() {
        return safeBottom;
    }

    /** 安全区中心 x（方向向量的原点）。 */
    public static float centerX() {
        return (safeLeft + safeRight) * 0.5f;
    }

    /** 安全区中心 y。 */
    public static float centerY() {
        return (safeTop + safeBottom) * 0.5f;
    }

    /**
     * 画一个边缘箭头 + 距离。
     *
     * @param graphics    绘制上下文
     * @param font        字体
     * @param directionX  从屏幕中心指向标点的方向（x，已处理相机背后取反）
     * @param directionY  同上（y，屏幕坐标向下为正）
     * @param color       箭头颜色（ARGB，alpha 已乘好）
     * @param distance    距离文字（{@code null} = 不画，配置关掉时传 null）
     * @param textColor   距离文字颜色（ARGB）
     * @param slot        堆叠槽位（0 = 不偏移，±1/±2 = 沿边缘错开）
     */
    public static void draw(GuiGraphicsExtractor graphics, Font font,
                            float directionX, float directionY,
                            int color, String distance, int textColor, int slot) {
        float angle = EdgeIndicatorMath.angleOf(directionX, directionY);
        float[] edge = EdgeIndicatorMath.rectIntersection(angle, safeLeft, safeTop, safeRight, safeBottom);

        // 沿边缘切线错开（同一方向的多个箭头不再重叠）
        float offset = EdgeIndicatorMath.slotOffset(slot, STACK_SPACING);
        float x = edge[0] + EdgeIndicatorMath.tangentX(angle) * offset;
        float y = edge[1] + EdgeIndicatorMath.tangentY(angle) * offset;
        // 再往屏幕中心挪一点，让箭头的"根部"贴在安全区边上
        x -= (float) Math.cos(angle) * INWARD_OFFSET * 0.5f;
        y -= (float) Math.sin(angle) * INWARD_OFFSET * 0.5f;

        drawArrow(graphics, x, y, angle, color);

        if (distance != null && !distance.isEmpty()) {
            float labelX = x - (float) Math.cos(angle) * LABEL_INWARD;
            float labelY = y - (float) Math.sin(angle) * LABEL_INWARD;
            int textWidth = font.width(distance);
            int drawX = Math.round(labelX - textWidth * 0.5f);
            int drawY = Math.round(labelY - font.lineHeight * 0.5f);
            // 底衬：屏幕边缘经常压着草地/天空，没有底衬数字看不清
            graphics.fill(drawX - 2, drawY - 1, drawX + textWidth + 2, drawY + font.lineHeight, BACKDROP);
            graphics.text(font, distance, drawX, drawY, textColor, false);
        }
    }

    /**
     * 画一个指向 {@code angle} 方向的三角形箭头（用水平线段拼，不依赖贴图）。
     *
     * <p>形状：尖端在 +x 方向，底边在 -x 方向。先画一圈"大一号"的深色底，
     * 再画彩色本体 —— 这样在任何背景上都有轮廓。
     */
    private static void drawArrow(GuiGraphicsExtractor graphics, float x, float y, float angle, int color) {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.rotate(angle);
        try {
            int radius = ARROW_RADIUS;
            // 深色轮廓（大一号）
            int outline = WorldProjector.withAlpha(0xFF000000, ((color >>> 24) & 0xFF) / 255f * 0.7f);
            for (int dy = -radius; dy <= radius; dy++) {
                int extent = radius - Math.abs(dy);
                int left = -radius;
                int right = -radius + extent * 2;
                if (right > left) {
                    graphics.fill(left - 1, dy, right + 1, dy + 1, outline);
                }
            }
            // 彩色本体
            for (int dy = -radius; dy <= radius; dy++) {
                int extent = radius - Math.abs(dy);
                int left = -radius;
                int right = -radius + extent * 2;
                if (right > left) {
                    graphics.fill(left, dy, right, dy + 1, color);
                }
            }
        } finally {
            pose.popMatrix();
        }
    }
}
