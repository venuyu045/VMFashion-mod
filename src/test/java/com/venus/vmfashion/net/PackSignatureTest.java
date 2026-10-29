package com.venus.vmfashion.net;

import com.venus.vmfashion.util.HashUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PackSignature} 单测：用 JDK 自带 Ed25519 自签自验，覆盖篡改、非法输入、信任库与策略。
 *
 * <p>纯逻辑（只用到 JDK + gson），不加载任何 Minecraft 类。
 */
class PackSignatureTest {

    private static final String PACK_ID = "wings";
    private static final String VERSION = "1.2.0";
    private static final String SHA = "9f2c1a4b7d8e0f3a5b6c7d8e9f0a1b2c3d4e5f60718293a4b5c6d7e8f9012345";
    private static final String KEY_ID = "vmtools-official";

    @TempDir
    Path tempDir;

    // ==================== 规范化字节串 ====================

    @Test
    void canonicalStringIsExactlyThreeLines() {
        String canonical = PackSignature.canonicalString(PACK_ID, VERSION, SHA);
        assertEquals(PACK_ID + "\n" + VERSION + "\n" + SHA, canonical);
        assertFalse(canonical.endsWith("\n"), "规范化串不能有结尾换行");
        // UTF-8 字节与字符串一致
        assertEquals(canonical, new String(PackSignature.canonicalBytes(PACK_ID, VERSION, SHA),
                StandardCharsets.UTF_8));
    }

    @Test
    void canonicalStringNormalizesTrimAndLowercase() {
        // 前后空白被 trim、sha256 统一小写：两端只要遵守同一条规则就能对上
        assertEquals(PackSignature.canonicalString(PACK_ID, VERSION, SHA),
                PackSignature.canonicalString("  " + PACK_ID + " ", " " + VERSION, SHA.toUpperCase()));
        // 字段顺序变了就不一样（防止后端把顺序写反）
        assertNotEquals(PackSignature.canonicalString(PACK_ID, VERSION, SHA),
                PackSignature.canonicalString(VERSION, PACK_ID, SHA));
    }

    @Test
    void canonicalStringRejectsInvalidFields() {
        assertThrows(IllegalArgumentException.class, () -> PackSignature.canonicalString(null, VERSION, SHA));
        assertThrows(IllegalArgumentException.class, () -> PackSignature.canonicalString("  ", VERSION, SHA));
        assertThrows(IllegalArgumentException.class, () -> PackSignature.canonicalString(PACK_ID, "", SHA));
        assertThrows(IllegalArgumentException.class, () -> PackSignature.canonicalString(PACK_ID, VERSION, "abc"));
        assertThrows(IllegalArgumentException.class, () -> PackSignature.canonicalString(PACK_ID, VERSION, null));
    }

    // ==================== 自签自验 ====================

    @Test
    void verifiesSignatureMadeWithSameKey() throws Exception {
        KeyPair pair = newKeyPair();
        String signature = sign(pair.getPrivate(), PACK_ID, VERSION, SHA);

        assertTrue(PackSignature.verify(pair.getPublic(), PACK_ID, VERSION, SHA, signature));
    }

    @Test
    void rejectsTamperedSha256() throws Exception {
        KeyPair pair = newKeyPair();
        String signature = sign(pair.getPrivate(), PACK_ID, VERSION, SHA);
        String otherSha = "0".repeat(64);

        assertFalse(PackSignature.verify(pair.getPublic(), PACK_ID, VERSION, otherSha, signature),
                "sha256 被换掉必须验签失败（这正是签名要防的）");
    }

    @Test
    void rejectsTamperedVersionOrPackId() throws Exception {
        KeyPair pair = newKeyPair();
        String signature = sign(pair.getPrivate(), PACK_ID, VERSION, SHA);

        assertFalse(PackSignature.verify(pair.getPublic(), PACK_ID, "9.9.9", SHA, signature));
        assertFalse(PackSignature.verify(pair.getPublic(), "other-pack", VERSION, SHA, signature));
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        KeyPair pair = newKeyPair();
        String signature = sign(pair.getPrivate(), PACK_ID, VERSION, SHA);
        byte[] raw = Base64.getDecoder().decode(signature);
        raw[10] ^= 0x01; // 改一个 bit
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertFalse(PackSignature.verify(pair.getPublic(), PACK_ID, VERSION, SHA, tampered));
    }

    @Test
    void rejectsSignatureFromAnotherKey() throws Exception {
        KeyPair trusted = newKeyPair();
        KeyPair attacker = newKeyPair();
        String signature = sign(attacker.getPrivate(), PACK_ID, VERSION, SHA);

        assertFalse(PackSignature.verify(trusted.getPublic(), PACK_ID, VERSION, SHA, signature));
    }

    @Test
    void rejectsMalformedSignatureInputsWithoutThrowing() throws Exception {
        KeyPair pair = newKeyPair();
        String valid = sign(pair.getPrivate(), PACK_ID, VERSION, SHA);

        assertFalse(PackSignature.verify(null, PACK_ID, VERSION, SHA, valid));
        assertFalse(PackSignature.verify(pair.getPublic(), PACK_ID, VERSION, SHA, null));
        assertFalse(PackSignature.verify(pair.getPublic(), PACK_ID, VERSION, SHA, ""));
        assertFalse(PackSignature.verify(pair.getPublic(), PACK_ID, VERSION, SHA, "   "));
        // 非法 base64
        assertFalse(PackSignature.verify(pair.getPublic(), PACK_ID, VERSION, SHA, "!!!not-base64!!!"));
        // 长度不对（16 字节）
        assertFalse(PackSignature.verify(pair.getPublic(), PACK_ID, VERSION, SHA,
                Base64.getEncoder().encodeToString(new byte[16])));
        // 字段本身非法
        assertFalse(PackSignature.verify(pair.getPublic(), "", VERSION, SHA, valid));
        assertFalse(PackSignature.verify(pair.getPublic(), PACK_ID, VERSION, "not-hex", valid));
    }

    // ==================== 公钥解析 ====================

    @Test
    void decodesRawAndSpkiAndPemPublicKeys() throws Exception {
        KeyPair pair = newKeyPair();
        byte[] spki = pair.getPublic().getEncoded();          // 44 字节
        byte[] raw = new byte[32];
        System.arraycopy(spki, spki.length - 32, raw, 0, 32); // 裸 32 字节

        PublicKey fromSpki = PackSignature.decodePublicKey(Base64.getEncoder().encodeToString(spki));
        PublicKey fromRaw = PackSignature.decodePublicKey(Base64.getEncoder().encodeToString(raw));
        PublicKey fromPem = PackSignature.decodePublicKey(
                "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(spki)
                        + "\n-----END PUBLIC KEY-----");

        assertNotNull(fromSpki);
        assertNotNull(fromRaw);
        assertNotNull(fromPem);
        // 三种写法解析出来必须是同一把钥匙（指纹一致）
        assertEquals(PackSignature.fingerprint(fromSpki), PackSignature.fingerprint(fromRaw));
        assertEquals(PackSignature.fingerprint(fromSpki), PackSignature.fingerprint(fromPem));
    }

    @Test
    void rejectsInvalidPublicKeysWithoutThrowing() {
        assertNull(PackSignature.decodePublicKey(null));
        assertNull(PackSignature.decodePublicKey(""));
        assertNull(PackSignature.decodePublicKey("   "));
        assertNull(PackSignature.decodePublicKey("not base64 at all"));
        assertNull(PackSignature.decodePublicKey(Base64.getEncoder().encodeToString(new byte[7])));
        assertNull(PackSignature.decodePublicKey(Base64.getEncoder().encodeToString(new byte[100])));
    }

    @Test
    void builtinKeyIsValidButUselessForRealSignatures() throws Exception {
        // 内置的是占位公钥：能解析（保证 builtin() 里有 1 把钥匙），但任何真实签名都验不过
        PublicKey builtin = PackSignature.decodePublicKey(PackSignature.BUILTIN_PUBLIC_KEY_BASE64);
        assertNotNull(builtin);

        KeyPair pair = newKeyPair();
        String signature = sign(pair.getPrivate(), PACK_ID, VERSION, SHA);
        assertFalse(PackSignature.verify(builtin, PACK_ID, VERSION, SHA, signature));
    }

    // ==================== 策略 / 信任库 ====================

    @Test
    void unsignedIsAllowedInLooseMode() {
        PackSignature.TrustStore store = PackSignature.TrustStore.of(Map.of(KEY_ID, publicKeyQuietly()), false);
        assertEquals(PackSignature.Decision.UNSIGNED_ALLOWED,
                PackSignature.evaluate(store, PACK_ID, VERSION, SHA, "", null));
        assertEquals(PackSignature.Decision.UNSIGNED_ALLOWED,
                PackSignature.evaluate(store, PACK_ID, VERSION, SHA, null, null));
    }

    @Test
    void unsignedIsRejectedInStrictMode() {
        PackSignature.TrustStore store = PackSignature.TrustStore.of(Map.of(KEY_ID, publicKeyQuietly()), true);
        assertEquals(PackSignature.Decision.UNSIGNED_REJECTED,
                PackSignature.evaluate(store, PACK_ID, VERSION, SHA, "", null));
        assertFalse(PackSignature.Decision.UNSIGNED_REJECTED.allowed());
    }

    @Test
    void signedPacksAreVerifiedAgainstKeyId() throws Exception {
        KeyPair pair = newKeyPair();
        String signature = sign(pair.getPrivate(), PACK_ID, VERSION, SHA);
        PackSignature.TrustStore store = PackSignature.TrustStore.of(Map.of(KEY_ID, pair.getPublic()), false);

        assertEquals(PackSignature.Decision.SIGNED_OK,
                PackSignature.evaluate(store, PACK_ID, VERSION, SHA, signature, KEY_ID));
        // 没给 keyId 时用任意受信公钥尝试
        assertEquals(PackSignature.Decision.SIGNED_OK,
                PackSignature.evaluate(store, PACK_ID, VERSION, SHA, signature, null));
        // 未知 keyId → 宽松模式放行但标记为 UNKNOWN_KEY（策略变更见 PackSignaturePolicyTest：
        // 内置公钥是占位值，若这里直接拒绝，后端一开始签名就会让所有 v1.0 客户端装不上包）
        assertEquals(PackSignature.Decision.UNKNOWN_KEY,
                PackSignature.evaluate(store, PACK_ID, VERSION, SHA, signature, "unknown-key"));
        // 严格模式下必须拒绝
        assertEquals(PackSignature.Decision.BAD_SIGNATURE,
                PackSignature.evaluate(PackSignature.TrustStore.of(Map.of(KEY_ID, pair.getPublic()), true),
                        PACK_ID, VERSION, SHA, signature, "unknown-key"));
    }

    @Test
    void triesAllTrustedKeysWhenKeyIdMissing() throws Exception {
        KeyPair first = newKeyPair();
        KeyPair second = newKeyPair();
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        keys.put("key-1", first.getPublic());
        keys.put("key-2", second.getPublic());
        PackSignature.TrustStore store = PackSignature.TrustStore.of(keys, true);

        // 用第二把钥匙签名、不给 keyId：仍然应该验过（后端轮换密钥时不至于全挂）
        String signature = sign(second.getPrivate(), PACK_ID, VERSION, SHA);
        assertEquals(PackSignature.Decision.SIGNED_OK,
                PackSignature.evaluate(store, PACK_ID, VERSION, SHA, signature, ""));
        // 显式指定错误的 keyId 则失败
        assertEquals(PackSignature.Decision.BAD_SIGNATURE,
                PackSignature.evaluate(store, PACK_ID, VERSION, SHA, signature, "key-1"));
    }

    @Test
    void evaluateNeverThrowsOnGarbage() {
        PackSignature.TrustStore store = PackSignature.TrustStore.of(Map.of(KEY_ID, publicKeyQuietly()), true);
        assertEquals(PackSignature.Decision.BAD_SIGNATURE,
                PackSignature.evaluate(store, null, null, null, "!!", "!!"));
        assertEquals(PackSignature.Decision.BAD_SIGNATURE,
                PackSignature.evaluate(null, PACK_ID, VERSION, SHA, "AAAA", KEY_ID));
        assertEquals(PackSignature.Decision.UNSIGNED_ALLOWED,
                PackSignature.evaluate(null, PACK_ID, VERSION, SHA, "", null));
    }

    // ==================== 信任库文件 ====================

    @Test
    void loadsTrustStoreFromFile() throws Exception {
        KeyPair pair = newKeyPair();
        Path file = tempDir.resolve(PackSignature.TRUST_FILE_NAME);
        Files.writeString(file, """
                {
                  "requireSignature": true,
                  "keys": [
                    { "id": "%s", "publicKey": "%s", "note": "official" },
                    { "id": "", "publicKey": "garbage" }
                  ]
                }
                """.formatted(KEY_ID, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())));

        PackSignature.TrustStore store = PackSignature.TrustStore.load(file, null);

        assertTrue(store.requireSignature());
        assertEquals(1, store.size(), "非法条目应该被跳过");
        assertNotNull(store.key(KEY_ID));
        assertNull(store.key("nope"));
        assertFalse(store.builtinOnly());

        String signature = sign(pair.getPrivate(), PACK_ID, VERSION, SHA);
        assertEquals(PackSignature.Decision.SIGNED_OK,
                PackSignature.evaluate(store, PACK_ID, VERSION, SHA, signature, KEY_ID));
    }

    @Test
    void missingOrBrokenTrustFileFallsBackToBuiltinLoose() throws Exception {
        PackSignature.TrustStore missing = PackSignature.TrustStore.load(tempDir.resolve("nope.json"), null);
        assertTrue(missing.builtinOnly());
        assertFalse(missing.requireSignature());
        assertEquals(1, missing.size());

        Path broken = tempDir.resolve("broken.json");
        Files.writeString(broken, "{ this is not json");
        PackSignature.TrustStore fromBroken = PackSignature.TrustStore.load(broken, null);
        assertTrue(fromBroken.builtinOnly());
        assertFalse(fromBroken.requireSignature());

        Path emptyKeys = tempDir.resolve("empty.json");
        Files.writeString(emptyKeys, "{\"requireSignature\":true,\"keys\":[]}");
        PackSignature.TrustStore fromEmpty = PackSignature.TrustStore.load(emptyKeys, null);
        assertTrue(fromEmpty.builtinOnly());
        assertFalse(fromEmpty.requireSignature(), "没有可用公钥时不能进入严格模式（否则谁都装不上）");

        // null 路径也不能炸
        assertTrue(PackSignature.TrustStore.load(null, null).builtinOnly());
    }

    @Test
    void trustStoreIgnoresInvalidEntries() throws Exception {
        Path file = tempDir.resolve(PackSignature.TRUST_FILE_NAME);
        Files.writeString(file, """
                {"keys":[
                  {"id":"bad-key","publicKey":"%%%"},
                  {"id":"","publicKey":"AAAA"},
                  {"publicKey":"AAAA"},
                  "not-an-object"
                ]}
                """);

        PackSignature.TrustStore store = PackSignature.TrustStore.load(file, null);
        assertTrue(store.builtinOnly(), "没有任何合法条目时回退内置公钥");
    }

    // ==================== 工具 ====================

    private static KeyPair newKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String sign(PrivateKey key, String packId, String version, String sha256) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(PackSignature.canonicalBytes(packId, version, sha256));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static PublicKey publicKeyQuietly() {
        try {
            return newKeyPair().getPublic();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
