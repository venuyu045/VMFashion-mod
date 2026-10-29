package com.venus.vmfashion.cosmetic;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 把时装 ID 映射成 Minecraft 资源路径（纯逻辑，不依赖任何 Minecraft 类，因此可单测）。
 *
 * <p>为什么不能只做字符替换：{@code Identifier} 的 path 只允许 {@code a-z0-9/._-}，
 * 旧实现把非法字符一律换成 {@code _} 并 {@code toLowerCase}，于是 {@code A:B} 和 {@code A_B}
 * 会映射到同一个 Identifier —— 两件时装的贴图互相覆盖，后加载的图被前一件复用（#13）。
 *
 * <p>现在的做法是「可读部分 + 来源哈希」：可读部分只为人眼看日志/排查方便，
 * 唯一性由 {@code CRC32(packId + ":" + localId + ":" + suffix)} 保证。
 * 哈希算的是<b>原始字符串</b>（大小写敏感、不做字符替换），
 * 因此不同来源一定不会落到同一个 ID。
 */
public final class CosmeticResourceId {

    /** CRC32 固定 8 位十六进制，保证路径长度可预期。 */
    private static final int HASH_HEX_LENGTH = 8;
    /** 三段全空时的兜底可读部分（避免出现以 '.' 开头的怪路径）。 */
    private static final String FALLBACK_PART = "unknown";
    private static final char SEPARATOR = '/';
    private static final char HASH_SEPARATOR = '.';

    private CosmeticResourceId() {
    }

    /**
     * 生成资源路径（不含命名空间），例如 {@code example/crown/texture.1a2b3c4d}。
     *
     * @param packId  包 ID，可为 {@code null}
     * @param localId 包内时装 ID，可为 {@code null}
     * @param suffix  资源用途（{@code model} / {@code texture} / {@code emissive}），可为 {@code null}
     * @return 只含 {@code a-z0-9/._-} 的路径，且对不同来源唯一
     */
    public static String pathFor(String packId, String localId, String suffix) {
        StringBuilder path = new StringBuilder(64);
        appendPart(path, packId);
        appendPart(path, localId);
        appendPart(path, suffix);
        if (path.isEmpty()) {
            path.append(FALLBACK_PART);
        }
        return path.append(HASH_SEPARATOR)
                .append(hashHex(rawKey(packId, localId, suffix)))
                .toString();
    }

    /**
     * 原始来源串：用 {@code :} 分隔，避免 {@code ("a/b","c")} 与 {@code ("a","b/c")} 拼出同一个串。
     */
    static String rawKey(String packId, String localId, String suffix) {
        return nullToEmpty(packId) + ':' + nullToEmpty(localId) + ':' + nullToEmpty(suffix);
    }

    /** CRC32 → 8 位小写十六进制（不分配格式化对象，避免在热路径上产生额外垃圾）。 */
    static String hashHex(String raw) {
        CRC32 crc = new CRC32();
        crc.update(raw.getBytes(StandardCharsets.UTF_8));
        String hex = Long.toHexString(crc.getValue());
        StringBuilder padded = new StringBuilder(HASH_HEX_LENGTH);
        for (int i = hex.length(); i < HASH_HEX_LENGTH; i++) {
            padded.append('0');
        }
        return padded.append(hex).toString();
    }

    private static void appendPart(StringBuilder out, String part) {
        if (part == null) {
            return;
        }
        String trimmed = part.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append(SEPARATOR);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = Character.toLowerCase(trimmed.charAt(i));
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            // 注意：'/' 也在非法字符之列 —— 段内出现斜杠不能变成路径分隔符，否则会拼出目录穿越观感的路径
            out.append(allowed ? c : '_');
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
