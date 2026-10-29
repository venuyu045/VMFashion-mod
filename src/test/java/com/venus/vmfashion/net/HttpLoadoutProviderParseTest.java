package com.venus.vmfashion.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.loadout.ScaleMath;
import com.venus.vmfashion.loadout.WornCosmetic;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpLoadoutProvider} 的解析/上报体单测。
 *
 * <p>覆盖 v1.0 新增的后端 {@code scale}：缺省、非法、超范围；以及"上报体刻意不带 scale"
 * 这个取舍（否则会把网页衣柜里的设置重置成 1.0）。
 */
class HttpLoadoutProviderParseTest {

    private static final float EPSILON = 1.0e-6F;

    private static JsonElement items(String json) {
        return JsonParser.parseString(json);
    }

    // ==================== scale 解析 ====================

    @Test
    void missingScaleDefaultsToOne() {
        // 老后端没有 scale 字段：按 1.0 渲染，与改造前行为一致
        Map<String, WornCosmetic> parsed = HttpLoadoutProvider.parseItems(items("""
                {"items":[{"slot":"hat","cosmeticId":"demo:hat"}]}
                """));
        assertEquals(1, parsed.size());
        assertEquals("demo:hat", parsed.get("hat").cosmeticId());
        assertEquals(1.0F, parsed.get("hat").scale(), EPSILON);
    }

    @Test
    void parsesExplicitScale() {
        Map<String, WornCosmetic> parsed = HttpLoadoutProvider.parseItems(items("""
                {"items":[
                  {"slot":"hat","cosmeticId":"demo:hat","scale":2.5},
                  {"slot":"cape","cosmeticId":"demo:cape","scale":0.5},
                  {"slot":"wings","cosmeticId":"demo:wings","scale":1}
                ]}
                """));
        assertEquals(3, parsed.size());
        assertEquals(2.5F, parsed.get("hat").scale(), EPSILON);
        assertEquals(0.5F, parsed.get("cape").scale(), EPSILON);
        assertEquals(1.0F, parsed.get("wings").scale(), EPSILON);
    }

    @Test
    void invalidScaleFallsBackToOne() {
        Map<String, WornCosmetic> parsed = HttpLoadoutProvider.parseItems(items("""
                {"items":[
                  {"slot":"a","cosmeticId":"demo:a","scale":"abc"},
                  {"slot":"b","cosmeticId":"demo:b","scale":0},
                  {"slot":"c","cosmeticId":"demo:c","scale":-3},
                  {"slot":"d","cosmeticId":"demo:d","scale":null},
                  {"slot":"e","cosmeticId":"demo:e","scale":{}}
                ]}
                """));
        // 单条脏数据不影响其它条目：全部保留，只是 scale 回退 1.0
        assertEquals(5, parsed.size());
        for (String slot : new String[]{"a", "b", "c", "d", "e"}) {
            assertEquals(1.0F, parsed.get(slot).scale(), EPSILON, "槽位 " + slot + " 应回退 1.0");
        }
    }

    @Test
    void outOfRangeScaleIsClamped() {
        Map<String, WornCosmetic> parsed = HttpLoadoutProvider.parseItems(items("""
                {"items":[
                  {"slot":"big","cosmeticId":"demo:big","scale":1000000},
                  {"slot":"tiny","cosmeticId":"demo:tiny","scale":0.0000001}
                ]}
                """));
        assertEquals(ScaleMath.MAX_SCALE, parsed.get("big").scale(), EPSILON);
        assertEquals(ScaleMath.MIN_SCALE, parsed.get("tiny").scale(), EPSILON);
    }

    @Test
    void skipsMissingAndBlankEntries() {
        Map<String, WornCosmetic> parsed = HttpLoadoutProvider.parseItems(items("""
                {"items":[
                  {"slot":"hat","cosmeticId":"demo:hat","missing":true},
                  {"slot":"","cosmeticId":"demo:x"},
                  {"slot":"y","cosmeticId":""},
                  {"slot":"z"},
                  "not-an-object",
                  {"slot":"ok","cosmeticId":"demo:ok"}
                ]}
                """));
        assertEquals(1, parsed.size(), "只有合法条目应该留下");
        assertTrue(parsed.containsKey("ok"));
    }

    @Test
    void slotKeysAreLowercased() {
        Map<String, WornCosmetic> parsed = HttpLoadoutProvider.parseItems(items("""
                {"items":[{"slot":"HAT","cosmeticId":"demo:hat","scale":3}]}
                """));
        assertTrue(parsed.containsKey("hat"), "槽位 key 必须小写，渲染层按小写查");
        assertEquals(3.0F, parsed.get("hat").scale(), EPSILON);
    }

    @Test
    void handlesMalformedEnvelopeWithoutThrowing() {
        assertTrue(HttpLoadoutProvider.parseItems(null).isEmpty());
        assertTrue(HttpLoadoutProvider.parseItems(items("[]")).isEmpty());
        assertTrue(HttpLoadoutProvider.parseItems(items("{}")).isEmpty());
        assertTrue(HttpLoadoutProvider.parseItems(items("{\"items\":\"oops\"}")).isEmpty());
        assertTrue(HttpLoadoutProvider.parseItems(items("{\"items\":[1,2,3]}")).isEmpty());
    }

    // ==================== 上报体 ====================

    @Test
    void uploadBodyDoesNotIncludeScale() {
        // 取舍：本地没有 scale 状态，带上 1.0 会把网页衣柜里的设置重置掉
        Map<String, String> loadout = new LinkedHashMap<>();
        loadout.put("hat", "demo:hat");
        loadout.put("cape", "demo:cape");

        JsonObject body = HttpLoadoutProvider.buildUploadBody(loadout);

        var items = body.getAsJsonArray("items");
        assertEquals(2, items.size());
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            assertTrue(item.has("slot"));
            assertTrue(item.has("cosmeticId"));
            assertFalse(item.has("scale"), "上报体刻意不带 scale，避免重置后端已保存的值");
        }
    }

    @Test
    void uploadBodySkipsBlankEntriesAndHandlesNull() {
        Map<String, String> loadout = new LinkedHashMap<>();
        loadout.put("hat", "demo:hat");
        loadout.put("", "demo:x");
        loadout.put("y", "");
        loadout.put("z", null);

        JsonObject body = HttpLoadoutProvider.buildUploadBody(loadout);
        assertEquals(1, body.getAsJsonArray("items").size());

        JsonObject empty = HttpLoadoutProvider.buildUploadBody(null);
        assertEquals(0, empty.getAsJsonArray("items").size());
    }
}
