package com.venus.vmfashion.ping.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：**五个扇区都必须能画出线段**。
 *
 * <p>背景（真实 bug）：{@code PingWheelScreen} 构造扇区时用
 * {@code sectorEnd(i) - gap/2} 作为结束角，而 {@link WheelGeometry#sectorEnd(int)} 会把
 * 360° 归一化成 0°，于是第 5 个扇区（求助）的结束角变成 {@code -1.5°}，
 * {@code sectorSpans} 里 {@code endDeg - startDeg <= 0} 直接返回空列表 ——
 * 游戏里表现为"轮盘少了一块"，而且**只有第 5 个扇区**受影响，肉眼很容易漏。
 *
 * <p>这里把 {@code PingWheelScreen} 的构造逻辑原样复算一遍，逐个断言非空。
 *
 * <p>合订版适配：包名迁入 {@code com.venus.vmfashion.ping.gui}，用例与被测行为不变。
 */
class WheelSectorSpanTest {

    private static final int OUTER = 78;
    private static final int INNER = 22;
    private static final float GAP = 3f;

    private static List<WheelGeometry.Span> spansFor(int sector) {
        float start = WheelGeometry.sectorStart(sector) + GAP / 2f;
        float end = WheelGeometry.sectorEnd(sector) - GAP / 2f;
        return WheelGeometry.sectorSpans(OUTER, INNER, start, end);
    }

    @Test
    void everySectorHasSpans() {
        for (int i = 0; i < WheelGeometry.SECTOR_COUNT; i++) {
            List<WheelGeometry.Span> spans = spansFor(i);
            assertFalse(spans.isEmpty(),
                    "扇区 " + i + " 画不出线段（角度 " + WheelGeometry.sectorStart(i) + "~"
                            + WheelGeometry.sectorEnd(i) + "）");
        }
    }

    @Test
    void lastSectorWrapsAroundZero() {
        // 第 5 个扇区跨越 0°：288°~360°，线段应当出现在左上（dy < 0, dx < 0）
        List<WheelGeometry.Span> spans = spansFor(4);
        assertFalse(spans.isEmpty());
        boolean hasUpperLeft = spans.stream().anyMatch(s -> s.dy() < 0 && s.x1() < 0);
        assertTrue(hasUpperLeft, "第 5 扇区应当覆盖左上区域");
    }

    @Test
    void spansStayInsideAnnulus() {
        for (int i = 0; i < WheelGeometry.SECTOR_COUNT; i++) {
            for (WheelGeometry.Span span : spansFor(i)) {
                double distance = Math.hypot(span.x1(), span.dy());
                assertTrue(distance >= INNER - 1.5 && distance <= OUTER + 1.5,
                        "扇区 " + i + " 的线段越出圆环: dy=" + span.dy() + " x=" + span.x1());
            }
        }
    }
}
