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
    }
}
