package com.venus.vmfashion.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PackCatalog} 单测：防御式解析、默认 URL 推导、packId 安全校验、磁盘缓存。 */
class PackCatalogTest {

    private static final String VALID_SHA = "a".repeat(64);
    private static final String OTHER_SHA = "b".repeat(64);

    @Test
    void parsesValidCatalog() {
        String json = """
                {"packs":[
                  {"id":"wings","version":"1.2.0","sha256":"%s","bytes":12345,
                   "url":"https://cdn.example.com/wings.zip","publishedAt":1700000000000},
                  {"id":"hats","version":"2.0.0","sha256":"%s","bytes":999}
                ]}
                """.formatted(VALID_SHA, OTHER_SHA);

        Map<String, PackCatalog.PackInfo> packs = PackCatalog.parse(json);

        assertEquals(2, packs.size());
        PackCatalog.PackInfo wings = packs.get("wings");
        assertNotNull(wings);
        assertEquals("1.2.0", wings.version());
        assertEquals(VALID_SHA, wings.sha256());
        assertEquals(12345L, wings.bytes());
        assertEquals("https://cdn.example.com/wings.zip", wings.url());
        assertEquals(1700000000000L, wings.publishedAt());

        // 没给 url 时按契约拼默认地址
        assertEquals("/packs/hats/2.0.0/download", packs.get("hats").url());
    }

    @Test
    void acceptsBareArrayResponse() {
        String json = """
                [{"id":"wings","version":"1.0.0","sha256":"%s","bytes":1}]
                """.formatted(VALID_SHA);
        Map<String, PackCatalog.PackInfo> packs = PackCatalog.parse(json);
        assertEquals(1, packs.size());
        assertNotNull(packs.get("wings"));
    }

    @Test
    void skipsInvalidEntriesWithoutFailingWholeCatalog() {
        String json = """
                {"packs":[
                  {"id":"../evil","version":"1.0.0","sha256":"%s","bytes":1},
                  {"id":"no-hash","version":"1.0.0","bytes":1},
                  {"id":"bad-hash","version":"1.0.0","sha256":"abc","bytes":1},
                  {"id":"no-version","sha256":"%s","bytes":1},
                  {"id":"no-bytes","version":"1.0.0","sha256":"%s"},
                  {"id":"negative-bytes","version":"1.0.0","sha256":"%s","bytes":-5},
                  {"id":"good","version":"1.0.0","sha256":"%s","bytes":42},
                  "not-an-object",
                  null
                ]}
                """.formatted(VALID_SHA, VALID_SHA, VALID_SHA, VALID_SHA, VALID_SHA);

        Map<String, PackCatalog.PackInfo> packs = PackCatalog.parse(json);

        assertEquals(1, packs.size(), "只有合法条目应该被保留");
        assertNotNull(packs.get("good"));
    }

    @Test
    void rejectsMalformedJsonAndEmptyInput() {
        assertTrue(PackCatalog.parse(null).isEmpty());
        assertTrue(PackCatalog.parse("").isEmpty());
        assertTrue(PackCatalog.parse("   ").isEmpty());
        assertTrue(PackCatalog.parse("{not json").isEmpty());
        assertTrue(PackCatalog.parse("{\"packs\":\"oops\"}").isEmpty());
        assertTrue(PackCatalog.parse("[]").isEmpty());
    }

    @Test
    void capsEntryCountToProtectMemory() {
        StringBuilder builder = new StringBuilder("{\"packs\":[");
        for (int i = 0; i < 3000; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"id\":\"pack").append(i)
                    .append("\",\"version\":\"1.0.0\",\"sha256\":\"").append(VALID_SHA)
                    .append("\",\"bytes\":1}");
        }
        builder.append("]}");

        Map<String, PackCatalog.PackInfo> packs = PackCatalog.parse(builder.toString());

        assertEquals(2048, packs.size(), "清单条目数必须封顶");
    }

    @Test
    void firstEntryWinsForDuplicateIds() {
        String json = """
                {"packs":[
                  {"id":"dup","version":"2.0.0","sha256":"%s","bytes":2},
                  {"id":"dup","version":"1.0.0","sha256":"%s","bytes":1}
                ]}
                """.formatted(VALID_SHA, OTHER_SHA);

        Map<String, PackCatalog.PackInfo> packs = PackCatalog.parse(json);

        assertEquals(1, packs.size());
        assertEquals("2.0.0", packs.get("dup").version());
    }

    @Test
    void emptyStoreDoesNotWipeExistingCatalog() {
        PackCatalog catalog = new PackCatalog(null);
        catalog.store(Map.of("wings", new PackCatalog.PackInfo(
                "wings", "1.0.0", VALID_SHA, 10L, "/packs/wings/1.0.0/download", 0L)), 1_000L);

        // 后端瞬时异常返回空数组：不能把已缓存的清单清空
        catalog.store(Map.of(), 2_000L);

        assertNotNull(catalog.find("wings"), "空清单不应覆盖已有缓存");
        assertEquals(1, catalog.packs().size());
    }

    @Test
    void findIsCaseInsensitive() {
        PackCatalog catalog = new PackCatalog(null);
        catalog.store(Map.of("wings", new PackCatalog.PackInfo(
                "wings", "1.0.0", VALID_SHA, 10L, "/packs/wings/1.0.0/download", 0L)), 1_000L);

        assertNotNull(catalog.find("WINGS"));
        assertNotNull(catalog.find(" wings "));
        assertNull(catalog.find("nope"));
        assertNull(catalog.find(null));
    }

    @Test
    void ttlDecidesFreshness() {
        PackCatalog catalog = new PackCatalog(null);
        assertFalse(catalog.isFresh(0L), "没拉过清单时必须视为过期");

        catalog.store(Map.of("wings", new PackCatalog.PackInfo(
                "wings", "1.0.0", VALID_SHA, 10L, "/packs/wings/1.0.0/download", 0L)), 1_000L);
        assertTrue(catalog.isFresh(1_000L));
        assertTrue(catalog.isFresh(1_000L + PackCatalog.DEFAULT_TTL_MS - 1));
        assertFalse(catalog.isFresh(1_000L + PackCatalog.DEFAULT_TTL_MS));
    }

    @Test
    void persistsAndReloadsFromDisk(@TempDir Path tempDir) throws Exception {
        Path cacheFile = tempDir.resolve("cache").resolve("pack-catalog.json");
        PackCatalog writer = new PackCatalog(cacheFile);
        writer.store(Map.of("wings", new PackCatalog.PackInfo(
                "wings", "1.2.3", VALID_SHA, 555L, "/packs/wings/1.2.3/download", 42L)), 7_000L);

        assertTrue(Files.isRegularFile(cacheFile));

        PackCatalog reader = new PackCatalog(cacheFile);
        reader.loadFromDiskIfNeeded(null);

        PackCatalog.PackInfo info = reader.find("wings");
        assertNotNull(info, "重启后应该能从磁盘恢复清单");
        assertEquals("1.2.3", info.version());
        assertEquals(VALID_SHA, info.sha256());
        assertEquals(555L, info.bytes());
        assertEquals(7_000L, reader.fetchedAtMs());
    }

    @Test
    void brokenCacheFileIsIgnored(@TempDir Path tempDir) throws Exception {
        Path cacheFile = tempDir.resolve("pack-catalog.json");
        Files.writeString(cacheFile, "{ broken json");

        PackCatalog catalog = new PackCatalog(cacheFile);
        catalog.loadFromDiskIfNeeded(message -> {
            // 只记日志，不能抛
        });

        assertTrue(catalog.packs().isEmpty());
        assertNull(catalog.find("wings"));
    }

    @Test
    void clearRemovesMemoryAndDisk(@TempDir Path tempDir) throws Exception {
        Path cacheFile = tempDir.resolve("pack-catalog.json");
        PackCatalog catalog = new PackCatalog(cacheFile);
        catalog.store(Map.of("wings", new PackCatalog.PackInfo(
                "wings", "1.0.0", VALID_SHA, 1L, "/x", 0L)), 1L);
        assertTrue(Files.isRegularFile(cacheFile));

        catalog.clear();

        assertTrue(catalog.packs().isEmpty());
        assertFalse(Files.exists(cacheFile));
    }

    @Test
    void packIdSafetyRulesProtectFilesystem() {
        assertTrue(PackCatalog.isSafePackId("wings"));
        assertTrue(PackCatalog.isSafePackId("my-pack_1.0"));
        assertTrue(PackCatalog.isSafePackId("A1"));

        assertFalse(PackCatalog.isSafePackId(null));
        assertFalse(PackCatalog.isSafePackId(""));
        assertFalse(PackCatalog.isSafePackId("."));
        assertFalse(PackCatalog.isSafePackId(".."));
        assertFalse(PackCatalog.isSafePackId("../evil"));
        assertFalse(PackCatalog.isSafePackId("a/b"));
        assertFalse(PackCatalog.isSafePackId("a\\b"));
        assertFalse(PackCatalog.isSafePackId("C:evil"));
        assertFalse(PackCatalog.isSafePackId("with space"));
        assertFalse(PackCatalog.isSafePackId("x".repeat(65)), "超长 id 必须拒绝");
        // 以 . 开头（尤其 .tmp-）会和 AssetSyncService 的临时目录前缀撞车，rescanDisk 会把它当
        // 崩溃残留删掉 → 这个包永不持久。必须拒绝。
        assertFalse(PackCatalog.isSafePackId(".tmp-abc"), ".tmp- 前缀必须拒绝");
        assertFalse(PackCatalog.isSafePackId(".hidden"));
        // server 是自动下发命名空间目录名
        assertFalse(PackCatalog.isSafePackId("server"), "保留名 server 必须拒绝");
        assertFalse(PackCatalog.isSafePackId("SERVER"));
    }

    // ==================== 签名（task-10） ====================

    @Test
    void parsesSignatureFields() {
        String json = """
                {"packs":[{"id":"wings","version":"1.2.0","sha256":"%s","bytes":10,
                           "signature":"MEUCIQ-signature-base64","signingKeyId":"vmtools-official"}]}
                """.formatted(VALID_SHA);

        PackCatalog.PackInfo info = PackCatalog.parse(json).get("wings");

        assertNotNull(info);
        assertTrue(info.hasSignature());
        assertEquals("MEUCIQ-signature-base64", info.signature());
        assertEquals("vmtools-official", info.signingKeyId());
    }

    @Test
    void absentSignatureIsEmptyAndDoesNotFailParsing() {
        // 老后端没有签名字段：条目仍然要能解析（否则自动下发直接失效），由验签策略决定放行还是拒绝
        String json = """
                {"packs":[{"id":"wings","version":"1.0.0","sha256":"%s","bytes":1}]}
                """.formatted(VALID_SHA);

        PackCatalog.PackInfo info = PackCatalog.parse(json).get("wings");

        assertNotNull(info);
        assertFalse(info.hasSignature());
        assertEquals("", info.signature());
        assertEquals("", info.signingKeyId());
    }

    @Test
    void signatureSurvivesDiskCacheRoundTrip(@TempDir Path tempDir) throws Exception {
        // 离线缓存也要保留签名，否则"重启后从缓存恢复的清单"会被严格模式判为无签名
        Path cacheFile = tempDir.resolve("pack-catalog.json");
        PackCatalog writer = new PackCatalog(cacheFile);
        writer.store(Map.of("wings", new PackCatalog.PackInfo(
                "wings", "1.2.0", VALID_SHA, 10L, "/packs/wings/1.2.0/download", 0L,
                "c2lnbmF0dXJl", "vmtools-official")), 1_000L);

        PackCatalog reader = new PackCatalog(cacheFile);
        reader.loadFromDiskIfNeeded(null);
        PackCatalog.PackInfo info = reader.find("wings");

        assertNotNull(info);
        assertTrue(info.hasSignature());
        assertEquals("c2lnbmF0dXJl", info.signature());
        assertEquals("vmtools-official", info.signingKeyId());
    }

    // ==================== 与后端实际响应形状的交叉核对 ====================

    @Test
    void acceptsItemsEnvelopeAsFallback() {
        // 后端其它列表接口（/catalog、/identities/me、/admin/assets）统一用 {"items":[...]}，
        // 实现 /packs 时很容易照抄这个形状。只认 "packs" 的话后端一上线客户端就是"清单 0 个包"，
        // 而且日志里看不出原因。
        String json = """
                {"items":[{"id":"wings","version":"1.2.0","sha256":"%s","bytes":10}]}
                """.formatted(VALID_SHA);

        Map<String, PackCatalog.PackInfo> packs = PackCatalog.parse(json);

        assertEquals(1, packs.size(), "items 包一层的响应也要能解析");
        assertNotNull(packs.get("wings"));
    }

    @Test
    void packsEnvelopeWinsOverItemsEnvelope() {
        // 两种形状同时出现时以契约字段 packs 为准（不能因为 items 恰好存在就换口径）
        String json = """
                {"packs":[{"id":"from-packs","version":"1.0.0","sha256":"%s","bytes":1}],
                 "items":[{"id":"from-items","version":"1.0.0","sha256":"%s","bytes":1}]}
                """.formatted(VALID_SHA, OTHER_SHA);

        Map<String, PackCatalog.PackInfo> packs = PackCatalog.parse(json);

        assertEquals(1, packs.size());
        assertNotNull(packs.get("from-packs"));
        assertNull(packs.get("from-items"));
    }

    @Test
    void parsesPublishedAtAsMillisecondsIsoStringAndNumericString() {
        // 契约写的是"毫秒 number"，但站点 docs/fashion-api.md 的全局约定是"时间用 ISO-8601 字符串"，
        // 实现 /packs 时两种都可能出现；认不出来只影响展示，绝不能因此丢条目。
        String json = """
                {"packs":[
                  {"id":"millis","version":"1.0.0","sha256":"%s","bytes":1,"publishedAt":1735603200000},
                  {"id":"iso-z","version":"1.0.0","sha256":"%s","bytes":1,"publishedAt":"2025-01-01T00:00:00Z"},
                  {"id":"iso-offset","version":"1.0.0","sha256":"%s","bytes":1,
                   "publishedAt":"2025-01-01T08:00:00+08:00"},
                  {"id":"iso-local","version":"1.0.0","sha256":"%s","bytes":1,
                   "publishedAt":"2025-01-01T00:00:00"},
                  {"id":"numeric-string","version":"1.0.0","sha256":"%s","bytes":1,
                   "publishedAt":"1735603200000"},
                  {"id":"garbage","version":"1.0.0","sha256":"%s","bytes":1,"publishedAt":"yesterday"},
                  {"id":"null-ts","version":"1.0.0","sha256":"%s","bytes":1,"publishedAt":null}
                ]}
                """.formatted(VALID_SHA, VALID_SHA, VALID_SHA, VALID_SHA, VALID_SHA, VALID_SHA, VALID_SHA);

        Map<String, PackCatalog.PackInfo> packs = PackCatalog.parse(json);

        assertEquals(7, packs.size(), "时间字段异常不能让条目被丢弃");
        assertEquals(1735603200000L, packs.get("millis").publishedAt());
        // 2025-01-01T00:00:00Z == 1735689600000
        assertEquals(1735689600000L, packs.get("iso-z").publishedAt());
        assertEquals(1735689600000L, packs.get("iso-offset").publishedAt());
        assertEquals(1735689600000L, packs.get("iso-local").publishedAt(), "无时区按 UTC 解释");
        assertEquals(1735603200000L, packs.get("numeric-string").publishedAt());
        assertEquals(0L, packs.get("garbage").publishedAt(), "认不出来回退 0");
        assertEquals(0L, packs.get("null-ts").publishedAt());
    }

    @Test
    void parseTimestampMillisHandlesAllShapesDirectly() {
        assertEquals(123L, PackCatalog.parseTimestampMillis(
                com.google.gson.JsonParser.parseString("123"), -1L));
        assertEquals(123L, PackCatalog.parseTimestampMillis(
                com.google.gson.JsonParser.parseString("\"123\""), -1L));
        assertEquals(-1L, PackCatalog.parseTimestampMillis(null, -1L));
        assertEquals(-1L, PackCatalog.parseTimestampMillis(
                com.google.gson.JsonParser.parseString("{}"), -1L));
        assertEquals(-1L, PackCatalog.parseTimestampMillis(
                com.google.gson.JsonParser.parseString("\"\""), -1L));
    }
}
