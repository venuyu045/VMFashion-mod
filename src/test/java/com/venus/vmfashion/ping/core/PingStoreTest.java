package com.venus.vmfashion.ping.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PingStore} 的单元测试。
 *
 * <p>重点覆盖"长轮询重放不会产生重复标记""过期清理""容量上限"三件事 —— 这三件
 * 出问题时玩家看到的是"标点越来越多 / 不消失 / 内存越用越大"。
 *
 * <p>合订版适配：包名迁入 {@code com.venus.vmfashion.ping.core}，用例与被测行为不变。
 */
class PingStoreTest {

    private static final UUID SELF = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID OTHER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static PingData ping(String id, long seq, UUID player, long serverTime, long expiresAt) {
        return new PingData(id, seq, player, player.equals(SELF) ? "Notch" : "Steve",
                "minecraft:overworld", 0d, 64d, 0d, PingKind.DEFAULT, null, null,
                serverTime, expiresAt, false);
    }

    @Test
    void putAndSnapshot() {
        PingStore store = new PingStore();
        store.put(ping("a", 1, OTHER, 100L, 10_000L));
        store.put(ping("b", 2, OTHER, 200L, 10_000L));
        List<PingData> snapshot = store.snapshot(1_000L);
        assertEquals(2, snapshot.size());
        assertEquals("a", snapshot.get(0).id(), "按 serverTime 升序");
        assertEquals(2L, store.lastSeq());
    }

    @Test
    void sameIdReplacesInsteadOfDuplicating() {
        PingStore store = new PingStore();
        store.put(ping("a", 1, OTHER, 100L, 10_000L));
        store.put(ping("a", 1, OTHER, 100L, 20_000L));
        assertEquals(1, store.size(), "长轮询重放同一条标点不能变成两条");
        assertEquals(20_000L, store.snapshot(1_000L).get(0).expiresAt());
    }

    @Test
    void expireRemovesOldPings() {
        PingStore store = new PingStore();
        store.put(ping("a", 1, OTHER, 100L, 1_000L));
        store.put(ping("b", 2, OTHER, 200L, 9_000L));
        assertEquals(1, store.expire(1_500L));
        assertEquals(1, store.size());
    }

    @Test
    void snapshotFiltersExpired() {
        PingStore store = new PingStore();
        store.put(ping("a", 1, OTHER, 100L, 1_000L));
        assertEquals(0, store.snapshot(2_000L).size());
        // 但快照不负责删除（由 tick 里的 expire 做）
        assertEquals(1, store.size());
    }

    @Test
    void snapshotByDimension() {
        PingStore store = new PingStore();
        store.put(new PingData("a", 1, OTHER, "Steve", "minecraft:overworld",
                0d, 0d, 0d, PingKind.DEFAULT, null, null, 100L, 9_000L, false));
        store.put(new PingData("b", 2, OTHER, "Steve", "minecraft:the_nether",
                0d, 0d, 0d, PingKind.DEFAULT, null, null, 200L, 9_000L, false));
        assertEquals(1, store.snapshot("minecraft:overworld", 1_000L).size());
        assertEquals(2, store.snapshot("", 1_000L).size(), "空维度 = 不过滤");
    }

    @Test
    void clearOthersKeepsOwn() {
        PingStore store = new PingStore();
        store.put(ping("own", 1, SELF, 100L, 9_000L));
        store.put(ping("other", 2, OTHER, 200L, 9_000L));
        store.clearOthers(SELF);
        assertEquals(1, store.size());
        assertEquals("own", store.snapshot(1_000L).get(0).id());
    }

    @Test
    void resetCursorKeepsPingsButRewindsSince() {
        // 房间被回收后 seq 归零：游标必须回到 0，否则下一次长轮询会带着旧的高位 since，
        // 服务端永远不返回新标点（只能退服恢复）。
        PingStore store = new PingStore();
        store.put(ping("a", 7, OTHER, 100L, 10_000L));
        store.put(ping("own", 8, SELF, 200L, 10_000L));
        assertEquals(8L, store.lastSeq());

        store.resetCursor();
        assertEquals(0L, store.lastSeq(), "游标归零");
        assertEquals(2, store.size(), "resetCursor 只动游标，不清标点");
        assertTrue(store.snapshot(1_000L).stream().anyMatch(p -> p.id().equals("a")));
    }

    @Test
    void clearResetsSeq() {
        PingStore store = new PingStore();
        store.put(ping("a", 42, OTHER, 100L, 9_000L));
        store.clear();
        assertEquals(0L, store.lastSeq());
        assertEquals(0, store.size());
    }

    @Test
    void capacityIsBounded() {
        PingStore store = new PingStore();
        long now = 1_000L;
        for (int i = 0; i < PingStore.MAX_PINGS + 50; i++) {
            store.put(ping("p" + i, i, OTHER, now + i, now + 60_000L));
        }
        assertTrue(store.size() <= PingStore.MAX_PINGS,
                "超过容量上限必须裁剪，实际 " + store.size());
    }

    @Test
    void trimPrefersRemovingOldest() {
        PingStore store = new PingStore();
        for (int i = 0; i < PingStore.MAX_PINGS + 10; i++) {
            store.put(ping("p" + i, i, OTHER, 1_000L + i, 999_999L));
        }
        List<PingData> snapshot = store.snapshot(2_000L);
        assertFalse(snapshot.isEmpty());
        // 最新的那条一定还在
        assertTrue(snapshot.stream().anyMatch(p -> p.id().equals("p" + (PingStore.MAX_PINGS + 9))));
    }

    @Test
    void clockSyncAffectsNow() {
        PingStore store = new PingStore();
        long before = store.now();
        store.syncClock(before + 30_000L);
        assertTrue(store.now() >= before + 25_000L, "服务端时间偏移应当生效");
    }

    // ==================== 房间回收检测（v2.0.1） ====================

    @Test
    void isRoomResetDetectsSeqRollbackIncludingZero() {
        // 房间被回收后 seq 归零；seq==0 必须也算回退 —— 后端对"不存在的房间"就返回 seq=0
        // （ping_relay.py 的 snapshot(create=False)）。漏掉这一支的后果：只剩自己在线时游标
        // 永远不重置，之后再也收不到任何标点（只能退服恢复）。
        assertTrue(PingStore.isRoomReset(0L, 45L), "seq=0 也是回退（房间被回收后重建）");
        assertTrue(PingStore.isRoomReset(1L, 45L));
        assertTrue(PingStore.isRoomReset(44L, 45L));
    }

    @Test
    void isRoomResetIgnoresNormalAndForwardSeq() {
        assertFalse(PingStore.isRoomReset(0L, 0L), "全新客户端（游标 0）不是回退");
        assertFalse(PingStore.isRoomReset(45L, 45L), "序号没动不是回退");
        assertFalse(PingStore.isRoomReset(46L, 45L), "序号前进不是回退");
        assertFalse(PingStore.isRoomReset(1L, 0L), "第一次进已有房间不是回退");
    }

    @Test
    void roomResetThenCursorRewindRefetchesFromZero() {
        // 端到端语义：检测到回退 → resetCursor → 下一次长轮询的 since 是 0 → 能补齐房间里的存活标点
        PingStore store = new PingStore();
        store.put(ping("old", 45, OTHER, 100L, 999_999L));
        assertEquals(45L, store.lastSeq());

        assertTrue(PingStore.isRoomReset(0L, store.lastSeq()));
        store.resetCursor();
        store.clearOthers(SELF);
        assertEquals(0L, store.lastSeq(), "下一次长轮询必须从 since=0 开始");

        // 服务端按 since=0 返回新房间里的标点，游标重新前进
        store.putAll(List.of(ping("new", 3, OTHER, 200L, 999_999L)));
        assertEquals(3L, store.lastSeq());
    }
}
