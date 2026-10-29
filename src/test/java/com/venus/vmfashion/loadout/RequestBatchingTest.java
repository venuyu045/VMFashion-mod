package com.venus.vmfashion.loadout;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RequestBatching} 单测：超 200 个 UUID 必须切块，不能静默丢数据。 */
class RequestBatchingTest {

    private static List<UUID> uuids(int count) {
        List<UUID> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new UUID(0L, i));
        }
        return list;
    }

    @Test
    void chunksBeyondBatchLimitWithoutDropping() {
        List<UUID> input = uuids(450);
        List<List<UUID>> chunks = RequestBatching.chunk(input, 200);

        assertEquals(3, chunks.size());
        assertEquals(200, chunks.get(0).size());
        assertEquals(200, chunks.get(1).size());
        assertEquals(50, chunks.get(2).size());

        // 拼回去必须和原列表完全一致：不丢、不重、不乱序
        List<UUID> merged = new ArrayList<>(input.size());
        for (List<UUID> chunk : chunks) {
            merged.addAll(chunk);
        }
        assertEquals(input, merged);
    }

    @Test
    void exactMultipleDoesNotProduceEmptyTailChunk() {
        List<List<UUID>> chunks = RequestBatching.chunk(uuids(400), 200);
        assertEquals(2, chunks.size());
        assertEquals(200, chunks.get(1).size());
    }

    @Test
    void smallInputStaysInOneChunk() {
        List<List<UUID>> chunks = RequestBatching.chunk(uuids(3), 200);
        assertEquals(1, chunks.size());
        assertEquals(3, chunks.get(0).size());
    }

    @Test
    void emptyOrNullInputYieldsNoChunks() {
        assertTrue(RequestBatching.chunk(List.of(), 200).isEmpty());
        assertTrue(RequestBatching.chunk(null, 200).isEmpty());
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> RequestBatching.chunk(uuids(1), 0));
        assertThrows(IllegalArgumentException.class, () -> RequestBatching.chunk(uuids(1), -5));
    }

    @Test
    void chunksAreImmutableSnapshots() {
        List<UUID> input = uuids(5);
        List<List<UUID>> chunks = RequestBatching.chunk(input, 2);
        // 块是不可变视图：调用方改原列表 / 改块都不能破坏已提交的批次
        assertThrows(UnsupportedOperationException.class, () -> chunks.get(0).add(UUID.randomUUID()));
        assertEquals(3, chunks.size());
    }
}
