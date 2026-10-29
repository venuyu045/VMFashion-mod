package com.venus.vmfashion.render.anim;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.render.CosmeticAssets;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每（玩家 × 时装）一份 {@link AnimationFrame} 的缓存。
 *
 * <p>淘汰策略：{@link #frameFor} 在插入新条目时顺手清理"超过 {@value #IDLE_EVICT_MS} 毫秒没被渲染"
 * 的条目 —— 玩家离场、脱下时装后状态不会一直占内存，也不需要额外的定时任务。
 *
 * <p><b>为什么是两层 Map（玩家 → 时装 → 帧）而不是 {@code "uuid|cosmeticId"} 拼字符串</b>：
 * {@code frameFor} 在渲染热路径上（每帧 × 每玩家 × 每件时装），拼 key 会**每帧分配一个 String**，
 * 违反"热路径零分配"。两层 Map 用已有的 {@link UUID} 和 {@code definition.id()} 直接查，零分配。
 *
 * <p>线程约束：只在渲染线程调用（{@code CosmeticRenderDispatcher} / {@code FirstPersonRenderer}）。
 * 用并发容器只是为了 {@code invalidate()} 可能从主线程进来时不至于撕裂。
 */
public final class AnimationCache {

    /** 超过这个时间没被渲染的动画状态会被淘汰。 */
    private static final long IDLE_EVICT_MS = 30_000L;
    /** 表里总条目超过这个值时触发一次清理（避免每次插入都遍历）。 */
    private static final int PRUNE_THRESHOLD = 128;

    /** 玩家 → （时装 ID → 动画帧）。 */
    private static final Map<UUID, Map<String, AnimationFrame>> FRAMES = new ConcurrentHashMap<>();

    private AnimationCache() {
    }

    /**
     * 取（或创建）某个玩家身上某件时装的动画状态。
     *
     * @param uuid       玩家 UUID
     * @param definition 时装（用于拼 key 与日志）
     * @param entry      烘焙后的资源（含解析好的动画表）
     * @return 没有动画 / 创建失败时返回 {@code null}（调用方按静态模型渲染）
     */
    public static AnimationFrame frameFor(UUID uuid, com.venus.vmfashion.cosmetic.CosmeticDefinition definition,
                                          CosmeticAssets.Entry entry) {
        if (uuid == null || definition == null || entry == null || entry.animations().isEmpty()) {
            return null;
        }
        Map<String, AnimationFrame> perPlayer = FRAMES.get(uuid);
        if (perPlayer != null) {
            AnimationFrame cached = perPlayer.get(definition.id());
            if (cached != null) {
                return cached;
            }
        }
        // 一件时装可能有多个动画：默认播第一个（按 anim.json 里的声明顺序），
        // 想指定具体动画可以用 pack.json 的 "animation" 字段指向某个文件（文件名即动画名的一部分）。
        BedrockAnimation animation = entry.animations().values().iterator().next();
        try {
            AnimationFrame frame = AnimationFrame.create(entry.model(), animation);
            if (frame == null) {
                return null;
            }
            Map<String, AnimationFrame> target = perPlayer;
            if (target == null) {
                target = new ConcurrentHashMap<>();
                Map<String, AnimationFrame> existing = FRAMES.putIfAbsent(uuid, target);
                if (existing != null) {
                    target = existing;
                }
            }
            target.put(definition.id(), frame);
            if (totalSize() > PRUNE_THRESHOLD) {
                prune();
            }
            return frame;
        } catch (Throwable t) {
            // 渲染回调里抛异常会崩客户端：动画状态建不起来就退化成静态模型
            VMFashionClient.LOGGER.warn("时装 {} 的动画状态创建失败，按静态模型渲染: {}",
                    definition.id(), t.toString());
            return null;
        }
    }

    /** 清空（{@code /vmfashion reload} / 资源重载时调用）。 */
    public static void invalidate() {
        FRAMES.clear();
    }

    /** 当前缓存的动画状态数量（诊断用）。 */
    public static int size() {
        return totalSize();
    }

    private static int totalSize() {
        int total = 0;
        for (Map<String, AnimationFrame> perPlayer : FRAMES.values()) {
            total += perPlayer.size();
        }
        return total;
    }

    private static void prune() {
        Iterator<Map.Entry<UUID, Map<String, AnimationFrame>>> players = FRAMES.entrySet().iterator();
        while (players.hasNext()) {
            Map<String, AnimationFrame> perPlayer = players.next().getValue();
            Iterator<Map.Entry<String, AnimationFrame>> frames = perPlayer.entrySet().iterator();
            while (frames.hasNext()) {
                if (frames.next().getValue().idleMillis() > IDLE_EVICT_MS) {
                    frames.remove();
                }
            }
            if (perPlayer.isEmpty()) {
                players.remove();
            }
        }
    }
}
