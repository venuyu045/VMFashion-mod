package com.venus.vmfashion.ping.core;

/**
 * 服务端时钟 —— 把「服务端毫秒时间戳」换算到本地时间轴。
 *
 * <p>为什么需要它：标点的 {@code expiresAt} 是**服务端**算出来的（服务端时间 + TTL）。
 * 玩家电脑的时钟可能慢几分钟，直接拿 {@code System.currentTimeMillis()} 比会立刻全部过期，
 * 或者永远不过期。做法是：每次收到服务端时间时记下偏移量 {@code serverTime - localTime}，
 * 之后本地时间加上偏移就是"服务端此刻"。
 *
 * <p>偏移量用 EMA（指数滑动平均）平滑，避免单次网络抖动把时钟带偏：
 * {@code offset = offset * (1-α) + sample * α}，α = 0.25。
 *
 * <p>纯逻辑、无 Minecraft 依赖，可单测。合订版适配：只改包名（{@code com.venus.vmfashion.ping.core}），
 * 算法与独立版 VMPing 完全一致。
 */
public final class PingClock {

    /** 平滑系数：新样本权重。 */
    private static final double ALPHA = 0.25d;
    /** 单次采样允许的最大偏差（超过视为网络抖动/服务端重启，直接重置而不是平滑）。 */
    private static final long MAX_PLAUSIBLE_SKEW_MS = 120_000L;

    private volatile long offsetMs;
    private volatile boolean synced;

    /** 用一次"服务端时间 + 本地收到时间"的采样校准时钟。 */
    public synchronized void sync(long serverTimeMs, long localTimeMs) {
        long sample = serverTimeMs - localTimeMs;
        if (!synced || Math.abs(sample - offsetMs) > MAX_PLAUSIBLE_SKEW_MS) {
            offsetMs = sample;
            synced = true;
            return;
        }
        offsetMs = Math.round(offsetMs * (1d - ALPHA) + sample * ALPHA);
    }

    /** 当前"服务端此刻"的毫秒时间戳（未校准时退化为本地时钟）。 */
    public long now() {
        return System.currentTimeMillis() + offsetMs;
    }

    /** 当前偏移量（诊断用）。 */
    public long offsetMs() {
        return offsetMs;
    }

    public boolean synced() {
        return synced;
    }

    /** 断开连接/换服时重置（不同服务器的时钟不能混用）。 */
    public synchronized void reset() {
        offsetMs = 0L;
        synced = false;
    }
}
