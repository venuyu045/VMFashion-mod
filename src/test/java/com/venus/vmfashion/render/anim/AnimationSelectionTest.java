package com.venus.vmfashion.render.anim;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnimationSelection} 单测：一件时装里有多个动画时"该播哪个"。
 *
 * <p>纯逻辑：{@link BedrockAnimation} 是 record，不需要 Minecraft 类。
 */
class AnimationSelectionTest {

    private static Map<String, BedrockAnimation> animations() {
        // LinkedHashMap 保序：与解析端一致（anim.json 的声明顺序）
        Map<String, BedrockAnimation> map = new LinkedHashMap<>();
        map.put("idle", new BedrockAnimation("idle", true, 2.0, Map.of()));
        map.put("float", new BedrockAnimation("float", true, 3.0, Map.of()));
        map.put("Wave", new BedrockAnimation("Wave", false, 1.0, Map.of()));
        return map;
    }

    @Test
    void blankNameFallsBackToFirst() {
        Map<String, BedrockAnimation> map = animations();
        assertSame(map.get("idle"), AnimationSelection.select(map, null));
        assertSame(map.get("idle"), AnimationSelection.select(map, ""));
        assertSame(map.get("idle"), AnimationSelection.select(map, "   "));
    }

    @Test
    void exactNameWins() {
        Map<String, BedrockAnimation> map = animations();
        assertSame(map.get("float"), AnimationSelection.select(map, "float"));
        assertSame(map.get("Wave"), AnimationSelection.select(map, "Wave"));
    }

    @Test
    void caseInsensitiveFallback() {
        Map<String, BedrockAnimation> map = animations();
        assertSame(map.get("Wave"), AnimationSelection.select(map, "wave"));
        assertSame(map.get("float"), AnimationSelection.select(map, "FLOAT"));
    }

    @Test
    void unknownNameReturnsNull() {
        Map<String, BedrockAnimation> map = animations();
        // 返回 null 让调用方回退第一个 + 告警，而不是静默播错动画
        assertNull(AnimationSelection.select(map, "not-there"));
        assertFalse(AnimationSelection.hasName(map, "not-there"));
        assertTrue(AnimationSelection.hasName(map, "wave"));
        assertFalse(AnimationSelection.hasName(map, ""));
    }

    @Test
    void emptyOrNullTableReturnsNull() {
        assertNull(AnimationSelection.select(null, "idle"));
        assertNull(AnimationSelection.select(Map.of(), "idle"));
        assertNull(AnimationSelection.select(Map.of(), ""));
        assertFalse(AnimationSelection.hasName(null, "idle"));
        assertFalse(AnimationSelection.hasName(Map.of(), "idle"));
    }

    @Test
    void declaredOrderIsPreserved() {
        // 顺序敏感：没指定名字时必须稳定取第一条（解析端用 LinkedHashMap 保序）
        Map<String, BedrockAnimation> map = animations();
        assertEquals("idle", AnimationSelection.select(map, "").name());
        assertEquals("idle", AnimationSelection.select(map, null).name());
    }
}
