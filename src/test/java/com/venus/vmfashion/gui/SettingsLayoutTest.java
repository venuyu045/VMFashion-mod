package com.venus.vmfashion.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置界面的布局数学（纯逻辑，不加载 Minecraft 类）。
 *
 * <p>回归的是 v2.0.0 的一个真实缺陷：{@link SettingsScreen} / {@link PingSettingsScreen}
 * 把面板高度夹成 {@code min(内容高, max(120, 屏幕高 - 8))}，但内容（14 项 ≈ 233 逻辑像素）
 * 比屏幕高时<b>控件照旧往下画</b>。320×180（720p 窗口 + GUI 缩放 4、1080p + 缩放 5
 * 都会出现）下「完成 / 取消 / 恢复默认」三个按钮的 y 落在 216..225 ——
 * <b>整排在屏幕外，玩家能改设置却永远存不了</b>。
 *
 * <p>预览器能复现：{@code bash tools/uipreview/run.sh --scenario=settings --width=320 --height=180 --report}
 * 修前 41 条越界告警，修后 0 条。
 */
class SettingsLayoutTest {

    /** 宽松度量（20px 行高）。 */
    private static final SettingsLayout.Metrics ROOMY = new SettingsLayout.Metrics(20, 4, 8, 11);
    /** 最紧凑度量（14px 行高，窗口很矮时用）。 */
    private static final SettingsLayout.Metrics COMPACT = new SettingsLayout.Metrics(14, 2, 4, 9);
    /** 时装设置界面的内容总高（14 项 + 3 个分区标题 + 标题 + 底部按钮，用 ROOMY 度量）。 */
    private static final int CONTENT_233 = 233;

    @Test
    void tallWindowFitsWithoutScrolling() {
        SettingsLayout.Layout layout = SettingsLayout.compute(640, 360, CONTENT_233, ROOMY);
        assertFalse(layout.scrollable(), "640×360 下内容放得下，不该出现滚动");
        assertEquals(0, layout.maxScroll());
        assertEquals(CONTENT_233, layout.panelHeight());
        assertTrue(layout.buttonY() + ROOMY.rowHeight() <= 360, "底部按钮要在屏幕内");
        assertTrue(layout.viewportHeight() >= 4 * ROOMY.rowHeight(), "视口至少能放 4 行");
    }

    @Test
    void shortWindowKeepsButtonsOnScreenAndScrolls() {
        // 320×180 = 720p 窗口 + GUI 缩放 4：v2.0.0 下按钮被画到 216..225（屏幕外）
        SettingsLayout.Layout layout = SettingsLayout.compute(320, 180, CONTENT_233, COMPACT);
        assertTrue(layout.scrollable(), "内容比屏幕高时必须启用滚动");
        assertTrue(layout.buttonY() >= 0, "按钮行不能为负");
        assertTrue(layout.buttonY() + COMPACT.rowHeight() <= 180, "底部按钮必须完整落在屏幕内");
        assertTrue(layout.panelY() >= 0, "面板顶部不能越界");
        assertTrue(layout.panelY() + layout.panelHeight() <= 180, "面板不能超出屏幕");
        assertTrue(layout.viewportHeight() >= COMPACT.rowHeight(), "视口至少要放得下一行");
        assertTrue(layout.viewportBottom() <= layout.buttonY(), "内容视口不能压到按钮行");
    }

    @Test
    void everyWindowHeightKeepsPanelAndButtonsInside() {
        for (int height = 100; height <= 400; height += 5) {
            SettingsLayout.Layout layout = SettingsLayout.compute(320, height, CONTENT_233, COMPACT);
            String at = "窗口高 " + height + " 时：";
            assertTrue(layout.panelY() >= 0, at + "面板顶部越界");
            assertTrue(layout.panelY() + layout.panelHeight() <= height, at + "面板底部越界");
            assertTrue(layout.buttonY() >= 0, at + "按钮行为负");
            assertTrue(layout.buttonY() + COMPACT.rowHeight() <= height, at + "按钮行越界");
            assertTrue(layout.contentTop() >= layout.panelY(), at + "内容区跑到标题上方");
        }
    }

    @Test
    void everyWindowWidthKeepsPanelInside() {
        for (int width = 160; width <= 900; width += 10) {
            SettingsLayout.Layout layout = SettingsLayout.compute(width, 300, CONTENT_233, ROOMY);
            String at = "窗口宽 " + width + " 时：";
            assertTrue(layout.panelX() >= 0, at + "面板左边越界");
            assertTrue(layout.panelX() + layout.panelWidth() <= width, at + "面板右边越界");
        }
    }

    @Test
    void maxScrollCoversWholeScrollableBody() {
        SettingsLayout.Layout layout = SettingsLayout.compute(320, 180, CONTENT_233, COMPACT);
        int fixed = SettingsLayout.TITLE_HEIGHT + 2 * SettingsLayout.PADDING
                + COMPACT.rowHeight() + SettingsLayout.BOTTOM_GAP;
        int bodyContent = CONTENT_233 - fixed;
        assertEquals(Math.max(0, bodyContent - layout.viewportHeight()), layout.maxScroll(),
                "滚动上限要正好覆盖整个可滚动内容");
    }

    @Test
    void pickMetricsPrefersRoomyWhenItFits() {
        SettingsLayout.Metrics[] candidates = {ROOMY, COMPACT};
        assertEquals(ROOMY, SettingsLayout.pickMetrics(300, candidates, m -> CONTENT_233));
        // 宽松度量放不下、紧凑度量放得下 → 选紧凑
        assertEquals(COMPACT, SettingsLayout.pickMetrics(150, candidates,
                m -> m.rowHeight() == ROOMY.rowHeight() ? 300 : 140));
        // 都放不下 → 返回最紧凑的一组（配合滚动，而不是把控件画到屏幕外）
        assertEquals(COMPACT, SettingsLayout.pickMetrics(100, candidates, m -> 400));
    }

    @Test
    void clampScrollKeepsValueInRange() {
        assertEquals(0, SettingsLayout.clampScroll(-50, 100));
        assertEquals(0, SettingsLayout.clampScroll(40, 0), "不需要滚动时恒为 0");
        assertEquals(100, SettingsLayout.clampScroll(180, 100));
        assertEquals(37, SettingsLayout.clampScroll(37, 100));
    }

    @Test
    void scrollByMovesOneRowPerNotchAndClamps() {
        int step = 24;
        assertEquals(24, SettingsLayout.scrollBy(0, -1, step, 100), "向上滚 = 内容下移");
        assertEquals(0, SettingsLayout.scrollBy(24, 1, step, 100));
        assertEquals(0, SettingsLayout.scrollBy(0, 1, step, 100), "已经在顶部就不能再滚");
        assertEquals(100, SettingsLayout.scrollBy(90, -5, step, 100), "滚动上限要夹住");
        assertEquals(0, SettingsLayout.scrollBy(0, 0, step, 100), "没有滚动量就不动");
        assertEquals(0, SettingsLayout.scrollBy(50, -1, step, 0), "不需要滚动时恒为 0");
    }

    @Test
    void rowVisibleRequiresFullFit() {
        assertTrue(SettingsLayout.rowVisible(110, 20, 100, 200));
        assertFalse(SettingsLayout.rowVisible(90, 20, 100, 200), "上半截露出视口的行不算可见");
        assertFalse(SettingsLayout.rowVisible(190, 20, 100, 200), "下半截露出视口的行不算可见");
        assertFalse(SettingsLayout.rowVisible(100, 20, 100, 100), "零高度视口里什么都不可见");
    }
}
