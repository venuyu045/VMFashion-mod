package com.venus.vmfashion.resource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.cosmetic.PackLoader;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 首次启动时把 mod 内置的时装包释放到 {@code config/vmfashion/packs/} 下。
 *
 * <p>目前内置两个包：
 * <ul>
 *   <li>{@code default_pack} → {@code packs/example/}：示例背板 + 示例皇冠，用来验证整条渲染链路；</li>
 *   <li>{@code official_pack} → {@code packs/official/}：官方时装（深渊龙翼），
 *       和网站上发布的包同 ID / 同内容，玩家就算没走服务端下发也会有一份。</li>
 * </ul>
 *
 * <p>为什么官方时装单独一个包、而不是塞进示例包：{@code packId} 会直接当客户端目录名，
 * 且<b>用户包优先级高于服务端下发包</b>。把新时装塞进已经释放过的 {@code example} 包里，
 * 老玩家本地的旧 {@code example} 会一直盖住服务端下发的新版本 —— 新时装永远看不见。
 * 单独一个 {@code official} 包对「本地还没有它」的玩家不存在这个坑。
 *
 * <p>包本体放在 jar 的 {@code assets/vmfashion/<pack>/} 下（用 {@code findPath} 读，
 * dev 环境和正式 jar 都能用），这样即使没有任何真实资产，也能验证整条渲染链路。
 *
 * <p><b>释放规则</b>：目录不存在 → 整包释放；已存在 → 只有 jar 里的 {@code version}
 * 比本地 {@code pack.json} 的版本<b>严格更新</b>时才覆盖（见 {@link #isBundledNewer}）。
 * 本地版本号读不出来（被手改坏）一律不动，避免冲掉玩家自己的修改。
 * 这条「按版本更新」是必要的：内置包落在<b>用户包目录</b>里，而
 * {@code AssetSyncService} 对用户包是「永不下载、永不更新」，否则官方包以后在网站上升级、
 * 玩家更新了 jar 也永远拿不到新内容（审计 v1.3 R1）。
 */
public final class DefaultPackInstaller {

    /** 内置包：{@code resource} 是 jar 里的资源目录，{@code target} 是释放到 packs/ 下的目录名。 */
    private record BundledPack(String resource, String target) {
    }

    private static final List<BundledPack> BUNDLED = List.of(
            new BundledPack("assets/vmfashion/default_pack", "example"),
            new BundledPack("assets/vmfashion/official_pack", "official")
    );

    private DefaultPackInstaller() {
    }

    public static void installIfMissing() {
        for (BundledPack pack : BUNDLED) {
            installIfMissing(pack);
        }
    }

    private static void installIfMissing(BundledPack pack) {
        Path target = PackLoader.packsDir().resolve(pack.target());
        Path localManifest = target.resolve("pack.json");
        boolean exists = Files.isRegularFile(localManifest);

        Optional<Path> source = FabricLoader.getInstance()
                .getModContainer(VMFashionClient.MOD_ID)
                .flatMap(container -> container.findPath(pack.resource()));
        if (source.isEmpty()) {
            VMFashionClient.LOGGER.warn("找不到内置时装包（{}），跳过释放", pack.resource());
            return;
        }

        if (exists && !isBundledNewer(localManifest, source.get().resolve("pack.json"))) {
            return;
        }

        try {
            copyRecursively(source.get(), target);
            VMFashionClient.LOGGER.info("已{}内置时装包 {} 到 {}",
                    exists ? "更新" : "释放", pack.resource(), target);
        } catch (IOException e) {
            VMFashionClient.LOGGER.warn("释放内置时装包 {} 失败: {}", pack.resource(), e.toString());
        }
    }

    /**
     * 本地已有一份内置包时，jar 里的版本是否更新（决定要不要覆盖）。
     *
     * <p>为什么需要这一步：内置包被释放到<b>用户包目录</b>，而用户包在
     * {@code AssetSyncService} 里是「永不下载、永不更新」的 —— 服务端下发同名包会被本地这份
     * 一直盖住。也就是说，如果只做「不存在才释放」，官方时装包以后在网站上升级，
     * 装了新 jar 的玩家也永远拿不到新内容（审计 R1）。所以这里比较两边 {@code pack.json}
     * 的 {@code version}，jar 里的更新才覆盖。
     *
     * <p>覆盖只发生在「jar 版本严格更新」时：本地版本号读不出来（用户手改坏了 / 不是 JSON）
     * 一律<b>不动</b>，避免把玩家的手工修改冲掉。
     */
    static boolean isBundledNewer(Path localManifest, Path bundledManifest) {
        String local = readVersion(localManifest);
        String bundled = readVersion(bundledManifest);
        if (local.isEmpty() || bundled.isEmpty()) {
            return false;
        }
        return compareVersions(bundled, local) > 0;
    }

    private static String readVersion(Path manifest) {
        try {
            if (!Files.isRegularFile(manifest)) {
                return "";
            }
            JsonObject json = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return json.has("version") ? json.get("version").getAsString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 比较两个版本号（按 {@code .} 切段、逐段按数字比，缺段按 0；非数字段按字符串比）。
     *
     * @return 负数 = {@code a} 更旧；0 = 相等；正数 = {@code a} 更新
     */
    static int compareVersions(String a, String b) {
        String[] left = a.trim().split("\\.");
        String[] right = b.trim().split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            String l = i < left.length ? left[i].trim() : "0";
            String r = i < right.length ? right[i].trim() : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(l), Integer.parseInt(r));
            } catch (NumberFormatException e) {
                cmp = l.compareTo(r);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        List<Path> entries;
        try (Stream<Path> stream = Files.walk(source)) {
            entries = stream.toList();
        }
        for (Path entry : entries) {
            Path relative = source.relativize(entry);
            Path destination = target.resolve(relative.toString());
            if (Files.isDirectory(entry)) {
                Files.createDirectories(destination);
                continue;
            }
            Files.createDirectories(destination.getParent());
            try (InputStream stream = Files.newInputStream(entry)) {
                Files.copy(stream, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
