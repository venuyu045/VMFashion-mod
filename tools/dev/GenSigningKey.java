import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;

/**
 * 生成 Ed25519 签名密钥对（纯 JDK，无第三方依赖）。
 *
 * <p>由 {@code tools/dev/gen-signing-key.sh} 调用，不要把私钥打到标准输出。
 *
 * <p>参数：{@code <输出目录>}。会在该目录下写：
 * <ul>
 *   <li>{@code ed25519-public-raw.b64} —— 裸 32 字节公钥（文档推荐的紧凑写法，填进 trusted_keys.json）；</li>
 *   <li>{@code ed25519-public-spki.b64} —— X.509 SPKI 公钥（另一种写法，二选一）；</li>
 *   <li>{@code ed25519-private-pkcs8.b64} —— PKCS#8 私钥（<b>只能放在后端</b>，泄露等于谁都能签发时装包）。</li>
 * </ul>
 */
public final class GenSigningKey {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: java GenSigningKey.java <输出目录>");
            System.exit(2);
        }
        java.nio.file.Path dir = java.nio.file.Path.of(args[0]);
        java.nio.file.Files.createDirectories(dir);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pair = generator.generateKeyPair();

        byte[] spki = pair.getPublic().getEncoded();
        // SPKI 的 Ed25519 公钥是最后 32 字节；裸公钥就是它
        byte[] raw = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
        byte[] pkcs8 = pair.getPrivate().getEncoded();

        Base64.Encoder base64 = Base64.getEncoder();
        java.nio.file.Path rawFile = dir.resolve("ed25519-public-raw.b64");
        java.nio.file.Path spkiFile = dir.resolve("ed25519-public-spki.b64");
        java.nio.file.Path privateFile = dir.resolve("ed25519-private-pkcs8.b64");

        java.nio.file.Files.writeString(rawFile, base64.encodeToString(raw));
        java.nio.file.Files.writeString(spkiFile, base64.encodeToString(spki));
        java.nio.file.Files.writeString(privateFile, base64.encodeToString(pkcs8));

        // 只把公钥与文件路径打到 stdout；私钥只落盘，不打印
        System.out.println("PUBLIC_RAW=" + base64.encodeToString(raw));
        System.out.println("PUBLIC_SPKI=" + base64.encodeToString(spki));
        System.out.println("PRIVATE_FILE=" + privateFile.toAbsolutePath());
    }
}
