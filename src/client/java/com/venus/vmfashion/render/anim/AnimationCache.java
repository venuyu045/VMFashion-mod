package com.venus.vmfashion.render.anim;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.render.CosmeticAssets;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
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
 * <p><b>负缓存</b>：{@code anim.json} 里有动画、但动画里的骨骼名与模型骨骼对不上时，
 * {@link AnimationFrame#create} 会返回 {@code null}。如果不记住这个结果，{@code frameFor}
 * 每帧都会重新 {@code new AnimationFrame}（建 HashMap + 递归遍历全模型骨骼）再丢弃 ——
 * 一个坏包就能让渲染线程每帧白跑一遍并持续产生垃圾。所以"有动画但建不出帧"也缓存下来（负结果）。
 *
 * <p>线程约束：只在渲染线程调用（{@code CosmeticRenderDispatcher} / {@code FirstPersonRenderer}）。
 * 用并发容器只是为了 {@code invalidate()} 可能从主线程进来时不至于撕裂。
 */
public final class AnimationCache {

    /** 超过这个时间没被渲染的动画状态会被淘汰。 */
    private static final long IDLE_EVICT_MS = 30_000L;
    /** 表里总条目超过这个值时触发一次清理（避免每次插入都遍历）。 */
    private static final int PRUNE_THRESHOLD = 128;

    /** 玩家 → 该玩家身上各时装的动画状态。 */
    private static final Map<UUID, PerPlayer> FRAMES = new ConcurrentHashMap<>();

    /**
     * 已经告警过"指定动画名不存在"的时装 ID。
     *
     * <p>为什么要去重：{@code frameFor} 每帧每玩家每件时装都会调一次，名字写错的包会刷爆日志。
     * 只记 ID 不记玩家：同一个包被多个人穿也只提醒一次。
     */
    private static final Set<String> MISSING_NAME_WARNED = ConcurrentHashMap.newKeySet();

    private AnimationCache() {
    }

    /** 单个玩家的缓存：正常帧 + "有动画但建不出帧"的负结果（避免每帧重建）。 */
    private static final class PerPlayer {
        final Map<String, AnimationFrame> frames = new ConcurrentHashMap<>();
        /** 负缓存：这些时装 ID 的动画状态创建失败，本代直接按静态模型渲染。 */
        final Set<String> negatives = ConcurrentHashMap.newKeySet();
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
        String cosmeticId = definition.id();
        PerPlayer perPlayer = FRAMES.get(uuid);
        if (perPlayer != null) {
            AnimationFrame cached = perPlayer.frames.get(cosmeticId);
            if (cached != null) {
                return cached;
            }
            if (perPlayer.negatives.contains(cosmeticId)) {
                // 负缓存命中：明确按静态模型渲染，不再重建（这是防止坏包每帧重建的关键）
                return null;
            }
        }
        // 一件时装可能有多个动画：默认播第一个（按 anim.json 的声明顺序 —— 解析端用 LinkedHashMap 保序），
        // 想指定具体动画用 pack.json / meta.json 的 "animationName"（见 AnimationSelection）。
        BedrockAnimation animation = AnimationSelection.select(entry.animations(), definition.animationName());
        if (animation == null) {
            // 指定了名字但 anim.json 里没有：回退第一个 + 每个时装只告警一次，避免每帧刷日志
            if (definition.animationName() != null && !definition.animationName().isBlank()
                    && MISSING_NAME_WARNED.add(cosmeticId)) {
                VMFashionClient.LOGGER.warn("时装 {} 指定的动画 \"{}\" 不存在，回退播放第一个动画",
                        cosmeticId, definition.animationName());
            }
            animation = AnimationSelection.select(entry.animations(), "");
        }
        if (animation == null) {
            return null;
        }
        try {
            AnimationFrame frame = AnimationFrame.create(entry.model(), animation);
            PerPlayer target = perPlayer;
            if (target == null) {
                PerPlayer created = new PerPlayer();
                PerPlayer existing = FRAMES.putIfAbsent(uuid, created);
                target = existing != null ? existing : created;
            }
            if (frame == null) {
                // 负缓存：骨骼名对不上模型之类的坏包，只尝试一次
                target.negatives.add(cosmeticId);
                if (totalSize() > PRUNE_THRESHOLD) {
                    prune();
                }
                return null;
            }
            target.frames.put(cosmeticId, frame);
            target.negatives.remove(cosmeticId);
            if (totalSize() > PRUNE_THRESHOLD) {
                prune();
            }
            return frame;
        } catch (Throwable t) {
            // 渲染回调里抛异常会崩客户端：动画状态建不起来就退化成静态模型（并记负缓存，避免每帧重试）
            if (perPlayer != null) {
                perPlayer.negatives.add(cosmeticId);
            }
            VMFashionClient.LOGGER.warn("时装 {} 的动画状态创建失败，按静态模型渲染: {}",
                    definition.id(), t.toString());
            return null;
        }
    }

    /**
     * 是否需要尝试创建动画帧（抽出来单测，避免单测依赖 Minecraft 类）。
     *
     * @param hasCachedFrame 已经有缓存的帧
     * @param knownNegative  已知"建不出帧"（负缓存命中）
     */
    static boolean needsCreation(boolean hasCachedFrame, boolean knownNegative) {
        return !hasCachedFrame && !knownNegative;
    }

    /** 清空（{@code /vmfashion reload} / 资源重载时调用）。 */
    public static void invalidate() {
        FRAMES.clear();
        // 重载后包可能已经改好，允许重新告警一次
        MISSING_NAME_WARNED.clear();
    }

    /** 当前缓存的动画状态数量（诊断用）。 */
    public static int size() {
        return totalSize();
    }

    /** 当前负缓存条目数（诊断 / 单测用）。 */
    public static int negativeCount() {
        int total = 0;
        for (PerPlayer perPlayer : FRAMES.values()) {
            total += perPlayer.negatives.size();
        }
        return total;
    }

    private static int totalSize() {
        int total = 0;
        for (PerPlayer perPlayer : FRAMES.values()) {
            total += perPlayer.frames.size() + perPlayer.negatives.size();
        }
        return total;
    }

    private static void prune() {
        Iterator<Map.Entry<UUID, PerPlayer>> players = FRAMES.entrySet().iterator();
        while (players.hasNext()) {
            PerPlayer perPlayer = players.next().getValue();
            Iterator<Map.Entry<String, AnimationFrame>> frames = perPlayer.frames.entrySet().iterator();
            while (frames.hasNext()) {
                if (frames.next().getValue().idleMillis() > IDLE_EVICT_MS) {
                    frames.remove();
                }
            }
            if (perPlayer.frames.isEmpty() && perPlayer.negatives.isEmpty()) {
                players.remove();
            }
        }
    }
}
