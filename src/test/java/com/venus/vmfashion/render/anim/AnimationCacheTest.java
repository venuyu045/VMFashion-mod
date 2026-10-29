package com.venus.vmfashion.render.anim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动画缓存的"是否需要创建"判定（纯逻辑，不加载 Minecraft 类）。
 *
 * <p>重点是负缓存：某件时装的 anim.json 有动画但骨骼名对不上模型时，
 * {@link AnimationFrame#create} 返回 null。如果不记住这个结果，渲染线程每帧都会重建一遍
 * （建 HashMap + 递归遍历全模型），坏包就能持续卡帧/GC。
 */
class AnimationCacheTest {

    @Test
    void needsCreationWhenNeitherCachedNorNegative() {
        assertTrue(AnimationCache.needsCreation(false, false));
    }

    @Test
    void skipsWhenFrameCached() {
        assertFalse(AnimationCache.needsCreation(true, false));
    }

    @Test
    void skipsWhenNegativeCached() {
        // 负缓存命中：不能每帧重试创建
        assertFalse(AnimationCache.needsCreation(false, true));
        assertFalse(AnimationCache.needsCreation(true, true));
    }

    @Test
    void emptyCacheHasNoNegatives() {
        AnimationCache.invalidate();
        org.junit.jupiter.api.Assertions.assertEquals(0, AnimationCache.negativeCount());
        org.junit.jupiter.api.Assertions.assertEquals(0, AnimationCache.size());
    }
}
