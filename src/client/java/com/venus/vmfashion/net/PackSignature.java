package com.venus.vmfashion.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.util.HashUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 时装包 Ed25519 签名校验。
 *
 * <p><b>为什么需要</b>：自动下发上线后，任何有管理权限的账号都能把任意 zip 推给所有玩家。
 * TLS 只保护传输、{@code sha256} 只防传输损坏，两者都防不了<strong>恶意源</strong>。
 * 签名让客户端能确认"这个包确实由我信任的密钥签发过"。
 *
 * <p><b>签名覆盖的规范化字节串</b>（前后端必须逐字节一致，{@link #canonicalString} 是唯一实现）：
 * <pre>
 * trim(packId) + "\n" + trim(version) + "\n" + lowercase(sha256hex)
 * </pre>
 * 用 UTF-8 编码，<b>没有</b>结尾换行。这样签的是"包身份 + 内容哈希"：
 * 换版本、换内容都必须重新签名；URL / 发布时间这类易变字段不参与签名（换了 CDN 不用重签）。
 *
 * <p><b>信任库</b>：内置公钥 + {@code config/vmfashion/trusted_keys.json} 覆盖（自建后端用）。
 * 默认宽松模式（后端没给签名时放行 + 记一次 warn），严格模式在信任库里开
 * {@code "requireSignature": true}。
 *
 * <p><b>失败语义</b>：所有解析 / 验签异常一律当作"验签失败"（返回 {@code false} 或
 * {@link Decision#BAD_SIGNATURE}），<b>绝不抛给调用方</b> —— 验签是安全边界，不是错误处理点。
 *
 * <p>纯 JDK 实现（{@code KeyFactory/Signature} 的 {@code Ed25519}），不引入任何第三方库；
 * 本类不加载 Minecraft 类，可直接单测（见 {@code PackSignatureTest}）。
 */
public final class PackSignature {

    /** 信任库文件名（放在 {@code config/vmfashion/} 下）。 */
    public static final String TRUST_FILE_NAME = "trusted_keys.json";

    /** 内置公钥的 keyId。 */
    public static final String BUILTIN_KEY_ID = "builtin";

    /**
     * 内置公钥（base64）。
     *
     * <p><b>⚠️ 占位值</b>：这里是 32 字节全零的合法 Ed25519 公钥，<b>没有对应私钥</b>，
     * 因此任何真实签名都不可能通过它验证 —— 效果是"客户端只信 trusted_keys.json 里配置的密钥"。
     *
     * <p>发布负责人用 {@code bash tools/dev/gen-signing-key.sh} 生成密钥对（纯 JDK，无依赖），
     * 把 {@code PUBLIC_RAW} 那一行替换到本常量、把私钥交给后端保管即可。
     * 在替换之前的行为：带签名的包一律落到 {@link Decision#UNKNOWN_KEY}（宽松模式放行 + 提示配公钥），
     * 所以"后端开始签名"不会打挂已有客户端；但也没有真正的密码学保证，见 docs/fashion-api.md §6。
     */
    public static final String BUILTIN_PUBLIC_KEY_BASE64 =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    /** Ed25519 SubjectPublicKeyInfo 的 DER 前缀：{@code 302a300506032b6570032100} + 32 字节裸公钥。 */
    private static final byte[] SPKI_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };
    private static final int RAW_KEY_LENGTH = 32;
    private static final int SIGNATURE_LENGTH = 64;

    private PackSignature() {
    }

    // ==================== 判定结果 ====================

    /** 一次验签的结论。 */
    public enum Decision {
        /** 签名有效。 */
        SIGNED_OK,
        /** 后端没给签名，宽松模式放行（调用方应记一次 warn 提示管理员开启严格模式）。 */
        UNSIGNED_ALLOWED,
        /** 严格模式要求签名，但条目没有签名 → 拒绝安装。 */
        UNSIGNED_REJECTED,
        /** 签名存在但无效：base64 非法 / 长度不对 / 密钥未知 / 内容被篡改 → 拒绝安装。 */
        /**
         * 签名格式合法，但<b>没有任何受信公钥</b>能验证它（keyId 未知，或没给 keyId 且一把都不匹配）。
         *
         * <p>为什么要和 {@link #BAD_SIGNATURE} 分开：内置公钥是占位值时、或者后端刚换密钥时，
         * 官方包会落到这个分支。宽松模式放行（记一条 warn 提示把公钥加进 trusted_keys.json），
         * 严格模式拒绝 —— 否则"后端开始签名"这一个动作会把所有 v1.0 客户端打挂。
         */
        UNKNOWN_KEY,
        /**
         * 签名<b>结构非法</b>（不是 base64 / 长度不是 64 字节），或用了已知密钥但验签不通过。
         * 这两种都说明内容或签名被改过，宽松模式也拒绝。
         */
        BAD_SIGNATURE;

        /** 是否允许安装。 */
        public boolean allowed() {
            return this == SIGNED_OK || this == UNSIGNED_ALLOWED || this == UNKNOWN_KEY;
        }
    }

    // ==================== 规范化字节串 ====================

    /**
     * 规范化字符串（签名与验签的唯一口径）。
     *
     * <p>规则：{@code packId} / {@code version} 两端 trim，{@code sha256} 统一小写，
     * 三段用单个 {@code \n} 连接，<b>不带</b>结尾换行。
     *
     * @throws IllegalArgumentException 任一段为空或 sha256 不是 64 位 hex
     */
    public static String canonicalString(String packId, String version, String sha256Hex) {
        String id = packId == null ? "" : packId.trim();
        String ver = version == null ? "" : version.trim();
        String sha = sha256Hex == null ? "" : sha256Hex.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("packId 不能为空");
        }
        if (ver.isEmpty()) {
            throw new IllegalArgumentException("version 不能为空");
        }
        if (!HashUtil.isSha256Hex(sha)) {
            throw new IllegalArgumentException("sha256 不是合法的 64 位 hex");
        }
        return id + "\n" + ver + "\n" + sha;
    }

    /** {@link #canonicalString} 的 UTF-8 字节。 */
    public static byte[] canonicalBytes(String packId, String version, String sha256Hex) {
        return canonicalString(packId, version, sha256Hex).getBytes(StandardCharsets.UTF_8);
    }

    // ==================== 验签 ====================

    /**
     * 用给定公钥验一个包。
     *
     * <p>任何异常（base64 非法、签名长度不对、密钥不匹配、JVM 缺算法）都返回 {@code false}。
     *
     * @param key             受信公钥，{@code null} 直接失败
     * @param packId          清单里的 packId
     * @param version         清单里的 version
     * @param sha256Hex       清单里的整包 sha256
     * @param signatureBase64 清单里的 signature（标准 base64，非 URL-safe）
     */
    public static boolean verify(PublicKey key, String packId, String version,
                                 String sha256Hex, String signatureBase64) {
        if (key == null || signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        byte[] canonical;
        try {
            canonical = canonicalBytes(packId, version, sha256Hex);
        } catch (IllegalArgumentException e) {
            return false; // 字段本身非法 → 不可能验过
        }
        try {
            byte[] signature = Base64.getDecoder().decode(signatureBase64.trim());
            if (signature.length != SIGNATURE_LENGTH) {
                return false;
            }
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(canonical);
            return verifier.verify(signature);
        } catch (Throwable t) {
            // 安全边界：一律当失败，绝不把异常抛给渲染/下载链路
            return false;
        }
    }

    /**
     * "未知密钥"是否应当被拒绝。
     *
     * <p>规则：严格模式，或者信任库来自管理员显式配置（{@code builtinOnly=false}）时拒绝；
     * 只有"纯内置占位公钥 + 宽松模式"才放行（见 {@link Decision#UNKNOWN_KEY} 的说明）。
     */
    static boolean rejectUnknownKey(TrustStore trust) {
        return trust.requireSignature() || !trust.builtinOnly();
    }

    /**
     * 签名结构是否合法：标准 base64 且解码后正好 64 字节（Ed25519 签名的固定长度）。
     *
     * <p>只判结构，不做密码学校验 —— 结构非法说明签名被截断/替换过，直接拒绝；
     * 结构合法但验不过则可能是"密钥未知"，由 {@link #evaluate} 区分。
     */
    static boolean isWellFormedSignature(String signatureBase64) {
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        try {
            byte[] raw = java.util.Base64.getDecoder().decode(signatureBase64.trim());
            return raw.length == 64;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按信任库与策略判定一个包能不能装。
     *
     * <p>{@code keyId} 为空时用<b>任意一个</b>受信公钥尝试（后端轮换密钥时不至于全挂）；
     * 显式给了 {@code keyId} 就只用那把（未知 keyId → {@link Decision#BAD_SIGNATURE}）。
     */
    public static Decision evaluate(TrustStore store, String packId, String version, String sha256Hex,
                                    String signatureBase64, String keyId) {
        TrustStore trust = store == null ? TrustStore.builtin() : store;
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            return trust.requireSignature() ? Decision.UNSIGNED_REJECTED : Decision.UNSIGNED_ALLOWED;
        }
        // 结构非法 = 被改过（正常后端不会产出），两种模式都拒绝
        if (!isWellFormedSignature(signatureBase64)) {
            return Decision.BAD_SIGNATURE;
        }
        String trimmedKeyId = keyId == null ? "" : keyId.trim();
        if (!trimmedKeyId.isEmpty()) {
            PublicKey key = trust.key(trimmedKeyId);
            if (key == null) {
                // 声明了一个我们不认识的 keyId。只要信任库来自"管理员显式配置的真实公钥"（非内置占位）
                // 或处于严格模式，未知密钥就必须拒绝 —— 否则攻陷的后端随便填个 keyId 就能绕过验签。
                // 只有纯内置占位公钥的宽松模式才放行（否则官方后端一开始签名就会打挂所有客户端）。
                return rejectUnknownKey(trust) ? Decision.BAD_SIGNATURE : Decision.UNKNOWN_KEY;
            }
            // 已知密钥却验不过 = 内容或签名被篡改
            return verify(key, packId, version, sha256Hex, signatureBase64)
                    ? Decision.SIGNED_OK : Decision.BAD_SIGNATURE;
        }
        for (PublicKey key : trust.keys().values()) {
            if (verify(key, packId, version, sha256Hex, signatureBase64)) {
                return Decision.SIGNED_OK;
            }
        }
        // 没给 keyId 且一把受信公钥都不匹配：同样是"未知密钥"而不是"被篡改"
        return rejectUnknownKey(trust) ? Decision.BAD_SIGNATURE : Decision.UNKNOWN_KEY;
    }

    // ==================== 公钥解析 ====================

    /**
     * 解析 base64 公钥。
     *
     * <p>接受三种写法，方便后端 / 运维同学：
     * <ul>
     *   <li>裸 32 字节 base64（最紧凑，文档推荐）；</li>
     *   <li>44 字节 X.509 SubjectPublicKeyInfo base64（{@code Key#getEncoded()} 的输出）；</li>
     *   <li>带 {@code -----BEGIN PUBLIC KEY-----} 包裹的 PEM（自动去头尾与空白）。</li>
     * </ul>
     *
     * @return 公钥；解析失败返回 {@code null}（调用方按"无可用密钥"处理）
     */
    public static PublicKey decodePublicKey(String base64OrPem) {
        if (base64OrPem == null || base64OrPem.isBlank()) {
            return null;
        }
        try {
            String cleaned = base64OrPem.trim()
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(cleaned);
            byte[] spki = decoded.length == RAW_KEY_LENGTH ? prependPrefix(decoded) : decoded;
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 公钥指纹（sha256 前 8 个 hex），只用于日志里区分是哪把钥匙。 */
    public static String fingerprint(PublicKey key) {
        if (key == null) {
            return "none";
        }
        try {
            String hex = HashUtil.sha256Hex(key.getEncoded());
            return hex.substring(0, 8);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static byte[] prependPrefix(byte[] raw) {
        byte[] result = new byte[SPKI_PREFIX.length + raw.length];
        System.arraycopy(SPKI_PREFIX, 0, result, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, result, SPKI_PREFIX.length, raw.length);
        return result;
    }

    // ==================== 信任库 ====================

    /**
     * 信任库 + 策略（不可变快照）。
     *
     * <p>由 {@code AssetSyncService} 在初始化 / 文件变化时构建一次，之后只读。
     */
    public static final class TrustStore {

        private final Map<String, PublicKey> keys;
        private final boolean requireSignature;
        private final boolean builtinOnly;

        private TrustStore(Map<String, PublicKey> keys, boolean requireSignature, boolean builtinOnly) {
            this.keys = Map.copyOf(keys);
            this.requireSignature = requireSignature;
            this.builtinOnly = builtinOnly;
        }

        /** 只有内置公钥、宽松模式（文件不存在时的默认）。 */
        public static TrustStore builtin() {
            PublicKey key = decodePublicKey(BUILTIN_PUBLIC_KEY_BASE64);
            Map<String, PublicKey> map = new LinkedHashMap<>();
            if (key != null) {
                map.put(BUILTIN_KEY_ID, key);
            }
            return new TrustStore(map, false, true);
        }

        /** 直接构造（单测 / 程序化配置用）。 */
        public static TrustStore of(Map<String, PublicKey> keys, boolean requireSignature) {
            Map<String, PublicKey> copy = new LinkedHashMap<>();
            if (keys != null) {
                keys.forEach((id, key) -> {
                    if (id != null && !id.isBlank() && key != null) {
                        copy.put(id, key);
                    }
                });
            }
            if (copy.isEmpty()) {
                return builtin();
            }
            return new TrustStore(copy, requireSignature, false);
        }

        /**
         * 从 {@code config/vmfashion/trusted_keys.json} 读取；文件不存在 / 坏掉 / 没有可用公钥时
         * 回退 {@link #builtin()}（宽松模式），并记录原因。
         *
         * <p>文件格式：
         * <pre>
         * {
         *   "requireSignature": true,
         *   "keys": [ { "id": "vmtools-official", "publicKey": "base64…" } ]
         * }
         * </pre>
         *
         * @param logger 日志回调，可为 {@code null}
         */
        public static TrustStore load(Path file, Consumer<String> logger) {
            if (file == null || !Files.isRegularFile(file)) {
                // 完全没配 = 默认宽松（未签名的包照常装，只提示一次）
                return builtin();
            }
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                JsonElement rootElement = JsonParser.parseString(json);
                if (!rootElement.isJsonObject()) {
                    log(logger, "trusted_keys.json 不是 JSON 对象：按配置错误处理，拒绝安装任何服务端包"
                            + "（fail-closed）。删掉该文件即可恢复默认宽松模式");
                    return failClosed(Map.of());
                }
                JsonObject root = rootElement.getAsJsonObject();
                boolean require = optBoolean(root, "requireSignature", false);
                Map<String, PublicKey> keys = new LinkedHashMap<>();
                JsonElement keysElement = root.get("keys");
                if (keysElement != null && keysElement.isJsonArray()) {
                    for (JsonElement element : keysElement.getAsJsonArray()) {
                        if (element == null || !element.isJsonObject()) {
                            continue;
                        }
                        JsonObject entry = element.getAsJsonObject();
                        String id = optString(entry, "id");
                        String publicKey = optString(entry, "publicKey");
                        PublicKey key = decodePublicKey(publicKey);
                        if (id.isBlank() || key == null) {
                            log(logger, "trusted_keys.json 跳过非法条目: id=" + id);
                            continue;
                        }
                        keys.put(id, key);
                    }
                }
                if (keys.isEmpty()) {
                    if (require) {
                        // 管理员明确要求强制验签，却没有可用公钥 → 绝不 fail-open，直接全拒
                        log(logger, "trusted_keys.json 要求 requireSignature=true 但没有可用公钥："
                                + "拒绝安装任何服务端包（fail-closed）");
                        return failClosed(Map.of());
                    }
                    log(logger, "trusted_keys.json 没有可用公钥（且未要求强制验签），回退内置公钥");
                    return builtin();
                }
                log(logger, "已加载信任库：" + keys.size() + " 把公钥，requireSignature=" + require);
                return new TrustStore(keys, require, false);
            } catch (Throwable t) {
                log(logger, "读取 trusted_keys.json 失败（按配置错误处理，拒绝安装任何服务端包，fail-closed）: " + t);
                return failClosed(Map.of());
            }
        }

        /** 配置错误时的"全拒"信任库：没有可用密钥 + 强制验签 → 一切服务端包都不装（本地包不受影响）。 */
        private static TrustStore failClosed(Map<String, PublicKey> keys) {
            return new TrustStore(keys, true, false);
        }

        public boolean requireSignature() {
            return requireSignature;
        }

        /** 是否仍在使用内置占位公钥（发布前必须替换成官方公钥）。 */
        public boolean builtinOnly() {
            return builtinOnly;
        }

        public int size() {
            return keys.size();
        }

        /** keyId → 公钥（只读）。 */
        public Map<String, PublicKey> keys() {
            return keys;
        }

        /** 按 keyId 取公钥；未知返回 {@code null}。 */
        public PublicKey key(String keyId) {
            if (keyId == null || keyId.isBlank()) {
                return null;
            }
            return keys.get(keyId.trim());
        }
    }

    // ==================== 工具 ====================

    private static void log(Consumer<String> logger, String message) {
        if (logger != null) {
            logger.accept(message);
        }
    }

    private static String optString(JsonObject object, String key) {
        try {
            JsonElement element = object.get(key);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
                return "";
            }
            return element.getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean optBoolean(JsonObject object, String key, boolean fallback) {
        try {
            JsonElement element = object.get(key);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
                return fallback;
            }
            return element.getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }
}
