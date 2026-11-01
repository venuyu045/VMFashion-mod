package com.venus.vmfashion.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时装 ID 比较规则（纯逻辑，不加载 Minecraft 类）。
 *
 * <p>回归的是 v1.3.0 衣柜的一个真实缺陷：未下载的收藏点穿上时，写进 {@code LocalLoadout}
 * 的是服务端给的<b>短 ID</b>（{@code angel}）；时装包下载完、注册表重建之后，同一个条目的
 * ID 变成全局 ID（{@code lw_angel:angel}）。只做字符串相等比较会出现：
 * <ul>
 *   <li>卡片从"已穿"退回"未穿"（✓ 角标消失、穿上按钮重新亮起）；</li>
 *   <li>右侧预览面板被清空（选中条目被判为失效）—— 玩家正盯着它等下载结果。</li>
 * </ul>
 */
class CosmeticIdMatchTest {

    @Test
    void fullIdsMatch() {
        assertTrue(CosmeticIdMatch.same("lw_angel:angel", "lw_angel:angel"));
    }

    @Test
    void fullIdMatchesCaseInsensitively() {
        assertTrue(CosmeticIdMatch.same("LW_Angel:Angel", "lw_angel:angel"));
    }

    @Test
    void shortIdMatchesGlobalId() {
        // 核心用例：穿戴表里存的是短 ID，条目在包下发后变成全局 ID
        assertTrue(CosmeticIdMatch.same("angel", "lw_angel:angel"));
        assertTrue(CosmeticIdMatch.same("lw_angel:angel", "angel"));
    }

    @Test
    void shortIdMatchingIsCaseInsensitive() {
        assertTrue(CosmeticIdMatch.same("ANGEL", "lw_angel:angel"));
    }

    @Test
    void differentShortIdsDoNotMatch() {
        // 不同包里的同名短 ID 不能算同一件（穿 A 显示成 B 比"显示没穿"更糟）
        assertFalse(CosmeticIdMatch.same("lw_angel:angel", "other_pack:angel"));
        assertFalse(CosmeticIdMatch.same("angel", "devil"));
    }

    @Test
    void nullsAreNotEqual() {
        assertFalse(CosmeticIdMatch.same(null, "angel"));
        assertFalse(CosmeticIdMatch.same("angel", null));
        assertFalse(CosmeticIdMatch.same(null, null));
    }

    @Test
    void localIdOfStripsOnlyFirstSegment() {
        assertEquals("angel", CosmeticIdMatch.localIdOf("lw_angel:angel"));
        // 没有冒号 → 原样
        assertEquals("angel", CosmeticIdMatch.localIdOf("angel"));
        // 冒号在末尾 → 原样（避免返回空串把"两个空 ID"判成相等）
        assertEquals("lw_angel:", CosmeticIdMatch.localIdOf("lw_angel:"));
        // localId 里再出现冒号：只剥第一段，与 AssetSyncService#requestMissing 同口径
        assertEquals("a:b", CosmeticIdMatch.localIdOf("pack:a:b"));
        assertEquals("", CosmeticIdMatch.localIdOf(null));
    }

    @Test
    void trailingColonDoesNotCollapseToEmptyMatch() {
        // "lw_angel:" 与 "pack:" 的 localId 都是空串 —— 不能被判成同一件
        assertFalse(CosmeticIdMatch.same("lw_angel:", "pack:"));
    }
}
