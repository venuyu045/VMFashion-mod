package com.venus.vmfashion.cosmetic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoadoutStore} 的持久化测试（纯逻辑：临时目录 + 文本文件，不加载任何 Minecraft 类）。
 *
 * <p>覆盖：写入-读回、原子写不留临时文件、父目录自动创建、损坏文件静默降级、未知字段忽略。
 */
class LoadoutStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveThenReadRoundTrip() {
        Path file = tempDir.resolve("loadout.json");
        Map<String, String> loadout = new LinkedHashMap<>();
        loadout.put("hat", "example:crown");
        loadout.put("back", "example:backboard");

        assertTrue(LoadoutStore.saveNow(file, loadout));

        assertEquals(loadout, LoadoutStore.read(file));
    }

    @Test
    void saveIsAtomicAndLeavesNoTempFileBehind() throws IOException {
        Path file = tempDir.resolve("loadout.json");

        assertTrue(LoadoutStore.saveNow(file, Map.of("hat", "a:b")));
        assertTrue(LoadoutStore.saveNow(file, Map.of("hat", "c:d")));

        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(List.of("loadout.json"),
                    files.map(path -> path.getFileName().toString()).sorted().toList(),
                    "原子写不能留下 .tmp 残留");
        }
        assertEquals(Map.of("hat", "c:d"), LoadoutStore.read(file), "第二次写必须覆盖第一次");
    }

    @Test
    void createsMissingParentDirectories() {
        Path file = tempDir.resolve("nested").resolve("vmfashion").resolve("loadout.json");

        assertTrue(LoadoutStore.saveNow(file, Map.of("hat", "a:b")));

        assertTrue(Files.isRegularFile(file));
        assertEquals(Map.of("hat", "a:b"), LoadoutStore.read(file));
    }

    @Test
    void savesCurrentSingletonLoadout() {
        // 不假设单例内容（其它测试可能写过），只验证"落盘 == 快照"
        Map<String, String> expected = LocalLoadout.getInstance().snapshot();
        Path file = tempDir.resolve("loadout.json");

        assertTrue(LoadoutStore.saveNow(file));

        assertEquals(expected, LoadoutStore.read(file));
    }

    @Test
    void missingFileDegradesToNull() {
        assertNull(LoadoutStore.read(tempDir.resolve("nope.json")));
    }

    @Test
    void corruptedFileDegradesSilently() throws IOException {
        Path file = write("{ 这不是 json ");

        assertNull(LoadoutStore.read(file));
        // loadInto 在坏文件上不能清空内存状态
        assertEquals(-1, LoadoutStore.loadInto(LocalLoadout.getInstance(), file));
    }

    @Test
    void nonObjectRootIsRejected() throws IOException {
        assertNull(LoadoutStore.read(write("[1, 2, 3]")));
        assertNull(LoadoutStore.read(write("\"just a string\"")));
    }

    @Test
    void unknownFieldsAndBadValuesAreIgnored() throws IOException {
        Path file = write("""
                {
                  "formatVersion": 99,
                  "futureField": 123,
                  "nested": { "a": 1 },
                  "loadout": {
                    "hat": "example:crown",
                    "back": 42,
                    "": "空 slot",
                    "  ": "空白 slot"
                  }
                }
                """);

        assertEquals(Map.of("hat", "example:crown"), LoadoutStore.read(file));
    }

    @Test
    void bareMapWithoutWrapperIsAccepted() throws IOException {
        Path file = write("""
                {"HAT": "example:crown", "formatVersion": 1}
                """);

        assertEquals(Map.of("hat", "example:crown"), LoadoutStore.read(file));
    }

    @Test
    void blankEntriesAreNotPersisted() {
        Path file = tempDir.resolve("loadout.json");
        Map<String, String> loadout = new LinkedHashMap<>();
        loadout.put("  HAT  ", "  example:crown  ");
        loadout.put("", "空 slot");
        loadout.put("back", "   ");

        assertTrue(LoadoutStore.saveNow(file, loadout));

        assertEquals(Map.of("hat", "example:crown"), LoadoutStore.read(file));
    }

    @Test
    void emptyLoadoutIsStillWritten() {
        Path file = tempDir.resolve("loadout.json");

        assertTrue(LoadoutStore.saveNow(file, Map.of()));

        assertEquals(Map.of(), LoadoutStore.read(file));
    }

    @Test
    void nullTargetIsRejected() {
        assertFalse(LoadoutStore.saveNow(null, Map.of("hat", "a:b")));
    }

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("loadout.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
