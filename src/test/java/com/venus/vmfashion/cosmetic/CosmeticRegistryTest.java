package com.venus.vmfashion.cosmetic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link CosmeticRegistry} 的查找语义测试（纯逻辑，不加载任何 Minecraft 类）。 */
class CosmeticRegistryTest {

    private final CosmeticRegistry registry = CosmeticRegistry.getInstance();

    @AfterEach
    void clearRegistry() {
        registry.replaceAll(List.of());
    }

    @Test
    void findsByFullId() {
        registry.replaceAll(List.of(pack("packa", "crown")));

        CosmeticDefinition found = registry.find("packa:crown");

        assertSame(CosmeticCategory.HAT, found.category());
        assertEquals("packa:crown", found.id());
    }

    @Test
    void findsByUniqueShortId() {
        registry.replaceAll(List.of(pack("packa", "crown"), pack("packa", "wings")));

        assertEquals("packa:wings", registry.find("wings").id());
    }

    @Test
    void returnsNullOnAmbiguousShortId() {
        registry.replaceAll(List.of(pack("packa", "crown"), pack("packb", "crown")));

        assertNull(registry.find("crown"), "同名时装分布在多个包时必须返回 null，不能猜");
    }

    @Test
    void fullIdIsNotAffectedByAmbiguousShortNames() {
        registry.replaceAll(List.of(pack("packa", "crown"), pack("packb", "crown")));

        assertEquals("packb:crown", registry.find("packb:crown").id());
    }

    @Test
    void lookupIsCaseInsensitive() {
        registry.replaceAll(List.of(pack("packa", "crown")));

        assertEquals("packa:crown", registry.find("PACKA:CROWN").id());
        assertEquals("packa:crown", registry.find("Crown").id());
    }

    @Test
    void blankOrNullIdReturnsNull() {
        registry.replaceAll(List.of(pack("packa", "crown")));

        assertNull(registry.find(null));
        assertNull(registry.find(""));
        assertNull(registry.find("   "));
        assertNull(registry.find("nope"));
    }

    @Test
    void byCategoryFilters() {
        registry.replaceAll(List.of(pack("packa", "crown"), pack("packa", "wings")));

        assertEquals(1, registry.byCategory(CosmeticCategory.HAT).size());
        assertEquals(1, registry.byCategory(CosmeticCategory.WINGS).size());
        assertEquals(0, registry.byCategory(CosmeticCategory.PET).size());
    }

    @Test
    void allAndSizeFlattenPacks() {
        registry.replaceAll(List.of(pack("packa", "crown", "wings"), pack("packb", "halo")));

        assertEquals(3, registry.size());
        assertEquals(2, registry.packs().size());
        assertEquals(3, registry.all().size());
    }

    // ==================== 工具 ====================

    private static CosmeticPack pack(String packId, String... localIds) {
        List<CosmeticDefinition> cosmetics = java.util.Arrays.stream(localIds)
                .map(localId -> definition(packId, localId))
                .toList();
        return new CosmeticPack(packId, packId, "1.0.0", "test", "MIT",
                Path.of("packs").resolve(packId), cosmetics);
    }

    /** 按 ID 猜分类，方便断言（crown/halo → hat，wings → wings）。 */
    private static CosmeticDefinition definition(String packId, String localId) {
        CosmeticCategory category = localId.contains("wing") ? CosmeticCategory.WINGS : CosmeticCategory.HAT;
        return new CosmeticDefinition(packId, localId, localId, category,
                Path.of("cosmetics").resolve(localId).resolve("model.geo.json"),
                Path.of("cosmetics").resolve(localId).resolve("texture.png"),
                null, null, category.defaultBone(), Set.of(), 1.0F);
    }
}
