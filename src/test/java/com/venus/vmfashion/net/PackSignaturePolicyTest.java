package com.venus.vmfashion.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验签策略（宽松 / 严格）的边界 —— 这是 v1.0 的发布阻塞项相关逻辑。
 *
 * <p>核心背景：**内置公钥是占位值**（没有对应私钥）。如果"签名来自未知密钥"被当成
 * {@code BAD_SIGNATURE} 直接拒绝，那么后端一旦开始给包签名，所有 v1.0 客户端会集体装不上包。
 * 所以策略必须是：
 * <ul>
 *   <li>宽松模式（默认）：未知密钥 → 放行 + 提示配 trusted_keys.json；畸形签名/已知密钥但验不过 → 拒绝；</li>
 *   <li>严格模式（{@code requireSignature: true}）：只有验签通过才放行。</li>
 * </ul>
 */
class PackSignaturePolicyTest {

    private static final String PACK_ID = "example";
    private static final String VERSION = "1.0.0";
    private static final String SHA = "a".repeat(64);

    @TempDir
    Path tempDir;

    private static KeyPair newKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String sign(KeyPair pair, String packId, String version, String sha) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(PackSignature.canonicalBytes(packId, version, sha));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String publicKeyBase64(KeyPair pair) {
        // 裸 32 字节（文档推荐的紧凑写法）
        byte[] encoded = pair.getPublic().getEncoded();
        return Base64.getEncoder().encodeToString(
                java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    private PackSignature.TrustStore trustFile(String keyId, KeyPair pair, boolean requireSignature)
            throws Exception {
        Path file = tempDir.resolve(PackSignature.TRUST_FILE_NAME);
        String json = "{\"requireSignature\": " + requireSignature + ", \"keys\": [{\"id\": \"" + keyId
                + "\", \"publicKey\": \"" + publicKeyBase64(pair) + "\"}]}";
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return PackSignature.TrustStore.load(file, message -> { });
    }

    @Test
    void knownKeyWithValidSignatureIsAcceptedInBothModes() throws Exception {
        KeyPair pair = newKeyPair();
        String signature = sign(pair, PACK_ID, VERSION, SHA);

        for (boolean strict : new boolean[]{false, true}) {
            PackSignature.TrustStore trust = trustFile("k1", pair, strict);
            PackSignature.Decision decision = PackSignature.evaluate(
                    trust, PACK_ID, VERSION, SHA, signature, "k1");
            assertEquals(PackSignature.Decision.SIGNED_OK, decision, "strict=" + strict);
            assertTrue(decision.allowed());
        }
    }

    @Test
    void unknownKeyIsAllowedInLenientModeButRejectedInStrictMode() throws Exception {
        KeyPair real = newKeyPair();
        KeyPair other = newKeyPair();
        String signature = sign(other, PACK_ID, VERSION, SHA);

        PackSignature.TrustStore lenient = trustFile("k1", real, false);
        PackSignature.Decision allowed = PackSignature.evaluate(
                lenient, PACK_ID, VERSION, SHA, signature, "some-unknown-key");
        assertEquals(PackSignature.Decision.UNKNOWN_KEY, allowed);
        assertTrue(allowed.allowed(), "宽松模式下未知密钥必须放行，否则后端一开始签名就全网装不上");

        PackSignature.TrustStore strict = trustFile("k1", real, true);
        PackSignature.Decision rejected = PackSignature.evaluate(
                strict, PACK_ID, VERSION, SHA, signature, "some-unknown-key");
        assertEquals(PackSignature.Decision.BAD_SIGNATURE, rejected);
        assertFalse(rejected.allowed());
    }

    @Test
    void noKeyIdAndNoMatchingKeyFollowsSamePolicy() throws Exception {
        KeyPair real = newKeyPair();
        KeyPair other = newKeyPair();
        String signature = sign(other, PACK_ID, VERSION, SHA);

        assertEquals(PackSignature.Decision.UNKNOWN_KEY, PackSignature.evaluate(
                trustFile("k1", real, false), PACK_ID, VERSION, SHA, signature, null));
        assertEquals(PackSignature.Decision.BAD_SIGNATURE, PackSignature.evaluate(
                trustFile("k1", real, true), PACK_ID, VERSION, SHA, signature, null));
    }

    @Test
    void tamperedContentIsRejectedEvenInLenientMode() throws Exception {
        KeyPair pair = newKeyPair();
        String signature = sign(pair, PACK_ID, VERSION, SHA);
        String tamperedSha = "b".repeat(64);

        PackSignature.Decision decision = PackSignature.evaluate(
                trustFile("k1", pair, false), PACK_ID, VERSION, tamperedSha, signature, "k1");
        assertEquals(PackSignature.Decision.BAD_SIGNATURE, decision,
                "已知密钥但内容被改：宽松模式也必须拒绝");
        assertFalse(decision.allowed());
    }

    @Test
    void malformedSignatureIsRejectedInBothModes() throws Exception {
        KeyPair pair = newKeyPair();
        for (String bad : new String[]{"not-base64!!!", "AAAA", "", "   "}) {
            for (boolean strict : new boolean[]{false, true}) {
                PackSignature.Decision decision = PackSignature.evaluate(
                        trustFile("k1", pair, strict), PACK_ID, VERSION, SHA, bad, "k1");
                if (bad.isBlank()) {
                    assertEquals(strict ? PackSignature.Decision.UNSIGNED_REJECTED
                                    : PackSignature.Decision.UNSIGNED_ALLOWED, decision,
                            "空白签名应按\"没有签名\"处理");
                } else {
                    assertFalse(decision.allowed(), "畸形签名必须拒绝: " + bad + " strict=" + strict);
                }
            }
        }
        assertFalse(PackSignature.isWellFormedSignature("AAAA"));
        assertFalse(PackSignature.isWellFormedSignature(null));
        assertTrue(PackSignature.isWellFormedSignature(
                Base64.getEncoder().encodeToString(new byte[64])));
    }

    @Test
    void unsignedPacksFollowRequireSignatureFlag() throws Exception {
        PackSignature.TrustStore lenient = PackSignature.TrustStore.builtin();
        assertEquals(PackSignature.Decision.UNSIGNED_ALLOWED,
                PackSignature.evaluate(lenient, PACK_ID, VERSION, SHA, null, null));

        // 注意：TrustStore.of 在 keys 为空时会回退 builtin()（宽松），所以这里必须给一把真密钥
        KeyPair pair = newKeyPair();
        PackSignature.TrustStore strict = PackSignature.TrustStore.of(Map.of("k1", pair.getPublic()), true);
        assertEquals(PackSignature.Decision.UNSIGNED_REJECTED,
                PackSignature.evaluate(strict, PACK_ID, VERSION, SHA, null, null));
    }

    @Test
    void canonicalStringIsTrimmedAndLowercased() {
        String upperSha = "ABCDEF0123456789".repeat(4);
        String canonical = PackSignature.canonicalString("  packA ", " 1.2.3 ", upperSha);
        assertEquals("packA\n1.2.3\n" + upperSha.toLowerCase(), canonical);
        // 非法 sha256 必须抛 IllegalArgumentException（签名契约的一部分）
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PackSignature.canonicalString("p", "1.0.0", "ABCDEF"));
    }
}
