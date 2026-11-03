package com.venus.vmfashion.sync;

import net.minecraft.network.chat.Component;

/**
 * UI 预览器专用的 {@code SyncService} <b>替身</b>（编译时替换真实类）。
 *
 * <p>真实实现要发 HTTP、跑认证、跟服务端对时 —— 预览器不需要联网，只需要
 * {@link #describeStatus()} 能产出<b>不同状态下的那一行文案</b>，好让界面底部状态行
 * 在各种状态下都能被截图检查（"未登录时底部说什么"、"本地模式下说什么"）。
 *
 * <p>状态通过 {@link #setState} 切换，{@code UiPreview} 按场景预先设好。
 */
public class SyncService {

    /** 预览器可模拟的同步状态。 */
    public enum State {
        /** 纯本地模式（配置里关掉了云端）。 */
        LOCAL_ONLY("vmfashion.status.local_only"),
        /** 已开启云端但没登录。 */
        NOT_LOGGED_IN("vmfashion.status.not_logged_in"),
        /** 已登录（正版认证）。 */
        LOGGED_IN("vmfashion.status.logged_in");

        private final String key;

        State(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private static final SyncService INSTANCE = new SyncService();
    private static State state = State.LOGGED_IN;

    public static SyncService getInstance() {
        return INSTANCE;
    }

    public static void setState(State newState) {
        state = newState == null ? State.LOGGED_IN : newState;
    }

    public void refreshWardrobeIfStale(long minIntervalMs) {
        // 预览器不联网：衣柜数据由 Fixtures 直接灌进 WardrobeCollection
    }

    public void uploadLocalLoadout() {
        // 预览器不联网：换装只改内存里的 LocalLoadout
    }

    public Component describeStatus() {
        return switch (state) {
            case LOCAL_ONLY -> Component.translatable("vmfashion.status.local_only");
            case NOT_LOGGED_IN -> Component.translatable("vmfashion.status.not_logged_in",
                    "https://www.venusyu045.cn/api/fashion");
            case LOGGED_IN -> Component.translatable("vmfashion.status.logged_in",
                    "VenusYu", "正版", "https://www.venusyu045.cn/api/fashion");
        };
    }
}
