package com.venus.vmfashion.ping.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 房间键计算 —— 「同一个服务器 = 同一个房间」。
 *
 * <p>标点协议 v1 §3：{@code roomId = hex(sha256(归一化服务器地址))[0:16]}。
 * 抽成纯函数（不碰 Minecraft 类）是为了能直接单测，也方便客户端/后端/测试脚本用同一套规则。
 *
 * <p>归一化规则：
 * <ul>
 *   <li>去空白、转小写（{@code Play.Example.COM} 与 {@code play.example.com} 是同一台服）；</li>
 *   <li>去掉可能被误粘贴进来的协议前缀（{@code https://}）与结尾斜杠；</li>
 *   <li>没有端口就补 {@code :25565}（{@code play.example.com} 与 {@code play.example.com:25565} 等价）；</li>
 *   <li>IPv6 字面量（{@code [::1]:25565}）按方括号后的冒号判断端口；</li>
 *   <li>单机固定 {@code singleplayer}、局域网固定 {@code lan}（不把存档名放进房间键，避免泄露）。</li>
 * </ul>
 *
 * <p>合订版适配：只改包名（{@code com.venus.vmfashion.ping.core}）。房间键是客户端与后端共享的
 * 协议算法，不能因为合订而改变，否则同一台服务器上的独立版与合订版会进不了同一个房间。
 */
public final class RoomKey {

    /** 默认 MC 端口。 */
    public static final String DEFAULT_PORT = "25565";
    /** 单机世界的固定房间键。 */
    public static final String SINGLEPLAYER = "singleplayer";
    /** 局域网世界的固定房间键。 */
    public static final String LAN = "lan";
    /** roomId 长度（hex 字符数），协议固定 16。 */
    public static final int ROOM_ID_LENGTH = 16;

    private RoomKey() {
    }

    /**
     * 归一化服务器地址。
     *
     * @param raw 原始地址（{@code Minecraft#getCurrentServer().ip} 或玩家手填）
     * @return 归一化后的地址；输入为空返回空串（调用方据此判定"不在多人服务器"）
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "";
        }
        // 误粘贴的协议前缀 / 路径
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        if (value.isEmpty()) {
            return "";
        }
        if (SINGLEPLAYER.equals(value) || LAN.equals(value)) {
            return value;
        }
        // IPv6：[::1] 或 [::1]:25565
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                return value;
            }
            boolean hasPort = close + 1 < value.length() && value.charAt(close + 1) == ':';
            return hasPort ? value : value + ":" + DEFAULT_PORT;
        }
        // 普通 host / host:port；只有一个冒号才当作 host:port
        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon) {
            String port = value.substring(firstColon + 1);
            // 端口是空串（"host:"）或不是数字时补默认端口，宁可多一个冒号也不要算错房间
            if (port.isEmpty() || !port.chars().allMatch(Character::isDigit)) {
                return value.substring(0, firstColon) + ":" + DEFAULT_PORT;
            }
            return value;
        }
        if (firstColon < 0) {
            return value + ":" + DEFAULT_PORT;
        }
        // 多个冒号且没有方括号：裸 IPv6 字面量，原样返回（房间键照样唯一）
        return value;
    }

    /**
     * 由归一化地址算房间 id。
     *
     * @param normalizedAddress 已归一化的地址（本方法不再做归一化，避免双重归一化产生歧义）
     * @return 16 位小写 hex；输入为空返回空串
     */
    public static String roomId(String normalizedAddress) {
        if (normalizedAddress == null || normalizedAddress.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedAddress.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, ROOM_ID_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // 所有 JVM 都必须有 SHA-256；真没有的话宁可抛出去，也不要静默算出错误房间
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 一步到位：原始地址 → roomId（归一化 + 哈希）。 */
    public static String roomIdFromRaw(String rawAddress) {
        return roomId(normalize(rawAddress));
    }

    /** roomId 是否是协议允许的形状（后端校验同一规则）。 */
    public static boolean isValidRoomId(String roomId) {
        if (roomId == null || roomId.length() != ROOM_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < roomId.length(); i++) {
            char c = roomId.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
