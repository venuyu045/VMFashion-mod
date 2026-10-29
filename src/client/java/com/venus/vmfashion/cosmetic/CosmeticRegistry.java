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
     * @param id 形如 {@code packId:localId}，也接受不带包名的 {@code localId}（唯一时）
     */
    public CosmeticDefinition find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String needle = id.trim();
        CosmeticDefinition fallback = null;
        for (CosmeticPack pack : packs) {
            for (CosmeticDefinition cosmetic : pack.cosmetics()) {
                if (cosmetic.id().equalsIgnoreCase(needle)) {
                    return cosmetic;
                }
                if (cosmetic.localId().equalsIgnoreCase(needle)) {
                    // 不带包名时只在唯一匹配的情况下采用
                    if (fallback != null) {
                        return null;
                    }
                    fallback = cosmetic;
                }
            }
        }
        return fallback;
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
