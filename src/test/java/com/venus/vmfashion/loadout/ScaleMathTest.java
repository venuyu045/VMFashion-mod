package com.venus.vmfashion.loadout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScaleMath} 单测：单层收敛规则 + 三层相乘。
 *
 * <p>纯逻辑，不加载任何 Minecraft 类。
 */
class ScaleMathTest {

    private static final float EPSILON = 1.0e-6F;

    // ==================== 单层收敛 ====================

    @Test
    void keepsValidScalesUntouched() {
        assertEquals(1.0F, ScaleMath.sanitize(1.0F), EPSILON);
        assertEquals(0.5F, ScaleMath.sanitize(0.5F), EPSILON);
        assertEquals(2.0F, ScaleMath.sanitize(2.0F), EPSILON);
        assertEquals(ScaleMath.MIN_SCALE, ScaleMath.sanitize(ScaleMath.MIN_SCALE), EPSILON);
        assertEquals(ScaleMath.MAX_SCALE, ScaleMath.sanitize(ScaleMath.MAX_SCALE), EPSILON);
    }

    @Test
    void fallsBackToOneForNonFiniteOrNonPositive() {
        // NaN / 无穷 / 0 / 负数：回退 1.0 而不是 0（0 会让模型直接消失，比 1.0 更糟）
        assertEquals(1.0F, ScaleMath.sanitize(Float.NaN), EPSILON);
        assertEquals(1.0F, ScaleMath.sanitize(Float.POSITIVE_INFINITY), EPSILON);
        assertEquals(1.0F, ScaleMath.sanitize(Float.NEGATIVE_INFINITY), EPSILON);
        assertEquals(1.0F, ScaleMath.sanitize(0.0F), EPSILON);
        assertEquals(1.0F, ScaleMath.sanitize(-0.0F), EPSILON);
        assertEquals(1.0F, ScaleMath.sanitize(-3.0F), EPSILON);
        // 注意 Float.MIN_VALUE 是最小的**正数**（4.9e-324），不是负数：属于"合法但极小"，按 clamp 下界处理
        assertEquals(ScaleMath.MIN_SCALE, ScaleMath.sanitize(Float.MIN_VALUE), EPSILON);
    }

    @Test
    void clampsToDocumentedRange() {
        assertEquals(ScaleMath.MAX_SCALE, ScaleMath.sanitize(200.0F), EPSILON);
        assertEquals(ScaleMath.MAX_SCALE, ScaleMath.sanitize(Float.MAX_VALUE), EPSILON);
        assertEquals(ScaleMath.MIN_SCALE, ScaleMath.sanitize(0.0001F), EPSILON);
        assertEquals(ScaleMath.MIN_SCALE, ScaleMath.sanitize(0.005F), EPSILON);
    }

    // ==================== 三层相乘 ====================

    @Test
    void multipliesAllThreeLayers() {
        // 包 2.0 × 后端 3.0 × 全局 4.0
        assertEquals(24.0F, ScaleMath.combine(2.0F, 3.0F, 4.0F), EPSILON);
        assertEquals(6.0F, ScaleMath.combine(1.5F, 2.0F, 2.0F), EPSILON);
        // 缺省层不改变结果
        assertEquals(2.0F, ScaleMath.combine(2.0F, 1.0F, 1.0F), EPSILON);
        assertEquals(3.0F, ScaleMath.combine(1.0F, 3.0F, 1.0F), EPSILON);
        assertEquals(4.0F, ScaleMath.combine(1.0F, 1.0F, 4.0F), EPSILON);
        assertEquals(1.0F, ScaleMath.combine(1.0F, 1.0F, 1.0F), EPSILON);
    }

    @Test
    void dirtyLayerOnlyAffectsItself() {
        // 后端 scale 是 NaN → 按 1.0 处理，其余两层照常相乘
        assertEquals(8.0F, ScaleMath.combine(2.0F, Float.NaN, 4.0F), EPSILON);
        // 包 scale 是 0 → 按 1.0 处理（不能把模型乘没）
        assertEquals(6.0F, ScaleMath.combine(0.0F, 3.0F, 2.0F), EPSILON);
        // 全局 scale 是负数 → 按 1.0 处理
        assertEquals(2.0F, ScaleMath.combine(2.0F, 1.0F, -1.0F), EPSILON);
    }

    @Test
    void extremeCombinationStaysFinite() {
        // 每层都取上限：100 × 100 × 8（全局上限 8）= 80000，必须是有限值
        float result = ScaleMath.combine(ScaleMath.MAX_SCALE, ScaleMath.MAX_SCALE, 8.0F);
        assertTrue(Float.isFinite(result), "三层上限相乘必须仍然有限");
        assertEquals(80_000.0F, result, 1.0F);
    }

    @Test
    void resultIsAlwaysPositiveAndFinite() {
        float[] inputs = {Float.NaN, 0.0F, -1.0F, 0.01F, 1.0F, 100.0F, Float.POSITIVE_INFINITY};
        for (float a : inputs) {
            for (float b : inputs) {
                for (float c : inputs) {
                    float result = ScaleMath.combine(a, b, c);
                    assertTrue(Float.isFinite(result) && result > 0.0F,
                            "combine(" + a + "," + b + "," + c + ") = " + result + " 必须有限且为正");
                }
            }
        }
    }

    @Test
    void constantsMatchPackLoaderSemantics() {
        // 与 PackLoader 对包内 scale 的处理保持一致（0.01 ~ 100，非法回退 1.0）
        assertEquals(0.01F, ScaleMath.MIN_SCALE, EPSILON);
        assertEquals(100.0F, ScaleMath.MAX_SCALE, EPSILON);
        assertEquals(1.0F, ScaleMath.DEFAULT_SCALE, EPSILON);
        assertTrue(ScaleMath.MIN_SCALE < ScaleMath.DEFAULT_SCALE);
        assertTrue(ScaleMath.MAX_SCALE > ScaleMath.DEFAULT_SCALE);
        assertFalse(Float.isNaN(ScaleMath.sanitize(Float.NaN)));
    }
}
