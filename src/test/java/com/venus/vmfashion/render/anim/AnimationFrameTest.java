package com.venus.vmfashion.render.anim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动画采样的"同帧复用"判定。
 *
 * <p>这个类存在的唯一原因是一个踩过的坑：初值用 {@code Long.MIN_VALUE} 时
 * {@code now - lastUpdateNanos} 会整数溢出成负数，导致"间隔小于阈值"永远成立、
 * 动画一次都不采样。所以这里专门把溢出边界钉死。
 */
class AnimationFrameTest {

    @Test
    void neverSampledMustNotReuse() {
        // 0 = 哨兵"从未求值"，必须采样（不能因为 0 比阈值小就复用）
        assertFalse(AnimationFrame.shouldReuseSample(1_000_000_000L, 0L));
        assertFalse(AnimationFrame.shouldReuseSample(Long.MAX_VALUE, 0L));
    }

    @Test
    void neverOverflowsOnExtremePreviousValues() {
        // 曾经的 bug：初值 Long.MIN_VALUE，now - MIN_VALUE 溢出成负数 → 被误判成"刚求过值"
        assertFalse(AnimationFrame.shouldReuseSample(1_000L, Long.MIN_VALUE),
                "Long.MIN_VALUE 作为上次时间必须判为'不复用'（旧实现会溢出并永远复用）");
        assertFalse(AnimationFrame.shouldReuseSample(0L, Long.MIN_VALUE));
        // 极端大值同样不能溢出成负数
        assertFalse(AnimationFrame.shouldReuseSample(0L, Long.MAX_VALUE));
        assertFalse(AnimationFrame.shouldReuseSample(1L, Long.MAX_VALUE));
    }

    @Test
    void reusesWithinWindow() {
        long now = 5_000_000_000L;
        assertTrue(AnimationFrame.shouldReuseSample(now, now));
        assertTrue(AnimationFrame.shouldReuseSample(now, now - 1));
        assertTrue(AnimationFrame.shouldReuseSample(now, now - (AnimationFrame.SAMPLE_REUSE_WINDOW_NANOS - 1)));
    }

    @Test
    void doesNotReuseOutsideWindow() {
        long now = 5_000_000_000L;
        assertFalse(AnimationFrame.shouldReuseSample(now, now - AnimationFrame.SAMPLE_REUSE_WINDOW_NANOS));
        // 一帧 16.7ms（60fps）远大于 1ms 窗口 → 每帧都会重新采样
        assertFalse(AnimationFrame.shouldReuseSample(now, now - 16_666_666L));
    }
}
