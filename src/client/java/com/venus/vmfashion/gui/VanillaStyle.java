package com.venus.vmfashion.gui;

import com.venus.vmfashion.config.ModConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Minecraft <b>原版容器界面</b>的绘制原语（面板 / 物品格 / 凸起凹陷）。
 *
 * <p>为什么单独抽一个类：衣柜（{@link WardrobeScreen}）和确认框（{@link ConfirmScreen}）
 * 都要画同样的面板和按钮，各写一份必然会漂移 —— 改了一处忘了另一处，界面就会
 * "一块像原版、一块不像"。抽出来之后配色和画法只有一处定义。
 *
 * <p>所有画法都是从客户端 jar 的
 * {@code assets/minecraft/textures/gui/container/inventory.png} 里<b>逐像素量出来</b>的，
 * 不是"看着像"：
 * <ul>
 *   <li>面板：{@code #C6C6C6} 底 + 1px 纯黑外框 + 左/上 2px 纯白高光 + 右/下 2px
 *       {@code #555555} 阴影，四角各留 1px 空 → 原版那种"像素圆角"；</li>
 *   <li>物品格：1px {@code #373737} 边框 + {@code #8B8B8B} 内底 + 右/底内侧各 1px 纯白，
 *       那两道白边是"凹进去"的来源；</li>
 *   <li>凸起 / 凹陷：原版 GUI 从不靠颜色表示状态，只靠"光从左上打来"——
 *       凸起是左上白右下暗，凹陷反过来。</li>
 * </ul>
 */
final class VanillaStyle {

    private VanillaStyle() {
    }

    /** 给颜色乘一个透明度系数（打开动画淡入用）。 */
    static int withAlpha(int color, float factor) {
        if (factor >= 1.0f) {
            return color;
        }
        if (factor <= 0.0f) {
            return color & 0x00FFFFFF;
        }
        int alpha = (int) (((color >>> 24) & 0xFF) * factor) & 0xFF;
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /** 原版容器面板。 */
    static void panel(GuiGraphicsExtractor context, int x, int y, int w, int h, float alpha) {
        if (w <= 4 || h <= 4) {
            return;
        }
        context.fill(x, y, x + w, y + h, withAlpha(ModConfig.UI.PANEL_COLOR, alpha));
        // 左/上白高光（2px）
        context.fill(x + 1, y + 1, x + w - 1, y + 3, withAlpha(ModConfig.UI.PANEL_HIGHLIGHT, alpha));
        context.fill(x + 1, y + 1, x + 3, y + h - 1, withAlpha(ModConfig.UI.PANEL_HIGHLIGHT, alpha));
        // 右/下深灰阴影（2px）
        context.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, withAlpha(ModConfig.UI.PANEL_SHADOW, alpha));
        context.fill(x + w - 3, y + 1, x + w - 1, y + h - 1, withAlpha(ModConfig.UI.PANEL_SHADOW, alpha));
        // 1px 黑外框，跳过四角
        int border = withAlpha(ModConfig.UI.PANEL_BORDER, alpha);
        context.fill(x + 1, y, x + w - 1, y + 1, border);
        context.fill(x + 1, y + h - 1, x + w - 1, y + h, border);
        context.fill(x, y + 1, x + 1, y + h - 1, border);
        context.fill(x + w - 1, y + 1, x + w, y + h - 1, border);
    }

    /** 原版物品格（凹槽）。 */
    static void slot(GuiGraphicsExtractor context, int x, int y, int w, int h, float alpha) {
        if (w <= 2 || h <= 2) {
            return;
        }
        context.fill(x, y, x + w, y + h, withAlpha(ModConfig.UI.SLOT_BORDER, alpha));
        context.fill(x + 1, y + 1, x + w - 1, y + h - 1, withAlpha(ModConfig.UI.SLOT_COLOR, alpha));
        int highlight = withAlpha(ModConfig.UI.PANEL_HIGHLIGHT, alpha);
        context.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, highlight);
        context.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, highlight);
    }

    /**
     * 原版凸起 / 凹陷块。
     *
     * @param sunken {@code true} 画成凹进去的（选中态）
     */
    static void bevel(GuiGraphicsExtractor context, int x, int y, int w, int h,
                      int fillColor, boolean sunken, float alpha) {
        if (w <= 1 || h <= 1) {
            return;
        }
        context.fill(x, y, x + w, y + h, withAlpha(fillColor, alpha));
        int light = withAlpha(ModConfig.UI.PANEL_HIGHLIGHT, alpha);
        int dark = withAlpha(ModConfig.UI.PANEL_SHADOW, alpha);
        if (sunken) {
            context.fill(x, y, x + w, y + 1, dark);
            context.fill(x, y, x + 1, y + h, dark);
            context.fill(x, y + h - 1, x + w, y + h, light);
            context.fill(x + w - 1, y, x + w, y + h, light);
        } else {
            context.fill(x, y, x + w, y + 1, light);
            context.fill(x, y, x + 1, y + h, light);
            context.fill(x, y + h - 1, x + w, y + h, dark);
            context.fill(x + w - 1, y, x + w, y + h, dark);
        }
    }

    /** 原版"凸起分隔线"：1px 深灰 + 1px 白。 */
    static void separator(GuiGraphicsExtractor context, int x0, int x1, int y, float alpha) {
        context.fill(x0, y, x1, y + 1, withAlpha(ModConfig.UI.PANEL_SHADOW, alpha));
        context.fill(x0, y + 1, x1, y + 2, withAlpha(ModConfig.UI.PANEL_HIGHLIGHT, alpha));
    }
}
