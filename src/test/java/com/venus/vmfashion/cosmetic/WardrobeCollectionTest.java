package com.venus.vmfashion.cosmetic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WardrobeCollection} 的纯逻辑测试（不加载任何 Minecraft 类）。
 *
 * <p>覆盖四块：
 * <ul>
 *   <li>{@link WardrobeCollection#parse(JsonObject)}：正常响应 / 缺字段 / 类型异常 / 重复 ID / 空响应；</li>
 *   <li>{@link WardrobeCollection#contains(String)}：全局 ID / 大小写 / 唯一短 ID / 歧义短 ID / 空值；</li>
 *   <li>持久化：写-读往返、坏文件降级、文件缺失、<b>未加载时绝不覆盖已有缓存</b>、原子写无残留；</li>
 *   <li>规范化与 {@link WardrobeCollection#clear()}。</li>
 * </ul>
 *
 * <p>注意 {@link WardrobeCollection} 是单例：每个用例前 {@code clear()} 一次，用例之间不共享状态。
 */
class WardrobeCollectionTest {

    @TempDir
    Path tempDir;

    private WardrobeCollection collection;

    @BeforeEach
    void setUp() {
        collection = WardrobeCollection.getInstance();
        // 单例：保证每个用例都从"未加载"开始（其它测试类也可能碰过它）
        collection.clear();
    }

    // ==================== parse ====================

    @Test
    void parseReadsFullResponse() {
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json("""
                {
                  "linked": true,
                  "items": [
                    {"cosmeticId": "lw_angel:angel", "name": "Angel Wings",
                     "category": "Wings", "packId": "lw_angel", "missing": false},
                    {"cosmeticId": "lw_hat:crown", "name": "Crown",
                     "category": "hat", "packId": "lw_hat", "missing": true}
                  ]
                }
                """));

        assertTrue(parsed.linked(), "linked 必须原样带出来");
        assertEquals(List.of(
                new WardrobeCollection.Entry("lw_angel:angel", "Angel Wings", "wings", "lw_angel"),
                new WardrobeCollection.Entry("lw_hat:crown", "Crown", "hat", "lw_hat")),
                parsed.entries(),
                "条目顺序 = 服务端顺序；category 统一小写；missing 字段不影响收藏");
    }

    @Test
    void parseKeepsItemsWhenLinkedIsFalse() {
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json("""
                {"linked": false, "items": [{"cosmeticId": "a:b", "name": "B"}]}
                """));

        assertFalse(parsed.linked());
        assertEquals(1, parsed.entries().size(), "linked=false 也要保留条目：界面自己决定是否过滤");
    }

    @Test
    void parseTreatsStringBooleanLinkedAsBoolean() {
        // 后端历史上把 linked 写成字符串过；防御式解析必须认
        assertTrue(WardrobeCollection.parse(json("""
                {"linked": "true", "items": []}
                """)).linked());
        assertFalse(WardrobeCollection.parse(json("""
                {"linked": "false", "items": []}
                """)).linked());
    }

    @Test
    void parseMissingItemsYieldsEmptyList() {
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json("""
                {"linked": true}
                """));

        assertTrue(parsed.linked());
        assertEquals(List.of(), parsed.entries());
    }

    @Test
    void parseNullItemsYieldsEmptyList() {
        assertEquals(List.of(), WardrobeCollection.parse(json("""
                {"linked": true, "items": null}
                """)).entries());
    }

    @Test
    void parseNonArrayItemsYieldsEmptyList() {
        assertEquals(List.of(), WardrobeCollection.parse(json("""
                {"linked": true, "items": {"cosmeticId": "a:b"}}
                """)).entries(), "items 是对象时忽略");
        assertEquals(List.of(), WardrobeCollection.parse(json("""
                {"linked": true, "items": "a:b"}
                """)).entries(), "items 是字符串时忽略");
        assertEquals(List.of(), WardrobeCollection.parse(json("""
                {"linked": true, "items": 42}
                """)).entries(), "items 是数字时忽略");
    }

    @Test
    void parseSkipsEntriesWithoutCosmeticId() {
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json("""
                {
                  "linked": true,
                  "items": [
                    {"name": "没有 id"},
                    {"cosmeticId": "", "name": "空 id"},
                    {"cosmeticId": "   ", "name": "空白 id"},
                    {"cosmeticId": null, "name": "null id"},
                    {"cosmeticId": "ok:one", "name": "正常"}
                  ]
                }
                """));

        assertEquals(List.of(new WardrobeCollection.Entry("ok:one", "正常", "", "")),
                parsed.entries(), "没有 ID 的条目没法穿戴 / 匹配，必须丢弃");
    }

    @Test
    void parseSkipsNonObjectItems() {
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json("""
                {"linked": true, "items": ["a:b", 42, null, [1], {"cosmeticId": "ok:one"}]}
                """));

        assertEquals(List.of(new WardrobeCollection.Entry("ok:one", "", "", "")), parsed.entries());
    }

    @Test
    void parseToleratesWrongFieldTypes() {
        // 数字当字符串：Gson 会转成 "42"；对象 / 数组当字符串：转不了就退化成空串。
        // 关键是"不崩 + 好条目不受影响"，不是把脏数据猜对。
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json("""
                {
                  "linked": true,
                  "items": [
                    {"cosmeticId": "a:num", "name": 42, "category": 7, "packId": true},
                    {"cosmeticId": "a:obj", "name": {"x": 1}, "category": [1], "packId": {"y": 2}},
                    {"cosmeticId": "a:ok", "name": "OK"}
                  ]
                }
                """));

        assertEquals(3, parsed.entries().size(), "脏字段不能让整条（或整份）收藏消失");
        assertEquals(new WardrobeCollection.Entry("a:num", "42", "7", "true"), parsed.entries().get(0));
        // Gson 的宽松转换：单元素数组 [1] 会转成 "1"，对象转不了就退化成空串
        assertEquals(new WardrobeCollection.Entry("a:obj", "", "1", ""), parsed.entries().get(1));
        assertEquals(new WardrobeCollection.Entry("a:ok", "OK", "", ""), parsed.entries().get(2));
    }

    @Test
    void parseDeduplicatesSameIdKeepingFirst() {
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json("""
                {
                  "linked": true,
                  "items": [
                    {"cosmeticId": "lw_angel:angel", "name": "第一条"},
                    {"cosmeticId": "LW_ANGEL:ANGEL", "name": "第二条（大小写不同，也算重复）"},
                    {"cosmeticId": "lw_hat:crown", "name": "Crown"}
                  ]
                }
                """));

        assertEquals(List.of(
                        new WardrobeCollection.Entry("lw_angel:angel", "第一条", "", ""),
                        new WardrobeCollection.Entry("lw_hat:crown", "Crown", "", "")),
                parsed.entries(), "重复 ID 只留第一条，否则界面会出现两张一样的卡");
    }

    @Test
    void parseTrimsWhitespaceInAllFields() {
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json("""
                {"linked": true, "items": [
                  {"cosmeticId": "  lw_angel:angel  ", "name": "  Angel  ",
                   "category": "  WINGS  ", "packId": "  lw_angel  "}
                ]}
                """));

        assertEquals(List.of(new WardrobeCollection.Entry("lw_angel:angel", "Angel", "wings", "lw_angel")),
                parsed.entries());
    }

    @Test
    void parseEmptyResponseIsUsable() {
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(new JsonObject());
        assertFalse(parsed.linked());
        assertEquals(List.of(), parsed.entries());
    }

    @Test
    void parseNullJsonIsUsable() {
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(null);
        assertFalse(parsed.linked());
        assertNotNull(parsed.entries());
        assertEquals(List.of(), parsed.entries());
    }

    // ==================== contains ====================

    @Test
    void containsMatchesGlobalId() {
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("lw_angel:angel", "Angel", "wings", "lw_angel"),
                new WardrobeCollection.Entry("lw_hat:crown", "Crown", "hat", "lw_hat")));

        assertTrue(collection.contains("lw_angel:angel"));
        assertTrue(collection.contains("lw_hat:crown"));
    }

    @Test
    void containsIsCaseInsensitive() {
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("lw_angel:angel", "Angel", "wings", "lw_angel")));

        assertTrue(collection.contains("LW_ANGEL:ANGEL"));
        assertTrue(collection.contains("Lw_Angel:Angel"));
    }

    @Test
    void containsTrimsInput() {
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("lw_angel:angel", "Angel", "wings", "lw_angel")));

        assertTrue(collection.contains("  lw_angel:angel  "));
        assertTrue(collection.contains("  angel  "), "短 ID 也要先 trim");
    }

    @Test
    void containsMatchesUniqueShortId() {
        // 服务端目录里存在历史"简单 ID"条目：CosmeticDefinition#id() 是全局 ID，
        // 直接字符串比较会漏掉，所以短 ID 别名必须能命中
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("lw_angel:angel", "Angel", "wings", "lw_angel"),
                new WardrobeCollection.Entry("lw_hat:crown", "Crown", "hat", "lw_hat")));

        assertTrue(collection.contains("angel"));
        assertTrue(collection.contains("crown"));
        assertTrue(collection.contains("ANGEL"));
    }

    @Test
    void containsRejectsAmbiguousShortId() {
        // 同一个 localId 出现在两个包里：不能猜（会匹配到错误的那件），和 CosmeticRegistry#find 的歧义规则一致
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("pack_a:angel", "A", "wings", "pack_a"),
                new WardrobeCollection.Entry("pack_b:angel", "B", "wings", "pack_b")));

        assertFalse(collection.contains("angel"), "有歧义的短 ID 绝不能命中");
        assertTrue(collection.contains("pack_a:angel"), "全局 ID 仍然精确命中");
        assertTrue(collection.contains("pack_b:angel"));
    }

    @Test
    void containsRejectsUnknownId() {
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("lw_angel:angel", "Angel", "wings", "lw_angel")));

        assertFalse(collection.contains("lw_hat:crown"));
        assertFalse(collection.contains("hat"), "不存在的短 ID");
        assertFalse(collection.contains("lw_angel"), "包 ID 不是时装 ID，不能命中");
    }

    @Test
    void containsRejectsNullOrBlank() {
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("lw_angel:angel", "Angel", "wings", "lw_angel")));

        assertFalse(collection.contains(null));
        assertFalse(collection.contains(""));
        assertFalse(collection.contains("   "));
    }

    @Test
    void containsOnEmptyCollectionIsFalse() {
        // clear() 之后（未加载）什么都不该命中
        assertFalse(collection.contains("lw_angel:angel"));
        assertFalse(collection.contains("angel"));

        collection.replaceAll(true, List.of());
        assertFalse(collection.contains("lw_angel:angel"), "空收藏（已加载但 0 件）同样不命中");
    }

    // ==================== 内存状态 ====================

    @Test
    void replaceAllSetsLoadedAndFetchTimestamp() {
        assertFalse(collection.loaded());
        assertEquals(0L, collection.fetchedAtMs());

        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("a:b", "B", "cape", "a")));

        assertTrue(collection.loaded());
        assertTrue(collection.linked());
        assertTrue(collection.usable());
        assertEquals(1, collection.size());
        assertTrue(collection.fetchedAtMs() > 0L, "拉取成功必须记录时间戳（界面用它做节流）");
    }

    @Test
    void replaceAllWithUnlinkedIsNotUsable() {
        collection.replaceAll(false, List.of(
                new WardrobeCollection.Entry("a:b", "B", "cape", "a")));

        assertTrue(collection.loaded());
        assertFalse(collection.linked());
        assertFalse(collection.usable(), "没绑定网站时界面要退回\"本地全部\"");
        assertEquals(1, collection.size(), "但条目本身要留着（绑定后立刻可用）");
    }

    @Test
    void replaceAllNormalizesEntries() {
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("  a:b  ", "  B  ", "  cape  ", "  a  "),
                new WardrobeCollection.Entry("   ", "空 ID 丢弃", "cape", "a"),
                new WardrobeCollection.Entry("A:B", "重复", "cape", "a")));

        assertEquals(List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a")),
                collection.entries(), "去空白 + 空 ID 丢弃 + 去重");
    }

    @Test
    void replaceAllAcceptsNullList() {
        collection.replaceAll(true, null);

        assertTrue(collection.loaded());
        assertEquals(List.of(), collection.entries());
        assertEquals(0, collection.size());
    }

    @Test
    void clearResetsEverything() {
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("a:b", "B", "cape", "a")));

        collection.clear();

        assertFalse(collection.loaded());
        assertFalse(collection.linked());
        assertFalse(collection.usable());
        assertEquals(0, collection.size());
        assertEquals(List.of(), collection.entries());
        assertEquals(0L, collection.fetchedAtMs());
        assertFalse(collection.contains("a:b"));
    }

    // ==================== 持久化 ====================

    @Test
    void saveThenLoadRoundTrip() {
        List<WardrobeCollection.Entry> entries = List.of(
                new WardrobeCollection.Entry("lw_angel:angel", "Angel Wings", "wings", "lw_angel"),
                new WardrobeCollection.Entry("lw_hat:crown", "Crown", "hat", "lw_hat"));
        collection.replaceAll(true, entries);
        long fetchedAt = collection.fetchedAtMs();
        Path file = tempDir.resolve("wardrobe.json");

        assertTrue(collection.saveNow(file), "落盘必须成功");
        assertTrue(Files.isRegularFile(file));

        // 模拟重启：内存清空后从磁盘恢复
        collection.clear();
        assertEquals(2, collection.loadFromDisk(file));
        assertTrue(collection.linked());
        assertTrue(collection.loaded());
        assertEquals(entries, collection.entries(), "条目顺序必须和写之前一致");
        assertEquals(fetchedAt, collection.fetchedAtMs(), "fetchedAt 也要落盘（跨会话判断\"多久没刷新\"）");
    }

    @Test
    void loadFromDiskKeepsUnlinkedFlag() {
        Path file = tempDir.resolve("wardrobe.json");
        collection.replaceAll(false, List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a")));
        assertTrue(collection.saveNow(file));

        collection.clear();
        assertEquals(1, collection.loadFromDisk(file));

        assertFalse(collection.linked(), "linked 也要往返一致");
        assertFalse(collection.usable());
    }

    @Test
    void saveNowCreatesParentDirectories() {
        collection.replaceAll(true, List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a")));
        Path file = tempDir.resolve("nested").resolve("vmfashion").resolve("wardrobe.json");

        assertTrue(collection.saveNow(file));

        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void saveNowLeavesNoTempFile() throws IOException {
        collection.replaceAll(true, List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a")));
        Path file = tempDir.resolve("wardrobe.json");

        assertTrue(collection.saveNow(file));
        assertTrue(collection.saveNow(file), "第二次写必须覆盖第一次");

        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(List.of("wardrobe.json"),
                    files.map(path -> path.getFileName().toString()).sorted().toList(),
                    "原子写不能留下 .tmp 残留");
        }
    }

    @Test
    void saveNowRefusesToOverwriteWhenNeverLoaded() throws IOException {
        // 关键防线：还没成功加载过就落盘，会用一份空收藏覆盖掉上一次的真实缓存
        Path file = tempDir.resolve("wardrobe.json");
        String existing = """
                {"formatVersion": 1, "linked": true, "fetchedAt": 123,
                 "items": [{"cosmeticId": "a:b", "name": "B", "category": "cape", "packId": "a"}]}
                """;
        Files.writeString(file, existing, StandardCharsets.UTF_8);
        assertFalse(collection.loaded());

        assertFalse(collection.saveNow(file), "未加载过必须拒绝写入");
        assertEquals(existing, Files.readString(file, StandardCharsets.UTF_8), "已有缓存一个字节都不能动");
    }

    @Test
    void saveNowRejectsNullPath() {
        collection.replaceAll(true, List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a")));

        assertFalse(collection.saveNow(null));
    }

    @Test
    void loadFromDiskMissingFileReturnsMinusOne() {
        assertEquals(-1, collection.loadFromDisk(tempDir.resolve("nope.json")));
        assertEquals(-1, collection.loadFromDisk(null));
        assertFalse(collection.loaded(), "文件不存在不能把状态改成\"已加载\"");
    }

    @Test
    void loadFromDiskCorruptFileKeepsMemory() throws IOException {
        collection.replaceAll(true, List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a")));
        Path file = write("{ 这不是 json ");

        assertEquals(-1, collection.loadFromDisk(file));

        assertTrue(collection.loaded(), "坏文件不能把已有内存镜像弄丢");
        assertEquals(1, collection.size());
        assertTrue(collection.contains("a:b"));
    }

    @Test
    void loadFromDiskRejectsNonObjectRoot() throws IOException {
        assertEquals(-1, collection.loadFromDisk(write("[1, 2, 3]")));
        assertEquals(-1, collection.loadFromDisk(write("\"just a string\"")));
        assertEquals(-1, collection.loadFromDisk(write("")), "空文件也要降级成\"未加载\"而不是抛异常");
        assertFalse(collection.loaded());
    }

    @Test
    void loadFromDiskToleratesUnknownFieldsAndFutureVersion() throws IOException {
        Path file = write("""
                {
                  "formatVersion": 99,
                  "futureField": {"nested": true},
                  "linked": true,
                  "fetchedAt": 1700000000000,
                  "items": [
                    {"cosmeticId": "a:b", "name": "B", "category": "cape", "packId": "a", "extra": 1},
                    {"name": "没有 id 的条目"}
                  ]
                }
                """);

        assertEquals(1, collection.loadFromDisk(file));
        assertEquals(List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a")), collection.entries());
        assertTrue(collection.linked());
        assertEquals(1700000000000L, collection.fetchedAtMs());
    }

    @Test
    void loadFromDiskWithNonArrayItemsLoadsEmptyList() throws IOException {
        Path file = write("""
                {"formatVersion": 1, "linked": true, "items": {"cosmeticId": "a:b"}}
                """);

        assertEquals(0, collection.loadFromDisk(file));
        assertTrue(collection.loaded());
        assertEquals(List.of(), collection.entries());
    }

    @Test
    void loadFromDiskAppliesNormalization() throws IOException {
        Path file = write("""
                {"formatVersion": 1, "linked": true, "items": [
                  {"cosmeticId": "  a:b  ", "name": "  B  ", "category": "  CAPE  ", "packId": "  a  "},
                  {"cosmeticId": "A:B", "name": "重复"},
                  {"cosmeticId": "   ", "name": "空 ID"}
                ]}
                """);

        assertEquals(1, collection.loadFromDisk(file));
        assertEquals(List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a")), collection.entries());
    }

    @Test
    void saveNowAfterLoadIsIdempotent() {
        Path file = tempDir.resolve("wardrobe.json");
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("a:b", "B", "cape", "a"),
                new WardrobeCollection.Entry("c:d", "D", "hat", "c")));
        assertTrue(collection.saveNow(file));
        List<WardrobeCollection.Entry> snapshot = new ArrayList<>(collection.entries());

        collection.clear();
        assertEquals(2, collection.loadFromDisk(file));
        assertTrue(collection.saveNow(file), "从磁盘加载后要能再次落盘（正常刷新链路）");

        collection.clear();
        assertEquals(2, collection.loadFromDisk(file));
        assertEquals(snapshot, collection.entries());
    }

    // ==================== 小工具 ====================

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("wardrobe.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
