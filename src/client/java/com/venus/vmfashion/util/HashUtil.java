package com.venus.vmfashion.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * sha256 工具：字节 / 流 / 文件的摘要计算与安全比较。
 *
 * <p>只依赖 JDK，不碰 Minecraft / Fabric，因此可以直接单测（见 {@code HashUtilTest}）。
 *
 * <p>为什么不用 {@code MessageDigest} 直接散落各处：下载校验必须"边下边算"，
 * 一处算错（例如忘了 reset、用了 {@code toString()} 而不是 hex）就会静默放过损坏的包。
 */
public final class HashUtil {

    /** sha256 十六进制字符串长度。 */
    public static final int SHA256_HEX_LENGTH = 64;
    /** 流式读取的缓冲区大小：64KB 是常见 SSD / 网络块大小的折中。 */
    private static final int BUFFER_SIZE = 64 * 1024;

    private HashUtil() {
    }

    /** 新建一个 SHA-256 {@link MessageDigest}（JVM 必须支持，否则直接失败而不是降级）。 */
    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // 任何合规 JVM 都有 SHA-256；没有就是运行环境损坏，不能"降级成不校验"
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    /** 计算字节数组的 sha256（小写 hex）。 */
    public static String sha256Hex(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data 不能为 null");
        }
        return toHex(newSha256().digest(data));
    }

    /**
     * 流式计算 sha256（小写 hex）。
     *
     * <p><b>不会关闭</b>传入的流：调用方可能还要继续读，关闭责任在调用方。
     */
    public static String sha256Hex(InputStream in) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("in 不能为 null");
        }
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) > 0) {
            digest.update(buffer, 0, read);
        }
        return toHex(digest.digest());
    }

    /** 计算文件的 sha256（小写 hex）；文件不存在 / 不可读时抛 {@link IOException}。 */
    public static String sha256Hex(Path file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file 不能为 null");
        }
        try (InputStream in = Files.newInputStream(file)) {
            return sha256Hex(in);
        }
    }

    /** 字节摘要 → 小写十六进制。 */
    public static String toHex(byte[] digest) {
        if (digest == null) {
            throw new IllegalArgumentException("digest 不能为 null");
        }
        return HexFormat.of().formatHex(digest);
    }

    /** 是否是合法的 sha256 十六进制串（64 位，大小写不限）。 */
    public static boolean isSha256Hex(String value) {
        if (value == null || value.length() != SHA256_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * 恒定时间比较两个 sha256（大小写不敏感）。
     *
     * <p>用 {@link MessageDigest#isEqual} 而不是 {@code equals}：后者会在第一个不同字节处返回，
     * 时间差理论上可用于侧信道。校验哈希的场景里没有秘密可偷，但这是零成本的正确写法。
     *
     * @return 两边都是合法 sha256 且内容一致时为 {@code true}
     */
    public static boolean sha256Equals(String expected, String actual) {
        if (!isSha256Hex(expected) || !isSha256Hex(actual)) {
            return false;
        }
        byte[] left = expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        byte[] right = actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(left, right);
    }
}
