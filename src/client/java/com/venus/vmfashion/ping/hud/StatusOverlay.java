package com.venus.vmfashion.ping.hud;

import com.venus.vmfashion.config.PingSettings;
import com.venus.vmfashion.ping.PingService;
import com.venus.vmfashion.ping.net.PingRelay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 连接状态角标 —— 屏幕右下角一行小字，只在"非在线"时短暂出现。
 *
 * <p>设计意图：
 * <ul>
 *   <li>认证中 / 降级 / 本地模式，玩家应该**知道**，但不能被刷屏 ——
 *       所以只在**状态变化时**显示 2 秒，然后淡出，不常驻、不弹窗、不进聊天栏；</li>
 *   <li>{@link PingSettings#isShowStatus()} 关掉后完全不画；</li>
 *   <li>在线（{@code ONLINE}）时什么都不画 —— 一切正常不需要存在感。</li>
 * </ul>
 *
 * <p>性能：状态不变时只做一次比较；文字 {@link Component} 在状态变化时才重建，
 * 每帧没有分配。
 *
 * <p>合订版适配：配置从 {@link PingSettings} 读、中继从 {@link PingService#relay()} 取，
 * 语言 key 改成 {@code vmfashion.ping.hud.*}；显示逻辑不变。
 */
public final class StatusOverlay {

    /** 完全显示的时长（毫秒）。 */
    private static final long HOLD_MS = 2_000L;
    /** 淡出时长（毫秒）。 */
    private static final long FADE_MS = 600L;
    /** 文字距屏幕右下角的边距（像素）。 */
    private static final int MARGIN = 6;
    /** 文字底衬色。 */
    private static final int BACKDROP = 0x66000000;
    /** 认证中：琥珀色。 */
    private static final int COLOR_PENDING = 0xFFFFD24D;
    /** 降级/本地模式：警示红。 */
    private static final int COLOR_WARNING = 0xFFFF8A65;
    /** 未连接：灰。 */
    private static final int COLOR_MUTED = 0xFFBBBBBB;

    /** 上一次观察到的状态（用来检测变化）。 */
    private static PingRelay.State lastState;
    /** 状态变化的时刻（{@link System#currentTimeMillis()}）。 */
    private static long changedAtMs;
    /** 当前状态的文字（状态变化时重建）。 */
    private static Component cachedText;
    /** 当前状态的文字颜色。 */
    private static int cachedColor;
    /** 上一次用于文案的错误 key（DEGRADED 时状态不变但原因可能变化）。 */
    private static String cachedErrorKey = "";

    private StatusOverlay() {
    }

    /**
     * 渲染入口（HUD 每帧调用一次）。
     *
     * @param graphics HUD 绘制上下文
     */
    public static void render(GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (minecraft.gui == null || minecraft.gui.hud.isHidden()) {
            return;
        }
        PingSettings config = PingService.getInstance().settings();
        if (config == null || !config.isEnabled() || !config.isShowStatus()) {
            return;
        }

        PingRelay relay = PingService.getInstance().relay();
        PingRelay.State state = relay.state();
        String errorKey = relay.lastErrorKey();
        long now = System.currentTimeMillis();
        if (state != lastState || !errorKey.equals(cachedErrorKey)) {
            lastState = state;
            cachedErrorKey = errorKey;
            changedAtMs = now;
            cachedText = textFor(state, config, errorKey);
            cachedColor = colorFor(state);
        }
        if (cachedText == null || state == PingRelay.State.ONLINE) {
            return;
        }

        long elapsed = now - changedAtMs;
        if (elapsed > HOLD_MS + FADE_MS) {
            return; // 已经淡出完，等下一次状态变化再出现
        }
        float alpha = elapsed <= HOLD_MS
                ? 1f
                : 1f - (float) (elapsed - HOLD_MS) / (float) FADE_MS;
        alpha = Math.max(0f, Math.min(1f, alpha));

        Font font = minecraft.font;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int textWidth = font.width(cachedText);
        int x = width - MARGIN - textWidth;
        int y = height - MARGIN - font.lineHeight;

        graphics.fill(x - 3, y - 2, x + textWidth + 3, y + font.lineHeight + 1,
                withAlpha(BACKDROP, alpha));
        graphics.text(font, cachedText, x, y, withAlpha(cachedColor, alpha), false);
    }

    /**
     * 状态 → 文案（在线返回 {@code null}：不显示）。
     *
     * <p>DEGRADED 时优先显示中继维护的**具体原因**（未登录 / 明文后端 / 限流 / 单机…），
     * 而不是永远一句"服务不可用"—— 玩家按提示就能自己处理，不用去翻命令。
     */
    private static Component textFor(PingRelay.State state, PingSettings config, String errorKey) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case ONLINE -> null;
            case AUTHENTICATING -> Component.translatable("vmfashion.ping.hud.status.authenticating");
            case DEGRADED -> errorKey != null && !errorKey.isBlank()
                    ? Component.translatable(errorKey)
                    : Component.translatable("vmfashion.ping.hud.status.degraded");
            case OFF -> config.relayEnabled(PingService.getInstance().authBridge().fashionApiBaseUrl())
                    ? Component.translatable("vmfashion.ping.hud.status.off")
                    : Component.translatable("vmfashion.ping.hud.status.local");
        };
    }

    /** 状态 → 颜色。 */
    private static int colorFor(PingRelay.State state) {
        if (state == null) {
            return COLOR_MUTED;
        }
        return switch (state) {
            case AUTHENTICATING -> COLOR_PENDING;
            case DEGRADED -> COLOR_WARNING;
            case OFF -> COLOR_MUTED;
            case ONLINE -> COLOR_MUTED;
        };
    }

    /** 给颜色乘透明度。 */
    private static int withAlpha(int argb, float factor) {
        int alpha = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** 退出世界时重置，避免下次进服时"状态没变"而不显示提示。 */
    public static void reset() {
        lastState = null;
        changedAtMs = 0L;
        cachedText = null;
        cachedErrorKey = "";
    }
}
