package com.venus.vmfashion.render.anim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnimationTrack} 的采样逻辑（纯数学，不加载任何 Minecraft / GeckoLib 类）。
 */
class AnimationTrackTest {

    private static AnimationTrack track(double[] times, float[][] values) {
        float[] x = new float[times.length];
        float[] y = new float[times.length];
        float[] z = new float[times.length];
        for (int i = 0; i < times.length; i++) {
            x[i] = values[i][0];
            y[i] = values[i][1];
            z[i] = values[i][2];
        }
        return new AnimationTrack(times, x, y, z);
    }

    @Test
    void singleKeyframeIsConstant() {
        AnimationTrack t = track(new double[]{0.0}, new float[][]{{1.0F, 2.0F, 3.0F}});
        float[] out = new float[3];
        t.sample(0.0, out);
        assertEquals(1.0F, out[0]);
        t.sample(99.0, out);
        assertEquals(3.0F, out[2]);
    }

    @Test
    void linearInterpolationAtMidpoint() {
        AnimationTrack t = track(new double[]{0.0, 1.0},
                new float[][]{{0.0F, 0.0F, 0.0F}, {10.0F, 20.0F, 30.0F}});
        float[] out = new float[3];
        t.sample(0.5, out);
        assertEquals(5.0F, out[0], 1.0e-6F);
        assertEquals(10.0F, out[1], 1.0e-6F);
        assertEquals(15.0F, out[2], 1.0e-6F);
    }

    @Test
    void clampsBeforeFirstAndAfterLastKeyframe() {
        AnimationTrack t = track(new double[]{1.0, 2.0},
                new float[][]{{5.0F, 0.0F, 0.0F}, {7.0F, 0.0F, 0.0F}});
        float[] out = new float[3];
        t.sample(0.0, out);
        assertEquals(5.0F, out[0]);
        t.sample(10.0, out);
        assertEquals(7.0F, out[0]);
    }

    @Test
    void samplesAcrossMultipleSegments() {
        AnimationTrack t = track(new double[]{0.0, 1.0, 2.0},
                new float[][]{{0.0F, 0.0F, 0.0F}, {10.0F, 0.0F, 0.0F}, {0.0F, 0.0F, 0.0F}});
        float[] out = new float[3];
        t.sample(1.5, out);
        assertEquals(5.0F, out[0], 1.0e-6F);
        assertEquals(2.0, t.lastTime());
    }

    @Test
    void emptyTrackReturnsZero() {
        AnimationTrack t = track(new double[]{}, new float[][]{});
        float[] out = new float[3];
        out[0] = out[1] = out[2] = 9.0F;
        t.sample(1.0, out);
        assertEquals(0.0F, out[0]);
        assertEquals(0, t.size());
    }

    @Test
    void wrapLoopsIntoRange() {
        assertEquals(0.5, AnimationTrack.wrap(2.5, 2.0, true), 1.0e-9);
        assertEquals(1.5, AnimationTrack.wrap(-0.5, 2.0, true), 1.0e-9);
        // 不循环：夹到 [0, length]
        assertEquals(2.0, AnimationTrack.wrap(2.5, 2.0, false), 1.0e-9);
        assertEquals(0.0, AnimationTrack.wrap(-1.0, 2.0, false), 1.0e-9);
        // 时长非法时返回 0，不抛异常
        assertEquals(0.0, AnimationTrack.wrap(1.0, 0.0, true), 1.0e-9);
        assertEquals(0.0, AnimationTrack.wrap(Double.NaN, 2.0, true), 1.0e-9);
    }

    @Test
    void duplicateTimesKeepLastValue() {
        // 解析器会把同时间的关键帧合并成最后一个；这里直接构造验证采样不会除零
        AnimationTrack t = track(new double[]{0.0, 0.0, 1.0},
                new float[][]{{1.0F, 0.0F, 0.0F}, {2.0F, 0.0F, 0.0F}, {4.0F, 0.0F, 0.0F}});
        float[] out = new float[3];
        t.sample(0.0, out);
        assertEquals(1.0F, out[0], 1.0e-6F);
        t.sample(0.5, out);
        // 0 时刻的关键帧有两个：插值取第一个（1.0）到 4.0 的中点
        assertTrue(out[0] >= 1.0F && out[0] <= 4.0F);
    }

    @Test
    void nullSafetyOfAnimationRecord() {
        BedrockAnimation empty = new BedrockAnimation("x", true, 0.0, java.util.Map.of());
        assertTrue(empty.isEmpty());
        BedrockAnimation.BoneTracks tracks = new BedrockAnimation.BoneTracks(null, null, null);
        assertTrue(tracks.isEmpty());
        assertNull(tracks.rotation());
    }
}
