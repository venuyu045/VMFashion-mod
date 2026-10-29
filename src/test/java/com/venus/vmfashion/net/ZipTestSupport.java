package com.venus.vmfashion.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 测试用的 zip 样本构造工具。
 *
 * <p>为什么不用 {@code ZipOutputStream} 一把梭：它<b>不允许</b>重复条目名，也没法设置
 * external attributes（符号链接 / reparse point）、伪造大小、写入加密标志或未知压缩算法。
 * 所以这里有两个构造器：
 * <ul>
 *   <li>{@link #writeZip} —— 正常包，用 {@code ZipOutputStream}（真实 deflate/stored）；</li>
 *   <li>{@link #rawZip} —— 手工拼本地文件头 + 中央目录 + EOCD，能精确控制每一个字段。</li>
 * </ul>
 */
final class ZipTestSupport {

    private ZipTestSupport() {
    }

    /** 用 {@code ZipOutputStream} 写一个正常 zip。{@code entries} 为"条目名 → 内容"。 */
    static Path writeZip(Path file, List<Entry> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Entry entry : entries) {
                ZipEntry zipEntry = new ZipEntry(entry.name());
                if (entry.name().endsWith("/")) {
                    zipEntry.setMethod(ZipEntry.STORED);
                    zipEntry.setSize(0);
                    zipEntry.setCompressedSize(0);
                    zipEntry.setCrc(0);
                }
                zos.putNextEntry(zipEntry);
                if (entry.data().length > 0) {
                    zos.write(entry.data());
                }
                zos.closeEntry();
            }
        }
        return file;
    }

    static Entry entry(String name, String content) {
        return new Entry(name, content.getBytes(StandardCharsets.UTF_8));
    }

    static Entry dir(String name) {
        return new Entry(name.endsWith("/") ? name : name + "/", new byte[0]);
    }

    /** 正常包的条目。 */
    record Entry(String name, byte[] data) {
    }

    /**
     * 手工拼一个 zip，每个条目可以控制 external attributes / 压缩方法 / 标志位 / 声明大小。
     *
     * <p>所有条目都按 STORED（方法 0）写入，除非显式指定 {@code method}；
     * 声明大小可以撒谎（{@code declaredUncompressed} / {@code declaredCompressed}），
     * 用来测"中央目录说谎"的攻击。
     */
    static byte[] rawZip(List<RawEntry> entries) {
        ByteArrayOutputStream local = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        int count = entries.size();
        for (RawEntry entry : entries) {
            byte[] nameBytes = entry.name().getBytes(StandardCharsets.UTF_8);
            byte[] payload = entry.payload() == null ? entry.data() : entry.payload();
            CRC32 crc = new CRC32();
            crc.update(entry.data());
            long crcValue = crc.getValue();
            long uncompressed = entry.declaredUncompressed() >= 0
                    ? entry.declaredUncompressed() : entry.data().length;
            long compressed = entry.declaredCompressed() >= 0
                    ? entry.declaredCompressed() : payload.length;
            int localOffset = local.size();

            writeU32(local, 0x04034b50L);            // 本地文件头签名
            writeU16(local, 20);                      // 解压所需版本
            writeU16(local, entry.flags());            // 通用标志
            writeU16(local, entry.method());           // 压缩方法
            writeU16(local, 0);                        // 修改时间
            writeU16(local, 0);                        // 修改日期
            writeU32(local, crcValue);                 // CRC32
            writeU32(local, compressed);               // 压缩后大小
            writeU32(local, uncompressed);             // 解压后大小
            writeU16(local, nameBytes.length);         // 名字长度
            writeU16(local, 0);                        // extra 长度
            local.writeBytes(nameBytes);
            local.writeBytes(payload);

            writeU32(central, 0x02014b50L);            // 中央目录签名
            writeU16(central, entry.versionMadeBy());   // 制作版本（高字节 = 宿主 OS）
            writeU16(central, 20);                      // 解压所需版本
            writeU16(central, entry.flags());
            writeU16(central, entry.method());
            writeU16(central, 0);
            writeU16(central, 0);
            writeU32(central, crcValue);
            writeU32(central, compressed);
            writeU32(central, uncompressed);
            writeU16(central, nameBytes.length);
            writeU16(central, 0);                       // extra
            writeU16(central, 0);                       // comment
            writeU16(central, 0);                       // 起始磁盘
            writeU16(central, 0);                       // 内部属性
            writeU32(central, entry.externalAttributes()); // 外部属性（符号链接在这里）
            writeU32(central, localOffset);             // 本地头偏移
            central.writeBytes(nameBytes);
        }

        byte[] localBytes = local.toByteArray();
        byte[] centralBytes = central.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(localBytes);
        out.writeBytes(centralBytes);
        writeU32(out, 0x06054b50L);            // EOCD
        writeU16(out, 0);                       // 磁盘号
        writeU16(out, 0);                       // 中央目录起始磁盘
        writeU16(out, count);                   // 本磁盘条目数
        writeU16(out, count);                   // 总条目数
        writeU32(out, centralBytes.length);     // 中央目录大小
        writeU32(out, localBytes.length);       // 中央目录偏移
        writeU16(out, 0);                       // 注释长度
        return out.toByteArray();
    }

    /** 手工 zip 的条目描述。 */
    record RawEntry(String name, byte[] data, int versionMadeBy, long externalAttributes,
                    int method, int flags, long declaredUncompressed, long declaredCompressed,
                    byte[] payload) {

        static RawEntry file(String name, byte[] data) {
            return new RawEntry(name, data, 20, 0, 0, 0, -1, -1, null);
        }

        static RawEntry file(String name, String content) {
            return file(name, content.getBytes(StandardCharsets.UTF_8));
        }

        /** Unix 符号链接条目：宿主 OS = 3，类型位 S_IFLNK。 */
        static RawEntry symlink(String name, String target) {
            byte[] data = target.getBytes(StandardCharsets.UTF_8);
            long unixMode = 0xA1FFL; // S_IFLNK | 0777
            return new RawEntry(name, data, (3 << 8) | 20, unixMode << 16, 0, 0, -1, -1, null);
        }

        /** Windows reparse point（junction / 符号链接）。 */
        static RawEntry reparsePoint(String name) {
            return new RawEntry(name, new byte[0], 0, 0x400L, 0, 0, -1, -1, null);
        }

        static RawEntry encrypted(String name, String content) {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            return new RawEntry(name, data, 20, 0, 0, 1, -1, -1, null);
        }

        static RawEntry unsupportedMethod(String name, String content) {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            return new RawEntry(name, data, 20, 0, 12 /* bzip2 */, 0, -1, -1, null);
        }

        /** 声明大小撒谎：用真实 deflate 数据，但中央目录声明"解压后只有 declaredUncompressed 字节"。 */
        static RawEntry deflatedLying(String name, byte[] data, long declaredUncompressed) {
            return new RawEntry(name, data, 20, 0, 8, 0, declaredUncompressed, -1, deflate(data));
        }

        /** 压缩比炸弹：声明"压缩后 10 字节、解压后 50MB"，payload 随便写（预校验就会拦下）。 */
        static RawEntry ratioBomb(String name, long declaredUncompressed) {
            return new RawEntry(name, new byte[0], 20, 0, 8, 0, declaredUncompressed, 10,
                    new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        }
    }

    // ==================== 小端写入 ====================

    /** raw deflate（nowrap）：模拟 method=8 的真实压缩数据。 */
    private static byte[] deflate(byte[] data) {
        java.util.zip.Deflater deflater =
                new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) {
                int written = deflater.deflate(buffer);
                out.write(buffer, 0, written);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
    }
}
