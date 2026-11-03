package com.venus.vmfashion.ping.render;

import com.venus.vmfashion.ping.gui.WheelGeometry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WheelGeometry} 的单元测试：扇区线段、角度换算、命中检测。
 *
 * <p>轮盘的几何是"渲染和输入共用同一份"的（{@code PingWheelScreen} 画扇区和判断鼠标选中
 * 用的是同一套函数），所以这里错一点，玩家看到的就是"鼠标明明在'危险'上，
 * 却标成了'注意'"。测试重点：
 * <ul>
 *   <li>角度约定：<b>0° = 正上，顺时针为正</b>（不是数学课的 0° = 正右）；</li>
 *   <li>五个扇区各占 72°，命中检测的边界不能漏、不能重；</li>
 *   <li>预计算的线段必须落在圆环内（否则轮盘会画出锯齿或溢出）。</li>
 * </ul>
 *
 * <p>合订版适配：包名迁入 {@code com.venus.vmfashion.ping.render}（沿用独立版的包布局），
 * 被测类在 {@code com.venus.vmfashion.ping.gui}，用例与被测行为不变。
 */
class WheelGeometryTest {

    private static final int OUTER = 78;
    private static final int INNER = 22;
    private static final int CENTER_X = 160;
    private static final int CENTER_Y = 120;

    @Test
    void angleOfZeroIsUpAndClockwise() {
        assertEquals(0f, WheelGeometry.angleOf(0, -1), 0.001f, "正上 = 0°");
        assertEquals(90f, WheelGeometry.angleOf(1, 0), 0.001f, "正右 = 90°");
        assertEquals(180f, WheelGeometry.angleOf(0, 1), 0.001f, "正下 = 180°");
        assertEquals(270f, WheelGeometry.angleOf(-1, 0), 0.001f, "正左 = 270°");
    }

    @Test
    void normalizeDegreesWrapsAround() {
        assertEquals(350f, WheelGeometry.normalizeDegrees(-10f), 0.001f);
        assertEquals(10f, WheelGeometry.normalizeDegrees(370f), 0.001f);
        assertEquals(0f, WheelGeometry.normalizeDegrees(360f), 0.001f);
        assertEquals(180f, WheelGeometry.normalizeDegrees(180f), 0.001f);
    }

    @Test
    void sectorRangesAreContiguousAndCoverFullCircle() {
        float total = 0f;
        for (int i = 0; i < WheelGeometry.SECTOR_COUNT; i++) {
            float start = WheelGeometry.sectorStart(i);
            float end = WheelGeometry.sectorEnd(i);
            assertEquals(i * WheelGeometry.SECTOR_DEGREES, start, 0.001f);
            assertEquals((i + 1) * WheelGeometry.SECTOR_DEGREES % 360f, end, 0.001f);
            total += WheelGeometry.SECTOR_DEGREES;
        }
        assertEquals(360f, total, 0.001f);
    }

    @Test
    void angleInSectorSupportsWrappingAroundZero() {
        // 340° → 20° 这个扇区跨越了 0°
        assertTrue(WheelGeometry.angleInSector(0f, 340f, 20f));
        assertTrue(WheelGeometry.angleInSector(350f, 340f, 20f));
        assertTrue(WheelGeometry.angleInSector(10f, 340f, 20f));
        assertFalse(WheelGeometry.angleInSector(30f, 340f, 20f));
        // 普通扇区
        assertTrue(WheelGeometry.angleInSector(10f, 0f, 72f));
        assertFalse(WheelGeometry.angleInSector(72f, 0f, 72f), "右边界是开区间");
    }

    @Test
    void hitTestMapsEachDirectionToItsSector() {
        int distance = 50;
        assertEquals(0, WheelGeometry.hitTest(CENTER_X, CENTER_Y - distance, CENTER_X, CENTER_Y, INNER, OUTER),
                "正上 → 扇区 0");
        assertEquals(1, WheelGeometry.hitTest(CENTER_X + distance, CENTER_Y, CENTER_X, CENTER_Y, INNER, OUTER),
                "正右 → 扇区 1");
        assertEquals(2, WheelGeometry.hitTest(CENTER_X, CENTER_Y + distance, CENTER_X, CENTER_Y, INNER, OUTER),
                "正下 → 扇区 2");
        assertEquals(3, WheelGeometry.hitTest(CENTER_X - distance, CENTER_Y, CENTER_X, CENTER_Y, INNER, OUTER),
                "正左 → 扇区 3");
        assertEquals(4, WheelGeometry.hitTest(CENTER_X - distance, CENTER_Y - distance, CENTER_X, CENTER_Y, INNER, OUTER),
                "左上 → 扇区 4");
    }

    @Test
    void hitTestDeadZoneAndOutsideReturnMinusOne() {
        assertEquals(-1, WheelGeometry.hitTest(CENTER_X, CENTER_Y, CENTER_X, CENTER_Y, INNER, OUTER),
                "中心死区不选中任何扇区");
        assertEquals(-1, WheelGeometry.hitTest(CENTER_X + INNER - 1, CENTER_Y, CENTER_X, CENTER_Y, INNER, OUTER),
                "内半径以内算死区");
        assertEquals(-1, WheelGeometry.hitTest(CENTER_X + OUTER + 5, CENTER_Y, CENTER_X, CENTER_Y, INNER, OUTER),
                "环外不选中");
    }

    /**
     * 每个扇区的预计算线段都必须落在圆环内，并且**每个扇区都画得出来**。
     *
     * <p>角度完全按 {@code PingWheelScreen} 的构造逻辑复算（{@code sectorStart(i)+gap/2}
     * 到 {@code sectorEnd(i)-gap/2}）—— 注意扇区 4 的结束角是 {@code -1.5°}（因为
     * {@code sectorEnd(4)} 把 360° 归一化成了 0°），这属于"跨 0° 的扇区"，
     * {@link WheelGeometry#sectorSpans} 必须能处理，否则轮盘上会少一块。
     */
    @Test
    void sectorSpansStayInsideRing() {
        for (int sector = 0; sector < WheelGeometry.SECTOR_COUNT; sector++) {
            float start = WheelGeometry.sectorStart(sector) + 1.5f;
            float end = WheelGeometry.sectorEnd(sector) - 1.5f;
            List<WheelGeometry.Span> spans = WheelGeometry.sectorSpans(OUTER, INNER, start, end);

            assertFalse(spans.isEmpty(), "扇区 " + sector + " 应该有线段");
            for (WheelGeometry.Span span : spans) {
                assertTrue(Math.abs(span.dy()) <= OUTER, "线段行号不能超出外半径");
                assertTrue(span.x1() <= span.x2(), "线段左端不能超过右端");
                assertTrue(Math.abs(span.x1()) <= OUTER && Math.abs(span.x2()) <= OUTER,
                        "线段列号不能超出外半径");
                double leftDistance = Math.hypot(span.x1(), span.dy());
                double rightDistance = Math.hypot(span.x2(), span.dy());
                assertTrue(leftDistance <= OUTER + 1.5, "左端点跑出圆外");
                assertTrue(rightDistance <= OUTER + 1.5, "右端点跑出圆外");
            }
        }
    }

    @Test
    void sectorSpansHandleDegenerateInput() {
        // 外半径 <= 0 时会被钳到 1，至少不抛异常
        assertTrue(WheelGeometry.sectorSpans(0, 0, 0f, 72f).size() >= 0);
        // 起止角度相同（扫过 0°）→ 空
        assertTrue(WheelGeometry.sectorSpans(OUTER, INNER, 90f, 90f).isEmpty());
    }

    @Test
    void isInsideRingAndSector() {
        // 正上方向、距离 50：在内半径 22 与外半径 78 之间，角度 0 落在 [0,72)
        assertTrue(WheelGeometry.isInside(0, -50, INNER, OUTER, 0f, 72f));
        // 中心点：在死区内
        assertFalse(WheelGeometry.isInside(0, 0, INNER, OUTER, 0f, 72f));
        // 环外
        assertFalse(WheelGeometry.isInside(0, -100, INNER, OUTER, 0f, 72f));
        // 角度落在扇区外（正右 90° 不在 [0,72)）
        assertFalse(WheelGeometry.isInside(50, 0, INNER, OUTER, 0f, 72f));
    }
}
