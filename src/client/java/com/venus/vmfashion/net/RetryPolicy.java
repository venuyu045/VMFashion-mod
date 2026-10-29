package com.venus.vmfashion.net;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * 弱网重试策略：指数退避 + 抖动 + 尊重 {@code Retry-After} + 熔断。
 *
 * <p>为什么单独成类：这套逻辑（退避序列、抖动边界、熔断状态机、{@code Retry-After} 解析）
 * 全是纯算术，抽出来就能不加载 Minecraft 直接单测；{@link FashionApi} 只负责在失败回调里问它
 * "要不要重试、等多久"。
 *
 * <p>三个刻意的设计：
 * <ul>
 *   <li><b>抖动只作用于自己算出来的退避</b>：服务端明确给了 {@code Retry-After} 时按服务端说的等，
 *       否则一批客户端会同时重试，把刚恢复的后端再打挂；</li>
 *   <li><b>只有"后端不可用"类失败计入熔断</b>：400/401/404 这种确定性错误重试也没用，
 *       若也计入熔断，一个坏参数就能把整个后端拉黑；</li>
 *   <li><b>时钟和随机源可注入</b>：测试里用固定时钟 + 固定抖动值断言精确数值，不靠 sleep。</li>
 * </ul>
 *
 * <p>线程安全：熔断状态用 {@code synchronized} 保护（频率极低，开销可忽略）。
 */
public final class RetryPolicy {

    /** 单次请求最多尝试次数（含首次）。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 第 1 次重试前的基准等待。 */
    public static final long DEFAULT_BASE_DELAY_MS = 400L;
    /** 单次等待上限，避免指数退避把一次查询拖成分钟级。 */
    public static final long DEFAULT_MAX_DELAY_MS = 8_000L;
    /** 抖动幅度：0.5 = 实际等待落在 {@code [raw*(1-0.5), raw]}。 */
    public static final double DEFAULT_JITTER_RATIO = 0.5d;
    /** 连续失败多少次后熔断。 */
    public static final int DEFAULT_BREAKER_THRESHOLD = 5;
    /** 熔断冷却时长。 */
    public static final long DEFAULT_BREAKER_COOLDOWN_MS = 30_000L;
    /** 服务端 {@code Retry-After} 的硬上限：再长也不等，避免一次刷新被卡死。 */
    private static final long MAX_RETRY_AFTER_MS = 60_000L;

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double jitterRatio;
    private final int breakerThreshold;
    private final long breakerCooldownMs;
    private final LongSupplier clock;
    private final DoubleSupplier jitter;

    private int consecutiveFailures;
    /** 熔断截止时间戳；{@code <= 0} 表示熔断关闭。 */
    private long breakerOpenUntilMs;

    /** 生产用默认参数：3 次尝试、400ms 起翻倍、最多 8s、连续 5 次失败熔断 30s。 */
    public RetryPolicy() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_DELAY_MS, DEFAULT_MAX_DELAY_MS, DEFAULT_JITTER_RATIO,
                DEFAULT_BREAKER_THRESHOLD, DEFAULT_BREAKER_COOLDOWN_MS,
                System::currentTimeMillis, Math::random);
    }

    /**
     * 全参数构造（测试用）。
     *
     * @param maxAttempts       最多尝试次数（含首次），{@code < 1} 时按 1 处理
     * @param baseDelayMs       基准退避
     * @param maxDelayMs        退避上限
     * @param jitterRatio       抖动幅度，收敛到 {@code [0, 1]}
     * @param breakerThreshold  连续失败多少次熔断
     * @param breakerCooldownMs 熔断冷却时长
     * @param clock             当前时间戳来源
     * @param jitter            抖动随机源，返回 {@code [0, 1)}，0 = 取退避下界
     */
    public RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs, double jitterRatio,
                       int breakerThreshold, long breakerCooldownMs,
                       LongSupplier clock, DoubleSupplier jitter) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMs = Math.max(0L, baseDelayMs);
        this.maxDelayMs = Math.max(this.baseDelayMs, maxDelayMs);
        this.jitterRatio = Double.isNaN(jitterRatio) ? DEFAULT_JITTER_RATIO
                : Math.max(0d, Math.min(1d, jitterRatio));
        this.breakerThreshold = Math.max(1, breakerThreshold);
        this.breakerCooldownMs = Math.max(0L, breakerCooldownMs);
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.jitter = jitter == null ? Math::random : jitter;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public long baseDelayMs() {
        return baseDelayMs;
    }

    public long maxDelayMs() {
        return maxDelayMs;
    }

    public int breakerThreshold() {
        return breakerThreshold;
    }

    public long breakerCooldownMs() {
        return breakerCooldownMs;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    // ==================== 可重试判定 ====================

    /**
     * 这次失败是否值得重试。
     *
     * <p>只重试"过一会儿可能好"的错误：连接层失败（{@code status <= 0}）、超时、限流、5xx。
     * 4xx 里除 408/429 外都是确定性错误（参数错 / 没权限 / 不存在），重试只会放大问题。
     *
     * @param status HTTP 状态码，{@code <= 0} 表示没拿到响应
     * @param code   后端错误码（{@code network_error} 等）
     */
    public static boolean isRetryable(int status, String code) {
        if ("network_error".equalsIgnoreCase(code)) {
            return true;
        }
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    /**
     * 这次失败是否该计入熔断计数。
     *
     * <p>和 {@link #isRetryable} 的区别：4xx 不计入 —— 否则玩家反复点一个非法时装 ID，
     * 就会把整个后端的请求全部熔断掉，看起来像"后端挂了"。
     */
    public static boolean countsTowardBreaker(int status, String code) {
        if ("network_error".equalsIgnoreCase(code)) {
            return true;
        }
        return status <= 0 || status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    // ==================== 熔断 ====================

    /**
     * 发请求前的闸门。
     *
     * @return {@code true} = 放行；{@code false} = 熔断冷却中，调用方应直接失败
     */
    public synchronized boolean tryAcquire(long nowMs) {
        if (breakerOpenUntilMs <= 0) {
            return true;
        }
        if (nowMs < breakerOpenUntilMs) {
            return false;
        }
        // 冷却结束：先放一个探针请求过去。探针成功会清零计数，失败会重新打开熔断。
        breakerOpenUntilMs = 0;
        return true;
    }

    /** 一次成功：清零连续失败并关闭熔断。 */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        breakerOpenUntilMs = 0;
    }

    /** 一次"后端不可用"类失败：累计到阈值就打开熔断。 */
    public synchronized void recordFailure(long nowMs) {
        consecutiveFailures++;
        if (consecutiveFailures >= breakerThreshold) {
            breakerOpenUntilMs = nowMs + breakerCooldownMs;
        }
    }

    /** 手动重置（切换后端地址 / 玩家点"重试"）。 */
    public synchronized void reset() {
        consecutiveFailures = 0;
        breakerOpenUntilMs = 0;
    }

    /** 当前是否处于熔断冷却中（诊断 / 测试用）。 */
    public synchronized boolean isOpen(long nowMs) {
        return breakerOpenUntilMs > 0 && nowMs < breakerOpenUntilMs;
    }

    // ==================== 退避计算 ====================

    /**
     * 第 {@code attempt} 次失败后，到下次尝试之间的等待毫秒数。
     *
     * @param attempt          已失败的尝试次数，从 1 开始
     * @param retryAfterMillis 服务端 {@code Retry-After} 换算的毫秒数；{@code < 0} 表示没有
     * @return 等待毫秒数，{@code 0} 表示立刻重试
     */
    public long nextDelayMillis(int attempt, long retryAfterMillis) {
        if (retryAfterMillis >= 0) {
            // 服务端说了算：不加抖动，避免"提前"打过去被再次限流
            return Math.min(retryAfterMillis, MAX_RETRY_AFTER_MS);
        }
        long raw = exponentialDelay(attempt);
        double value = jitter.getAsDouble();
        if (Double.isNaN(value) || value < 0d) {
            value = 0d;
        } else if (value >= 1d) {
            value = 0.999_999d;
        }
        // 抖动后落在 [raw*(1-ratio), raw]：ratio=0 时完全确定，ratio=1 时下界为 0
        double factor = 1d - jitterRatio + jitterRatio * value;
        long delay = Math.round(raw * factor);
        return Math.max(0L, Math.min(maxDelayMs, delay));
    }

    /** 指数部分：{@code base * 2^(attempt-1)}，封顶 {@code maxDelayMs} 且防溢出。 */
    private long exponentialDelay(int attempt) {
        int shift = Math.max(0, attempt - 1);
        if (shift > 20) {
            return maxDelayMs;
        }
        long value = baseDelayMs << shift;
        if (value <= 0L || value > maxDelayMs) {
            return maxDelayMs;
        }
        return value;
    }

    /** 当前时间戳（用注入的时钟，便于测试）。 */
    public long now() {
        return clock.getAsLong();
    }

    // ==================== Retry-After 解析 ====================

    /**
     * 解析 {@code Retry-After} 响应头，两种合法格式都支持：
     * <ul>
     *   <li>秒数：{@code "5"} → 5000ms</li>
     *   <li>HTTP-date：{@code "Wed, 21 Oct 2015 07:28:00 GMT"} → 与 {@code nowMs} 的差值</li>
     * </ul>
     *
     * <p>超出 {@value #MAX_RETRY_AFTER_MS}ms 的值会被截断：宁可少等，也不能让一次刷新卡住几分钟。
     *
     * @return 等待毫秒数；头缺失 / 非法返回 {@code -1}
     */
    public static long parseRetryAfterMillis(String header, long nowMs) {
        if (header == null) {
            return -1L;
        }
        String value = header.trim();
        if (value.isEmpty()) {
            return -1L;
        }
        boolean allDigits = true;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            try {
                long seconds = Long.parseLong(value);
                long millis = seconds > MAX_RETRY_AFTER_MS / 1000L ? MAX_RETRY_AFTER_MS : seconds * 1000L;
                return Math.max(0L, Math.min(MAX_RETRY_AFTER_MS, millis));
            } catch (NumberFormatException e) {
                return -1L;
            }
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            long delta = when.toInstant().toEpochMilli() - nowMs;
            return Math.max(0L, Math.min(MAX_RETRY_AFTER_MS, delta));
        } catch (DateTimeParseException e) {
            return -1L;
        }
    }
}
