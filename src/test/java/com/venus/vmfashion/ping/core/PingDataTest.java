package com.venus.vmfashion.ping.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PingData} / {@link PingClock} 的单元测试。
 *
 * <p>淡出曲线与时钟偏移是"看起来没问题、实际会翻车"的典型：
 * 服务端时间戳 vs 本地时钟差几分钟时，标点会立刻全部消失或者永远不消失。
 *
 * <p>合订版适配：包名迁入 {@code com.venus.vmfashion.ping.core}，用例与被测行为不变。
 */
class PingDataTest {

    private static final UUID SELF = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private static PingData ping(long serverTime, long expiresAt) {
        return new PingData("id-1", 1L, SELF, "Notch", "minecraft:overworld",
                1d, 64d, 2d, PingKind.DEFAULT, null, null, serverTime, expiresAt, false);
    }

    @Test
    void expiredAndRemaining() {
        PingData data = ping(1_000L, 2_000L);
        assertFalse(data.expired(1_999L));
        assertTrue(data.expired(2_000L));
        assertEquals(0L, data.remainingMs(2_500L));
        assertEquals(500L, data.remainingMs(1_500L));
    }

    @Test
    void fadeAlphaIsFullUntilLastWindow() {
        PingData data = ping(0L, 20_000L);
        assertEquals(1f, data.fadeAlpha(0L, 3_000L), 1e-6);
        assertEquals(1f, data.fadeAlpha(17_000L, 3_000L), 1e-6);
        assertEquals(0.5f, data.fadeAlpha(18_500L, 3_000L), 1e-3);
        assertEquals(0f, data.fadeAlpha(20_000L, 3_000L), 1e-6);
        assertEquals(0f, data.fadeAlpha(25_000L, 3_000L), 1e-6);
    }

    @Test
    void fadeAlphaWithoutFadeWindow() {
        PingData data = ping(0L, 20_000L);
        assertEquals(1f, data.fadeAlpha(19_999L, 0L), 1e-6);
    }

    @Test
    void isOwn() {
        assertTrue(ping(0L, 1L).isOwn(SELF));
        assertFalse(ping(0L, 1L).isOwn(UUID.randomUUID()));
        assertFalse(ping(0L, 1L).isOwn(null));
    }

    @Test
    void localPingHasTtl() {
        PingData local = PingData.local(SELF, "Notch", "minecraft:overworld",
                0d, 0d, 0d, PingKind.HELP, null, null, 1_000L, 5_000L);
        assertTrue(local.local());
        assertEquals(6_000L, local.expiresAt());
        assertTrue(local.id().startsWith("local-"));
        assertFalse(local.expired(5_999L));
        assertTrue(local.expired(6_000L));
    }

    @Test
    void localPingTtlHasFloor() {
        // TTL 传 0 时不能立刻过期（至少活 1 秒），否则标点会闪一下就没了
        PingData local = PingData.local(SELF, "Notch", "minecraft:overworld",
                0d, 0d, 0d, PingKind.DEFAULT, null, null, 1_000L, 0L);
        assertEquals(2_000L, local.expiresAt());
    }

    // ==================== PingClock ====================

    @Test
    void clockSyncsOffset() {
        PingClock clock = new PingClock();
        long localNow = 10_000L;
        clock.sync(localNow + 5_000L, localNow);
        assertEquals(5_000L, clock.offsetMs());
        assertTrue(clock.synced());
    }

    @Test
    void clockResetsOnHugeSkew() {
        PingClock clock = new PingClock();
        clock.sync(10_000L, 10_000L);
        assertEquals(0L, clock.offsetMs());
        // 偏差超过 2 分钟：视为换了服务器/服务端重启，直接重置而不是平滑
        clock.sync(10_000_000L, 10_000L);
        assertEquals(9_990_000L, clock.offsetMs());
    }

    @Test
    void clockReset() {
        PingClock clock = new PingClock();
        clock.sync(10_500L, 10_000L);
        clock.reset();
        assertFalse(clock.synced());
        assertEquals(0L, clock.offsetMs());
    }
}
