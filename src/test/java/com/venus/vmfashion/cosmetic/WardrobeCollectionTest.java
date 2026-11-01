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
import static org.junit.jupiter.api.Assertions.assertNull;
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
                new WardrobeCollection.Entry("lw_angel:angel", "Angel Wings", "wings", "lw_angel", false),
                new WardrobeCollection.Entry("lw_hat:crown", "Crown", "hat", "lw_hat", true)),
                parsed.entries(),
                "条目顺序 = 服务端顺序；category 统一小写；missing 原样带出来");
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

    // ==================== missing（目录已删除 / 已停用） ====================

    @Test
    void parseReadsMissingFlag() {
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json("""
                {"linked": true, "items": [
                  {"cosmeticId": "a:ok", "missing": false},
                  {"cosmeticId": "a:gone", "missing": true}
                ]}
                """));

        assertFalse(parsed.entries().get(0).missing());
        assertTrue(parsed.entries().get(1).missing(),
                "missing 是服务端字段（目录已删除/已停用），必须解析出来，界面据此禁止穿戴");
    }

    @Test
    void parseDefaultsMissingToFalseWhenAbsentOrInvalid() {
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json("""
                {"linked": true, "items": [
                  {"cosmeticId": "a:absent"},
                  {"cosmeticId": "a:null", "missing": null},
                  {"cosmeticId": "a:str", "missing": "true"},
                  {"cosmeticId": "a:num", "missing": 1},
                  {"cosmeticId": "a:obj", "missing": {"x": 1}}
                ]}
                """));

        assertFalse(parsed.entries().get(0).missing(), "缺字段 → false（老后端没有这个字段）");
        assertFalse(parsed.entries().get(1).missing(), "null → false");
        assertTrue(parsed.entries().get(2).missing(), "字符串 \"true\" 也认（后端序列化差异）");
        assertFalse(parsed.entries().get(3).missing(), "数字 1 不当 true：Gson 的 getAsBoolean 对 1 抛异常");
        assertFalse(parsed.entries().get(4).missing(), "对象 → false，不能因为一条脏字段崩掉");
    }

    @Test
    void missingFlagSurvivesPersistence() {
        Path file = tempDir.resolve("wardrobe.json");
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("a:gone", "已下架", "cape", "a", true),
                new WardrobeCollection.Entry("a:ok", "正常", "cape", "a", false)));

        assertTrue(collection.saveNow(file));

        collection.clear();
        assertEquals(2, collection.loadFromDisk(file));
        assertEquals(List.of(
                        new WardrobeCollection.Entry("a:gone", "已下架", "cape", "a", true),
                        new WardrobeCollection.Entry("a:ok", "正常", "cape", "a", false)),
                collection.entries(),
                "missing 必须落盘：否则重启后已下架的时装又变成可穿戴");
        assertTrue(collection.isMissing("a:gone"));
        assertEquals(1, collection.missingCount());
    }

    @Test
    void oldCacheWithoutMissingFieldLoadsAsNotMissing() throws IOException {
        // v1.3.0 写的缓存没有 missing 字段：升级后必须按 false 读，不能因为缺字段丢条目
        Path file = write("""
                {"formatVersion": 1, "linked": true, "fetchedAt": 1700000000000,
                 "items": [{"cosmeticId": "a:b", "name": "B", "category": "cape", "packId": "a"}]}
                """);

        assertEquals(1, collection.loadFromDisk(file));
        assertEquals(List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a", false)),
                collection.entries());
        assertFalse(collection.isMissing("a:b"));
        assertEquals(0, collection.missingCount());
    }

    @Test
    void isMissingUsesSameLookupAsContains() {
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("lw_angel:angel", "Angel", "wings", "lw_angel", true),
                new WardrobeCollection.Entry("pack_a:hat", "A", "hat", "pack_a", false),
                new WardrobeCollection.Entry("pack_b:hat", "B", "hat", "pack_b", true)));

        assertTrue(collection.isMissing("lw_angel:angel"));
        assertTrue(collection.isMissing("LW_ANGEL:ANGEL"), "大小写不敏感");
        assertTrue(collection.isMissing("angel"), "唯一短 ID 也能命中");
        assertFalse(collection.isMissing("pack_a:hat"), "收藏了但没下架 → false");
        assertFalse(collection.isMissing("hat"), "歧义短 ID 不命中 → 不算已下架");
        assertFalse(collection.isMissing("nope:nope"), "没收藏 ≠ 已下架");
        assertFalse(collection.isMissing(null));
        assertFalse(collection.isMissing("   "));
    }

    @Test
    void findEntryReturnsNullForUnknownId() {
        collection.replaceAll(true, List.of(
                new WardrobeCollection.Entry("a:b", "B", "cape", "a", true)));

        assertNotNull(collection.findEntry("a:b"));
        assertTrue(collection.findEntry("a:b").missing());
        assertNull(collection.findEntry("a:c"));
        assertNull(collection.findEntry(""));
    }

    // ==================== 输入上限（防御异常 / 恶意后端） ====================

    @Test
    void parseCapsEntryCount() {
        StringBuilder body = new StringBuilder("{\"linked\": true, \"items\": [");
        int total = WardrobeCollection.MAX_ENTRIES + 50;
        for (int i = 0; i < total; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"cosmeticId\": \"p:c").append(i).append("\"}");
        }
        body.append("]}");

        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json(body.toString()));

        assertEquals(WardrobeCollection.MAX_ENTRIES, parsed.entries().size(),
                "超过上限的条目必须丢掉：后端异常返回十万条不能把内存和界面拖死");
    }

    @Test
    void parseTruncatesOverlongFields() {
        String longText = "x".repeat(WardrobeCollection.MAX_FIELD_LENGTH + 120);
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json("""
                {"linked": true, "items": [{"cosmeticId": "%s", "name": "%s", "packId": "%s"}]}
                """.formatted("a:b", longText, longText)));

        assertEquals(1, parsed.entries().size());
        WardrobeCollection.Entry entry = parsed.entries().get(0);
        assertEquals(WardrobeCollection.MAX_FIELD_LENGTH, entry.name().length(), "超长 name 截断");
        assertEquals(WardrobeCollection.MAX_FIELD_LENGTH, entry.packId().length(), "超长 packId 截断");
        assertEquals("a:b", entry.cosmeticId(), "正常长度字段不受影响");
    }

    @Test
    void normalizeCapsEntriesGivenDirectlyToReplaceAll() {
        List<WardrobeCollection.Entry> many = new ArrayList<>();
        for (int i = 0; i < WardrobeCollection.MAX_ENTRIES + 10; i++) {
            many.add(new WardrobeCollection.Entry("p:c" + i, "", "", ""));
        }
        collection.replaceAll(true, many);

        assertEquals(WardrobeCollection.MAX_ENTRIES, collection.size());
    }

    // ==================== linked 推断（老 / 自建后端不返回该字段） ====================

    @Test
    void parseInfersLinkedWhenFieldAbsentButItemsPresent() {
        WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json("""
                {"items": [{"cosmeticId": "a:b"}]}
                """));

        assertTrue(parsed.linked(),
                "没给 linked 却有收藏：按已绑定处理，否则界面退回\"本地全部\"，玩家会以为衣柜没生效");
    }

    @Test
    void parseKeepsExplicitLinkedFalseEvenWithItems() {
        // 字段明确给了 false：契约上 items 必为空，这里保持服务端语义（界面按\"未绑定\"降级）
        assertFalse(WardrobeCollection.parse(json("""
                {"linked": false, "items": [{"cosmeticId": "a:b"}]}
                """)).linked());
        assertFalse(WardrobeCollection.parse(json("""
                {"linked": null, "items": []}
                """)).linked());
    }

    // ==================== 归属身份（换号 / 换后端防串号） ====================

    @Test
    void shouldDiscardForOwnerOnlyWhenBothKnownAndDifferent() {
        assertFalse(WardrobeCollection.shouldDiscardForOwner("", "u|b"), "旧缓存没有身份标记 → 不丢");
        assertFalse(WardrobeCollection.shouldDiscardForOwner(null, "u|b"));
        assertFalse(WardrobeCollection.shouldDiscardForOwner("u|b", ""), "新身份未知 → 不丢");
        assertFalse(WardrobeCollection.shouldDiscardForOwner("u|b", "U|B"), "大小写不同算同一个");
        assertTrue(WardrobeCollection.shouldDiscardForOwner("u1|b", "u2|b"), "换号 → 丢");
        assertTrue(WardrobeCollection.shouldDiscardForOwner("u|b1", "u|b2"), "换后端 → 丢");
    }

    @Test
    void adoptOwnerKeepsFirstOwnerWithoutDiscarding() {
        collection.replaceAll(true, List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a")));

        assertFalse(collection.adoptOwner("u1|https://a"), "首次归属只记身份，不清理");
        assertEquals("u1|https://a", collection.owner());
        assertEquals(1, collection.size());
        assertTrue(collection.loaded());
    }

    @Test
    void adoptOwnerDiscardsWhenIdentityChanges() {
        collection.replaceAll(true, List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a")));
        collection.adoptOwner("u1|https://a");

        assertTrue(collection.adoptOwner("u2|https://a"), "换号必须丢旧收藏，否则断网时 B 会看到 A 的衣柜");

        assertEquals(0, collection.size());
        assertFalse(collection.loaded(), "清掉后界面要退回\"本地全部\"，而不是显示空衣柜");
        assertFalse(collection.linked());
        assertEquals("u2|https://a", collection.owner());
    }

    @Test
    void adoptOwnerIgnoresBlankCurrentOwner() {
        collection.replaceAll(true, List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a")));
        collection.adoptOwner("u1|https://a");

        assertFalse(collection.adoptOwner(null), "身份未知（客户端还没准备好）时不能动缓存");
        assertFalse(collection.adoptOwner(""));
        assertEquals(1, collection.size());
        assertEquals("u1|https://a", collection.owner());
    }

    @Test
    void ownerSurvivesPersistence() {
        Path file = tempDir.resolve("wardrobe.json");
        collection.replaceAll(true, List.of(new WardrobeCollection.Entry("a:b", "B", "cape", "a")));
        collection.adoptOwner("u1|https://a");

        assertTrue(collection.saveNow(file));
        collection.clear();
        assertEquals(1, collection.loadFromDisk(file));

        assertEquals("u1|https://a", collection.owner(), "owner 必须落盘：否则重启后无法判断这份缓存是不是自己的");
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
