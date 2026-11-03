package com.venus.vmfashion.gui;

import java.util.function.ToIntFunction;

/**
 * 设置类界面（{@link SettingsScreen} / {@link PingSettingsScreen}）的<b>纯布局数学</b>。
 *
 * <p>为什么单独抽一个类：两个设置界面是同一套"标题 + 若干分区 + 底部按钮"的结构，
 * 尺寸计算如果各写一份必然漂移；更重要的是，这里的每个公式都能<b>脱离 Minecraft 单测</b>
 * （{@code src/test/java/.../gui/SettingsLayoutTest}），而布局正是"窗口一矮就点不到按钮"
 * 这类缺陷的藏身处。
 *
 * <p><b>v2.0.1 修的问题</b>：旧实现把面板高度夹成 {@code min(内容高, max(120, 屏幕高-8))}，
 * 但内容（14 个配置项 ≈ 233 逻辑像素）比屏幕高时<b>控件照旧往下画</b> ——
 * 320×180（1080p 窗口 + GUI 缩放 5、720p + 缩放 4 都会出现）下「完成 / 取消 / 恢复默认」
 * 三个按钮的 y 落在 216..225，<b>整排在屏幕外</b>：玩家能改设置，却<b>永远存不了</b>。
 * 预览器 {@code tools/uipreview --width=320 --height=180 --scenario=settings} 能直接复现
 * （41 条越界告警）。
 *
 * <p>现在的做法：
 * <ol>
 *   <li>面板高度<b>永不超出屏幕</b>（连 {@link #MIN_PANEL_HEIGHT} 也让位于屏幕高度）；</li>
 *   <li>底部按钮<b>钉在面板底部</b>，任何窗口尺寸下都在屏幕内；</li>
 *   <li>中间的内容区变成"视口"，内容比视口高时按 {@link Layout#maxScroll()} 滚动
 *       （滚轮驱动，界面只创建<b>完全落在视口内</b>的控件 —— 半截露在外面的控件会变成
 *       "看不见但点得到"的幽灵点击）。</li>
 * </ol>
 */
final class SettingsLayout {

    /** 面板最大宽度。 */
    static final int PANEL_MAX_WIDTH = 400;
    /** 面板期望的最小宽度（屏幕更窄时让位于屏幕）。 */
    static final int PANEL_MIN_WIDTH = 280;
    /** 面板内边距。 */
    static final int PADDING = 10;
    /** 标题区高度。 */
    static final int TITLE_HEIGHT = 18;
    /** 内容区与底部按钮区之间的间距。 */
    static final int BOTTOM_GAP = 8;
    /** 面板最小高度（屏幕足够高时用；屏幕更矮时让位于屏幕）。 */
    static final int MIN_PANEL_HEIGHT = 120;
    /** 面板与屏幕边缘的期望间距。 */
    static final int SCREEN_MARGIN = 8;
    /** 极端小窗口下的最小间距（再小就贴边，但绝不越界）。 */
    static final int MIN_SCREEN_MARGIN = 2;
    /** 滚轮一格滚多少"行"（按行高换算，保证滚动后行仍对齐）。 */
    static final int SCROLL_ROWS_PER_NOTCH = 1;

    private SettingsLayout() {
    }

    /**
     * 一组布局度量。
     *
     * @param rowHeight   控件行高
     * @param rowGap      行间距
     * @param sectionGap  分区之间的额外间距
     * @param labelHeight 分区标题高度
     */
    record Metrics(int rowHeight, int rowGap, int sectionGap, int labelHeight) {
    }

    /**
     * 一次布局的结果（全部是屏幕坐标，逻辑像素）。
     *
     * @param panelWidth    面板宽
     * @param panelHeight   面板高（已夹进屏幕）
     * @param panelX        面板左上角 x
     * @param panelY        面板左上角 y
     * @param contentTop    可滚动内容区顶部（= 标题下方第一行像素）
     * @param viewportHeight 可滚动内容区高度
     * @param maxScroll     最大滚动量（0 = 内容放得下，不需要滚动）
     * @param contentHeight 内容总高（含标题 / 内边距 / 底部按钮行）
     * @param buttonY       底部按钮行的 y（固定，不随滚动变化）
     */
    record Layout(int panelWidth, int panelHeight, int panelX, int panelY,
                  int contentTop, int viewportHeight, int maxScroll, int contentHeight,
                  int buttonY) {

        /** 可滚动内容区底部（不含）。 */
        int viewportBottom() {
            return contentTop + viewportHeight;
        }

        /** 内容放不下、需要滚动。 */
        boolean scrollable() {
            return maxScroll > 0;
        }
    }

    /**
     * 选一组能在给定高度里放下的布局度量（从宽松到紧凑，取第一个放得下的）。
     *
     * <p>都放不下时返回最紧凑的一组 —— 此时界面会启用滚动，而不是把控件画到屏幕外。
     *
     * @param availableHeight 可用高度（屏幕高减去一点边距）
     * @param candidates      候选度量（从宽松到紧凑）
     * @param contentHeight   给定度量下的内容总高
     */
    static Metrics pickMetrics(int availableHeight, Metrics[] candidates, ToIntFunction<Metrics> contentHeight) {
        if (candidates == null || candidates.length == 0) {
            throw new IllegalArgumentException("candidates 不能为空");
        }
        for (Metrics candidate : candidates) {
            if (contentHeight.applyAsInt(candidate) <= availableHeight) {
                return candidate;
            }
        }
        return candidates[candidates.length - 1];
    }

    /**
     * 算面板几何 + 视口 + 滚动上限。
     *
     * @param screenWidth   当前 GUI 逻辑宽
     * @param screenHeight  当前 GUI 逻辑高
     * @param contentHeight 内容总高（含标题 / 内边距 / 底部按钮行）
     * @param metrics       本次使用的度量
     */
    static Layout compute(int screenWidth, int screenHeight, int contentHeight, Metrics metrics) {
        if (metrics == null) {
            throw new IllegalArgumentException("metrics 不能为空");
        }
        int width = Math.max(1, screenWidth);
        int height = Math.max(1, screenHeight);

        // ---- 面板宽：与原实现逐字节等价（先按 400 上限，再保证不越出屏幕）----
        int panelWidth = Math.min(PANEL_MAX_WIDTH, Math.max(1, width - 24));
        panelWidth = Math.max(Math.min(PANEL_MIN_WIDTH, width - 8), panelWidth);
        panelWidth = Math.min(panelWidth, Math.max(1, width));

        // ---- 固定区域：标题 + 上下内边距 + 底部按钮行 + 按钮上方的间距 ----
        int fixed = TITLE_HEIGHT + 2 * PADDING + metrics.rowHeight() + BOTTOM_GAP;
        int bodyContent = Math.max(0, contentHeight - fixed);

        // ---- 面板高：绝不超出屏幕；同时保证至少能看到一行内容 ----
        int maxPanelHeight = Math.max(1, height - 2 * MIN_SCREEN_MARGIN);
        int minPanelHeight = Math.min(contentHeight, fixed + metrics.rowHeight());
        int panelHeight = Math.min(contentHeight, maxPanelHeight);
        panelHeight = Math.max(panelHeight, Math.min(minPanelHeight, maxPanelHeight));

        int panelX = (width - panelWidth) / 2;
        int panelY = Math.max(MIN_SCREEN_MARGIN, (height - panelHeight) / 2);
        // 极端小窗口下居中会让面板顶部越界：再夹一次（宁可贴顶，也不要画到屏幕外）
        panelY = Math.min(panelY, Math.max(0, height - panelHeight));

        int contentTop = panelY + TITLE_HEIGHT + PADDING;
        int buttonY = panelY + panelHeight - PADDING - metrics.rowHeight();
        int viewportHeight = Math.max(0, buttonY - BOTTOM_GAP - contentTop);
        int maxScroll = Math.max(0, bodyContent - viewportHeight);
        return new Layout(panelWidth, panelHeight, panelX, panelY,
                contentTop, viewportHeight, maxScroll, contentHeight, buttonY);
    }

    /** 把滚动量夹进 {@code [0, maxScroll]}（NaN / 负数一律当 0）。 */
    static int clampScroll(int scroll, int maxScroll) {
        if (maxScroll <= 0 || scroll <= 0) {
            return 0;
        }
        return Math.min(scroll, maxScroll);
    }

    /**
     * 处理一次滚轮事件后的滚动量。
     *
     * @param scroll     当前滚动量
     * @param wheelDelta 滚轮方向（正 = 向上滚 = 内容下移，与 {@code WardrobeScreen} 一致）
     * @param step       一格滚轮滚多少像素
     * @param maxScroll  滚动上限
     * @return 新的滚动量（没变化时返回原值，调用方据此决定要不要重建控件）
     */
    static int scrollBy(int scroll, int wheelDelta, int step, int maxScroll) {
        if (maxScroll <= 0 || wheelDelta == 0 || step <= 0) {
            return clampScroll(scroll, maxScroll);
        }
        long next = (long) clampScroll(scroll, maxScroll) - (long) wheelDelta * step * SCROLL_ROWS_PER_NOTCH;
        return clampScroll((int) Math.max(Integer.MIN_VALUE / 2, Math.min(Integer.MAX_VALUE / 2, next)), maxScroll);
    }

    /**
     * 某个控件是否<b>完整</b>落在视口内。
     *
     * <p>只放完整可见的控件是刻意的：半截露在视口外的控件在游戏里既看不见、又照样吃鼠标
     * 事件（幽灵点击），比"少显示一行"糟得多。
     *
     * @param y           控件顶部（屏幕坐标）
     * @param height      控件高
     * @param viewportTop 视口顶部
     * @param viewportBottom 视口底部（不含）
     */
    static boolean rowVisible(int y, int height, int viewportTop, int viewportBottom) {
        return y >= viewportTop && y + height <= viewportBottom;
    }
}
