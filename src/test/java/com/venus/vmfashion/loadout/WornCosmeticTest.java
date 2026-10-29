package com.venus.vmfashion.loadout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WornCosmetic} 单测：构造期收敛、空 ID 处理、record 相等性。 */
class WornCosmeticTest {

    private static final float EPSILON = 1.0e-6F;

    @Test
    void keepsValidIdAndScale() {
        WornCosmetic worn = new WornCosmetic("demo:hat", 2.5F);
        assertEquals("demo:hat", worn.cosmeticId());
        assertEquals(2.5F, worn.scale(), EPSILON);
        assertFalse(worn.isBlank());
    }

    @Test
    void ofDefaultsToOne() {
        WornCosmetic worn = WornCosmetic.of("demo:hat");
        assertEquals("demo:hat", worn.cosmeticId());
        assertEquals(ScaleMath.DEFAULT_SCALE, worn.scale(), EPSILON);
    }

    @Test
    void sanitizesScaleAtConstruction() {
        // 后端传什么进来都不能让下游拿到非法值
        assertEquals(1.0F, new WornCosmetic("a:b", Float.NaN).scale(), EPSILON);
        assertEquals(1.0F, new WornCosmetic("a:b", 0.0F).scale(), EPSILON);
        assertEquals(1.0F, new WornCosmetic("a:b", -5.0F).scale(), EPSILON);
        assertEquals(ScaleMath.MAX_SCALE, new WornCosmetic("a:b", 1.0e9F).scale(), EPSILON);
        assertEquals(ScaleMath.MIN_SCALE, new WornCosmetic("a:b", 0.00001F).scale(), EPSILON);
    }

    @Test
    void nullIdBecomesBlankInsteadOfThrowing() {
        // 网络数据缺字段时上层可能传 null：不能 NPE，交给调用方用 isBlank() 跳过
        WornCosmetic worn = new WornCosmetic(null, 2.0F);
        assertEquals("", worn.cosmeticId());
        assertTrue(worn.isBlank());
        assertEquals(2.0F, worn.scale(), EPSILON);
    }

    @Test
    void blankIdIsReported() {
        assertTrue(new WornCosmetic("", 1.0F).isBlank());
        assertTrue(new WornCosmetic("   ", 1.0F).isBlank());
        assertFalse(new WornCosmetic("a:b", 1.0F).isBlank());
    }

    @Test
    void recordEqualityIncludesScale() {
        assertEquals(new WornCosmetic("a:b", 2.0F), new WornCosmetic("a:b", 2.0F));
        assertNotEquals(new WornCosmetic("a:b", 2.0F), new WornCosmetic("a:b", 3.0F));
        assertNotEquals(new WornCosmetic("a:b", 2.0F), new WornCosmetic("a:c", 2.0F));
        // 非法 scale 在构造时就被收敛成 1.0，所以"非法值"与"显式 1.0"相等
        assertEquals(new WornCosmetic("a:b", 1.0F), new WornCosmetic("a:b", Float.NaN));
    }
}
