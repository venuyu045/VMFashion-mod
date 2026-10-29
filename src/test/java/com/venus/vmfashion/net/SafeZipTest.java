package com.venus.vmfashion.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SafeZip} 的安全单测。
 *
 * <p>自动下发会把远端 zip 解到本地磁盘，所以每一种攻击都必须有独立断言：
 * 路径逃逸、绝对路径、Windows 盘符 / 保留名、符号链接 / reparse point、大小写冲突、
 * 重复条目、文件与目录冲突、条目数 / 单文件 / 总量 / 压缩比上限、结构损坏、加密与未知算法。
 */
class SafeZipTest {

    private static final String PACK_JSON =
            "{\"id\":\"demo\",\"name\":\"Demo Pack\",\"version\":\"1.0.0\","
                    + "\"cosmetics\":[{\"id\":\"hat\",\"category\":\"hat\"}]}";

    @TempDir
    Path tempDir;

    private int zipCounter;

    // ==================== 正常路径 ====================

    @Test
    void extractsNormalPackAndKeepsContent() throws Exception {
        Path zip = normalZip("normal");
        Path target = tempDir.resolve("out-normal");

        SafeZip.ExtractionResult result = SafeZip.extract(zip, target);

        // 目录条目不计入文件数：pack.json + model.geo.json + texture.png
        assertEquals(3, result.fileCount());
        assertTrue(Files.isRegularFile(target.resolve("pack.json")));
        assertEquals(PACK_JSON, Files.readString(target.resolve("pack.json"), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(target.resolve("cosmetics/hat/model.geo.json")));
        assertTrue(Files.isRegularFile(target.resolve("cosmetics/hat/texture.png")));
        assertTrue(result.totalBytes() > 0);
    }

    @Test
    void directoryEntriesAreCreated() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("dirs.zip"), List.of(
                ZipTestSupport.dir("cosmetics"),
                ZipTestSupport.dir("cosmetics/hat"),
                ZipTestSupport.entry("cosmetics/hat/meta.json", "{}")));
        Path target = tempDir.resolve("out-dirs");

        SafeZip.extract(zip, target);

        assertTrue(Files.isDirectory(target.resolve("cosmetics/hat")));
        assertTrue(Files.isRegularFile(target.resolve("cosmetics/hat/meta.json")));
    }

    @Test
    void normalizesDotAndDuplicateSlashes() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("dots.zip"), List.of(
                ZipTestSupport.entry("./pack.json", "{}"),
                ZipTestSupport.entry("cosmetics//hat//model.geo.json", "{}")));
        Path target = tempDir.resolve("out-dots");

        SafeZip.extract(zip, target);

        assertTrue(Files.isRegularFile(target.resolve("pack.json")));
        assertTrue(Files.isRegularFile(target.resolve("cosmetics/hat/model.geo.json")));
    }

    // ==================== 路径逃逸 ====================

    @Test
    void rejectsParentTraversal() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("traversal.zip"), List.of(
                ZipTestSupport.entry("../evil.txt", "pwned")));
        Path target = tempDir.resolve("out-traversal");

        assertReason(SafeZip.Reason.PATH_TRAVERSAL, zip, target);
        // 预校验发生在解压之前：目标目录里不能出现任何文件
        assertDirectoryEmpty(target);
    }

    @Test
    void rejectsNestedParentTraversal() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("traversal2.zip"), List.of(
                ZipTestSupport.entry("cosmetics/../../evil.txt", "pwned")));
        assertReason(SafeZip.Reason.PATH_TRAVERSAL, zip, tempDir.resolve("out-traversal2"));
    }

    @Test
    void rejectsBackslashTraversal() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("traversal3.zip"), List.of(
                ZipTestSupport.entry("..\\..\\evil.txt", "pwned")));
        assertReason(SafeZip.Reason.PATH_TRAVERSAL, zip, tempDir.resolve("out-traversal3"));
    }

    @Test
    void rejectsAbsolutePath() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("abs.zip"), List.of(
                ZipTestSupport.entry("/etc/passwd", "root:x:0:0")));
        assertReason(SafeZip.Reason.ABSOLUTE_PATH, zip, tempDir.resolve("out-abs"));
    }

    @Test
    void rejectsWindowsDriveLetter() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("drive.zip"), List.of(
                ZipTestSupport.entry("C:/Windows/evil.dll", "pwned")));
        assertReason(SafeZip.Reason.WINDOWS_DRIVE, zip, tempDir.resolve("out-drive"));
    }

    @Test
    void rejectsNtfsAlternateDataStream() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("ads.zip"), List.of(
                ZipTestSupport.entry("texture.png:hidden", "pwned")));
        assertReason(SafeZip.Reason.WINDOWS_DRIVE, zip, tempDir.resolve("out-ads"));
    }

    @Test
    void rejectsWindowsReservedDeviceNames() throws Exception {
        for (String name : List.of("CON", "con.txt", "NUL", "COM1.log", "lpt9")) {
            Path zip = ZipTestSupport.writeZip(tempDir.resolve("reserved-" + name.replace('.', '_') + ".zip"),
                    List.of(ZipTestSupport.entry(name, "pwned")));
            assertReason(SafeZip.Reason.WINDOWS_RESERVED_NAME, zip,
                    tempDir.resolve("out-reserved-" + name.replace('.', '_')));
        }
    }

    @Test
    void rejectsTrailingDotOrSpace() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("trailing.zip"), List.of(
                ZipTestSupport.entry("pack.json.", "{}")));
        assertReason(SafeZip.Reason.BAD_ENTRY_NAME, zip, tempDir.resolve("out-trailing"));

        Path zip2 = ZipTestSupport.writeZip(tempDir.resolve("trailing2.zip"), List.of(
                ZipTestSupport.entry("pack.json ", "{}")));
        assertReason(SafeZip.Reason.BAD_ENTRY_NAME, zip2, tempDir.resolve("out-trailing2"));
    }

    @Test
    void rejectsNulInName() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("nul.zip"), List.of(
                ZipTestSupport.entry("pack.json\0.png", "{}")));
        assertReason(SafeZip.Reason.BAD_ENTRY_NAME, zip, tempDir.resolve("out-nul"));
    }

    // ==================== 链接类条目 ====================

    @Test
    void rejectsUnixSymlinkEntry() throws Exception {
        Path zip = rawZip("symlink.zip", List.of(
                ZipTestSupport.RawEntry.symlink("link", "/etc/passwd")));
        assertReason(SafeZip.Reason.UNSAFE_ENTRY_TYPE, zip, tempDir.resolve("out-symlink"));
    }

    @Test
    void rejectsWindowsReparsePoint() throws Exception {
        Path zip = rawZip("reparse.zip", List.of(
                ZipTestSupport.RawEntry.reparsePoint("junction")));
        assertReason(SafeZip.Reason.UNSAFE_ENTRY_TYPE, zip, tempDir.resolve("out-reparse"));
    }

    // ==================== 重名 / 冲突 ====================

    @Test
    void rejectsDuplicateEntries() throws Exception {
        Path zip = rawZip("duplicate.zip", List.of(
                ZipTestSupport.RawEntry.file("pack.json", "{}"),
                ZipTestSupport.RawEntry.file("pack.json", "{}")));
        assertReason(SafeZip.Reason.DUPLICATE_ENTRY, zip, tempDir.resolve("out-duplicate"));
    }

    @Test
    void rejectsCaseConflict() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("case.zip"), List.of(
                ZipTestSupport.entry("pack.json", "{}"),
                ZipTestSupport.entry("Cosmetics/hat/meta.json", "{}"),
                ZipTestSupport.entry("cosmetics/hat/meta.json", "{}")));
        // Windows / macOS 上这两个路径会互相覆盖，必须直接拒绝
        assertReason(SafeZip.Reason.CASE_CONFLICT, zip, tempDir.resolve("out-case"));
    }

    @Test
    void rejectsFileUsedAsDirectory() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("filedir.zip"), List.of(
                ZipTestSupport.entry("a", "file"),
                ZipTestSupport.entry("a/b", "under file")));
        assertReason(SafeZip.Reason.FILE_DIR_CONFLICT, zip, tempDir.resolve("out-filedir"));
    }

    @Test
    void rejectsDirectoryUsedAsFile() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("dirfile.zip"), List.of(
                ZipTestSupport.entry("a/b", "under dir"),
                ZipTestSupport.entry("a", "file")));
        assertReason(SafeZip.Reason.FILE_DIR_CONFLICT, zip, tempDir.resolve("out-dirfile"));
    }

    // ==================== 上限 ====================

    @Test
    void rejectsTooManyEntries() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("many.zip"), List.of(
                ZipTestSupport.entry("a.txt", "a"),
                ZipTestSupport.entry("b.txt", "b"),
                ZipTestSupport.entry("c.txt", "c"),
                ZipTestSupport.entry("d.txt", "d"),
                ZipTestSupport.entry("e.txt", "e")));
        SafeZip.Limits limits = limits(3, 1_000L, 10_000L, 1_000_000L);
        assertReasonWithLimits(SafeZip.Reason.TOO_MANY_ENTRIES, zip, tempDir.resolve("out-many"), limits);
    }

    @Test
    void rejectsOversizedSingleEntry() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("big.zip"), List.of(
                ZipTestSupport.entry("big.bin", "x".repeat(500))));
        SafeZip.Limits limits = limits(100, 100L, 10_000L, 1_000_000L);
        assertReasonWithLimits(SafeZip.Reason.ENTRY_TOO_LARGE, zip, tempDir.resolve("out-big"), limits);
    }

    @Test
    void rejectsOversizedTotal() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("total.zip"), List.of(
                ZipTestSupport.entry("a.bin", "x".repeat(600)),
                ZipTestSupport.entry("b.bin", "y".repeat(600))));
        SafeZip.Limits limits = limits(100, 1_000L, 1_000L, 1_000_000L);
        assertReasonWithLimits(SafeZip.Reason.TOTAL_TOO_LARGE, zip, tempDir.resolve("out-total"), limits);
    }

    @Test
    void rejectsCompressionRatioBomb() throws Exception {
        // 声明"压缩后 10 字节、解压后 50MB"：预校验阶段就该拦下，不需要真的解压
        Path zip = rawZip("bomb.zip", List.of(
                ZipTestSupport.RawEntry.ratioBomb("bomb.bin", 50L * 1024 * 1024)));
        assertReason(SafeZip.Reason.RATIO_TOO_HIGH, zip, tempDir.resolve("out-bomb"));
    }

    @Test
    void rejectsOversizedArchive() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("archive.zip"), List.of(
                ZipTestSupport.entry("pack.json", "{}")));
        SafeZip.Limits limits = new SafeZip.Limits(100, 1_000L, 10_000L, 50L, 100d, 10_000L, 255);
        assertReasonWithLimits(SafeZip.Reason.TOTAL_TOO_LARGE, zip, tempDir.resolve("out-archive"), limits);
    }

    // ==================== 结构 / 算法 ====================

    @Test
    void rejectsEncryptedEntries() throws Exception {
        Path zip = rawZip("encrypted.zip", List.of(
                ZipTestSupport.RawEntry.encrypted("pack.json", "{}")));
        assertReason(SafeZip.Reason.UNSUPPORTED, zip, tempDir.resolve("out-encrypted"));
    }

    @Test
    void rejectsUnsupportedCompressionMethod() throws Exception {
        Path zip = rawZip("method.zip", List.of(
                ZipTestSupport.RawEntry.unsupportedMethod("pack.json", "{}")));
        assertReason(SafeZip.Reason.UNSUPPORTED, zip, tempDir.resolve("out-method"));
    }

    @Test
    void rejectsZip64() throws Exception {
        byte[] archive = ZipTestSupport.rawZip(List.of(
                ZipTestSupport.RawEntry.file("pack.json", "{}")));
        // 把 EOCD 里的"总条目数"改成 0xFFFF，模拟 ZIP64 标记
        int eocd = archive.length - 22;
        archive[eocd + 10] = (byte) 0xFF;
        archive[eocd + 11] = (byte) 0xFF;
        Path zip = writeRaw("zip64.zip", archive);
        assertReason(SafeZip.Reason.UNSUPPORTED, zip, tempDir.resolve("out-zip64"));
    }

    @Test
    void rejectsLyingCentralDirectorySize() throws Exception {
        // 中央目录说 5 字节、实际解压出 100 字节：解压时按"实际写出字节"二次卡住。
        // 用 deflate 条目：stored 条目会被 ZipInputStream 自己先拦掉，测不到我们的大小校验。
        byte[] data = "x".repeat(100).getBytes(StandardCharsets.UTF_8);
        Path zip = rawZip("lying.zip", List.of(
                ZipTestSupport.RawEntry.deflatedLying("pack.json", data, 5)));
        assertReason(SafeZip.Reason.SIZE_MISMATCH, zip, tempDir.resolve("out-lying"));
    }

    @Test
    void rejectsCorruptedPayloadAsSafeZipException() throws Exception {
        // 正常 zip 里把数据段改坏：底层解压器抛 ZipException，必须归一化成 SafeZipException
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("corrupt-payload.zip"), List.of(
                ZipTestSupport.entry("pack.json", "{\"id\":\"demo\"}".repeat(50))));
        byte[] bytes = Files.readAllBytes(zip);
        // 改坏最后一个字节（属于压缩数据），保持 EOCD 完好
        bytes[bytes.length - 23] ^= 0xFF;
        Path broken = tempDir.resolve("corrupt-payload2.zip");
        Files.write(broken, bytes);

        assertReason(SafeZip.Reason.CORRUPT, broken, tempDir.resolve("out-corrupt-payload"));
    }

    @Test
    void rejectsCorruptFile() throws Exception {
        Path zip = tempDir.resolve("garbage.zip");
        Files.write(zip, "this is definitely not a zip file".getBytes(StandardCharsets.UTF_8));
        assertReason(SafeZip.Reason.CORRUPT, zip, tempDir.resolve("out-garbage"));
    }

    @Test
    void rejectsMissingFile() {
        assertReason(SafeZip.Reason.CORRUPT, tempDir.resolve("nope.zip"), tempDir.resolve("out-nope"));
    }

    @Test
    void rejectsNonEmptyTargetDirectory() throws Exception {
        Path zip = normalZip("nonempty");
        Path target = tempDir.resolve("out-nonempty");
        Files.createDirectories(target);
        Files.writeString(target.resolve("stale.txt"), "left over");

        assertReason(SafeZip.Reason.CORRUPT, zip, target);
    }

    @Test
    void rejectsEmptyEntryName() throws Exception {
        Path zip = ZipTestSupport.writeZip(tempDir.resolve("empty.zip"), List.of(
                ZipTestSupport.entry("./", "")));
        assertReason(SafeZip.Reason.BAD_ENTRY_NAME, zip, tempDir.resolve("out-empty"));
    }

    // ==================== 辅助 ====================

    private Path normalZip(String name) throws IOException {
        return ZipTestSupport.writeZip(tempDir.resolve(name + ".zip"), List.of(
                ZipTestSupport.entry("pack.json", PACK_JSON),
                ZipTestSupport.dir("cosmetics"),
                ZipTestSupport.dir("cosmetics/hat"),
                ZipTestSupport.entry("cosmetics/hat/model.geo.json", "{\"format_version\":\"1.12.0\"}"),
                ZipTestSupport.entry("cosmetics/hat/texture.png", "PNG")));
    }

    private Path rawZip(String name, List<ZipTestSupport.RawEntry> entries) throws IOException {
        return writeRaw(name, ZipTestSupport.rawZip(entries));
    }

    private Path writeRaw(String name, byte[] archive) throws IOException {
        Path zip = tempDir.resolve(name);
        Files.write(zip, archive);
        return zip;
    }

    private static SafeZip.Limits limits(int maxEntries, long maxEntryBytes, long maxTotalBytes,
                                         long maxArchiveBytes) {
        return new SafeZip.Limits(maxEntries, maxEntryBytes, maxTotalBytes, maxArchiveBytes,
                100d, 10L * 1024 * 1024, 255);
    }

    private static void assertReason(SafeZip.Reason expected, Path zip, Path target) {
        assertReasonWithLimits(expected, zip, target, SafeZip.Limits.defaults());
    }

    private static void assertReasonWithLimits(SafeZip.Reason expected, Path zip, Path target,
                                               SafeZip.Limits limits) {
        SafeZip.SafeZipException exception = assertThrows(SafeZip.SafeZipException.class,
                () -> SafeZip.extract(zip, target, limits));
        assertEquals(expected, exception.reason(), "拒绝原因不符: " + exception);
    }

    private static void assertDirectoryEmpty(Path target) throws IOException {
        if (!Files.isDirectory(target)) {
            return;
        }
        try (Stream<Path> children = Files.list(target)) {
            assertFalse(children.findAny().isPresent(), "校验失败时目标目录必须为空");
        }
    }
}
