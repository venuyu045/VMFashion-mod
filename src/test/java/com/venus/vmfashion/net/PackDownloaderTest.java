package com.venus.vmfashion.net;

import com.venus.vmfashion.util.HashUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PackDownloader} 单测。
 *
 * <p>不联调网络：{@link PackDownloader#writeVerified} 是包可见的纯函数，直接喂内存流就能覆盖
 * "哈希不符 / 字节数不符 / 超限 / 取消 / 残包清理"这些真正危险的路径；URL 相关逻辑单独测。
 */
class PackDownloaderTest {

    private static final byte[] PAYLOAD = "时装包内容".repeat(100).getBytes(StandardCharsets.UTF_8);
    private static final String PAYLOAD_SHA = HashUtil.sha256Hex(PAYLOAD);

    @TempDir
    Path tempDir;

    @Test
    void writesFileAndVerifiesHash() throws Exception {
        Path target = tempDir.resolve("pack.zip");

        PackDownloader.DownloadResult result = PackDownloader.writeVerified(
                new ByteArrayInputStream(PAYLOAD), target, PAYLOAD_SHA, PAYLOAD.length, 0L, null);

        assertTrue(Files.isRegularFile(target));
        assertEquals(PAYLOAD.length, result.bytes());
        assertEquals(PAYLOAD_SHA, result.sha256());
        assertEquals(PAYLOAD_SHA, HashUtil.sha256Hex(target));
    }

    @Test
    void acceptsUpperCaseExpectedHash() throws Exception {
        Path target = tempDir.resolve("upper.zip");
        PackDownloader.writeVerified(new ByteArrayInputStream(PAYLOAD), target,
                PAYLOAD_SHA.toUpperCase(java.util.Locale.ROOT), PAYLOAD.length, 0L, null);
        assertTrue(Files.isRegularFile(target));
    }

    @Test
    void rejectsHashMismatchAndDeletesPartialFile() {
        Path target = tempDir.resolve("mismatch.zip");
        String wrongSha = "0".repeat(64);

        PackDownloader.DownloadException exception = assertThrows(PackDownloader.DownloadException.class,
                () -> PackDownloader.writeVerified(new ByteArrayInputStream(PAYLOAD), target,
                        wrongSha, PAYLOAD.length, 0L, null));

        assertEquals(PackDownloader.FailureReason.HASH_MISMATCH, exception.reason());
        assertFalse(Files.exists(target), "校验失败必须删掉残包，绝不能留下可被误装的文件");
    }

    @Test
    void rejectsByteCountMismatch() {
        Path target = tempDir.resolve("size.zip");

        PackDownloader.DownloadException exception = assertThrows(PackDownloader.DownloadException.class,
                () -> PackDownloader.writeVerified(new ByteArrayInputStream(PAYLOAD), target,
                        PAYLOAD_SHA, PAYLOAD.length + 1, 0L, null));

        assertEquals(PackDownloader.FailureReason.SIZE_MISMATCH, exception.reason());
        assertFalse(Files.exists(target));
    }

    @Test
    void abortsWhenDeclaredSizeIsSmallerThanActual() {
        // 清单说 10 字节、实际 100 字节：必须在中途就中断，不能等下完
        byte[] big = new byte[100_000];
        Path target = tempDir.resolve("liar.zip");

        PackDownloader.DownloadException exception = assertThrows(PackDownloader.DownloadException.class,
                () -> PackDownloader.writeVerified(new ByteArrayInputStream(big), target,
                        HashUtil.sha256Hex(big), 10L, 0L, null));

        assertEquals(PackDownloader.FailureReason.SIZE_MISMATCH, exception.reason());
        assertFalse(Files.exists(target));
    }

    @Test
    void enforcesHardSizeLimit() {
        byte[] big = new byte[100_000];
        Path target = tempDir.resolve("toolarge.zip");

        PackDownloader.DownloadException exception = assertThrows(PackDownloader.DownloadException.class,
                () -> PackDownloader.writeVerified(new ByteArrayInputStream(big), target,
                        HashUtil.sha256Hex(big), 0L, 1_000L, null));

        assertEquals(PackDownloader.FailureReason.TOO_LARGE, exception.reason());
        assertFalse(Files.exists(target));
    }

    @Test
    void honoursCancellationFlag() {
        AtomicBoolean aborted = new AtomicBoolean(true);
        Path target = tempDir.resolve("aborted.zip");

        PackDownloader.DownloadException exception = assertThrows(PackDownloader.DownloadException.class,
                () -> PackDownloader.writeVerified(new ByteArrayInputStream(PAYLOAD), target,
                        PAYLOAD_SHA, PAYLOAD.length, 0L, aborted::get));

        assertEquals(PackDownloader.FailureReason.ABORTED, exception.reason());
        assertFalse(Files.exists(target));
    }

    @Test
    void refusesToWriteWithoutTrustworthyHash() {
        Path target = tempDir.resolve("nohash.zip");

        PackDownloader.DownloadException exception = assertThrows(PackDownloader.DownloadException.class,
                () -> PackDownloader.writeVerified(new ByteArrayInputStream(PAYLOAD), target,
                        "not-a-sha256", PAYLOAD.length, 0L, null));

        assertEquals(PackDownloader.FailureReason.HASH_MISMATCH, exception.reason());
        assertFalse(Files.exists(target));
    }

    @Test
    void downloadFailsFastWithoutTrustworthyHash() throws Exception {
        PackDownloader downloader = new PackDownloader(null, Runnable::run);
        CompletableFuture<PackDownloader.DownloadResult> future = downloader.download(
                "https://example.com/pack.zip", "https://example.com", null,
                tempDir.resolve("never.zip"), "", 1L, 0L, null);

        assertTrue(future.isCompletedExceptionally());
        assertFailureReason(future, PackDownloader.FailureReason.HASH_MISMATCH);
    }

    @Test
    void resolvesRelativeAndAbsoluteUrls() {
        assertEquals(URI.create("https://api.example.com/api/fashion/packs/a/1/download"),
                PackDownloader.resolveUri("/packs/a/1/download", "https://api.example.com/api/fashion/"));
        assertEquals(URI.create("https://api.example.com/api/fashion/packs/a/1/download"),
                PackDownloader.resolveUri("packs/a/1/download", "https://api.example.com/api/fashion"));
        assertEquals(URI.create("https://cdn.example.com/x.zip"),
                PackDownloader.resolveUri("https://cdn.example.com/x.zip", "https://api.example.com"));

        // 非 http(s) 一律拒绝（file:// 会变成任意文件读取）
        assertNull(PackDownloader.resolveUri("file:///etc/passwd", "https://api.example.com"));
        assertNull(PackDownloader.resolveUri("ftp://example.com/x.zip", "https://api.example.com"));
        assertNull(PackDownloader.resolveUri("", "https://api.example.com"));
        assertNull(PackDownloader.resolveUri("/packs/a", ""));
        assertNull(PackDownloader.resolveUri(null, "https://api.example.com"));
    }

    @Test
    void tokenOnlySentToSameOrigin() {
        assertTrue(PackDownloader.sameOrigin(URI.create("https://api.example.com/packs/a/download"),
                "https://api.example.com/api/fashion"));
        // 默认端口与显式端口等价
        assertTrue(PackDownloader.sameOrigin(URI.create("https://api.example.com:443/x"),
                "https://api.example.com/x"));
        // 换 host / 换 scheme 都不能带 token
        assertFalse(PackDownloader.sameOrigin(URI.create("https://cdn.evil.com/x"),
                "https://api.example.com"));
        assertFalse(PackDownloader.sameOrigin(URI.create("http://api.example.com/x"),
                "https://api.example.com"));
        assertFalse(PackDownloader.sameOrigin(URI.create("https://api.example.com:8443/x"),
                "https://api.example.com"));
        assertFalse(PackDownloader.sameOrigin(null, "https://api.example.com"));
        assertFalse(PackDownloader.sameOrigin(URI.create("https://api.example.com/x"), ""));
    }

    @Test
    void createsMissingParentDirectories() throws Exception {
        Path target = tempDir.resolve("a/b/c/pack.zip");
        PackDownloader.writeVerified(new ByteArrayInputStream(PAYLOAD), target,
                PAYLOAD_SHA, PAYLOAD.length, 0L, null);
        assertTrue(Files.isRegularFile(target));
    }

    @Test
    void overwritesLeftoverFileFromPreviousAttempt() throws Exception {
        Path target = tempDir.resolve("stale.zip");
        Files.writeString(target, "old junk");
        PackDownloader.writeVerified(new ByteArrayInputStream(PAYLOAD), target,
                PAYLOAD_SHA, PAYLOAD.length, 0L, null);
        assertEquals(PAYLOAD_SHA, HashUtil.sha256Hex(target));
    }

    private static void assertFailureReason(CompletableFuture<PackDownloader.DownloadResult> future,
                                            PackDownloader.FailureReason expected) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("期望失败但没有");
        } catch (ExecutionException e) {
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof PackDownloader.DownloadException,
                    "异常类型不对: " + e.getCause());
            assertEquals(expected, ((PackDownloader.DownloadException) e.getCause()).reason());
        }
    }
}
