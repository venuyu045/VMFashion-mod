package com.venus.vmfashion.cosmetic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalLoadout#wear(String, String)} 的入参加固单测。
 *
 * <p>背景：{@code wear(categoryKey, cosmeticId)} 是 v1.3.0 新增的<b>唯一</b>能绕开
 * {@link CosmeticDefinition} 直接写穿戴表的入口（衣柜里"本地没装包"的收藏走这条路）。
 * 它的两个参数最终来自服务端下发的收藏列表，会顺着
 * {@code snapshot()} → 上报后端 → 渲染查找 一路传下去：
 * 空串会污染槽位、超长 / 带控制字符（换行、NUL）会搅乱日志与聊天栏、把 JSON 撑大。
 *
 * <p>纯逻辑，不加载任何 Minecraft 类（{@link LoadoutStore} 的静态初始化只建锁和懒线程池）。
 */
class LocalLoadoutTest {

    private final LocalLoadout loadout = LocalLoadout.getInstance();

    @AfterEach
    void tearDown() {
        loadout.clear();
    }

    @Test
    void wearStoresNormalizedSlotAndId() {
        loadout.wear("  WINGS  ", "  lw_angel:angel  ");

        assertEquals(Map.of("wings", "lw_angel:angel"), loadout.snapshot());
    }

    @Test
    void wearIgnoresBlankArguments() {
        loadout.wear(null, "a:b");
        loadout.wear("wings", null);
        loadout.wear("", "a:b");
        loadout.wear("   ", "a:b");
        loadout.wear("wings", "");
        loadout.wear("wings", "   ");

        assertTrue(loadout.snapshot().isEmpty(), "空 / 空白参数一律忽略，不能往穿戴表里塞脏 key");
    }

    @Test
    void wearRejectsControlCharacters() {
        loadout.wear("wings", "a:b\nsecond line");
        loadout.wear("wings", "a:b\u0000tail");
        loadout.wear("wi\nngs", "a:b");

        assertTrue(loadout.snapshot().isEmpty(), "控制字符会搅乱日志 / 聊天栏 / JSON");
    }

    @Test
    void wearRejectsOverlongArguments() {
        loadout.wear("wings", "a:" + "x".repeat(LocalLoadout.MAX_ID_LENGTH));
        loadout.wear("s".repeat(LocalLoadout.MAX_SLOT_LENGTH + 1), "a:b");

        assertTrue(loadout.snapshot().isEmpty());
    }

    @Test
    void wearAcceptsBoundaryLengths() {
        String id = "a:" + "x".repeat(LocalLoadout.MAX_ID_LENGTH - 2);
        loadout.wear("other", id);

        assertEquals(Map.of("other", id), loadout.snapshot());
    }

    @Test
    void normalizeSlotDoesNotRemapUnknownCategories() {
        // 后端可能先于客户端支持新分类：强行改成 other 会把玩家的槽位写错
        assertEquals("shoes", LocalLoadout.normalizeSlot("SHOES"));
        assertEquals("", LocalLoadout.normalizeSlot(null));
        assertEquals("", LocalLoadout.normalizeSlot("   "));
    }

    @Test
    void normalizeCosmeticIdKeepsUnicode() {
        // 用户自制包的 localId 允许中文（PackLoader#isSafeLocalId 是黑名单，不是白名单）
        assertEquals("皇冠:王冠-1.0", LocalLoadout.normalizeCosmeticId("  皇冠:王冠-1.0  "));
    }
}
