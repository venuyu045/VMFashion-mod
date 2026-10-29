package com.venus.vmfashion.render.anim;

/**
 * 一条关键帧时间轴（某个骨骼的 rotation / position / scale 之一）。
 *
 * <p>用并行数组而不是 {@code List<Keyframe>}：采样发生在渲染热路径上
 * （每帧 × 每玩家 × 每件时装 × 每根骨骼），并行数组可以做到**零分配**。
 *
 * <p>单位约定（与 Bedrock / GeckoLib 一致）：
 * <ul>
 *   <li>{@code rotation}：<b>弧度</b>（解析时已从 JSON 的度数转换）；</li>
 *   <li>{@code position}：Bedrock 的 1/16 格（{@code BoneSnapshot#translate} 自己会除以 16）；</li>
 *   <li>{@code scale}：倍率，1.0 = 原始大小。</li>
 * </ul>
 */
public final class AnimationTrack {

    /** 关键帧时间（秒），严格递增。 */
    private final double[] times;
    private final float[] x;
    private final float[] y;
    private final float[] z;

    AnimationTrack(double[] times, float[] x, float[] y, float[] z) {
        this.times = times;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int size() {
        return times.length;
    }

    /** 最后一个关键帧的时间（秒）。 */
    public double lastTime() {
        return times.length == 0 ? 0.0 : times[times.length - 1];
    }

    /**
     * 在 {@code time} 处采样，结果写进 {@code out[0..2]}。
     *
     * <p>线性插值：Bedrock 的 {@code lerp_mode} 为 {@code catmullrom} 时按线性处理
     * （曲线插值的差异在时装上肉眼几乎看不出来，但实现复杂度差一个量级）。
     * 时间超出两端时取端点值（循环由调用方先用 {@link #wrap(double, double, boolean)} 处理）。
     */
    public void sample(double time, float[] out) {
        int count = times.length;
        if (count == 0) {
            out[0] = 0.0F;
            out[1] = 0.0F;
            out[2] = 0.0F;
            return;
        }
        if (count == 1 || time <= times[0]) {
            out[0] = x[0];
            out[1] = y[0];
            out[2] = z[0];
            return;
        }
        if (time >= times[count - 1]) {
            out[0] = x[count - 1];
            out[1] = y[count - 1];
            out[2] = z[count - 1];
            return;
        }
        // 二分查找：找到最后一个 times[index] <= time 的下标（结果与线性扫描完全一致）。
        // 关键帧通常只有几个，线性扫描本来也不慢；但"烘焙过 / 恶意"的动画可能有上万个关键帧，
        // 那时线性扫描会让每帧采样退化成 O(n)（骨骼数 × 3 通道 × n），直接卡死一帧。
        // 二分是 O(log n)，对少量关键帧的额外开销也可以忽略。
        int lo = 0;
        int hi = count - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= time) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        int index = lo;
        double span = times[index + 1] - times[index];
        double t = span <= 0.0 ? 0.0 : (time - times[index]) / span;
        float f = (float) t;
        out[0] = x[index] + (x[index + 1] - x[index]) * f;
        out[1] = y[index] + (y[index + 1] - y[index]) * f;
        out[2] = z[index] + (z[index + 1] - z[index]) * f;
    }

    /**
     * 把任意时间映射到 {@code [0, length)}（循环）或 {@code [0, length]}（不循环）。
     *
     * @param length 动画时长（秒），{@code <= 0} 时返回 0
     */
    public static double wrap(double time, double length, boolean loop) {
        if (!(length > 0.0) || Double.isNaN(time)) {
            return 0.0;
        }
        if (loop) {
            double t = time % length;
            return t < 0.0 ? t + length : t;
        }
        if (time < 0.0) {
            return 0.0;
        }
        return Math.min(time, length);
    }
}
