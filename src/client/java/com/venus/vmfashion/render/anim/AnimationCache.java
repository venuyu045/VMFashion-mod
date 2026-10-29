package com.venus.vmfashion.render.anim;

import com.venus.vmfashion.render.CosmeticAssets;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每（玩家 × 时装）一份 {@link AnimationFrame} 的缓存。
 *
 * <p>淘汰策略：{@link #frameFor} 在表变大时顺手清理"超过 {@value #IDLE_EVICT_MS} 毫秒没被渲染"的条目
 * —— 玩家离场、脱下时装后状态不会一直占内存，也不需要额外的定时任务。
 */
public final class AnimationCache {

    /** 超过这个时间没被渲染的动画状态会被淘汰。 */
    private static final long IDLE_EVICT_MS = 30_000L;
    /** 表大小超过这个值时触发一次清理（避免每次渲染都遍历）。 */
    private static final int PRUNE_THRESHOLD = 128;

    private static final Map<String, AnimationFrame> FRAMES = new ConcurrentHashMap<>();

    private AnimationCache() {
    }

    /**
     * 取（或创建）某个玩家身上某件时装的动画状态。
     *
     * @param uuid       玩家 UUID
     * @param definition 时装（用于拼 key 与日志）
     * @param entry      烘焙后的资源（含解析好的动画表）
     * @return 没有动画时返回 {@code null}
     */
    public static AnimationFrame frameFor(UUID uuid, com.venus.vmfashion.cosmetic.CosmeticDefinition definition,
                                          CosmeticAssets.Entry entry) {
        if (uuid == null || definition == null || entry == null || entry.animations().isEmpty()) {
            return null;
        }
        String key = uuid + "|" + definition.id();
        AnimationFrame cached = FRAMES.get(key);
        if (cached != null) {
            return cached;
        }
        // 一件时装可能有多个动画：默认播第一个（按 anim.json 里的声明顺序），
        // 想指定具体动画可以用 pack.json 的 "animation" 字段指向某个文件（文件名即动画名的一部分）。
        BedrockAnimation animation = entry.animations().values().iterator().next();
        AnimationFrame frame = AnimationFrame.create(entry.model(), animation);
        if (frame != null) {
            FRAMES.put(key, frame);
            if (FRAMES.size() > PRUNE_THRESHOLD) {
                prune();
            }
        }
        return frame;
    }

    /** 清空（{@code /vmfashion reload} / 资源重载时调用）。 */
    public static void invalidate() {
        FRAMES.clear();
    }

    /** 当前缓存的动画状态数量（诊断用）。 */
    public static int size() {
        return FRAMES.size();
    }

    private static void prune() {
        Iterator<Map.Entry<String, AnimationFrame>> iterator = FRAMES.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().idleMillis() > IDLE_EVICT_MS) {
                iterator.remove();
            }
        }
    }
}
