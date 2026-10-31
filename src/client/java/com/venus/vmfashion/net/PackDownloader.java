package com.venus.vmfashion.net;

import com.venus.vmfashion.util.HashUtil;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * 时装包 zip 的流式下载 + 完整性校验。
 *
 * <p>安全要求（自动下发 = 远程数据落地，任何一条都不能省）：
 * <ul>
 *   <li><b>边下边算</b> sha256（{@link DigestInputStream}），不把整个包读进内存；</li>
 *   <li>同时校验<b>字节数</b>：清单说多少就是多少，超出立刻中断（防"清单很小、实际巨大"）；</li>
 *   <li>校验失败 / 取消 / IO 异常都<b>删除</b>写了一半的文件，绝不留下可被误装的残包；</li>
 *   <li>只允许 {@code http(s)}；<b>不跟随重定向</b>（与 {@code FashionApi} 一致，
 *       避免 {@code Authorization} 被 302 带到别的 host）；</li>
 *   <li>只有当下载地址与后端<b>同源</b>时才带 token，绝对 URL 指向第三方时一律不带。</li>
 * </ul>
 *
 * <p>本类只负责"下载到一个临时文件并校验"，解压与安装由 {@code SafeZip} / {@code AssetSyncService} 负责。
 */
public final class PackDownloader {

    /** 单次 HTTP 请求超时（只覆盖到响应头）。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /**
     * 读到完整 body 的硬超时（毫秒）。
     *
     * <p>为什么必须有：{@link #REQUEST_TIMEOUT} 只覆盖"拿到响应头"，后端/CDN 完全可以在返回 200 之后
     * 卡住不发 body（或极慢滴流）。旧实现靠 {@code while(read>0)} 一直等，卡住的线程会占满下载线程池，
     * 让整个自动下发停摆（发布前审查的严重项）。
     */
    private static final long BODY_TIMEOUT_MS = 120_000L;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** 失败原因：上层据此决定"重试"还是"永久跳过"。 */
    public enum FailureReason {
        /** 非 2xx 响应。 */
        HTTP_ERROR,
        /** 超过大小上限。 */
        TOO_LARGE,
        /** 实际字节数与清单不符。 */
        SIZE_MISMATCH,
        /** sha256 不符（传输损坏或被篡改）。 */
        HASH_MISMATCH,
        /** 被取消（离服 / 清缓存）。 */
        ABORTED,
        /** 地址非法 / IO 失败。 */
        IO
    }

    /** 下载失败。{@code status} 为 0 表示不是 HTTP 错误。 */
    public static final class DownloadException extends IOException {

        private final FailureReason reason;
        private final int status;

        public DownloadException(FailureReason reason, String message) {
            this(reason, 0, message);
        }

        public DownloadException(FailureReason reason, int status, String message) {
            super(message);
            this.reason = reason;
            this.status = status;
        }

        public FailureReason reason() {
            return reason;
        }

        public int status() {
            return status;
        }

        @Override
        public String toString() {
            return "DownloadException[" + reason + (status > 0 ? " " + status : "") + ": " + getMessage() + "]";
        }
    }

    /**
     * 下载结果。
     *
     * @param file   落盘位置
     * @param sha256 实际算出的 sha256（已与期望值校验通过）
     * @param bytes  实际字节数
     */
    public record DownloadResult(Path file, String sha256, long bytes) {
    }

    private final HttpClient http;
    private final Executor executor;

    /**
     * @param http     共享的 HttpClient（必须关闭重定向）
     * @param executor 读取响应体用的线程池（{@code ofInputStream} 的消费不能占用 HttpClient 线程）
     */
    public PackDownloader(HttpClient http, Executor executor) {
        this.http = http;
        this.executor = executor;
    }

    /**
     * 下载并校验一个包。
     *
     * @param url            下载地址，绝对（http/https）或相对后端地址
     * @param baseUrl        后端地址，用于把相对 URL 补全、并判断是否同源
     * @param token          鉴权 token，可为 {@code null}
     * @param targetFile     落盘位置（通常在 {@code packs/server/.tmp-*} 里）
     * @param expectedSha256 清单里的 sha256，必须合法，否则直接失败
     * @param expectedBytes  清单里的字节数，{@code <= 0} 表示不校验字节数
     * @param maxBytes       硬上限，{@code <= 0} 表示不限制（建议永远传上限）
     * @param aborted        取消检查（离服 / 清缓存时返回 true），可为 {@code null}
     */
    public CompletableFuture<DownloadResult> download(String url, String baseUrl, String token, Path targetFile,
                                                      String expectedSha256, long expectedBytes, long maxBytes,
                                                      BooleanSupplier aborted) {
        return download(url, baseUrl, token, targetFile, expectedSha256, expectedBytes, maxBytes, aborted, null);
    }

    /**
     * 下载并校验，额外把"正在读取的响应体流"交给 {@code streamTracker}。
     *
     * <p>为什么需要 {@code streamTracker}：body 卡住时 {@code InputStream#read} 会无限阻塞，
     * 循环里的 deadline 检查根本轮不到执行。调用方（{@code AssetSyncService} 的看门狗）
     * 需要在超时后**从另一个线程把这个流关掉**，才能让阻塞的 read 抛异常、释放线程。
     * 本方法在开始读之前 {@code accept(in)}、读完之后 {@code accept(null)}。
     *
     * @param streamTracker 可为 {@code null}；参数是当前正在读的流（读完后传 {@code null}）
     */
    public CompletableFuture<DownloadResult> download(String url, String baseUrl, String token, Path targetFile,
                                                      String expectedSha256, long expectedBytes, long maxBytes,
                                                      BooleanSupplier aborted,
                                                      java.util.function.Consumer<InputStream> streamTracker) {
        if (!HashUtil.isSha256Hex(expectedSha256)) {
            return CompletableFuture.failedFuture(new DownloadException(FailureReason.HASH_MISMATCH,
                    "清单里没有可信的 sha256，拒绝下载"));
        }
        URI uri = resolveUri(url, baseUrl);
        if (uri == null) {
            return CompletableFuture.failedFuture(new DownloadException(FailureReason.IO,
                    "下载地址非法（只允许 https，本机回环除外）: " + url));
        }
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/zip, application/octet-stream")
                    .header("User-Agent", "VMFashion")
                    .GET();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new DownloadException(FailureReason.IO,
                    "下载地址非法: " + uri));
        }
        // 只有"同源 + 目标本身是安全地址"才带 token：绝对 URL 可能指向第三方 CDN，
        // 而明文 http 的目标可能被中间人读到 Authorization（audit v1.2 N05）。
        if (token != null && !token.isBlank() && sameOrigin(uri, baseUrl) && isSecureUrl(uri)) {
            builder.header("Authorization", "Bearer " + token);
        }

        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                .thenApplyAsync(response -> {
                    int status = response.statusCode();
                    if (status / 100 != 2) {
                        // 必须把流关掉，否则连接不会被回收
                        try (InputStream ignored = response.body()) {
                            // 只为关闭
                        } catch (IOException ignored) {
                            // 关不掉也无所谓，马上要抛错
                        }
                        throw new CompletionException(new DownloadException(FailureReason.HTTP_ERROR, status,
                                "下载失败: HTTP " + status));
                    }
                    InputStream in = response.body();
                    if (streamTracker != null) {
                        // 交给调用方保管：body 卡住时它能从别的线程 close 这个流，解阻塞 read
                        streamTracker.accept(in);
                    }
                    try (InputStream closeable = in) {
                        return writeVerified(closeable, targetFile, expectedSha256, expectedBytes, maxBytes,
                                aborted, System.nanoTime() + BODY_TIMEOUT_MS * 1_000_000L);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    } finally {
                        if (streamTracker != null) {
                            streamTracker.accept(null);
                        }
                    }
                }, executor)
                .exceptionallyCompose(throwable -> {
                    Throwable cause = unwrap(throwable);
                    deleteQuietly(targetFile);
                    if (cause instanceof DownloadException downloadException) {
                        return CompletableFuture.failedFuture(downloadException);
                    }
                    if (cause instanceof IOException ioException) {
                        return CompletableFuture.failedFuture(new DownloadException(FailureReason.IO,
                                ioException.getMessage() == null ? "下载失败" : ioException.getMessage()));
                    }
                    return CompletableFuture.failedFuture(new DownloadException(FailureReason.IO,
                            cause.getMessage() == null ? "下载失败" : cause.getMessage()));
                });
    }

    /**
     * 把流写入文件并校验（不涉及 HTTP，单测直接喂 {@code ByteArrayInputStream}）。
     *
     * <p>任何失败都会删除目标文件 —— 半成品残包一旦被误装，比没有包更糟。
     *
     * @return 校验通过的结果
     * @throws DownloadException 校验失败 / 取消 / 超限
     * @throws IOException       IO 失败
     */
    static DownloadResult writeVerified(InputStream rawInput, Path targetFile, String expectedSha256,
                                        long expectedBytes, long maxBytes, BooleanSupplier aborted) throws IOException {
        // 单测/直接调用：默认给一个很宽裕的 deadline（正常数据瞬间读完，不会触发）
        return writeVerified(rawInput, targetFile, expectedSha256, expectedBytes, maxBytes, aborted,
                System.nanoTime() + BODY_TIMEOUT_MS * 1_000_000L);
    }

    /**
     * 带绝对 deadline 的写入校验。
     *
     * @param deadlineNanos 读超时的绝对时间点（{@link System#nanoTime()}）；{@code <= 0} 表示不限制
     */
    static DownloadResult writeVerified(InputStream rawInput, Path targetFile, String expectedSha256,
                                        long expectedBytes, long maxBytes, BooleanSupplier aborted,
                                        long deadlineNanos) throws IOException {
        if (rawInput == null || targetFile == null) {
            throw new IllegalArgumentException("rawInput / targetFile 不能为 null");
        }
        if (!HashUtil.isSha256Hex(expectedSha256)) {
            throw new DownloadException(FailureReason.HASH_MISMATCH, "清单里没有可信的 sha256，拒绝写入");
        }
        Path parent = targetFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.deleteIfExists(targetFile);

        MessageDigest digest = HashUtil.newSha256();
        long written = 0;
        boolean success = false;
        try (InputStream in = new DigestInputStream(new BufferedInputStream(rawInput, BUFFER_SIZE), digest);
             OutputStream out = Files.newOutputStream(targetFile,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (aborted != null && aborted.getAsBoolean()) {
                    throw new DownloadException(FailureReason.ABORTED, "下载已取消");
                }
                if (deadlineNanos > 0 && System.nanoTime() > deadlineNanos) {
                    // 慢速滴流：每次 read 都返回了，但总量拖过 deadline → 判超时（卡死的那种由外部 close 流处理）
                    throw new DownloadException(FailureReason.IO, "下载超时（读取超过 "
                            + (BODY_TIMEOUT_MS / 1000) + " 秒）");
                }
                written += read;
                if (maxBytes > 0 && written > maxBytes) {
                    throw new DownloadException(FailureReason.TOO_LARGE,
                            "下载内容超过上限: " + written + " > " + maxBytes);
                }
                if (expectedBytes > 0 && written > expectedBytes) {
                    // 清单说 1MB、实际一直在涨：立刻中断，不要等下完
                    throw new DownloadException(FailureReason.SIZE_MISMATCH,
                            "下载内容超过清单声明的大小: " + written + " > " + expectedBytes);
                }
                out.write(buffer, 0, read);
            }
            success = true;
        } finally {
            if (!success) {
                deleteQuietly(targetFile);
            }
        }

        String actual = HashUtil.toHex(digest.digest());
        if (expectedBytes > 0 && written != expectedBytes) {
            deleteQuietly(targetFile);
            throw new DownloadException(FailureReason.SIZE_MISMATCH,
                    "字节数与清单不符: " + written + " != " + expectedBytes);
        }
        if (!HashUtil.sha256Equals(expectedSha256, actual)) {
            deleteQuietly(targetFile);
            throw new DownloadException(FailureReason.HASH_MISMATCH,
                    "sha256 校验失败: 期望 " + expectedSha256 + "，实际 " + actual);
        }
        return new DownloadResult(targetFile, actual, written);
    }

    // ==================== 工具 ====================

    /** 相对 URL 用 {@code baseUrl} 补全；绝对 URL 必须是 https（本机回环允许 http）。非法返回 {@code null}。 */
    static URI resolveUri(String url, String baseUrl) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String value = url.trim();
        try {
            URI uri;
            String scheme = extractScheme(value);
            if (scheme != null) {
                // 带 scheme 的一律当绝对地址：file: / ftp: / data: 全部拒绝（否则变成任意文件读取）
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    return null;
                }
                uri = URI.create(value);
            } else {
                if (baseUrl == null || baseUrl.isBlank()) {
                    return null;
                }
                String base = baseUrl.trim();
                while (base.endsWith("/")) {
                    base = base.substring(0, base.length() - 1);
                }
                uri = URI.create(base + (value.startsWith("/") ? value : "/" + value));
            }
            String finalScheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!"http".equals(finalScheme) && !"https".equals(finalScheme)) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            // 只允许 https（本机回环除外）：清单本身未签名，明文下载地址等于把"装什么内容"
            // 交给中间人；相对 URL 拼到 http 后端上同样会被拒（audit v1.2 N05）。
            if (!isSecureUrl(uri)) {
                return null;
            }
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 地址是否安全到可以承载凭据 / 远程内容：{@code https}，或本机回环的 {@code http}。
     *
     * <p>与 {@code FashionAuth#validateAuthServer} 的口径保持一致（那边管的是会收到 Minecraft
     * accessToken 的会话服务器地址，这里管下载 URL 与 {@code Authorization} 头）。
     */
    public static boolean isSecureUrl(URI uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if ("https".equals(scheme)) {
            return true;
        }
        if (!"http".equals(scheme) || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return "localhost".equals(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }

    /** {@link #isSecureUrl(URI)} 的字符串重载；空白 / 非法 URL 返回 {@code false}。 */
    public static boolean isSecureUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            return isSecureUrl(URI.create(url.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 取出 URL 的 scheme（{@code "https"} / {@code "file"} / {@code "C"} 等）。
     *
     * <p>{@code "/packs/a"}、{@code "packs/a"} 这类相对路径返回 {@code null}。
     */
    private static String extractScheme(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        int slash = value.indexOf('/');
        if (slash >= 0 && slash < colon) {
            return null; // 冒号在路径里，不是 scheme
        }
        String candidate = value.substring(0, colon);
        char first = candidate.charAt(0);
        if (!Character.isLetter(first)) {
            return null;
        }
        for (int i = 1; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.';
            if (!allowed) {
                return null;
            }
        }
        return candidate;
    }

    /** 目标 URI 是否与后端同源（scheme + host + 端口）。 */
    static boolean sameOrigin(URI target, String baseUrl) {
        if (target == null || baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            URI base = URI.create(baseUrl.trim());
            if (base.getHost() == null) {
                return false;
            }
            String targetScheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(java.util.Locale.ROOT);
            String baseScheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
            return targetScheme.equals(baseScheme)
                    && target.getHost().equalsIgnoreCase(base.getHost())
                    && effectivePort(target) == effectivePort(base);
        } catch (Exception e) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        return switch (scheme) {
            case "https" -> 443;
            case "http" -> 80;
            default -> -1;
        };
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof CompletionException || cause instanceof UncheckedIOException) {
            if (cause.getCause() == null || cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return cause;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 删不掉就算了，临时目录最终会被整体清理
        }
    }
}
