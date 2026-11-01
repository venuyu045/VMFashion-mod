package com.venus.vmfashion.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SyncService#evaluateWardrobeRefresh} 单测。
 *
 * <p>背景：{@code /vmfashion wardrobe refresh} 旧实现无条件先打"正在刷新…"，
 * 然后在同步层因为"没登录 / 后端没有该端点 / 正在退避 / 已有请求在飞"静默 return ——
 * 玩家看到一条提示后再无下文，以为命令卡死。把"这次会不会真的发请求"抽成纯函数后，
 * 命令层就能按真实原因给出提示（复用既有 lang key，不新增文案）。
 *
 * <p>纯逻辑，不加载任何 Minecraft 类（{@link SyncService} 的静态初始化只建一个空实例）。
 */
class SyncServiceWardrobeTest {

    private static SyncService.WardrobeRefreshResult evaluate(boolean enabled, boolean hasToken,
                                                             boolean unsupported, long now,
                                                             long backoffUntil, boolean inFlight) {
        return SyncService.evaluateWardrobeRefresh(enabled, true, true, hasToken,
                unsupported, now, backoffUntil, inFlight);
    }

    @Test
    void dispatchesWhenEverythingIsReady() {
        assertEquals(SyncService.WardrobeRefreshResult.DISPATCHED,
                evaluate(true, true, false, 1_000L, 0L, false));
    }

    @Test
    void notEnabledWhenSyncLayerIsOff() {
        assertEquals(SyncService.WardrobeRefreshResult.NOT_ENABLED,
                SyncService.evaluateWardrobeRefresh(false, false, false, false,
                        false, 1_000L, 0L, false));
        assertEquals(SyncService.WardrobeRefreshResult.NOT_ENABLED,
                SyncService.evaluateWardrobeRefresh(true, false, true, true,
                        false, 1_000L, 0L, false), "没有 api 也算未启用");
        assertEquals(SyncService.WardrobeRefreshResult.NOT_ENABLED,
                SyncService.evaluateWardrobeRefresh(true, true, false, true,
                        false, 1_000L, 0L, false), "没有 auth 也算未启用");
    }

    @Test
    void notLoggedInWithoutToken() {
        assertEquals(SyncService.WardrobeRefreshResult.NOT_LOGGED_IN,
                evaluate(true, false, false, 1_000L, 0L, false));
    }

    @Test
    void endpointUnsupportedStopsForTheWholeSession() {
        assertEquals(SyncService.WardrobeRefreshResult.ENDPOINT_UNSUPPORTED,
                evaluate(true, true, true, 1_000L, 0L, false));
    }

    @Test
    void inFlightWinsOverBackoff() {
        // 已有请求在飞时，"正在刷新"是准确的描述，比"退避中"更贴近玩家感知
        assertEquals(SyncService.WardrobeRefreshResult.IN_FLIGHT,
                evaluate(true, true, false, 1_000L, 5_000L, true));
    }

    @Test
    void backingOffBlocksDispatch() {
        assertEquals(SyncService.WardrobeRefreshResult.BACKING_OFF,
                evaluate(true, true, false, 1_000L, 5_000L, false));
        // 退避到期后立刻可以再试（边界：now == backoffUntil）
        assertEquals(SyncService.WardrobeRefreshResult.DISPATCHED,
                evaluate(true, true, false, 5_000L, 5_000L, false));
    }

    @Test
    void tokenCheckRunsBeforeEndpointCheck() {
        // 没 token 时先说"没登录"：这时提"后端没这个端点"对玩家没有意义（他还没登录过）
        assertEquals(SyncService.WardrobeRefreshResult.NOT_LOGGED_IN,
                evaluate(true, false, true, 1_000L, 0L, false));
    }
}
