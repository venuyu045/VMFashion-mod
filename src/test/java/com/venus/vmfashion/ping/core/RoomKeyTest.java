package com.venus.vmfashion.ping.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoomKey} 的单元测试。
 *
 * <p>房间键是"谁和谁能互相看见标点"的唯一依据，算错就是**串服**（在 A 服看到 B 服的标点），
 * 所以归一化的每个分支都必须有测试。
 *
 * <p>合订版适配：包名迁入 {@code com.venus.vmfashion.ping.core}，用例与被测行为不变
 * （房间键算法是客户端与后端共享的协议，合订后也不能改）。
 */
class RoomKeyTest {

    @Test
    void normalizeAddsDefaultPort() {
        assertEquals("play.example.com:25565", RoomKey.normalize("play.example.com"));
    }

    @Test
    void normalizeKeepsExplicitPort() {
        assertEquals("play.example.com:25566", RoomKey.normalize("play.example.com:25566"));
    }

    @Test
    void normalizeIsCaseInsensitive() {
        assertEquals(RoomKey.normalize("Play.Example.COM"), RoomKey.normalize("play.example.com"));
    }

    @Test
    void normalizeStripsSchemeAndPath() {
        assertEquals("play.example.com:25565", RoomKey.normalize("https://play.example.com/"));
        assertEquals("play.example.com:25565", RoomKey.normalize("  https://play.example.com:25565/status  "));
    }

    @Test
    void normalizeHandlesIpv6() {
        assertEquals("[::1]:25565", RoomKey.normalize("[::1]"));
        assertEquals("[::1]:25566", RoomKey.normalize("[::1]:25566"));
    }

    @Test
    void normalizeHandlesBlankPort() {
        assertEquals("play.example.com:25565", RoomKey.normalize("play.example.com:"));
        assertEquals("play.example.com:25565", RoomKey.normalize("play.example.com:abc"));
    }

    @Test
    void normalizeKeepsSpecialRooms() {
        assertEquals(RoomKey.SINGLEPLAYER, RoomKey.normalize("singleplayer"));
        assertEquals(RoomKey.LAN, RoomKey.normalize("LAN"));
    }

    @Test
    void normalizeEmptyInputs() {
        assertEquals("", RoomKey.normalize(null));
        assertEquals("", RoomKey.normalize("   "));
        assertEquals("", RoomKey.normalize("https://"));
    }

    @Test
    void roomIdIsStableAndHex() {
        String a = RoomKey.roomIdFromRaw("play.example.com");
        String b = RoomKey.roomIdFromRaw("PLAY.example.com:25565");
        assertEquals(a, b, "归一化后同一个房间必须得到同一个 id");
        assertEquals(RoomKey.ROOM_ID_LENGTH, a.length());
        assertTrue(RoomKey.isValidRoomId(a));
    }

    @Test
    void differentServersDifferentRooms() {
        assertNotEquals(RoomKey.roomIdFromRaw("a.example.com"),
                RoomKey.roomIdFromRaw("b.example.com"));
    }

    @Test
    void roomIdEmptyForBlank() {
        assertEquals("", RoomKey.roomId(""));
        assertEquals("", RoomKey.roomId(null));
        assertFalse(RoomKey.isValidRoomId(""));
        assertFalse(RoomKey.isValidRoomId("0123456789ABCDEF"), "大写 hex 不合法（协议要求小写）");
        assertFalse(RoomKey.isValidRoomId("0123456789abcde"), "长度不足 16");
        assertFalse(RoomKey.isValidRoomId("zzzzzzzzzzzzzzzz"), "非 hex 字符");
    }
}
