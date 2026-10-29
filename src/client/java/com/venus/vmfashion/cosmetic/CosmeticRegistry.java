package com.venus.vmfashion.cosmetic;

import java.util.ArrayList;
import java.util.List;

/**
 * 已加载的时装总表。线程安全（加载完成后只读，重载时整体替换）。
 */
public final class CosmeticRegistry {

    private static final CosmeticRegistry INSTANCE = new CosmeticRegistry();

    private volatile List<CosmeticPack> packs = List.of();

    private CosmeticRegistry() {
    }

    public static CosmeticRegistry getInstance() {
        return INSTANCE;
    }

    /** 整体替换（由 {@link PackLoader} 在加载完成后调用）。 */
    void replaceAll(List<CosmeticPack> loaded) {
        this.packs = List.copyOf(loaded);
    }

    public List<CosmeticPack> packs() {
        return packs;
    }

    /** 所有时装，按包顺序展开。 */
    public List<CosmeticDefinition> all() {
        List<CosmeticDefinition> result = new ArrayList<>();
        for (CosmeticPack pack : packs) {
            result.addAll(pack.cosmetics());
        }
        return result;
    }

    public int size() {
        return all().size();
    }

    /**
     * 按 ID 查找时装。
     *
     * <p>匹配规则：先找 {@code packId:localId} 全名（大小写不敏感），命中就返回；
     * 没有再找不带包名的 {@code localId}，且<b>只有唯一匹配</b>才返回 —— 同名时装分布在多个包时返回
     * {@code null}，避免"猜错了给玩家穿错东西"。
     *
     * @param id 形如 {@code packId:localId}，也接受不带包名的 {@code localId}（唯一时）
     */
    public CosmeticDefinition find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String needle = id.trim();
        CosmeticDefinition shortMatch = null;
        int shortMatches = 0;
        for (CosmeticPack pack : packs) {
            for (CosmeticDefinition cosmetic : pack.cosmetics()) {
                if (cosmetic.id().equalsIgnoreCase(needle)) {
                    // 全名永远优先：即使前面已经出现过同名短名，也不该判成歧义
                    return cosmetic;
                }
                if (cosmetic.localId().equalsIgnoreCase(needle)) {
                    shortMatch = cosmetic;
                    shortMatches++;
                }
            }
        }
        return shortMatches == 1 ? shortMatch : null;
    }

    public List<CosmeticDefinition> byCategory(CosmeticCategory category) {
        List<CosmeticDefinition> result = new ArrayList<>();
        for (CosmeticDefinition cosmetic : all()) {
            if (cosmetic.category() == category) {
                result.add(cosmetic);
            }
        }
        return result;
    }
}
