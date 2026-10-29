package com.venus.vmfashion.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HashUtil} 单测：sha256 向量、文件/流式摘要、格式校验、恒定时间比较。 */
class HashUtilTest {

    /** "abc" 的 sha256，来自 FIPS 180-4 标准向量。 */
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void hashesKnownVectors() {
        assertEquals(ABC_SHA256, HashUtil.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(EMPTY_SHA256, HashUtil.sha256Hex(new byte[0]));
        // 输出必须是小写 hex，长度 64
        assertEquals(HashUtil.SHA256_HEX_LENGTH, HashUtil.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)).length());
    }

    @Test
    void hashesFileContents(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("abc.txt");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);
        assertEquals(ABC_SHA256, HashUtil.sha256Hex(file));

        Path empty = tempDir.resolve("empty.bin");
        Files.write(empty, new byte[0]);
        assertEquals(EMPTY_SHA256, HashUtil.sha256Hex(empty));
    }

    @Test
    void hashesStreamsAndReadsToEnd() throws IOException {
        byte[] data = new byte[200_000]; // 超过 64KB 缓冲区，验证分段读取
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }
        String fromStream = HashUtil.sha256Hex(new ByteArrayInputStream(data));
        assertEquals(HashUtil.sha256Hex(data), fromStream);
    }

    @Test
    void streamHashingDoesNotCloseCallerStream() throws IOException {
        TrackingStream stream = new TrackingStream("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(ABC_SHA256, HashUtil.sha256Hex(stream));
        assertFalse(stream.closed, "HashUtil 不能关闭调用方传入的流");
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class, () -> HashUtil.sha256Hex((byte[]) null));
        assertThrows(IllegalArgumentException.class, () -> HashUtil.sha256Hex((InputStream) null));
        assertThrows(IllegalArgumentException.class, () -> HashUtil.toHex(null));
    }

    @Test
    void validatesSha256HexFormat() {
        assertTrue(HashUtil.isSha256Hex(ABC_SHA256));
        assertTrue(HashUtil.isSha256Hex(ABC_SHA256.toUpperCase(java.util.Locale.ROOT)));
        assertFalse(HashUtil.isSha256Hex(null));
        assertFalse(HashUtil.isSha256Hex(""));
        assertFalse(HashUtil.isSha256Hex(ABC_SHA256.substring(0, 63)), "长度不足必须拒绝");
        assertFalse(HashUtil.isSha256Hex(ABC_SHA256.substring(1) + "z"), "非 hex 字符必须拒绝");
        assertFalse(HashUtil.isSha256Hex("g".repeat(64)));
    }

    @Test
    void comparesSha256IgnoringCase() {
        assertTrue(HashUtil.sha256Equals(ABC_SHA256, ABC_SHA256.toUpperCase(java.util.Locale.ROOT)));
        assertTrue(HashUtil.sha256Equals(ABC_SHA256, ABC_SHA256));
        assertFalse(HashUtil.sha256Equals(ABC_SHA256, EMPTY_SHA256));
        // 非法输入一律 false，绝不能"因为解析失败就当通过"
        assertFalse(HashUtil.sha256Equals(ABC_SHA256, null));
        assertFalse(HashUtil.sha256Equals(null, ABC_SHA256));
        assertFalse(HashUtil.sha256Equals("not-a-hash", "not-a-hash"));
    }

    /** 记录是否被 close 的流。 */
    private static final class TrackingStream extends ByteArrayInputStream {

        private boolean closed;

        private TrackingStream(byte[] data) {
            super(data);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
