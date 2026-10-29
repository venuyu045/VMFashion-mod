package com.venus.vmfashion.render;

import com.venus.vmfashion.loadout.LoadoutService;
import com.venus.vmfashion.loadout.WornCosmetic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 渲染热路径的"每 tick 快照"缓存。
 *
 * <p>为什么需要：{@code LoadoutService#loadoutFor} 对本地玩家每次调用都会
 * {@code new LinkedHashMap}（{@code LocalLoadout#snapshot}），而它原本每帧被调<strong>两次</strong>
 * （{@code submit} 一次、{@code applyHideParts} 一次）—— 10 个玩家就是 20 个 Map/帧。
 * 这里按 tick 缓存一次，把每帧的分配降到每玩家每 tick 1 个。
 *
 * <p>缓存的是 {@code slot → WornCosmetic}（ID + 后端 scale），所以渲染热路径上既不用重建
 * record，也不用为了取 scale 再查一次 Map。
 *
 * <p>为什么是 tick 而不是帧：穿戴数据只可能被 tick 线程（命令 / GUI）或网络回调线程改，
 * 网络回调写入的是并发容器、由 tick 线程消费；50ms 的延迟对肉眼不可见，
 * 但可以把每帧 N 次 Map 拷贝压成每 tick 1 次。
 *
 * <p>线程约束：只在渲染线程访问（普通 {@code HashMap} 即可，不需要并发容器）。
 */
final class RenderCache {

    private static final Map<UUID, Entry> CACHE = new HashMap<>();

    /** 上一次缓存所属的 level；换世界时整表丢弃，避免把上个世界的穿戴带过来。 */
    private static ClientLevel cachedLevel;
    private static long cachedTick = Long.MIN_VALUE;

    private RenderCache() {
    }

    /** 某个玩家本 tick 的穿戴快照（分类 → {@link WornCosmetic}）。只读，不要修改。 */
    static Map<String, WornCosmetic> loadout(UUID uuid) {
        return entry(uuid).loadout;
    }

    /**
     * 某个玩家本 tick 的 hideParts 掩码，见 {@link HiddenParts}。
     *
     * <p>懒计算：{@code applyHideParts} 每帧都会要，{@code submit} 不一定（例如没有时装时
     * 提前返回），所以第一次真正需要时才去遍历穿戴列表。
     */
    static int hiddenMask(UUID uuid) {
        Entry entry = entry(uuid);
        if (!entry.hiddenReady) {
            entry.hiddenMask = CosmeticRenderDispatcher.computeHiddenMask(entry.loadout);
            entry.hiddenReady = true;
        }
        return entry.hiddenMask;
    }

    private static Entry entry(UUID uuid) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (level != cachedLevel) {
            CACHE.clear();
            cachedLevel = level;
            cachedTick = Long.MIN_VALUE;
        }
        long tick = level == null ? 0L : level.getGameTime();
        if (tick != cachedTick) {
            CACHE.clear();
            cachedTick = tick;
        }
        Entry entry = CACHE.get(uuid);
        if (entry == null) {
            entry = new Entry(LoadoutService.getInstance().loadoutFor(uuid));
            CACHE.put(uuid, entry);
        }
        return entry;
    }

    private static final class Entry {
        private final Map<String, WornCosmetic> loadout;
        private int hiddenMask;
        private boolean hiddenReady;

        private Entry(Map<String, WornCosmetic> loadout) {
            this.loadout = loadout;
        }
    }
}
