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
 * <p>目前只内置一个包：
 * <ul>
 *   <li>{@code official_pack} → {@code packs/official/}：官方时装（深渊龙翼），
 *       和网站上发布的包同 ID / 同内容，玩家就算没走服务端下发也会有一份。</li>
 * </ul>
 *
 * <p>v1.2.1 起不再内置示例包（{@code example}：示例背板 + 示例皇冠）。旧版本已经把它释放到
 * 玩家本地的 {@code packs/example/}，所以启动时还会调用 {@link #removeLegacySamplePack()}
 * 把那份清掉 —— 否则光从 jar 里去掉资源，老玩家的衣柜里仍会继续显示两个示例样本。
 *
 * <p>为什么官方时装单独一个包：{@code packId} 会直接当客户端目录名，
 * 且<b>用户包优先级高于服务端下发包</b>。把新时装塞进已经释放过的旧包里，
 * 老玩家本地的旧包会一直盖住服务端下发的新版本 —— 新时装永远看不见。
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
            new BundledPack("assets/vmfashion/official_pack", "official")
    );

    /** 旧版本内置示例包的 packId（{@code default_pack} 的 id）。 */
    private static final String LEGACY_SAMPLE_ID = "example";
    /** 旧版本内置示例包的作者字段，用来确认"这份是我们释放的"而不是玩家自建包。 */
    private static final String LEGACY_SAMPLE_AUTHOR = "VMFashion";

    private DefaultPackInstaller() {
    }

    public static void installIfMissing() {
        // 先清旧版示例包，再释放/更新内置包：顺序反过来也不影响结果，但先清更符合"升级"语义
        removeLegacySamplePack();
        for (BundledPack pack : BUNDLED) {
            installIfMissing(pack);
        }
    }

    /**
     * 删除 v1.2.0 及更早版本释放到 {@code packs/example/} 的示例包。
     *
     * <p>为什么要主动删：内置包落在<b>用户包目录</b>，而用户包优先级最高、永不自动更新，
     * 光从 jar 里去掉示例资源，老玩家本地的 {@code packs/example/} 还在，衣柜里会继续显示
     * "示例背板 / 示例皇冠"。
     *
     * <p>只删「{@code id=example} 且 {@code author=VMFashion}」的清单：玩家自己改过 ID / 作者
     * 的包不碰，避免误删玩家自己的内容。
     */
    public static void removeLegacySamplePack() {
        removeLegacySamplePack(PackLoader.packsDir());
    }

    /**
     * 删除旧示例包的实现（目录可注入，便于单测）。
     *
     * @param packsDir 用户包目录（{@code config/vmfashion/packs}）
     * @return 是否真的删掉了一份旧示例包
     */
    static boolean removeLegacySamplePack(Path packsDir) {
        Path target = packsDir.resolve(LEGACY_SAMPLE_ID);
        Path manifest = target.resolve("pack.json");
        if (!Files.isRegularFile(manifest)) {
            return false;
        }
        JsonObject json = readManifest(manifest);
        if (json == null) {
            // 清单读不出来（被手改坏 / 不是 JSON）就不动，避免误删玩家内容
            return false;
        }
        if (!LEGACY_SAMPLE_ID.equals(optionalString(json, "id"))
                || !LEGACY_SAMPLE_AUTHOR.equalsIgnoreCase(optionalString(json, "author"))) {
            return false;
        }
        try {
            deleteRecursively(target);
            VMFashionClient.LOGGER.info("已删除旧版内置示例时装包 {}", target);
            return true;
        } catch (IOException e) {
            VMFashionClient.LOGGER.warn("删除旧版内置示例时装包失败: {}", e.toString());
            return false;
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
        JsonObject json = readManifest(manifest);
        if (json == null) {
            return "";
        }
        return optionalString(json, "version").trim();
    }

    /** 读 {@code pack.json}；文件不存在 / 不是合法 JSON 对象都返回 {@code null}。 */
    private static JsonObject readManifest(Path manifest) {
        try {
            if (!Files.isRegularFile(manifest)) {
                return null;
            }
            return JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /** 读字符串字段；字段缺失 / 类型不对 / 值是 null 都返回 {@code ""}。 */
    private static String optionalString(JsonObject json, String key) {
        try {
            if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
                return "";
            }
            return json.get(key).getAsString();
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

    /** 递归删除目录（{@code walk} 不跟随符号链接，链接本身按文件删掉）。 */
    private static void deleteRecursively(Path root) throws IOException {
        List<Path> entries;
        try (Stream<Path> stream = Files.walk(root)) {
            entries = stream.sorted(java.util.Comparator.reverseOrder()).toList();
        }
        for (Path entry : entries) {
            Files.deleteIfExists(entry);
        }
    }
}
