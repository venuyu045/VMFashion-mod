package com.venus.vmfashion.ping.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.ping.core.PingData;
import com.venus.vmfashion.ping.core.PingKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PingCodec} 的单元测试 —— 协议 v1 的 JSON 往返。
 *
 * <p>这些用例同时是**前后端契约测试**：字段名（camelCase）、类型、容错行为都要和后端
 * `api/schemas/ping.py` 对得上。改协议时先改这里，再改后端。
 *
 * <p>合订版适配：包名迁入 {@code com.venus.vmfashion.ping.net}，字段名/容错行为不变
 * （合订版后端只是把认证换成时装 token，{@code /rooms/...} 契约一致）。
 */
class PingCodecTest {

    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    // ==================== 发送 ====================

    @Test
    void encodeSendUsesProtocolFieldNames() {
        JsonObject body = PingCodec.encodeSend("minecraft:overworld", 1.5, 64.0, -3.25,
                PingKind.DANGER, PLAYER, "Steve", 1_730_000_000_000L);
        assertEquals("minecraft:overworld", body.get("dimension").getAsString());
        assertEquals(1.5, body.get("x").getAsDouble(), 1e-9);
        assertEquals(64.0, body.get("y").getAsDouble(), 1e-9);
        assertEquals(-3.25, body.get("z").getAsDouble(), 1e-9);
        assertEquals("danger", body.get("kind").getAsString());
        assertEquals(PLAYER.toString(), body.get("targetUuid").getAsString());
        assertEquals("Steve", body.get("targetName").getAsString());
        assertEquals(1_730_000_000_000L, body.get("clientTime").getAsLong());
    }

    @Test
    void encodeSendOmitsOptionalFields() {
        JsonObject body = PingCodec.encodeSend("minecraft:overworld", 0, 0, 0,
                PingKind.DEFAULT, null, "  ", 1L);
        assertFalse(body.has("targetUuid"));
        assertFalse(body.has("targetName"));
        assertEquals("default", body.get("kind").getAsString());
    }

    @Test
    void encodeSendDefaultsNullKind() {
        JsonObject body = PingCodec.encodeSend("minecraft:overworld", 0, 0, 0, null, null, null, 1L);
        assertEquals("default", body.get("kind").getAsString());
    }

    // ==================== 拉取 ====================

    private static final String POLL_JSON = """
            {
              "seq": 45,
              "serverTime": 1730000001000,
              "pings": [
                {
                  "seq": 43,
                  "id": "9f2c",
                  "player": {"uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "name": "Notch"},
                  "dimension": "minecraft:overworld",
                  "x": 123.45, "y": 64.0, "z": -89.25,
                  "kind": "help",
                  "targetUuid": null,
                  "targetName": null,
                  "serverTime": 1730000000200,
                  "expiresAt": 1730000020200
                },
                {
                  "seq": 44,
                  "id": "a1b2",
                  "player": {"uuid": "11111111-2222-3333-4444-555555555555", "name": "Steve"},
                  "dimension": "minecraft:overworld",
                  "x": 0, "y": 0, "z": 0,
                  "kind": "unknown-kind",
                  "serverTime": 1730000000300,
                  "expiresAt": 1730000020300
                }
              ]
            }
            """;

    @Test
    void decodePollResponse() {
        PingCodec.PollResult result = PingCodec.decodePollResponse(
                JsonParser.parseString(POLL_JSON).getAsJsonObject());
        assertEquals(45L, result.seq());
        assertEquals(1_730_000_001_000L, result.serverTime());
        assertEquals(2, result.pings().size());

        PingData first = result.pings().get(0);
        assertEquals("9f2c", first.id());
        assertEquals(43L, first.seq());
        assertEquals(PLAYER, first.playerUuid());
        assertEquals("Notch", first.playerName());
        assertEquals(PingKind.HELP, first.kind());
        assertEquals(123.45, first.x(), 1e-9);
        assertEquals(1_730_000_020_200L, first.expiresAt());
        assertFalse(first.local());
    }

    @Test
    void unknownKindFallsBackToDefault() {
        PingCodec.PollResult result = PingCodec.decodePollResponse(
                JsonParser.parseString(POLL_JSON).getAsJsonObject());
        assertEquals(PingKind.DEFAULT, result.pings().get(1).kind(),
                "未知 kind 必须降级成 default，不能崩");
    }

    @Test
    void decodePingDropsNonFiniteCoordinates() {
        JsonObject bad = JsonParser.parseString("""
                {"id":"x","seq":1,"x":null,"y":64,"z":0,"serverTime":1,"expiresAt":2}
                """).getAsJsonObject();
        assertNull(PingCodec.decodePing(bad), "坐标缺失/非有限数必须丢弃");

        JsonObject nan = JsonParser.parseString("""
                {"id":"x","seq":1,"x":1,"y":64,"z":0,"serverTime":1,"expiresAt":2}
                """).getAsJsonObject();
        assertNotNull(PingCodec.decodePing(nan));
    }

    @Test
    void decodePingRequiresId() {
        JsonObject noId = JsonParser.parseString("""
                {"seq":1,"x":1,"y":2,"z":3,"serverTime":1,"expiresAt":2}
                """).getAsJsonObject();
        assertNull(PingCodec.decodePing(noId));
    }

    @Test
    void decodePollResponseToleratesGarbage() {
        assertEquals(PingCodec.PollResult.EMPTY, PingCodec.decodePollResponse(null));
        PingCodec.PollResult empty = PingCodec.decodePollResponse(new JsonObject());
        assertEquals(0L, empty.seq());
        assertTrue(empty.pings().isEmpty());
    }

    @Test
    void decodePollResponseSkipsNonObjectEntries() {
        JsonObject json = JsonParser.parseString("""
                {"seq":3,"serverTime":1,"pings":[1,"x",{"id":"ok","x":1,"y":2,"z":3,"serverTime":1,"expiresAt":2}]}
                """).getAsJsonObject();
        List<PingData> pings = PingCodec.decodePollResponse(json).pings();
        assertEquals(1, pings.size());
        assertEquals("ok", pings.get(0).id());
    }

    // ==================== 工具函数 ====================

    @Test
    void parseUuidAcceptsBothForms() {
        assertEquals(PLAYER, PingCodec.parseUuid("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
        assertEquals(PLAYER, PingCodec.parseUuid("069a79f444e94726a5befca90e38aaf5"));
        assertNull(PingCodec.parseUuid("not-a-uuid"));
        assertNull(PingCodec.parseUuid(""));
        assertNull(PingCodec.parseUuid(null));
    }

    @Test
    void optHelpersReturnFallbacks() {
        JsonObject json = JsonParser.parseString("""
                {"s":"v","n":7,"d":1.5,"b":true,"nil":null,"obj":{"a":1}}
                """).getAsJsonObject();
        assertEquals("v", PingCodec.optString(json, "s", "x"));
        assertEquals("x", PingCodec.optString(json, "missing", "x"));
        assertEquals("x", PingCodec.optString(json, "obj", "x"));
        assertEquals("x", PingCodec.optString(json, "nil", "x"));
        assertEquals(7L, PingCodec.optLong(json, "n", -1L));
        assertEquals(-1L, PingCodec.optLong(json, "s", -1L));
        assertEquals(1.5, PingCodec.optDouble(json, "d", 0d), 1e-9);
        assertTrue(PingCodec.optBoolean(json, "b", false));
        assertFalse(PingCodec.optBoolean(json, "missing", false));
    }
}
