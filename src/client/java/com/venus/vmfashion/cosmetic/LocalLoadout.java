package com.venus.vmfashion.cosmetic;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地玩家的穿戴状态：分类 → 时装 ID。
 *
 * <p>这是纯客户端状态，渲染层只读它；同步层（服务端 / 中心 API）将来会用它作为"我穿了什么"的载荷。
 * 线程安全：渲染线程读、命令线程写，因此内部用 {@code synchronized} 保护。
 */
public final class LocalLoadout {

    private static final LocalLoadout INSTANCE = new LocalLoadout();

    /** 分类 key（小写）→ 时装全局 ID。 */
    private final Map<String, String> worn = new LinkedHashMap<>();

    private LocalLoadout() {
    }

    public static LocalLoadout getInstance() {
        return INSTANCE;
    }

    public synchronized String get(String categoryKey) {
        return worn.get(categoryKey);
    }

    public synchronized void wear(CosmeticDefinition cosmetic) {
        worn.put(cosmetic.category().key(), cosmetic.id());
        // 纯本地模式下没有云端兜底，改完立刻请求落盘（LoadoutStore 内部 debounce，连续换装只写一次）
        LoadoutStore.requestSave();
    }

    public synchronized String unwear(String categoryKey) {
        String removed = worn.remove(categoryKey);
        if (removed != null) {
            LoadoutStore.requestSave();
        }
        return removed;
    }

    public synchronized void clear() {
        boolean changed = !worn.isEmpty();
        worn.clear();
        if (changed) {
            LoadoutStore.requestSave();
        }
    }

    /**
     * 用远端配置整体替换本地穿戴（网站衣柜 → 游戏内同步）。
     *
     * <p>只接受非空 key/value，避免脏数据把本地状态清成一半。
     */
    public synchronized void replaceAll(Map<String, String> loadout) {
        worn.clear();
        if (loadout != null) {
            loadout.forEach((slot, id) -> {
                if (slot != null && !slot.isBlank() && id != null && !id.isBlank()) {
                    worn.put(slot.toLowerCase(java.util.Locale.ROOT), id);
                }
            });
        }
        // 云端拉回 / 本地文件恢复的穿戴同样要落盘：断网、后端挂了时这就是唯一副本
        LoadoutStore.requestSave();
    }

    /** 当前穿戴的快照（分类 → 时装 ID）。 */
    public synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(worn);
    }

    /** 解析成定义对象，缺失或已失效的返回 {@code null}。 */
    public CosmeticDefinition resolve(String categoryKey) {
        String id = get(categoryKey);
        return id == null ? null : CosmeticRegistry.getInstance().find(id);
    }
}
