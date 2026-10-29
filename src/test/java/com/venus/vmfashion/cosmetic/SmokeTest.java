package com.venus.vmfashion.cosmetic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 冒烟测试：验证测试基础设施本身可用（纯逻辑，不加载任何 Minecraft 类）。 */
class SmokeTest {

    @Test
    void categoryLookupFallsBackToOther() {
        assertSame(CosmeticCategory.HAT, CosmeticCategory.byKey("HAT"));
        assertSame(CosmeticCategory.OTHER, CosmeticCategory.byKey("nonsense"));
        assertEquals("head", CosmeticCategory.HAT.defaultBone());
    }
}
