package com.venus.vmfashion.net;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 安全解压：自动下发时装包 = 把远端 zip 解到本地磁盘，是本 mod 唯一的"远程数据落地"入口，
 * 必须假设每个 zip 都是恶意的。
 *
 * <p>防御清单（每条都有对应单测，见 {@code SafeZipTest}）：
 * <ul>
 *   <li><b>路径逃逸</b>：拒绝 {@code ..} 段、绝对路径、Windows 盘符（{@code C:}）、UNC、NTFS 数据流（{@code :}）；</li>
 *   <li><b>符号链接 / 硬链接</b>：解析 zip 中央目录的 external attributes，拒绝一切非"普通文件 / 目录"的条目
 *       （Unix 的 {@code S_IFLNK} 符号链接、设备、FIFO，以及 Windows 的 reparse point / junction）。
 *       ZIP 格式本身不表达硬链接，所以硬链接无法出现在合法包里；解压完再用 {@code NOFOLLOW} 复核一遍目标树；</li>
 *   <li><b>大小写冲突</b>：{@code A.png} 与 {@code a.png} 在 Windows / macOS 上会互相覆盖，直接拒绝；</li>
 *   <li><b>文件/目录冲突</b>：{@code a} 是文件、又出现 {@code a/b}，会让解压行为依赖平台，拒绝；</li>
 *   <li><b>zip bomb</b>：条目数、单文件大小、总解压大小、压缩比四道上限，且解压时按"实际写出的字节"再卡一次
 *       （中央目录里的 size 可以撒谎）；</li>
 *   <li><b>ZIP64 / 加密 / 未知压缩算法</b>：直接拒绝（时装包不需要这些特性，支持它们只是扩大攻击面）。</li>
 * </ul>
 *
 * <p>为什么自己解析中央目录：{@code java.util.zip.ZipEntry} 不暴露 external attributes，
 * 光用 {@code ZipInputStream} 无法发现"符号链接条目"（解压器不会自己创建符号链接，
 * 但一个指向外部的链接条目配合后续写入就能写出目标目录）。这里先读中央目录做全量预校验，
 * 再用 {@code ZipInputStream} 流式解压，并要求本地条目名与预校验结果一一对应。
 *
 * <p>失败时<b>不负责</b>清理 {@code targetDir}：调用方（{@code AssetSyncService}）把解压目标放在
 * {@code packs/server/.tmp-*} 下，失败直接整目录删掉即可。这样本类保持纯函数式，好测试。
 */
public final class SafeZip {

    /** 中央目录条目签名 {@code PK\x01\x02}。 */
    private static final long CENTRAL_HEADER_SIGNATURE = 0x02014b50L;
    /** 中央目录结尾签名 {@code PK\x05\x06}。 */
    private static final long END_OF_CENTRAL_DIR_SIGNATURE = 0x06054b50L;

    private static final int END_OF_CENTRAL_DIR_MIN_SIZE = 22;
    /** ZIP 注释最长 64KB。 */
    private static final int MAX_ZIP_COMMENT = 0xFFFF;
    private static final int CENTRAL_HEADER_MIN_SIZE = 46;

    /** 解压缓冲区：64KB，够大又不至于让 1.2GB 内存的机器吃紧。 */
    private static final int BUFFER_SIZE = 64 * 1024;

    /** 压缩方法：0 = stored，8 = deflate。其它（bzip2 / lzma / 加密）一律拒绝。 */
    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATE = 8;

    private SafeZip() {
    }

    // ==================== 公共类型 ====================

    /** 拒绝原因。用枚举而不是字符串，方便单测精确断言、也方便上层按原因决定是否重试。 */
    public enum Reason {
        /** 不是合法的 zip / 结构损坏。 */
        CORRUPT,
        /** 条目路径含 {@code ..}。 */
        PATH_TRAVERSAL,
        /** 绝对路径（{@code /etc/passwd}）。 */
        ABSOLUTE_PATH,
        /** Windows 盘符（{@code C:\}）或 NTFS 数据流（{@code a:b}）。 */
        WINDOWS_DRIVE,
        /** Windows 保留设备名（{@code CON}、{@code NUL}、{@code COM1}…）。 */
        WINDOWS_RESERVED_NAME,
        /** 符号链接 / 设备 / FIFO / reparse point 等非普通文件条目。 */
        UNSAFE_ENTRY_TYPE,
        /** 条目名非法（空、含 NUL、以点或空格结尾等）。 */
        BAD_ENTRY_NAME,
        /** 同名条目重复。 */
        DUPLICATE_ENTRY,
        /** 仅大小写不同的条目（Windows / macOS 会互相覆盖）。 */
        CASE_CONFLICT,
        /** 文件与目录同名冲突（{@code a} 与 {@code a/b}）。 */
        FILE_DIR_CONFLICT,
        /** 条目数超限。 */
        TOO_MANY_ENTRIES,
        /** 单文件解压后超限。 */
        ENTRY_TOO_LARGE,
        /** 总解压大小超限。 */
        TOTAL_TOO_LARGE,
        /** 压缩比异常（zip bomb）。 */
        RATIO_TOO_HIGH,
        /** 声明大小与实际写出的字节数不一致。 */
        SIZE_MISMATCH,
        /** 使用了 ZIP64 / 加密 / 未知压缩算法等本 mod 不支持的特性。 */
        UNSUPPORTED
    }

    /** 安全解压失败。带上 {@link Reason}，上层可据此决定"重试"还是"永久跳过"。 */
    public static final class SafeZipException extends IOException {

        private final Reason reason;

        public SafeZipException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }

        @Override
        public String toString() {
            return "SafeZipException[" + reason + ": " + getMessage() + "]";
        }
    }

    /**
     * 解压上限。
     *
     * @param maxEntries               条目数上限
     * @param maxEntryBytes            单文件解压后上限
     * @param maxTotalBytes            总解压大小上限
     * @param maxArchiveBytes          zip 本身的大小上限（解压前就能拒掉超大文件）
     * @param maxCompressionRatio      压缩比上限（解压后 / 压缩后）
     * @param maxCompressionRatioBytes 压缩比只在"解压后大于该值"时才检查，避免误伤小文件
     * @param maxNameLength            条目名长度上限（防止超长名字把文件系统撑爆）
     */
    public record Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes, long maxArchiveBytes,
                         double maxCompressionRatio, long maxCompressionRatioBytes, int maxNameLength) {

        public Limits {
            if (maxEntries < 1 || maxEntryBytes < 1 || maxTotalBytes < 1 || maxArchiveBytes < 1) {
                throw new IllegalArgumentException("解压上限必须是正数");
            }
            if (maxNameLength < 16) {
                throw new IllegalArgumentException("maxNameLength 太小");
            }
        }

        /**
         * 默认上限：4096 个条目、单文件 64MB、总量 512MB、zip 本身 128MB、
         * 压缩比 100:1 且仅在解压后 > 10MB 时判定。
         *
         * <p>数值依据：一件时装的资源是"模型 JSON + 若干 PNG"，正常包远小于这些上限；
         * 512MB 总量配合 {@code ModConfig#getMaxPackCacheMb()}（默认 512MB）也刚好对齐。
         */
        public static Limits defaults() {
            return new Limits(4096, 64L * 1024 * 1024, 512L * 1024 * 1024,
                    128L * 1024 * 1024, 100d, 10L * 1024 * 1024, 255);
        }
    }

    /**
     * 解压结果。
     *
     * @param fileCount  实际写出的文件数（不含目录）
     * @param totalBytes 实际写出的总字节数
     */
    public record ExtractionResult(int fileCount, long totalBytes) {
    }

    /** 中央目录里的一条记录（预校验后保留解压时需要的字段）。 */
    private record ValidatedEntry(String normalizedPath, boolean directory, long uncompressedSize) {
    }

    // ==================== 入口 ====================

    /** 用默认上限解压。 */
    public static ExtractionResult extract(Path zipFile, Path targetDir) throws IOException {
        return extract(zipFile, targetDir, Limits.defaults());
    }

    /**
     * 安全解压 {@code zipFile} 到 {@code targetDir}。
     *
     * <p>流程：先解析中央目录做<b>全量预校验</b>（任何一条不合规立即失败，不产生半成品），
     * 再用 {@code ZipInputStream} 流式解压，边写边按实际字节数二次卡上限。
     *
     * @param zipFile   待解压的 zip（必须是普通文件）
     * @param targetDir 目标目录，不存在则创建；必须为空目录（避免覆盖已有内容）
     * @param limits    上限
     * @throws SafeZipException 安全校验失败
     * @throws IOException       IO 失败（磁盘满、权限等）
     */
    public static ExtractionResult extract(Path zipFile, Path targetDir, Limits limits) throws IOException {
        if (zipFile == null || targetDir == null || limits == null) {
            throw new IllegalArgumentException("zipFile / targetDir / limits 都不能为 null");
        }
        if (!Files.isRegularFile(zipFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new SafeZipException(Reason.CORRUPT, "不是普通文件: " + zipFile.getFileName());
        }
        long archiveSize = Files.size(zipFile);
        if (archiveSize > limits.maxArchiveBytes()) {
            throw new SafeZipException(Reason.TOTAL_TOO_LARGE,
                    "zip 本身超过上限: " + archiveSize + " > " + limits.maxArchiveBytes());
        }

        Map<String, ValidatedEntry> validated = readAndValidateCentralDirectory(zipFile, archiveSize, limits);

        Files.createDirectories(targetDir);
        Path rootReal = targetDir.toRealPath();
        // 目标目录必须为空：否则一个包里没写全的文件可能"继承"上一轮的残留
        try (var children = Files.list(rootReal)) {
            if (children.findAny().isPresent()) {
                throw new SafeZipException(Reason.CORRUPT, "解压目标目录不是空目录: " + targetDir);
            }
        }

        int fileCount = 0;
        long totalWritten = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String normalizedPath = normalizeEntryName(entry.getName(), limits);
                ValidatedEntry expected = validated.get(normalizedPath);
                if (expected == null) {
                    // 本地文件头里的条目不在中央目录里：典型的"zip 结构欺骗"
                    throw new SafeZipException(Reason.CORRUPT, "条目不在中央目录中: " + entry.getName());
                }
                Path output = rootReal.resolve(normalizedPath).normalize();
                // 规范化后必须仍在目标根之内（逐条目校验，不依赖前面的 .. 检查）
                if (!output.startsWith(rootReal)) {
                    throw new SafeZipException(Reason.PATH_TRAVERSAL, "条目逃逸目标目录: " + entry.getName());
                }

                if (expected.directory()) {
                    Files.createDirectories(output);
                    zis.closeEntry();
                    continue;
                }

                Path parent = output.getParent();
                if (parent == null || !parent.normalize().startsWith(rootReal)) {
                    throw new SafeZipException(Reason.PATH_TRAVERSAL, "条目父目录非法: " + entry.getName());
                }
                Files.createDirectories(parent);

                long written = 0;
                // CREATE_NEW：绝不复用已有文件，也就不会顺着已存在的符号链接写出去
                try (OutputStream out = Files.newOutputStream(output,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = zis.read(buffer)) > 0) {
                        written += read;
                        // 中央目录可以撒谎，这里按"实际写出字节"再卡一次上限
                        if (written > expected.uncompressedSize()) {
                            throw new SafeZipException(Reason.SIZE_MISMATCH,
                                    "条目实际大小超过声明: " + entry.getName());
                        }
                        if (written > limits.maxEntryBytes()) {
                            throw new SafeZipException(Reason.ENTRY_TOO_LARGE,
                                    "条目解压后超过单文件上限: " + entry.getName());
                        }
                        totalWritten += read;
                        if (totalWritten > limits.maxTotalBytes()) {
                            throw new SafeZipException(Reason.TOTAL_TOO_LARGE, "总解压大小超过上限");
                        }
                        out.write(buffer, 0, read);
                    }
                }
                if (written != expected.uncompressedSize()) {
                    throw new SafeZipException(Reason.SIZE_MISMATCH,
                            "条目实际大小与声明不一致: " + entry.getName() + "（" + written
                                    + " != " + expected.uncompressedSize() + "）");
                }
                fileCount++;
                zis.closeEntry();
            }
        } catch (java.util.zip.ZipException e) {
            // 底层解压器自己发现的损坏（CRC 不符、stored 条目声明大小与实际不符等）：
            // 统一归一化成 SafeZipException，调用方只需处理一种异常
            throw new SafeZipException(Reason.CORRUPT, "zip 数据损坏: " + e.getMessage());
        }

        // 最后一道：解压出来的树里不允许出现符号链接（NOFOLLOW，不跟随）
        verifyNoSymbolicLinks(rootReal);
        return new ExtractionResult(fileCount, totalWritten);
    }

    // ==================== 中央目录解析 ====================

    private static Map<String, ValidatedEntry> readAndValidateCentralDirectory(Path zipFile, long fileSize,
                                                                              Limits limits) throws IOException {
        if (fileSize < END_OF_CENTRAL_DIR_MIN_SIZE) {
            throw new SafeZipException(Reason.CORRUPT, "文件太小，不是合法 zip");
        }
        int tailLength = (int) Math.min(fileSize, END_OF_CENTRAL_DIR_MIN_SIZE + MAX_ZIP_COMMENT + 4L);
        byte[] tail = readRange(zipFile, fileSize - tailLength, tailLength);
        int eocd = findEndOfCentralDirectory(tail);
        if (eocd < 0) {
            throw new SafeZipException(Reason.CORRUPT, "找不到 zip 中央目录结尾（EOCD）");
        }

        int entryCount = u16(tail, eocd + 10);
        long centralDirSize = u32(tail, eocd + 12);
        long centralDirOffset = u32(tail, eocd + 16);
        if (entryCount == 0xFFFF || centralDirSize == 0xFFFFFFFFL || centralDirOffset == 0xFFFFFFFFL) {
            throw new SafeZipException(Reason.UNSUPPORTED, "不支持 ZIP64 格式");
        }
        if (entryCount > limits.maxEntries()) {
            throw new SafeZipException(Reason.TOO_MANY_ENTRIES,
                    "条目数超限: " + entryCount + " > " + limits.maxEntries());
        }
        if (centralDirOffset < 0 || centralDirSize < 0
                || centralDirOffset + centralDirSize > fileSize
                || centralDirSize > Integer.MAX_VALUE) {
            throw new SafeZipException(Reason.CORRUPT, "中央目录位置非法");
        }
        byte[] centralDir = readRange(zipFile, centralDirOffset, (int) centralDirSize);

        Map<String, ValidatedEntry> validated = new LinkedHashMap<>(Math.max(16, entryCount));
        // 小写路径 → 原始大小写：用来区分"完全重复"（同名）与"仅大小写不同"（Windows / macOS 上会互相覆盖）
        Map<String, String> entryOrigins = new HashMap<>();
        // 隐式父目录同样参与大小写检查：Cosmetics/hat/x 与 cosmetics/hat/y 在 Windows 上会落进同一个目录
        Map<String, String> ancestorOrigins = new HashMap<>();
        // 所有显式条目的小写路径（有序，用于"文件被当成目录"的反向检测）
        TreeSet<String> lowercasePaths = new TreeSet<>();
        TreeSet<String> lowercaseFiles = new TreeSet<>();
        long totalUncompressed = 0;
        int position = 0;

        for (int index = 0; index < entryCount; index++) {
            if (position + CENTRAL_HEADER_MIN_SIZE > centralDir.length) {
                throw new SafeZipException(Reason.CORRUPT, "中央目录被截断");
            }
            if (u32(centralDir, position) != CENTRAL_HEADER_SIGNATURE) {
                throw new SafeZipException(Reason.CORRUPT, "中央目录条目签名不正确");
            }
            int versionMadeBy = u16(centralDir, position + 4);
            int flags = u16(centralDir, position + 8);
            int method = u16(centralDir, position + 10);
            long compressedSize = u32(centralDir, position + 20);
            long uncompressedSize = u32(centralDir, position + 24);
            int nameLength = u16(centralDir, position + 28);
            int extraLength = u16(centralDir, position + 30);
            int commentLength = u16(centralDir, position + 32);
            long externalAttributes = u32(centralDir, position + 38);

            int next = position + CENTRAL_HEADER_MIN_SIZE + nameLength + extraLength + commentLength;
            if (next > centralDir.length) {
                throw new SafeZipException(Reason.CORRUPT, "中央目录条目越界");
            }
            String rawName = new String(centralDir,
                    position + CENTRAL_HEADER_MIN_SIZE, nameLength, StandardCharsets.UTF_8);
            position = next;

            if ((flags & 0x0001) != 0) {
                throw new SafeZipException(Reason.UNSUPPORTED, "不支持加密 zip: " + rawName);
            }
            if (method != METHOD_STORED && method != METHOD_DEFLATE) {
                throw new SafeZipException(Reason.UNSUPPORTED, "不支持的压缩算法 " + method + ": " + rawName);
            }

            boolean directory = rawName.endsWith("/");
            int hostOs = (versionMadeBy >>> 8) & 0xFF;
            if (hostOs == 3 || hostOs == 19) { // 3 = Unix，19 = macOS
                int unixMode = (int) ((externalAttributes >>> 16) & 0xFFFF);
                int type = unixMode & 0xF000;
                if (type == 0xA000) {
                    throw new SafeZipException(Reason.UNSAFE_ENTRY_TYPE, "条目是符号链接: " + rawName);
                }
                if (type != 0 && type != 0x8000 && type != 0x4000) {
                    throw new SafeZipException(Reason.UNSAFE_ENTRY_TYPE,
                            "条目不是普通文件/目录（设备/FIFO/socket）: " + rawName);
                }
                if (type == 0x4000) {
                    directory = true;
                }
            }
            if ((externalAttributes & 0x400L) != 0) {
                // Windows FILE_ATTRIBUTE_REPARSE_POINT：junction / 符号链接
                throw new SafeZipException(Reason.UNSAFE_ENTRY_TYPE, "条目是 Windows 重解析点: " + rawName);
            }
            if (!directory && (externalAttributes & 0x10L) != 0 && hostOs == 0) {
                // FAT 宿主把目录标志放在低字节
                directory = true;
            }

            String normalizedPath = normalizeEntryName(rawName, limits);
            String lowercase = normalizedPath.toLowerCase(Locale.ROOT);

            // ① 完全重复 / 仅大小写不同
            String existingEntry = entryOrigins.get(lowercase);
            if (existingEntry != null) {
                if (existingEntry.equals(normalizedPath)) {
                    throw new SafeZipException(Reason.DUPLICATE_ENTRY, "重复条目: " + rawName);
                }
                throw new SafeZipException(Reason.CASE_CONFLICT,
                        "仅大小写不同的条目: " + rawName + " 与 " + existingEntry);
            }
            // ② 隐式父目录的大小写必须一致（否则不同平台会解到不同目录，甚至合并覆盖）
            String[] segments = normalizedPath.split("/");
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < segments.length - 1; i++) {
                if (i > 0) {
                    prefix.append('/');
                }
                prefix.append(segments[i]);
                String originalPrefix = prefix.toString();
                String lowercasePrefix = originalPrefix.toLowerCase(Locale.ROOT);
                String known = entryOrigins.get(lowercasePrefix);
                if (known == null) {
                    known = ancestorOrigins.get(lowercasePrefix);
                }
                if (known == null) {
                    ancestorOrigins.put(lowercasePrefix, originalPrefix);
                } else if (!known.equals(originalPrefix)) {
                    throw new SafeZipException(Reason.CASE_CONFLICT,
                            "父目录大小写不一致: " + originalPrefix + " 与 " + known);
                }
            }
            entryOrigins.put(lowercase, normalizedPath);
            lowercasePaths.add(lowercase);

            if (directory) {
                // 目录不能和已有文件同名（a 是文件、又出现 a/b 的父目录 a）
                if (lowercaseFiles.contains(lowercase)) {
                    throw new SafeZipException(Reason.FILE_DIR_CONFLICT, "文件与目录同名: " + rawName);
                }
            } else {
                if (uncompressedSize < 0) {
                    throw new SafeZipException(Reason.CORRUPT, "条目大小非法: " + rawName);
                }
                if (uncompressedSize > limits.maxEntryBytes()) {
                    throw new SafeZipException(Reason.ENTRY_TOO_LARGE,
                            "单文件解压后超限: " + rawName + "（" + uncompressedSize + "）");
                }
                totalUncompressed += uncompressedSize;
                if (totalUncompressed > limits.maxTotalBytes()) {
                    throw new SafeZipException(Reason.TOTAL_TOO_LARGE, "总解压大小超限");
                }
                if (compressedSize <= 0 && uncompressedSize > 0) {
                    throw new SafeZipException(Reason.CORRUPT, "压缩后大小非法: " + rawName);
                }
                if (uncompressedSize > limits.maxCompressionRatioBytes() && compressedSize > 0
                        && (double) uncompressedSize / (double) compressedSize > limits.maxCompressionRatio()) {
                    throw new SafeZipException(Reason.RATIO_TOO_HIGH,
                            "压缩比异常（疑似 zip bomb）: " + rawName + "（" + uncompressedSize
                                    + " / " + compressedSize + "）");
                }
                // 祖先路径里不能已经出现过同名文件
                String ancestor = lowercase;
                int slash;
                while ((slash = ancestor.lastIndexOf('/')) > 0) {
                    ancestor = ancestor.substring(0, slash);
                    if (lowercaseFiles.contains(ancestor)) {
                        throw new SafeZipException(Reason.FILE_DIR_CONFLICT,
                                "文件与目录同名: " + rawName + " 与 " + ancestor);
                    }
                }
                lowercaseFiles.add(lowercase);
            }

            ValidatedEntry previous = validated.put(normalizedPath,
                    new ValidatedEntry(normalizedPath, directory, uncompressedSize));
            if (previous != null) {
                throw new SafeZipException(Reason.DUPLICATE_ENTRY, "重复条目: " + rawName);
            }
        }

        // 反向冲突：先出现文件 a，后出现 a/b（此时 a 已在 lowercaseFiles 里，上面的祖先检查覆盖）；
        // 先出现 a/b，后出现文件 a（需要检查是否存在以 "a/" 开头的条目）
        for (String file : lowercaseFiles) {
            String descendantPrefix = file + "/";
            String ceiling = lowercasePaths.ceiling(descendantPrefix);
            if (ceiling != null && ceiling.startsWith(descendantPrefix)) {
                throw new SafeZipException(Reason.FILE_DIR_CONFLICT,
                        "文件与目录同名: " + file + " 与 " + ceiling);
            }
        }
        return validated;
    }

    /**
     * 条目名规范化 + 安全校验。
     *
     * <p>规则（顺序即优先级）：
     * <ol>
     *   <li>反斜杠统一成 {@code /}（Windows 打包工具会产生 {@code a\b}）；</li>
     *   <li>拒绝空名、含 NUL、超长；</li>
     *   <li>拒绝绝对路径、UNC（{@code //}）、Windows 盘符（{@code C:}）；</li>
     *   <li>逐段：丢弃 {@code .} 与空段；拒绝 {@code ..}；拒绝含 {@code :}（NTFS 数据流）；
     *       拒绝 Windows 保留设备名；拒绝以点/空格结尾（Windows 会静默裁剪，制造同名冲突）；</li>
     *   <li>全部段被丢弃（例如 {@code ./}）视为非法。</li>
     * </ol>
     *
     * @return 规范化后的相对路径（用 {@code /} 分隔，无首尾斜杠）
     */
    static String normalizeEntryName(String rawName, Limits limits) throws SafeZipException {
        if (rawName == null || rawName.isEmpty()) {
            throw new SafeZipException(Reason.BAD_ENTRY_NAME, "条目名为空");
        }
        if (rawName.length() > limits.maxNameLength()) {
            throw new SafeZipException(Reason.BAD_ENTRY_NAME, "条目名过长: " + rawName.length());
        }
        if (rawName.indexOf('\0') >= 0) {
            throw new SafeZipException(Reason.BAD_ENTRY_NAME, "条目名含 NUL 字符");
        }
        String name = rawName.replace('\\', '/');
        if (name.startsWith("/")) {
            throw new SafeZipException(Reason.ABSOLUTE_PATH, "绝对路径条目: " + rawName);
        }
        if (name.length() >= 2 && name.charAt(1) == ':' && Character.isLetter(name.charAt(0))) {
            throw new SafeZipException(Reason.WINDOWS_DRIVE, "Windows 盘符条目: " + rawName);
        }
        List<String> segments = new ArrayList<>(4);
        for (String part : name.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new SafeZipException(Reason.PATH_TRAVERSAL, "条目路径含 ..: " + rawName);
            }
            if (part.indexOf(':') >= 0) {
                throw new SafeZipException(Reason.WINDOWS_DRIVE, "条目名含 NTFS 数据流分隔符: " + rawName);
            }
            if (isWindowsReservedName(part)) {
                throw new SafeZipException(Reason.WINDOWS_RESERVED_NAME, "Windows 保留设备名: " + rawName);
            }
            if (part.endsWith(".") || part.endsWith(" ")) {
                throw new SafeZipException(Reason.BAD_ENTRY_NAME,
                        "条目名以点或空格结尾（Windows 会裁剪，可能造成同名冲突）: " + rawName);
            }
            segments.add(part);
        }
        if (segments.isEmpty()) {
            throw new SafeZipException(Reason.BAD_ENTRY_NAME, "条目名规范化后为空: " + rawName);
        }
        return String.join("/", segments);
    }

    /** Windows 保留设备名（不带扩展名也算，例如 {@code CON.txt} 依然非法）。 */
    private static boolean isWindowsReservedName(String segment) {
        String base = segment;
        int dot = base.indexOf('.');
        if (dot >= 0) {
            base = base.substring(0, dot);
        }
        String upper = base.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "CON", "PRN", "AUX", "NUL", "CLOCK$" -> true;
            default -> {
                if (upper.length() == 4 && (upper.startsWith("COM") || upper.startsWith("LPT"))) {
                    char digit = upper.charAt(3);
                    yield digit >= '1' && digit <= '9';
                }
                yield false;
            }
        };
    }

    /** 解压完成后复核目标树：不允许任何符号链接（防止预置链接 + 后续写入的组合攻击）。 */
    private static void verifyNoSymbolicLinks(Path root) throws IOException {
        Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) {
                    throw new SafeZipException(Reason.UNSAFE_ENTRY_TYPE, "解压结果里出现符号链接: " + file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ==================== 字节读取工具 ====================

    /** 从文件的指定偏移读取 {@code length} 字节。 */
    private static byte[] readRange(Path file, long offset, int length) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            if (channel.size() < offset + length) {
                throw new SafeZipException(Reason.CORRUPT, "zip 文件被截断");
            }
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
            byte[] result = new byte[buffer.position()];
            buffer.flip();
            buffer.get(result);
            if (result.length != length) {
                throw new SafeZipException(Reason.CORRUPT, "zip 文件读取不完整");
            }
            return result;
        }
    }

    /** 从尾部向前找 EOCD（ZIP 注释可能跟在后面）。 */
    private static int findEndOfCentralDirectory(byte[] tail) {
        for (int i = tail.length - END_OF_CENTRAL_DIR_MIN_SIZE; i >= 0; i--) {
            if (u32(tail, i) != END_OF_CENTRAL_DIR_SIGNATURE) {
                continue;
            }
            int commentLength = u16(tail, i + 20);
            if (i + END_OF_CENTRAL_DIR_MIN_SIZE + commentLength == tail.length) {
                return i;
            }
        }
        return -1;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }
}
