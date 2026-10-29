package com.venus.vmfashion.net;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RetryPolicy} 的纯逻辑单测：退避序列、抖动边界、Retry-After 解析、熔断状态机。 */
class RetryPolicyTest {

    /** 抖动系数 0 时退避完全确定，方便断言精确值。 */
    private static RetryPolicy deterministic(long baseDelayMs, long maxDelayMs, int maxAttempts) {
        return new RetryPolicy(maxAttempts, baseDelayMs, maxDelayMs, 0d, 3, 30_000L,
                () -> 0L, () -> 0.5d);
    }

    @Test
    void exponentialBackoffDoublesWithoutJitter() {
        RetryPolicy policy = deterministic(100L, 8_000L, 5);
        assertEquals(100L, policy.nextDelayMillis(1, -1L));
        assertEquals(200L, policy.nextDelayMillis(2, -1L));
        assertEquals(400L, policy.nextDelayMillis(3, -1L));
        assertEquals(800L, policy.nextDelayMillis(4, -1L));
    }

    @Test
    void backoffIsCappedAtMaxDelay() {
        RetryPolicy policy = deterministic(1_000L, 3_000L, 10);
        // 第 4 次失败理论上是 8000ms，必须被 maxDelayMs 封顶
        assertEquals(3_000L, policy.nextDelayMillis(4, -1L));
        // 位移再大也不能溢出成负数
        assertTrue(policy.nextDelayMillis(60, -1L) > 0L);
    }

    @Test
    void jitterStaysWithinHalfToFullBackoff() {
        long base = 400L;
        RetryPolicy lower = new RetryPolicy(4, base, 8_000L, 0.5d, 3, 30_000L, () -> 0L, () -> 0d);
        assertEquals(base / 2, lower.nextDelayMillis(1, -1L));

        RetryPolicy upper = new RetryPolicy(4, base, 8_000L, 0.5d, 3, 30_000L,
                () -> 0L, () -> 0.999_999d);
        long high = upper.nextDelayMillis(1, -1L);
        assertTrue(high > base / 2 && high <= base, "抖动上界应在 (" + (base / 2) + ", " + base + "]，实际 " + high);

        // 抖动只影响自己算的退避，绝不能越过 raw 上界（否则等于变相延长退避）
        for (double value = 0d; value < 1d; value += 0.05d) {
            final double jitter = value;
            RetryPolicy policy = new RetryPolicy(4, base, 8_000L, 0.5d, 3, 30_000L,
                    () -> 0L, () -> jitter);
            long delay = policy.nextDelayMillis(1, -1L);
            assertTrue(delay >= base / 2 && delay <= base, "抖动值 " + jitter + " 得到越界退避 " + delay);
        }
    }

    @Test
    void retryAfterOverridesBackoffAndIsCapped() {
        RetryPolicy policy = deterministic(100L, 8_000L, 4);
        // 服务端明确要求等 3s：指数退避的 100ms 不算数
        assertEquals(3_000L, policy.nextDelayMillis(1, 3_000L));
        assertEquals(0L, policy.nextDelayMillis(1, 0L));
        // 硬上限 60s：再长也不等，避免一次刷新被卡几分钟
        assertEquals(60_000L, policy.nextDelayMillis(1, 999_000L));
    }

    @Test
    void parsesRetryAfterSecondsAndHttpDate() {
        assertEquals(5_000L, RetryPolicy.parseRetryAfterMillis("5", 0L));
        assertEquals(0L, RetryPolicy.parseRetryAfterMillis("0", 0L));
        assertEquals(-1L, RetryPolicy.parseRetryAfterMillis(null, 0L));
        assertEquals(-1L, RetryPolicy.parseRetryAfterMillis("   ", 0L));
        assertEquals(-1L, RetryPolicy.parseRetryAfterMillis("soon", 0L));
        // 超过 60s 的秒数被截断
        assertEquals(60_000L, RetryPolicy.parseRetryAfterMillis("120", 0L));

        long target = ZonedDateTime.parse("2015-10-21T07:28:00Z").toInstant().toEpochMilli();
        assertEquals(7_000L, RetryPolicy.parseRetryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT", target - 7_000L));
        // 时间点已经过去：立刻重试（0 而不是负数）
        assertEquals(0L, RetryPolicy.parseRetryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT", target + 60_000L));
    }

    @Test
    void classifiesRetryableFailures() {
        assertTrue(RetryPolicy.isRetryable(-1, "network_error"));
        assertTrue(RetryPolicy.isRetryable(429, "rate_limited"));
        assertTrue(RetryPolicy.isRetryable(500, "http_500"));
        assertTrue(RetryPolicy.isRetryable(503, "http_503"));
        assertTrue(RetryPolicy.isRetryable(408, "http_408"));
        assertFalse(RetryPolicy.isRetryable(401, "no_token"));
        assertFalse(RetryPolicy.isRetryable(404, "unknown_cosmetic"));
        assertFalse(RetryPolicy.isRetryable(400, "bad_request"));

        // 4xx 是确定性错误：重试没用，也绝不能计入熔断（否则一个坏请求就拉黑整个后端）
        assertFalse(RetryPolicy.countsTowardBreaker(400, "bad_request"));
        assertFalse(RetryPolicy.countsTowardBreaker(401, "no_token"));
        assertTrue(RetryPolicy.countsTowardBreaker(500, "http_500"));
        assertTrue(RetryPolicy.countsTowardBreaker(-1, "network_error"));
    }

    @Test
    void circuitBreakerOpensAfterThresholdAndRecoversAfterCooldown() {
        AtomicLong now = new AtomicLong(1_000L);
        RetryPolicy policy = new RetryPolicy(3, 100L, 1_000L, 0d, 2, 30_000L, now::get, () -> 0d);

        assertTrue(policy.tryAcquire(now.get()));
        policy.recordFailure(now.get());
        assertTrue(policy.tryAcquire(now.get()), "未到阈值前应放行");

        policy.recordFailure(now.get());
        assertTrue(policy.isOpen(now.get()), "连续 2 次失败应打开熔断");
        assertFalse(policy.tryAcquire(now.get()), "熔断期间应直接失败，不再打后端");

        // 冷却结束：放行一个探针请求
        now.addAndGet(30_001L);
        assertFalse(policy.isOpen(now.get()));
        assertTrue(policy.tryAcquire(now.get()));

        // 探针也失败 → 立刻重新熔断
        policy.recordFailure(now.get());
        assertFalse(policy.tryAcquire(now.get()));

        // 冷却后再放行，这次探针成功 → 彻底恢复
        now.addAndGet(30_001L);
        assertTrue(policy.tryAcquire(now.get()));
        policy.recordSuccess();
        assertEquals(0, policy.consecutiveFailures());
        assertFalse(policy.isOpen(now.get()));
        assertTrue(policy.tryAcquire(now.get()));
    }

    @Test
    void defaultPolicyUsesExpectedAttemptBudget() {
        RetryPolicy policy = new RetryPolicy();
        assertEquals(RetryPolicy.DEFAULT_MAX_ATTEMPTS, policy.maxAttempts());
        assertEquals(RetryPolicy.DEFAULT_BREAKER_THRESHOLD, policy.breakerThreshold());
        // 默认抖动下退避必须落在 [base/2, base]
        long delay = policy.nextDelayMillis(1, -1L);
        assertTrue(delay >= RetryPolicy.DEFAULT_BASE_DELAY_MS / 2
                && delay <= RetryPolicy.DEFAULT_BASE_DELAY_MS, "默认退避越界: " + delay);
    }
}
