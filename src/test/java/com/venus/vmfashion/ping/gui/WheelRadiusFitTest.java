package com.venus.vmfashion.ping.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轮盘半径自适应（纯几何，不加载 Minecraft 类）。
 *
 * <p>回归的是 v2.0.0 的一个真实缺陷：轮盘半径写死成配置常量 78，而 GUI 逻辑高度可以是
 * 150（720p 窗口 + GUI 缩放 4、1080p + 缩放 5 都会出现）——
 * 半径 78 + 中心圆 + 底部操作提示会让轮盘上下各溢出 3px、提示文字整行画到屏幕外；
 * 更小的窗口（GUI 逻辑 100 高）下连中心文字都会被挤出去。
 *
 * <p>{@link PingWheelScreen#init()} 现在用这里的三个 {@code fit*} 方法算半径，
 * 命中检测、扇区线段、图标/文字位置全部跟着同一个值走。
 */
class WheelRadiusFitTest {

    /** 配置里的期望外半径。 */
    private static final int PREFERRED_OUTER = 78;
    /** 配置里的期望内半径（中心死区）。 */
    private static final int PREFERRED_INNER = 22;
    /** 配置里的中心圆半径。 */
    private static final int PREFERRED_CENTER = 16;

    @Test
    void keepsPreferredRadiusOnLargeScreens() {
        assertEquals(PREFERRED_OUTER, WheelGeometry.fitOuterRadius(640, 360, PREFERRED_OUTER));
        assertEquals(PREFERRED_OUTER, WheelGeometry.fitOuterRadius(1920, 1080, PREFERRED_OUTER));
    }

    @Test
    void shrinksRadiusOnShortScreens() {
        // 180/2 - 16 = 74：轮盘 + 图标 + 底部提示刚好在屏幕内
        assertEquals(74, WheelGeometry.fitOuterRadius(320, 180, PREFERRED_OUTER));
        int radius = WheelGeometry.fitOuterRadius(320, 180, PREFERRED_OUTER);
        int centerY = 180 / 2;
        assertTrue(centerY - radius >= 0, "轮盘顶部不能越界");
        // 底部提示的 y 会被夹进屏幕（PingWheelScreen#extractRenderState），这里断言夹完仍在屏内
        int hintY = Math.min(centerY + radius + 10, 180 - 9 - 2);
        assertTrue(hintY + 9 <= 180, "底部操作提示不能越界");
    }

    @Test
    void neverShrinksBelowMinimumUsableRadius() {
        // 极小的 GUI（窗口被拖到很小 / 缩放极大）：半径退回下限 24，仍然可选
        assertEquals(WheelGeometry.MIN_OUTER_RADIUS, WheelGeometry.fitOuterRadius(60, 60, PREFERRED_OUTER));
        // 100 高的 GUI：100/2 - 16 = 34（还没到下限，但已经远小于 78）
        assertEquals(34, WheelGeometry.fitOuterRadius(120, 100, PREFERRED_OUTER));
    }

    @Test
    void radiusIsMonotonicInWindowHeight() {
        int previous = 0;
        for (int height = 60; height <= 400; height += 2) {
            int radius = WheelGeometry.fitOuterRadius(320, height, PREFERRED_OUTER);
            assertTrue(radius >= previous, "窗口变高时半径不能变小（height=" + height + "）");
            assertTrue(radius <= PREFERRED_OUTER, "半径不能超过配置值");
            previous = radius;
        }
    }

    @Test
    void innerRadiusKeepsRatioAndStaysInsideOuter() {
        assertEquals(PREFERRED_INNER, WheelGeometry.fitInnerRadius(PREFERRED_OUTER,
                PREFERRED_INNER, PREFERRED_OUTER));
        // 外半径 44 时内圈按 22/78 等比缩放 ≈ 12
        assertEquals(12, WheelGeometry.fitInnerRadius(44, PREFERRED_INNER, PREFERRED_OUTER));
        for (int outer = 8; outer <= 120; outer++) {
            int inner = WheelGeometry.fitInnerRadius(outer, PREFERRED_INNER, PREFERRED_OUTER);
            assertTrue(inner >= 0, "内半径不能为负（outer=" + outer + "）");
            assertTrue(inner < outer, "内半径必须小于外半径（outer=" + outer + "）");
        }
    }

    @Test
    void centerCircleNeverSwallowsTheWheel() {
        assertEquals(PREFERRED_CENTER, WheelGeometry.fitCenterRadius(PREFERRED_OUTER, PREFERRED_CENTER));
        // 小轮盘：中心圆最多占 1/3
        assertEquals(10, WheelGeometry.fitCenterRadius(30, PREFERRED_CENTER));
        assertEquals(0, WheelGeometry.fitCenterRadius(0, PREFERRED_CENTER));
        for (int outer = 0; outer <= 120; outer++) {
            int center = WheelGeometry.fitCenterRadius(outer, PREFERRED_CENTER);
            assertTrue(center * 3 <= Math.max(outer, 1) || center <= PREFERRED_CENTER);
        }
    }

    @Test
    void fittedGeometryStillCoversAllFiveSectors() {
        // 半径被夹小之后，5 个扇区的线段都不能为空（否则有扇区点了没反应）
        int radius = WheelGeometry.fitOuterRadius(320, 180, PREFERRED_OUTER);
        int inner = WheelGeometry.fitInnerRadius(radius, PREFERRED_INNER, PREFERRED_OUTER);
        float gap = 3f;
        for (int i = 0; i < WheelGeometry.SECTOR_COUNT; i++) {
            float start = WheelGeometry.sectorStart(i) + gap / 2f;
            float end = WheelGeometry.sectorEnd(i) - gap / 2f;
            var spans = WheelGeometry.sectorSpans(radius, inner, start, end);
            assertTrue(!spans.isEmpty(), "扇区 " + i + " 在半径 " + radius + " 下画不出来");
        }
    }
}
