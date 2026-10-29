package com.venus.vmfashion.cosmetic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CosmeticResourceId} 测试（路线图 #13：贴图 Identifier 撞车）。
 *
 * <p>纯逻辑，不加载任何 Minecraft 类 —— 这里验证的正是"两个不同来源绝不会得到同一个资源路径"。
 */
class CosmeticResourceIdTest {

    @Test
    void distinguishesIdsThatSanitiseToTheSameString() {
        // 旧的 resourceId 会把 ':' 换成 '_' 并小写，于是这两件时装落到同一个 Identifier，
        // 后加载的那件直接复用前一件的贴图。
        String colon = CosmeticResourceId.pathFor("a", "b:c", "texture");
        String underscore = CosmeticResourceId.pathFor("a", "b_c", "texture");

        assertNotEquals(colon, underscore, "不同来源必须映射到不同路径");
        assertTrue(colon.startsWith("a/b_c/texture."), "可读部分会被压成同样的字符: " + colon);
        assertTrue(underscore.startsWith("a/b_c/texture."), "可读部分会被压成同样的字符: " + underscore);
    }

    @Test
    void distinguishesPackAndLocalIdSplits() {
        assertNotEquals(CosmeticResourceId.pathFor("a/b", "c", "texture"),
                CosmeticResourceId.pathFor("a", "b/c", "texture"));
    }

    @Test
    void isCaseSensitive() {
        assertNotEquals(CosmeticResourceId.pathFor("Pack", "Crown", "texture"),
                CosmeticResourceId.pathFor("pack", "crown", "texture"));
    }

    @Test
    void differsPerSuffix() {
        assertNotEquals(CosmeticResourceId.pathFor("example", "crown", "model"),
                CosmeticResourceId.pathFor("example", "crown", "texture"));
    }

    @Test
    void isDeterministic() {
        assertEquals(CosmeticResourceId.pathFor("example", "crown", "texture"),
                CosmeticResourceId.pathFor("example", "crown", "texture"));
    }

    @Test
    void producesValidIdentifierPath() {
        String path = CosmeticResourceId.pathFor("My Pack!", "皇冠 Crown", "texture");

        assertTrue(path.matches("[a-z0-9/._-]+"), "Identifier 只接受 a-z0-9/._-: " + path);
        assertFalse(path.startsWith("/"), path);
        assertFalse(path.contains("//"), path);
    }

    @Test
    void hashSuffixIsEightHexChars() {
        String path = CosmeticResourceId.pathFor("example", "crown", "texture");
        String hash = path.substring(path.lastIndexOf('.') + 1);

        assertTrue(hash.matches("[0-9a-f]{8}"), "CRC32 应是 8 位十六进制: " + hash);
    }

    @Test
    void nullPartsAreSafe() {
        assertNotNull(CosmeticResourceId.pathFor(null, null, null));
        assertTrue(CosmeticResourceId.pathFor(null, null, null).matches("[a-z0-9/._-]+"));
    }
}
