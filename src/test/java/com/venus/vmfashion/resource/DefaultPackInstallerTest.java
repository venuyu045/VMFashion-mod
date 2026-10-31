package com.venus.vmfashion.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultPackInstaller} 的版本比较与「要不要覆盖本地内置包」判定。
 *
 * <p>为什么单独测这两个纯函数：真正触发覆盖的路径依赖 {@code FabricLoader}（要起客户端），
 * 但「jar 版本 > 本地版本才覆盖」这条规则本身是纯逻辑，也是最容易写反的地方 ——
 * 写反的后果是玩家每次启动都被覆盖（冲掉手工修改）或者永远不更新（审计 v1.3 R1）。
 */
class DefaultPackInstallerTest {

    @Test
    void compareVersionsHandlesNumericSegments() {
        assertTrue(DefaultPackInstaller.compareVersions("1.0.0", "1.0.0") == 0);
        assertTrue(DefaultPackInstaller.compareVersions("1.1.0", "1.0.0") > 0);
        assertTrue(DefaultPackInstaller.compareVersions("1.0.0", "1.1.0") < 0);
        // 缺段按 0：1.0 与 1.0.0 等价
        assertTrue(DefaultPackInstaller.compareVersions("1.0", "1.0.0") == 0);
        // 按数字比，不是按字符串比（1.10 > 1.9）
        assertTrue(DefaultPackInstaller.compareVersions("1.10.0", "1.9.0") > 0);
        assertTrue(DefaultPackInstaller.compareVersions("2.0.0", "1.99.99") > 0);
    }

    @Test
    void isBundledNewerOnlyWhenVersionAdvances(@TempDir Path dir) throws Exception {
        Path local = writeManifest(dir.resolve("local"), "1.0.0");
        Path bundledNewer = writeManifest(dir.resolve("bundled-newer"), "1.1.0");
        Path bundledSame = writeManifest(dir.resolve("bundled-same"), "1.0.0");
        Path bundledOlder = writeManifest(dir.resolve("bundled-older"), "0.9.0");

        assertTrue(DefaultPackInstaller.isBundledNewer(local, bundledNewer), "jar 版本更新 → 覆盖");
        assertFalse(DefaultPackInstaller.isBundledNewer(local, bundledSame), "版本相同 → 不动");
        assertFalse(DefaultPackInstaller.isBundledNewer(local, bundledOlder), "jar 更旧 → 不降级");
    }

    @Test
    void isBundledNewerNeverTouchesBrokenOrMissingLocalManifest(@TempDir Path dir) throws Exception {
        Path bundled = writeManifest(dir.resolve("bundled"), "2.0.0");
        Path missing = dir.resolve("missing").resolve("pack.json");
        Path broken = dir.resolve("broken").resolve("pack.json");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "{ not json", StandardCharsets.UTF_8);
        Path noVersion = dir.resolve("no-version").resolve("pack.json");
        Files.createDirectories(noVersion.getParent());
        Files.writeString(noVersion, "{\"id\": \"official\"}", StandardCharsets.UTF_8);

        assertFalse(DefaultPackInstaller.isBundledNewer(missing, bundled), "本地没有清单 → 不覆盖（按首次释放处理）");
        assertFalse(DefaultPackInstaller.isBundledNewer(broken, bundled), "本地 JSON 坏了 → 不覆盖");
        assertFalse(DefaultPackInstaller.isBundledNewer(noVersion, bundled), "本地没写 version → 不覆盖");
        // jar 里的清单读不出来时也不该覆盖
        assertFalse(DefaultPackInstaller.isBundledNewer(bundled, missing), "jar 清单缺失 → 不覆盖");
    }

    // ==================== 旧版示例包清理（v1.2.1） ====================

    @Test
    void removesLegacyBundledSamplePack(@TempDir Path dir) throws Exception {
        Path packs = dir.resolve("packs");
        Path sample = packs.resolve("example");
        writeSampleManifest(sample, "{\"id\": \"example\", \"author\": \"VMFashion\", \"version\": \"1.0.0\"}");
        Files.createDirectories(sample.resolve("cosmetics/crown"));
        Files.writeString(sample.resolve("cosmetics/crown/model.geo.json"), "{}", StandardCharsets.UTF_8);

        assertTrue(DefaultPackInstaller.removeLegacySamplePack(packs), "旧版内置示例包应当被删除");
        assertFalse(Files.exists(sample), "示例包目录应被整个删掉");
    }

    @Test
    void keepsPlayerOwnedOrBrokenExamplePack(@TempDir Path dir) throws Exception {
        Path packs = dir.resolve("packs");
        Path modified = packs.resolve("example");
        // 玩家改过作者的包不是"我们释放的那份"，不能删
        writeSampleManifest(modified, "{\"id\": \"example\", \"author\": \"someone-else\"}");
        assertFalse(DefaultPackInstaller.removeLegacySamplePack(packs));
        assertTrue(Files.exists(modified.resolve("pack.json")));

        // 清单坏掉时也不动（避免误删玩家内容）
        Files.writeString(modified.resolve("pack.json"), "{ not json", StandardCharsets.UTF_8);
        assertFalse(DefaultPackInstaller.removeLegacySamplePack(packs));
        assertTrue(Files.exists(modified.resolve("pack.json")));
    }

    @Test
    void ignoresMissingLegacySamplePack(@TempDir Path dir) {
        assertFalse(DefaultPackInstaller.removeLegacySamplePack(dir.resolve("packs")));
    }

    private static void writeSampleManifest(Path dir, String json) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pack.json"), json, StandardCharsets.UTF_8);
    }

    private static Path writeManifest(Path dir, String version) throws Exception {
        Files.createDirectories(dir);
        Path manifest = dir.resolve("pack.json");
        Files.writeString(manifest,
                "{\"formatVersion\": 1, \"id\": \"official\", \"version\": \"" + version + "\"}",
                StandardCharsets.UTF_8);
        return manifest;
    }
}
