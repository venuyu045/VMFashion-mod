package com.venus.vmfashion.render.anim;

import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一个（玩家 × 时装）实例的动画播放状态。
 *
 * <p><b>为什么需要"每玩家一份"</b>：{@link BakedGeoModel} 是每件时装烘焙一次、所有玩家共享的，
 * 而 {@link GeoBone#frameSnapshot} 是共享骨骼上的可变字段。如果把 A 的动画帧写进共享骨骼，
 * B 就会跟着动（多人同屏必现）。所以这里给每个（玩家 × 时装）配一套独立的
 * {@link BoneSnapshot}，渲染时临时挂到共享骨骼上、画完立刻还原
 * （{@code GeoModelRenderer} 负责挂/还原，26.x 的 flush 回调是单线程串行执行的，安全）。
 *
 * <p>时间基准是创建时的 {@link System#nanoTime()}，所以同一件时装在不同玩家身上
 * 相位不同（看起来更自然，也避免所有人整齐划一地摆动）。
 */
public final class AnimationFrame {

    private final BedrockAnimation animation;
    private final Map<GeoBone, BoneSnapshot> snapshots = new IdentityHashMap<>();
    /** 骨骼名（小写）→ 骨骼，用于把动画里的骨骼名对上真实骨骼。 */
    private final Map<String, GeoBone> bonesByName;
    private final long startNanos = System.nanoTime();
    /** 采样用的复用缓冲，避免每帧每骨骼分配数组。 */
    private final float[] scratch = new float[3];

    private long lastUsedNanos = startNanos;
    /**
     * 上次求值的时间点；同一帧里主层 + emissive 层会各调一次 {@link #update()}，第二次直接复用。
     *
     * <p>初值用 {@code 0} 当哨兵（{@code System.nanoTime()} 不保证从 0 开始，但绝不会是 0
     * —— 它是任意原点起的单调时钟）。<b>不能用 {@code Long.MIN_VALUE} 当初值</b>：
     * {@code now - Long.MIN_VALUE} 会溢出成负数，导致"永远小于阈值"、动画一次都不采样。
     */
    private long lastUpdateNanos = 0L;

    private AnimationFrame(BakedGeoModel model, BedrockAnimation animation) {
        this.animation = animation;
        this.bonesByName = indexBones(model);
        // 只为"动画里真的有通道"的骨骼建快照，静态骨骼保持 frameSnapshot = null（零额外开销）
        for (String boneName : animation.bones().keySet()) {
            GeoBone bone = bonesByName.get(boneName);
            if (bone != null) {
                snapshots.put(bone, BoneSnapshot.create(bone));
            }
        }
    }

    /**
     * 创建（或复用）一帧动画状态。
     *
     * @return 模型里找不到任何动画骨骼时返回 {@code null}（调用方按静态模型渲染）
     */
    public static AnimationFrame create(BakedGeoModel model, BedrockAnimation animation) {
        if (model == null || animation == null || animation.isEmpty()) {
            return null;
        }
        AnimationFrame frame = new AnimationFrame(model, animation);
        if (frame.snapshots.isEmpty()) {
            return null;
        }
        frame.update();
        return frame;
    }

    /** 动画是否循环。 */
    public boolean loop() {
        return animation.loop();
    }

    /** 当前帧的骨骼快照（渲染时由 {@code GeoModelRenderer} 读取）。 */
    public Map<GeoBone, BoneSnapshot> snapshots() {
        lastUsedNanos = System.nanoTime();
        return snapshots;
    }

    /**
     * 按当前时间重新采样，把结果写进快照。
     *
     * <p><b>必须在渲染回调内调用</b>：26.x 是延迟提交，submit 阶段算出来的值到 flush 时已经过时了。
     */
    public void update() {
        long now = System.nanoTime();
        // 同一帧里主层和 emissive 层会各调一次；两次间隔通常在微秒级，
        // 复用第一次的采样结果：既省一遍全部骨骼采样，也让两层用**同一组**值（更正确）。
        // lastUpdateNanos == 0 表示"还没求过值"，必须走到下面的采样（否则初值会踩溢出坑）
        if (shouldReuseSample(now, lastUpdateNanos)) {
            lastUsedNanos = now;
            return;
        }
        lastUpdateNanos = now;
        double seconds = (now - startNanos) / 1_000_000_000.0;
        double t = AnimationTrack.wrap(seconds, animation.length(), animation.loop());
        for (Map.Entry<String, BedrockAnimation.BoneTracks> entry : animation.bones().entrySet()) {
            GeoBone bone = bonesByName.get(entry.getKey());
            if (bone == null) {
                continue;
            }
            BoneSnapshot snapshot = snapshots.get(bone);
            if (snapshot == null) {
                continue;
            }
            BedrockAnimation.BoneTracks tracks = entry.getValue();
            if (tracks.rotation() != null) {
                tracks.rotation().sample(t, scratch);
                snapshot.setRotation(scratch[0], scratch[1], scratch[2]);
            } else {
                snapshot.setRotation(0.0F, 0.0F, 0.0F);
            }
            if (tracks.position() != null) {
                tracks.position().sample(t, scratch);
                snapshot.setTranslation(scratch[0], scratch[1], scratch[2]);
            } else {
                snapshot.setTranslation(0.0F, 0.0F, 0.0F);
            }
            if (tracks.scale() != null) {
                tracks.scale().sample(t, scratch);
                snapshot.setScale(scratch[0], scratch[1], scratch[2]);
            } else {
                snapshot.setScale(1.0F, 1.0F, 1.0F);
            }
        }
        lastUsedNanos = System.nanoTime();
    }

    /**
     * 采样结果是否可以复用（同一帧里主层 + emissive 层会各调一次 {@link #update()}）。
     *
     * <p>抽成静态方法是为了能单测：这里踩过一个很隐蔽的坑 ——
     * 初值如果用 {@code Long.MIN_VALUE}，{@code now - lastUpdateNanos} 会溢出成负数，
     * 于是"间隔 < 1ms"永远成立，动画一次都不会被采样。所以：
     * <ul>
     *   <li>{@code lastUpdateNanos == 0} 视为"从未求值"，必须采样；</li>
     *   <li>其余情况只在间隔小于 {@value #SAMPLE_REUSE_WINDOW_NANOS}ns 时复用。</li>
     * </ul>
     *
     * @param now             当前 {@link System#nanoTime()}
     * @param lastUpdateNanos 上次求值的时间点（0 = 从未求值）
     */
    static boolean shouldReuseSample(long now, long lastUpdateNanos) {
        if (lastUpdateNanos == 0L) {
            return false; // 从未求值
        }
        long delta = now - lastUpdateNanos;
        // delta >= 0 这一步是防溢出的：nanoTime 单调递增，正常情况 delta 一定 >= 0；
        // 一旦算出来是负数（时钟异常 / 极端输入），宁可重新采样也不要错误复用。
        return delta >= 0L && delta < SAMPLE_REUSE_WINDOW_NANOS;
    }

    /** 同一帧内复用的时间窗口（纳秒）。取 1ms：远大于两层提交的间隔，远小于任何真实帧间隔。 */
    static final long SAMPLE_REUSE_WINDOW_NANOS = 1_000_000L;

    /** 多久没被渲染过了（毫秒），用于淘汰离场玩家的动画状态。 */
    public long idleMillis() {
        return (System.nanoTime() - lastUsedNanos) / 1_000_000L;
    }

    /** 把模型里的骨骼按名字（小写）建索引。 */
    private static Map<String, GeoBone> indexBones(BakedGeoModel model) {
        Map<String, GeoBone> index = new HashMap<>(32);
        for (GeoBone bone : model.topLevelBones()) {
            indexBone(bone, index);
        }
        return index;
    }

    private static void indexBone(GeoBone bone, Map<String, GeoBone> index) {
        if (bone == null) {
            return;
        }
        String name = bone.name();
        if (name != null) {
            index.putIfAbsent(name.toLowerCase(Locale.ROOT), bone);
        }
        for (GeoBone child : bone.children()) {
            indexBone(child, index);
        }
    }
}
