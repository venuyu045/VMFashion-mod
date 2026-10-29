package com.venus.vmfashion.cosmetic;

import java.util.Locale;

/**
 * 时装分类。分类只影响 UI 分组和默认挂载骨骼，不限制资源格式。
 */
public enum CosmeticCategory {

    CAPE("cape", "body"),
    WINGS("wings", "body"),
    HAT("hat", "head"),
    BACK("back", "body"),
    PET("pet", "body"),
    AURA("aura", "body"),
    OTHER("other", "body");

    private final String key;
    private final String defaultBone;

    CosmeticCategory(String key, String defaultBone) {
        this.key = key;
        this.defaultBone = defaultBone;
    }

    public String key() {
        return key;
    }

    /** 该分类未显式指定 attachedBone 时使用的默认骨骼。 */
    public String defaultBone() {
        return defaultBone;
    }

    public static CosmeticCategory byKey(String key) {
        if (key == null || key.isBlank()) {
            return OTHER;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (CosmeticCategory category : values()) {
            if (category.key.equals(normalized)) {
                return category;
            }
        }
        return OTHER;
    }
}
