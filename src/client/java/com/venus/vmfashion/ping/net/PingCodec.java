package com.venus.vmfashion.ping.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.venus.vmfashion.ping.core.PingData;
import com.venus.vmfashion.ping.core.PingKind;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 标点协议 v1 的 JSON 编解码（纯函数，不碰网络、不碰 Minecraft，可直接单测）。
 *
 * <p>契约（沿用独立版 VMPing 的 {@code /api/ping} 协议，合订版后端只是把认证换成时装 token）：
 * <ul>
 *   <li>发送：{@code POST /rooms/{roomId}/pings}，body 由 {@link #encodeSend} 生成；</li>
 *   <li>拉取：{@code GET /rooms/{roomId}/pings?since=}，响应由 {@link #decodePollResponse} 解析。</li>
 * </ul>
 *
 * <p>解析一律「容错」：服务端将来加字段、某些字段缺失，都不能让客户端崩。
 * 唯一不能容错的是坐标 —— 非有限数（NaN/Inf）的标点直接丢弃，否则渲染层会把整个
 * 世界坐标算成 NaN，画面直接黑掉。
 *
 * <p>合订版适配：只改包名（{@code com.venus.vmfashion.ping.net}）；字段名与容错规则必须与后端一致，
 * 因此逻辑逐行保留。
 */
public final class PingCodec {

    private PingCodec() {
    }

    // ==================== 发送 ====================

    /**
     * 组装发送标点的请求体。
     *
     * @param dimension  维度 id（{@code minecraft:overworld}）
     * @param x          世界坐标
     * @param y          世界坐标
     * @param z          世界坐标
     * @param kind       标点类型
     * @param targetUuid 点到实体时的实体 UUID，可空
     * @param targetName 点到实体时的实体名，可空
     * @param clientTime 客户端时间戳（仅用于服务端统计延迟，服务端不信它）
     */
    public static JsonObject encodeSend(String dimension, double x, double y, double z,
                                        PingKind kind, UUID targetUuid, String targetName,
                                        long clientTime) {
        JsonObject body = new JsonObject();
        body.addProperty("dimension", dimension == null ? "" : dimension);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("z", z);
        body.addProperty("kind", (kind == null ? PingKind.DEFAULT : kind).id());
        if (targetUuid != null) {
            body.addProperty("targetUuid", targetUuid.toString());
        }
        if (targetName != null && !targetName.isBlank()) {
            body.addProperty("targetName", targetName);
        }
        body.addProperty("clientTime", clientTime);
        return body;
    }

    // ==================== 拉取 ====================

    /** 一次长轮询响应的解析结果。 */
    public record PollResult(long seq, long serverTime, List<PingData> pings) {
        public static final PollResult EMPTY = new PollResult(0L, 0L, List.of());
    }

    /**
     * 解析长轮询响应。
     *
     * <p>响应形状：
     * <pre>{@code {"seq": 45, "serverTime": 1730000001000, "pings": [ ... ]}}</pre>
     */
    public static PollResult decodePollResponse(JsonObject response) {
        if (response == null) {
            return PollResult.EMPTY;
        }
        long seq = optLong(response, "seq", 0L);
        long serverTime = optLong(response, "serverTime", 0L);
        List<PingData> pings = new ArrayList<>();
        JsonElement element = response.get("pings");
        if (element != null && element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                if (item == null || !item.isJsonObject()) {
                    continue;
                }
                PingData data = decodePing(item.getAsJsonObject());
                if (data != null) {
                    pings.add(data);
                }
            }
        }
        return new PollResult(seq, serverTime, pings);
    }

    /**
     * 解析单条标点；坐标非法（NaN/Inf）时返回 {@code null}（丢弃这条，而不是整批失败）。
     */
    public static PingData decodePing(JsonObject object) {
        if (object == null) {
            return null;
        }
        String id = optString(object, "id", "");
        if (id.isBlank()) {
            return null;
        }
        double x = optDouble(object, "x", Double.NaN);
        double y = optDouble(object, "y", Double.NaN);
        double z = optDouble(object, "z", Double.NaN);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        JsonObject player = object.has("player") && object.get("player").isJsonObject()
                ? object.getAsJsonObject("player") : null;
        UUID playerUuid = parseUuid(player == null ? null : optString(player, "uuid", ""));
        String playerName = player == null ? "" : optString(player, "name", "");

        return new PingData(
                id,
                optLong(object, "seq", 0L),
                playerUuid,
                playerName,
                optString(object, "dimension", ""),
                x, y, z,
                PingKind.fromId(optString(object, "kind", "default")),
                parseUuid(optString(object, "targetUuid", "")),
                optString(object, "targetName", ""),
                optLong(object, "serverTime", 0L),
                optLong(object, "expiresAt", 0L),
                false);
    }

    // ==================== 小工具 ====================

    /** 宽松 UUID 解析：接受带横线与 32 位无横线两种写法，非法返回 {@code null}。 */
    public static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.length() == 32) {
                StringBuilder dashed = new StringBuilder(36);
                for (int i = 0; i < 32; i++) {
                    if (i == 8 || i == 12 || i == 16 || i == 20) {
                        dashed.append('-');
                    }
                    dashed.append(value.charAt(i));
                }
                return UUID.fromString(dashed.toString());
            }
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String optString(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static long optLong(JsonObject object, String key, long fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static double optDouble(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static boolean optBoolean(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }
}
