package com.venus.vmfashion.cosmetic;

import java.util.LinkedHashMap;
import java.util.Locale;
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

    /**
     * 按分类 key + 时装 ID 穿戴（用于"本地没装包、但网站衣柜里有"的时装）。
     *
     * <p>没有 {@link CosmeticDefinition} 也要能穿：网站衣柜里的收藏可能是本地还没下载的时装包，
     * 先把 ID 记进穿戴表（渲染层找不到定义就什么都不画，等包下发完重新扫描后自动生效），
     * 再触发时装包下载（调用方负责）。
     *
     * <p>参数为空 / 空白 / 超长 / 含控制字符时直接忽略：宁可什么都不做，也不要往穿戴表里塞脏 key。
     * 这一点很重要 —— 这个方法是<b>唯一</b>能绕开 {@link CosmeticDefinition} 直接写穿戴表的入口，
     * 而它的入参来自服务端下发的收藏列表（{@code WardrobeCollection.Entry}），
     * 一旦塞进脏数据，会顺着 {@link #snapshot()} → 上报后端 → 渲染查找 一路传下去。
     */
    public synchronized void wear(String categoryKey, String cosmeticId) {
        String slot = normalizeSlot(categoryKey);
        String id = normalizeCosmeticId(cosmeticId);
        if (slot.isEmpty() || id.isEmpty()) {
            return;
        }
        worn.put(slot, id);
        LoadoutStore.requestSave();
    }

    /** 槽位 key 长度上限：后端 {@code slot} 字段就是分类 key（最长 {@code "other"}）。 */
    static final int MAX_SLOT_LENGTH = 32;
    /** 时装 ID 长度上限：{@code packId:localId}，两者各自都有更严格的来源校验，这里只是兜底。 */
    static final int MAX_ID_LENGTH = 200;

    /**
     * 规范化槽位 key：trim + 小写；空 / 超长 / 含控制字符返回空串。
     *
     * <p>不做"未知分类 → other"的映射：后端可能先于客户端支持新分类，
     * 强行改成 {@code other} 会把玩家的槽位写错。
     */
    static String normalizeSlot(String categoryKey) {
        if (categoryKey == null) {
            return "";
        }
        String slot = categoryKey.trim().toLowerCase(Locale.ROOT);
        if (slot.isEmpty() || slot.length() > MAX_SLOT_LENGTH || hasControlChar(slot)) {
            return "";
        }
        return slot;
    }

    /** 规范化时装 ID：trim；空 / 超长 / 含控制字符返回空串。 */
    static String normalizeCosmeticId(String cosmeticId) {
        if (cosmeticId == null) {
            return "";
        }
        String id = cosmeticId.trim();
        if (id.isEmpty() || id.length() > MAX_ID_LENGTH || hasControlChar(id)) {
            return "";
        }
        return id;
    }

    /** 是否含 ASCII 控制字符（换行 / NUL 等）：它们会把日志、聊天栏和 JSON 都搅乱。 */
    private static boolean hasControlChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
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
