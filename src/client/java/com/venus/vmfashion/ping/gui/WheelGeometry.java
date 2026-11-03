package com.venus.vmfashion.ping.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * 轮盘几何计算（纯函数，不碰 Minecraft，可直接单测）。
 *
 * <p>为什么不用「画三角形」：MC 的 {@code GuiGraphicsExtractor#fill} 只能画轴对齐矩形，
 * 没有多边形/扇形 API。这里把环形扇区**预计算成一行行水平线段**（{@link Span}），
 * 渲染时逐行 {@code fill} 即可 —— 156 行 × 5 个扇区 ≈ 780 个矩形，对 GUI 渲染状态毫无压力，
 * 而且边缘是 1px 精度，比用大格子拼出来的扇形干净得多。
 *
 * <p>坐标系：屏幕坐标，y 向下；角度以**正上方**为 0°，顺时针为正。
 * 扇区 i 的角度范围是 {@code [i*72°, (i+1)*72°)}（5 个扇区）。
 *
 * <p>合订版适配：只改包名（{@code com.venus.vmfashion.ping.gui}）；几何算法与独立版逐行一致
 * （有单测覆盖），UI 尺寸常量由 {@code PingSettings.UI} 提供。
 */
public final class WheelGeometry {

    /** 扇区数量（= {@code PingKind} 的枚举数量）。 */
    public static final int SECTOR_COUNT = 5;
    /** 每个扇区的角度（度）。 */
    public static final float SECTOR_DEGREES = 360f / SECTOR_COUNT;

    private WheelGeometry() {
    }

    /** 一行水平线段（相对轮盘中心的坐标，y 向下）。 */
    public record Span(int dy, int x1, int x2) {
    }

    /**
     * 计算一个环形扇区的水平线段。
     *
     * @param outerRadius 外半径
     * @param innerRadius 内半径（中心死区）
     * @param startDeg    起始角度（度，0 = 正上，顺时针）
     * @param endDeg      结束角度（度，可以 &gt; 360 表示跨 0°）
     * @return 相对中心的水平线段列表（按 dy 升序）
     */
    public static List<Span> sectorSpans(int outerRadius, int innerRadius, float startDeg, float endDeg) {
        List<Span> spans = new ArrayList<>();
        int outer = Math.max(1, outerRadius);
        int inner = Math.max(0, Math.min(innerRadius, outer - 1));
        // 注意：sectorEnd(4) 会被归一化成 0（360 → 0），于是 endDeg - gap/2 变成负数，
        // 旧实现按 endDeg - startDeg <= 0 直接返回空列表 —— 第 5 个扇区（求助）永远画不出来。
        // 这里把"跨 0° 的扇区"补成 360 度以内的正扫角；angleInSector 本身已能处理 s > e 的情况。
        float sweep = endDeg - startDeg;
        if (sweep < 0f) {
            sweep += 360f;
        }
        if (sweep <= 0.001f) {
            return spans;
        }
        // 扫描每一行，找出该行里落在环内且角度落在扇区内的连续像素段
        for (int dy = -outer; dy <= outer; dy++) {
            int rowOuter = (int) Math.floor(Math.sqrt((double) outer * outer - (double) dy * dy));
            if (rowOuter < 0) {
                continue;
            }
            int runStart = Integer.MIN_VALUE;
            int runEnd = Integer.MIN_VALUE;
            for (int dx = -rowOuter; dx <= rowOuter; dx++) {
                boolean inside = isInside(dx, dy, inner, outer, startDeg, endDeg);
                if (inside) {
                    if (runStart == Integer.MIN_VALUE) {
                        runStart = dx;
                    }
                    runEnd = dx;
                } else if (runStart != Integer.MIN_VALUE) {
                    spans.add(new Span(dy, runStart, runEnd));
                    runStart = Integer.MIN_VALUE;
                    runEnd = Integer.MIN_VALUE;
                }
            }
            if (runStart != Integer.MIN_VALUE) {
                spans.add(new Span(dy, runStart, runEnd));
            }
        }
        return spans;
    }

    /** 某个相对坐标点是否落在环形扇区内（渲染与命中检测共用同一判定）。 */
    public static boolean isInside(int dx, int dy, int innerRadius, int outerRadius,
                                   float startDeg, float endDeg) {
        double distance = Math.hypot(dx, dy);
        if (distance < innerRadius || distance > outerRadius) {
            return false;
        }
        return angleInSector(angleOf(dx, dy), startDeg, endDeg);
    }

    /**
     * 鼠标位置命中哪个扇区。
     *
     * @return 扇区下标 {@code [0,4]}；落在中心死区或环外返回 {@code -1}
     */
    public static int hitTest(int mouseX, int mouseY, int centerX, int centerY,
                              int innerRadius, int outerRadius) {
        int dx = mouseX - centerX;
        int dy = mouseY - centerY;
        double distance = Math.hypot(dx, dy);
        if (distance < innerRadius || distance > outerRadius) {
            return -1;
        }
        float angle = angleOf(dx, dy);
        int sector = (int) (angle / SECTOR_DEGREES);
        return Math.max(0, Math.min(SECTOR_COUNT - 1, sector));
    }

    /** 相对坐标 → 角度（度，0 = 正上，顺时针，范围 [0,360)）。 */
    public static float angleOf(int dx, int dy) {
        // atan2(x, -y)：正上 = 0°，右 = 90°，下 = 180°，左 = 270°
        double degrees = Math.toDegrees(Math.atan2(dx, -dy));
        return normalizeDegrees((float) degrees);
    }

    /** 把任意角度归一化到 [0,360)。 */
    public static float normalizeDegrees(float degrees) {
        float value = degrees % 360f;
        if (value < 0f) {
            value += 360f;
        }
        return value;
    }

    /** 角度 a 是否落在 [start, end)（支持跨越 0°）。 */
    public static boolean angleInSector(float a, float start, float end) {
        float s = normalizeDegrees(start);
        float e = normalizeDegrees(end);
        float angle = normalizeDegrees(a);
        if (s <= e) {
            return angle >= s && angle < e;
        }
        // 跨越 0°
        return angle >= s || angle < e;
    }

    /** 扇区 i 的起始角度（度）。 */
    public static float sectorStart(int index) {
        return normalizeDegrees(index * SECTOR_DEGREES);
    }

    /** 扇区 i 的结束角度（度）。 */
    public static float sectorEnd(int index) {
        return normalizeDegrees((index + 1) * SECTOR_DEGREES);
    }
}
