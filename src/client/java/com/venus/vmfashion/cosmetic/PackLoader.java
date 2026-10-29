package com.venus.vmfashion.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.VMFashionClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 扫描并解析时装包。
 *
 * <p>目录结构：
 * <pre>
 * config/vmfashion/packs/&lt;packId&gt;/
 *   pack.json                 # 包元数据（可选 cosmetics 列表，缺省自动扫描）
 *   cosmetics/&lt;cosmeticId&gt;/
 *     model.geo.json          # Bedrock 几何
 *     texture.png             # 贴图
 *     emissive.png            # 可选发光层
 *     anim.json               # 可选 Bedrock 动画
 * </pre>
 *
 * <p>解析过程绝不抛出异常给调用方：单个包/单件时装出错只记录警告并跳过，
 * 保证一个坏包不会让整个 mod 不可用。
 */
public final class PackLoader {

    /** pack.json 的格式版本，将来结构变化时用来做兼容。 */
    public static final int FORMAT_VERSION = 1;

    private PackLoader() {
    }

    /** 时装包根目录。 */
    public static Path packsDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("vmfashion").resolve("packs");
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
     */
    public static LoadResult reload() {
        List<String> warnings = new ArrayList<>();
        List<CosmeticPack> packs = new ArrayList<>();
        Path root = packsDir();

        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            warnings.add("无法创建时装包目录: " + e.getMessage());
            CosmeticRegistry.getInstance().replaceAll(List.of());
            return new LoadResult(List.of(), warnings);
        }

        List<Path> candidates = new ArrayList<>();
        Set<String> seenPackIds = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .sorted()
                    .forEach(candidates::add);
        } catch (IOException e) {
            warnings.add("读取时装包目录失败: " + e.getMessage());
        }

        for (Path dir : candidates) {
            try {
                CosmeticPack pack = loadPack(dir, warnings);
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

    private static CosmeticPack loadPack(Path dir, List<String> warnings) throws IOException {
        // 拒绝 config/packs 下指向外部目录的符号链接（避免恶意包读取任意位置）
        try {
            Path rootReal = packsDir().toRealPath();
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

        JsonObject manifest;
        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                warnings.add(dir.getFileName() + "/pack.json 不是 JSON 对象，已跳过");
                return null;
            }
            manifest = parsed.getAsJsonObject();
        } catch (Exception e) {
            warnings.add(dir.getFileName() + "/pack.json 解析失败: " + e.getMessage());
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

        JsonArray list = manifest.has("cosmetics") && manifest.get("cosmetics").isJsonArray()
                ? manifest.getAsJsonArray("cosmetics")
                : null;

        if (list != null) {
            for (JsonElement element : list) {
                if (!element.isJsonObject()) {
                    warnings.add(packId + ": cosmetics 数组里出现非对象元素，已跳过");
                    continue;
                }
                CosmeticDefinition definition = parseEntry(dir, packId, element.getAsJsonObject(), warnings);
                if (definition == null) {
                    continue;
                }
                if (!seenIds.add(definition.localId())) {
                    warnings.add(packId + ": 时装 ID 重复 " + definition.localId() + "，已跳过");
                    continue;
                }
                cosmetics.add(definition);
            }
        } else {
            cosmetics.addAll(autoDiscover(dir, packId, warnings));
        }

        if (cosmetics.isEmpty()) {
            warnings.add(packId + ": 没有解析出任何时装");
        }
        return new CosmeticPack(packId, packName, version, author, license, dir, cosmetics);
    }

    /** 解析显式声明的单件时装。 */
    private static CosmeticDefinition parseEntry(Path dir, String packId, JsonObject entry, List<String> warnings) {
        String localId = optString(entry, "id", "");
        if (localId.isBlank()) {
            warnings.add(packId + ": 有一件时装缺少 id，已跳过");
            return null;
        }

        Path model = resolveSafe(dir, optString(entry, "model", "cosmetics/" + localId + "/model.geo.json"));
        if (model == null || !Files.isRegularFile(model)) {
            warnings.add(packId + "/" + localId + ": 模型文件不存在");
            return null;
        }
        Path texture = resolveSafe(dir, optString(entry, "texture", "cosmetics/" + localId + "/texture.png"));
        if (texture == null || !Files.isRegularFile(texture)) {
            warnings.add(packId + "/" + localId + ": 贴图文件不存在");
            return null;
        }

        Path emissive = resolveSafe(dir, optString(entry, "emissive", ""));
        if (emissive != null && !Files.isRegularFile(emissive)) {
            emissive = null;
        }
        Path animation = resolveSafe(dir, optString(entry, "animation", ""));
        if (animation != null && !Files.isRegularFile(animation)) {
            animation = null;
        }

        if (!looksLikeGeometry(model)) {
            warnings.add(packId + "/" + localId + ": 模型不是有效的 Bedrock 几何（缺少 minecraft:geometry）");
        }

        CosmeticCategory category = CosmeticCategory.byKey(optString(entry, "category", "other"));
        String bone = optString(entry, "attachedBone", category.defaultBone()).toLowerCase(Locale.ROOT);
        Set<String> hideParts = optStringSet(entry, "hideParts");
        float scale = (float) optDouble(entry, "scale", 1.0);
        // 恶意/手滑写成 NaN、Infinity、负数会让 PoseStack 矩阵失效，直接回退默认值
        if (!Float.isFinite(scale) || scale <= 0.0F) {
            warnings.add(packId + "/" + localId + ": scale 非法（" + scale + "），已回退 1.0");
            scale = 1.0F;
        }
        scale = Math.max(0.01F, Math.min(100.0F, scale));

        return new CosmeticDefinition(packId, localId, optString(entry, "name", localId), category,
                model, texture, emissive, animation, bone, hideParts, scale);
    }

    /** 没有 cosmetics 列表时，按目录结构自动发现。 */
    private static List<CosmeticDefinition> autoDiscover(Path dir, String packId, List<String> warnings) {
        List<CosmeticDefinition> result = new ArrayList<>();
        Path cosmeticsRoot = dir.resolve("cosmetics");
        if (!Files.isDirectory(cosmeticsRoot)) {
            warnings.add(packId + ": 既没有 cosmetics 列表，也没有 cosmetics/ 目录");
            return result;
        }
        try {
            if (!cosmeticsRoot.toRealPath().startsWith(dir.toRealPath())) {
                warnings.add(packId + ": cosmetics/ 是越界符号链接，已跳过");
                return result;
            }
        } catch (IOException e) {
            warnings.add(packId + ": 无法解析 cosmetics/ 目录: " + e.getMessage());
            return result;
        }

        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(cosmeticsRoot)) {
            stream.filter(Files::isDirectory).sorted().forEach(entries::add);
        } catch (IOException e) {
            warnings.add(packId + ": 扫描 cosmetics/ 失败: " + e.getMessage());
            return result;
        }

        for (Path entryDir : entries) {
            String localId = entryDir.getFileName().toString();
            Path model = findFirst(entryDir, ".geo.json");
            // 优先标准名 texture.png；找不到再退回目录里的第一个 .png
            // （否则自动发现可能把 emissive.png 当成主贴图）
            Path texture = entryDir.resolve("texture.png");
            if (!Files.isRegularFile(texture) || Files.isSymbolicLink(texture)) {
                texture = findFirst(entryDir, ".png");
            }
            if (model == null || texture == null) {
                warnings.add(packId + "/" + localId + ": 缺少 .geo.json 或 .png，已跳过");
                continue;
            }
            Path emissive = entryDir.resolve("emissive.png");
            Path animation = findFirst(entryDir, ".anim.json");
            CosmeticCategory category = CosmeticCategory.byKey(guessCategory(localId));
            result.add(new CosmeticDefinition(packId, localId, localId, category,
                    model, texture,
                    Files.isRegularFile(emissive) && !Files.isSymbolicLink(emissive) ? emissive : null,
                    animation,
                    category.defaultBone(), Set.of(), 1.0f));
        }
        return result;
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

    private static Path findFirst(Path dir, String suffix) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean looksLikeGeometry(Path model) {
        try (Reader reader = Files.newBufferedReader(model, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.isJsonObject() && parsed.getAsJsonObject().has("minecraft:geometry");
        } catch (Exception e) {
            return false;
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
