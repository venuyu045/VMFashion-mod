package com.venus.vmfashion.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 扫描并解析时装包。
 *
 * <p>目录结构：
 * <pre>
 * config/vmfashion/packs/&lt;packId&gt;/
 *   pack.json                 # 包元数据；cosmetics 数组可选（声明式），缺省自动扫描目录
 *   cosmetics/&lt;cosmeticId&gt;/
 *     meta.json               # 可选，单件时装的元数据（字段与 pack.json 的 cosmetic entry 相同）
 *     model.geo.json          # Bedrock 几何
 *     texture.png             # 贴图
 *     emissive.png            # 可选发光层
 *     anim.json               # 可选 Bedrock 动画（也接受 *.anim.json / *.animation.json）
 * </pre>
 *
 * <p>解析过程绝不抛出异常给调用方：单个包/单件时装出错只记录警告并跳过，
 * 保证一个坏包不会让整个 mod 不可用。
 *
 * <p><b>元数据优先级</b>（低 → 高）：目录内容自动推断（分类猜名字、贴图/动画按文件名发现）
 * → {@code pack.json} 里 cosmetics 数组按 id 的声明项 → 单件目录下的 {@code meta.json}。
 * 这样即使 {@code pack.json} 完全不写 cosmetics 数组（只有目录），
 * hideParts / scale / attachedBone / category / name 也能从 meta.json 拿到，而不是被硬编码成默认值。
 */
public final class PackLoader {

    /** pack.json 的格式版本，将来结构变化时用来做兼容。 */
    public static final int FORMAT_VERSION = 1;

    /** 单件时装目录下的可选元数据文件名。 */
    private static final String META_FILE_NAME = "meta.json";
    /** 存放各件时装的子目录名。 */
    private static final String COSMETICS_DIR_NAME = "cosmetics";
    /** 发光层贴图的标准文件名；自动发现主贴图时要排除它。 */
    private static final String EMISSIVE_FILE_NAME = "emissive.png";
    /** 服务端自动下发包的命名空间目录名（{@code packs/server/<packId>/}）。 */
    public static final String SERVER_DIR_NAME = "server";
    /**
     * 单个模型文件（{@code *.geo.json}）的大小上限：4MB。
     *
     * <p>正常 Blockbench 导出远小于 1MB；解压侧的单文件上限是 64MB，一个"合法但灾难性"的
     * 几何文件足以让渲染线程烘爆堆，所以这里单独设一道闸（audit v1.2 N06）。
     */
    static final long MAX_MODEL_BYTES = 4L * 1024 * 1024;

    private PackLoader() {
    }

    /** 时装包根目录。 */
    public static Path packsDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("vmfashion").resolve("packs");
    }

    /**
     * 服务端自动下发的时装包目录（{@code packs/server/<packId>/}）。
     *
     * <p>为什么要单独一层命名空间：自动下发的包是**远程数据**，必须和玩家自己放进去的包
     * 隔离 —— 否则 {@code /vmfashion cache clear}、LRU 淘汰、包版本升级都可能误删玩家的手工包。
     * 约定：用户包（{@code packs/<packId>/}）优先，同一个 packId 同时存在时保用户包。
     */
    public static Path serverPacksDir() {
        return packsDir().resolve(SERVER_DIR_NAME);
    }

    /** 加载结果：成功的包 + 警告信息。 */
    public record LoadResult(List<CosmeticPack> packs, List<String> warnings) {

        public int cosmeticCount() {
            int total = 0;
            for (CosmeticPack pack : packs) {
                total += pack.cosmetics().size();
            }
            return total;
        }
    }

    /**
     * 扫描并加载全部时装包，同时刷新 {@link CosmeticRegistry}。
     *
     * <p>内部委托给 {@link #reload(Path)}，只是把根目录换成 {@link #packsDir()}。
     */
    public static LoadResult reload() {
        return reload(packsDir());
    }

    /**
     * 扫描指定根目录下的全部时装包，并刷新 {@link CosmeticRegistry}。
     *
     * <p>单独暴露一个接受 {@link Path} 的重载，是为了让单元测试能在临时目录里跑
     * （解析逻辑本身不依赖 {@code FabricLoader}）。
     *
     * @param root 时装包根目录（不存在时会尝试创建）
     */
    public static LoadResult reload(Path root) {
        List<String> warnings = new ArrayList<>();
        List<CosmeticPack> packs = new ArrayList<>();
        if (root == null) {
            warnings.add("时装包根目录为空，已跳过加载");
            CosmeticRegistry.getInstance().replaceAll(List.of());
            return new LoadResult(List.of(), warnings);
        }

        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            warnings.add("无法创建时装包目录: " + e.getMessage());
            CosmeticRegistry.getInstance().replaceAll(List.of());
            return new LoadResult(List.of(), warnings);
        }

        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    // server/ 是自动下发的命名空间，它自己不是包，里面才是
                    .filter(dir -> !SERVER_DIR_NAME.equals(dir.getFileName().toString()))
                    .sorted()
                    .forEach(candidates::add);
        } catch (IOException e) {
            warnings.add("读取时装包目录失败: " + e.getMessage());
        }
        // 用户包先入列（排序在前），同 packId 时由下面的 seenPackIds 保证用户包胜出；
        // 服务端下发的包排在后面，被覆盖时只留一条警告。
        Path serverRoot = root.resolve(SERVER_DIR_NAME);
        if (Files.isDirectory(serverRoot)) {
            try (Stream<Path> stream = Files.list(serverRoot)) {
                stream.filter(Files::isDirectory)
                        .filter(dir -> !Files.isSymbolicLink(dir))
                        .sorted()
                        .forEach(candidates::add);
            } catch (IOException e) {
                warnings.add("读取服务端时装包目录失败: " + e.getMessage());
            }
        }

        Set<String> seenPackIds = new LinkedHashSet<>();
        for (Path dir : candidates) {
            try {
                CosmeticPack pack = loadPack(root, dir, warnings);
                if (pack != null && !pack.cosmetics().isEmpty()) {
                    if (!seenPackIds.add(pack.id().toLowerCase(Locale.ROOT))) {
                        warnings.add("重复的 packId=" + pack.id() + "（目录 " + dir.getFileName() + "），已跳过");
                        continue;
                    }
                    packs.add(pack);
                }
            } catch (Exception e) {
                warnings.add("加载包 " + dir.getFileName() + " 失败: " + e.getMessage());
            }
        }

        CosmeticRegistry.getInstance().replaceAll(packs);
        return new LoadResult(List.copyOf(packs), List.copyOf(warnings));
    }

    // ==================== 单包解析 ====================

    private static CosmeticPack loadPack(Path root, Path dir, List<String> warnings) throws IOException {
        // 拒绝指向外部目录的符号链接（避免恶意包读取任意位置）
        try {
            Path rootReal = root.toRealPath();
            if (!dir.toRealPath().startsWith(rootReal)) {
                warnings.add("包目录是越界符号链接，已跳过: " + dir.getFileName());
                return null;
            }
        } catch (IOException e) {
            warnings.add("无法解析包目录: " + dir.getFileName());
            return null;
        }
        Path manifestPath = dir.resolve("pack.json");
        if (!Files.isRegularFile(manifestPath)) {
            // 没有 pack.json 的目录直接忽略（例如用户放了一半的包）
            return null;
        }

        JsonObject manifest = readJsonObject(manifestPath);
        if (manifest == null) {
            warnings.add(dir.getFileName() + "/pack.json 解析失败或不是 JSON 对象，已跳过");
            return null;
        }

        int formatVersion = optInt(manifest, "formatVersion", FORMAT_VERSION);
        if (formatVersion > FORMAT_VERSION) {
            warnings.add(dir.getFileName() + " 的 formatVersion=" + formatVersion
                    + " 高于本 mod 支持的 " + FORMAT_VERSION + "，可能有字段无法识别");
        }

        String packId = optString(manifest, "id", dir.getFileName().toString());
        String packName = optString(manifest, "name", packId);
        String version = optString(manifest, "version", "1.0.0");
        String author = optString(manifest, "author", "unknown");
        String license = optString(manifest, "license", "unknown");

        List<CosmeticDefinition> cosmetics = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        Map<String, JsonObject> declared = declaredEntries(packId, manifest, warnings);
        List<Path> entryDirs = cosmeticDirs(packId, dir, warnings);

        if (declared.isEmpty() && entryDirs.isEmpty()) {
            warnings.add(packId + ": 既没有 cosmetics 列表，也没有 cosmetics/ 目录");
        }

        // ① 先按 pack.json 的声明顺序建时装（保证 registry 顺序稳定、显式声明优先）
        for (Map.Entry<String, JsonObject> entry : declared.entrySet()) {
            String declaredId = entry.getKey();
            Path entryDir = findEntryDir(dir, declaredId);
            // meta.json 比 pack.json 的声明项优先级更高（局部覆盖全局）
            JsonObject meta = mergeMeta(entry.getValue(), readMeta(entryDir, packId, warnings));
            CosmeticDefinition definition =
                    buildDefinition(dir, packId, entryDir, meta, declaredId, warnings);
            if (definition == null) {
                continue;
            }
            if (!seenIds.add(definition.localId().toLowerCase(Locale.ROOT))) {
                warnings.add(packId + ": 时装 ID 重复 " + definition.localId() + "，已跳过");
                continue;
            }
            cosmetics.add(definition);
        }

        // ② 再扫描 cosmetics/ 目录，把没被声明覆盖的目录也当时装（自动发现）
        for (Path entryDir : entryDirs) {
            String dirName = entryDir.getFileName().toString();
            if (seenIds.contains(dirName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            JsonObject meta = readMeta(entryDir, packId, warnings);
            CosmeticDefinition definition =
                    buildDefinition(dir, packId, entryDir, meta, dirName, warnings);
            if (definition == null) {
                continue;
            }
            if (!seenIds.add(definition.localId().toLowerCase(Locale.ROOT))) {
                warnings.add(packId + ": 时装 ID 重复 " + definition.localId() + "，已跳过");
                continue;
            }
            cosmetics.add(definition);
        }

        if (cosmetics.isEmpty()) {
            warnings.add(packId + ": 没有解析出任何时装");
        }
        return new CosmeticPack(packId, packName, version, author, license, dir, cosmetics);
    }

    /**
     * 把一条元数据（pack.json 的 cosmetic entry，或单件目录下的 meta.json）解析成时装定义。
     *
     * <p>所有字段都是可选的：缺省时按 {@code entryDir} 里的文件名自动发现，
     * 再缺省才退回分类默认值 —— 这正是 #14 的修复点（旧实现自动发现时硬编码 {@code Set.of(), 1.0f}）。
     *
     * @param packDir  包根目录（所有相对路径的基准）
     * @param entryDir 该件时装的目录，可为 {@code null}（声明式包可以把资源放在任意位置）
     * @param meta     元数据，可为 {@code null}
     * @param fallbackId 元数据里没有 id 时使用的 ID（目录名或声明 id）
     */
    private static CosmeticDefinition buildDefinition(Path packDir, String packId, Path entryDir,
                                                      JsonObject meta, String fallbackId,
                                                      List<String> warnings) {
        String localId = optString(meta, "id", fallbackId == null ? "" : fallbackId).trim();
        if (localId.isBlank()) {
            warnings.add(packId + ": 有一件时装缺少 id，已跳过");
            return null;
        }
        if (!isSafeLocalId(localId)) {
            // 远程 pack.json 声明的 id 会拼进文件系统路径与 cosmeticId：含 / \ 或 .. 一律拒绝
            // （audit v1.2 N13）
            warnings.add(packId + ": 时装 id 含非法字符（不允许 / \\ 或 ..）: " + localId + "，已跳过");
            return null;
        }
        String context = packId + "/" + localId;

        Path model = resolveSafe(packDir, optString(meta, "model", ""));
        if (model == null && entryDir != null) {
            model = findFirst(entryDir, ".geo.json");
        }
        if (model == null || !Files.isRegularFile(model)) {
            warnings.add(context + ": 模型文件不存在");
            return null;
        }
        long modelBytes = fileSizeOrZero(model);
        if (isModelFileTooLarge(modelBytes)) {
            // 解压上限只卡字节数，一个 64MB 的 geo.json 能在渲染线程上烘爆堆（audit v1.2 N06）
            warnings.add(context + ": 模型文件过大（" + modelBytes + " 字节，上限 "
                    + MAX_MODEL_BYTES + "），已跳过");
            return null;
        }

        Path texture = resolveSafe(packDir, optString(meta, "texture", ""));
        if (texture == null && entryDir != null) {
            texture = standardFile(entryDir, "texture.png");
            if (texture == null) {
                // 退回到目录里第一个 .png，但要排除 emissive.png，
                // 否则发光层会被当成主贴图（模型整体走发光色）
                texture = findFirst(entryDir, name -> name.endsWith(".png")
                        && !name.equals(EMISSIVE_FILE_NAME));
            }
        }
        if (texture == null || !Files.isRegularFile(texture)) {
            warnings.add(context + ": 贴图文件不存在");
            return null;
        }

        Path emissive = resolveSafe(packDir, optString(meta, "emissive", ""));
        if (emissive == null && entryDir != null) {
            emissive = standardFile(entryDir, EMISSIVE_FILE_NAME);
        }
        if (emissive != null && !Files.isRegularFile(emissive)) {
            emissive = null;
        }

        Path animation = resolveSafe(packDir, optString(meta, "animation", ""));
        if (animation == null && entryDir != null) {
            // #11：anim.json 自身不满足 endsWith(".anim.json")，必须显式接受裸文件名，
            // 同时兼容 xxx.animation.json（Blockbench 默认导出名）
            animation = findFirst(entryDir, PackLoader::isAnimationFileName);
        }
        if (animation != null && !Files.isRegularFile(animation)) {
            animation = null;
        }

        if (!looksLikeGeometry(model)) {
            warnings.add(context + ": 模型不是有效的 Bedrock 几何（缺少 minecraft:geometry）");
        }

        String categoryKey = optString(meta, "category", "");
        CosmeticCategory category = categoryKey.isBlank()
                ? CosmeticCategory.byKey(guessCategory(localId))
                : CosmeticCategory.byKey(categoryKey);
        String bone = optString(meta, "attachedBone", category.defaultBone()).toLowerCase(Locale.ROOT);
        Set<String> hideParts = optStringSet(meta, "hideParts");
        float scale = (float) optDouble(meta, "scale", 1.0);
        // 恶意/手滑写成 NaN、Infinity、负数会让 PoseStack 矩阵失效，直接回退默认值
        if (!Float.isFinite(scale) || scale <= 0.0F) {
            warnings.add(context + ": scale 非法（" + scale + "），已回退 1.0");
            scale = 1.0F;
        }
        scale = Math.max(0.01F, Math.min(100.0F, scale));

        // 材质：material 优先（cutout / translucent），兼容老写法 "translucent": true。
        // 只有这两档：cutout 是 alpha 二值（默认，绝大多数时装），translucent 才真混合。
        boolean translucent = false;
        String material = optString(meta, "material", "").trim().toLowerCase(Locale.ROOT);
        if (!material.isEmpty()) {
            if ("translucent".equals(material) || "blend".equals(material)) {
                translucent = true;
            } else if (!"cutout".equals(material) && !"opaque".equals(material)) {
                warnings.add(context + ": material 只支持 cutout/translucent，已回退 cutout（" + material + "）");
            }
        } else if (meta != null && meta.has("translucent") && !meta.get("translucent").isJsonNull()) {
            try {
                translucent = meta.get("translucent").getAsBoolean();
            } catch (Exception ignored) {
                // 非布尔值：按默认 cutout 处理，不额外告警（上面 material 才是推荐写法）
            }
        }

        // 动画名：一件时装里有多个动画时选播哪个；空 = 播第一个（保持旧行为）
        String animationName = optString(meta, "animationName", "").trim();

        return new CosmeticDefinition(packId, localId, optString(meta, "name", localId), category,
                model, texture, emissive, animation, bone, hideParts, scale, translucent, animationName);
    }

    // ==================== 目录扫描 ====================

    /** pack.json 的 cosmetics 数组：小写 id → 声明项（保持声明顺序）。 */
    private static Map<String, JsonObject> declaredEntries(String packId, JsonObject manifest,
                                                           List<String> warnings) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        if (!manifest.has("cosmetics") || !manifest.get("cosmetics").isJsonArray()) {
            return result;
        }
        JsonArray list = manifest.getAsJsonArray("cosmetics");
        for (JsonElement element : list) {
            if (!element.isJsonObject()) {
                warnings.add(packId + ": cosmetics 数组里出现非对象元素，已跳过");
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String id = optString(entry, "id", "").trim();
            if (id.isBlank()) {
                warnings.add(packId + ": 有一件时装缺少 id，已跳过");
                continue;
            }
            if (result.putIfAbsent(id.toLowerCase(Locale.ROOT), entry) != null) {
                warnings.add(packId + ": cosmetics 数组里 id 重复 " + id + "，后者已忽略");
            }
        }
        return result;
    }

    /** cosmetics/ 下的各件时装目录（排序后返回，保证解析顺序稳定）。 */
    private static List<Path> cosmeticDirs(String packId, Path packDir, List<String> warnings) {
        List<Path> result = new ArrayList<>();
        Path cosmeticsRoot = packDir.resolve(COSMETICS_DIR_NAME);
        if (!Files.isDirectory(cosmeticsRoot)) {
            return result;
        }
        try {
            if (!cosmeticsRoot.toRealPath().startsWith(packDir.toRealPath())) {
                warnings.add(packId + ": cosmetics/ 是越界符号链接，已跳过");
                return result;
            }
        } catch (IOException e) {
            warnings.add(packId + ": 无法解析 cosmetics/ 目录: " + e.getMessage());
            return result;
        }
        try (Stream<Path> stream = Files.list(cosmeticsRoot)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted()
                    .forEach(result::add);
        } catch (IOException e) {
            warnings.add(packId + ": 扫描 cosmetics/ 失败: " + e.getMessage());
        }
        return result;
    }

    /** 声明项对应的单件目录；不存在时返回 {@code null}（资源路径全部由声明项给出）。 */
    private static Path findEntryDir(Path packDir, String localId) {
        if (!isSafeLocalId(localId)) {
            return null; // 含 / \ 或 .. 的声明 id 不能拼进文件系统路径（audit v1.2 N13）
        }
        Path candidate = packDir.resolve(COSMETICS_DIR_NAME).resolve(localId);
        if (!Files.isDirectory(candidate) || Files.isSymbolicLink(candidate)) {
            return null;
        }
        return candidate;
    }

    /**
     * 时装 id 是否可安全用于"目录名 / cosmeticId 片段"。
     *
     * <p>只拒绝危险字符（路径分隔符、NUL、{@code .}/{@code ..}），其余（中文、空格、点）全部放行 ——
     * 这是"黑名单"而不是白名单，不会破坏既有的用户自制包（audit v1.2 N13）。
     */
    static boolean isSafeLocalId(String localId) {
        if (localId == null || localId.isBlank()) {
            return false;
        }
        if (localId.indexOf('/') >= 0 || localId.indexOf('\\') >= 0 || localId.indexOf('\0') >= 0) {
            return false;
        }
        return !".".equals(localId) && !"..".equals(localId);
    }

    /** 读取单件目录下的 meta.json；不存在返回 {@code null}，损坏时记警告并忽略。 */
    private static JsonObject readMeta(Path entryDir, String packId, List<String> warnings) {
        if (entryDir == null) {
            return null;
        }
        Path metaPath = entryDir.resolve(META_FILE_NAME);
        if (!Files.isRegularFile(metaPath) || Files.isSymbolicLink(metaPath)) {
            return null;
        }
        JsonObject meta = readJsonObject(metaPath);
        if (meta == null) {
            warnings.add(packId + "/" + entryDir.getFileName() + ": meta.json 解析失败，已忽略");
        }
        return meta;
    }

    /** overlay 的字段覆盖 base（meta.json 覆盖 pack.json 声明项）。 */
    private static JsonObject mergeMeta(JsonObject base, JsonObject overlay) {
        if (overlay == null || overlay.isEmpty()) {
            return base == null ? new JsonObject() : base;
        }
        JsonObject merged = base == null ? new JsonObject() : base.deepCopy();
        overlay.entrySet().forEach(field -> merged.add(field.getKey(), field.getValue()));
        return merged;
    }

    // ==================== 工具 ====================

    /**
     * 解析相对路径，并保证结果不会逃出包目录（防止恶意包读到系统其它文件）。
     */
    private static Path resolveSafe(Path root, String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        try {
            Path rootNormalized = root.toRealPath();
            Path resolved = root.resolve(relative).normalize();
            if (!resolved.startsWith(root.normalize())) {
                return null;
            }
            if (Files.exists(resolved)) {
                Path real = resolved.toRealPath();
                if (!real.startsWith(rootNormalized)) {
                    return null;
                }
                return real;
            }
            return resolved;
        } catch (Exception e) {
            return null;
        }
    }

    /** 目录下指定的标准文件名（必须是普通文件且不是符号链接）。 */
    private static Path standardFile(Path dir, String fileName) {
        Path candidate = dir.resolve(fileName);
        if (!Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate)) {
            return null;
        }
        return candidate;
    }

    private static Path findFirst(Path dir, String suffix) {
        return findFirst(dir, name -> name.endsWith(suffix));
    }

    /**
     * 目录下第一个匹配的文件（名字已转小写）。
     *
     * <p>用谓词而不是后缀，是因为动画文件名有好几种合法写法，
     * 而 {@code "anim.json".endsWith(".anim.json") == false} 正是 #11 的根因。
     */
    private static Path findFirst(Path dir, Predicate<String> nameFilter) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> nameFilter.test(path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 判断文件名是否是 Bedrock 动画文件。
     *
     * <p>接受三种写法：{@code anim.json}（裸名）、{@code *.anim.json}、
     * {@code *.animation.json}（Blockbench 默认导出名）。
     */
    static boolean isAnimationFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.equals("anim.json")
                || name.endsWith(".anim.json")
                || name.endsWith(".animation.json");
    }

    private static boolean looksLikeGeometry(Path model) {
        JsonObject json = readJsonObject(model);
        return json != null && json.has("minecraft:geometry");
    }

    /** 模型文件是否超过 {@link #MAX_MODEL_BYTES}（纯逻辑，可单测，audit v1.2 N06）。 */
    static boolean isModelFileTooLarge(long bytes) {
        return bytes > MAX_MODEL_BYTES;
    }

    /** 文件大小；读不到按 0 处理（后面的 {@code isRegularFile} 已经保证文件存在）。 */
    private static long fileSizeOrZero(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 读一个 JSON 对象；文件不存在 / 不是对象 / 解析失败都返回 {@code null}（不抛异常）。 */
    private static JsonObject readJsonObject(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String guessCategory(String localId) {
        String id = localId.toLowerCase(Locale.ROOT);
        if (id.contains("wing")) {
            return "wings";
        }
        if (id.contains("cape") || id.contains("cloak")) {
            return "cape";
        }
        if (id.contains("hat") || id.contains("helm") || id.contains("halo")) {
            return "hat";
        }
        if (id.contains("pet")) {
            return "pet";
        }
        return "other";
    }

    private static String optString(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int optInt(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double optDouble(JsonObject object, String key, double fallback) {
        try {
            return object.has(key) ? object.get(key).getAsDouble() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Set<String> optStringSet(JsonObject object, String key) {
        Set<String> result = new LinkedHashSet<>();
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return result;
        }
        for (JsonElement element : object.getAsJsonArray(key)) {
            try {
                result.add(element.getAsString().toLowerCase(Locale.ROOT));
            } catch (Exception ignored) {
                // 忽略非法元素
            }
        }
        return result;
    }
}
