import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.loading.loader.GeckoLibGsonLoader;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.render.anim.BedrockAnimation;
import com.venus.vmfashion.render.anim.BedrockAnimations;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 一件时装在预览工具眼里的"全部输入"：模型 / 贴图 / 动画 + 烘焙结果。
 *
 * <p>文件定位规则与生产端 {@code PackLoader} 保持一致（不要自己发明一套，否则"游戏里能显示、
 * 预览里找不到文件"这种诡异问题会浪费一整天）：
 * <ol>
 *   <li>包目录下有 {@code pack.json} → 在 {@code cosmetics[]} 里按 id 找条目，用条目里的
 *       {@code model} / {@code texture} / {@code animation} 字段（相对包目录）；</li>
 *   <li>没写字段（或没找到条目）→ 退回 {@code cosmetics/<id>/} 目录里的标准文件名
 *       {@code model.geo.json} / {@code texture.png} / {@code anim.json}；</li>
 *   <li>再退回"目录里第一个匹配的文件"（{@code *.geo.json}、非 emissive 的 {@code *.png}、
 *       {@code *.anim.json} / {@code *.animation.json}）；</li>
 *   <li>传进来的目录本身就是一件时装（直接含 {@code *.geo.json}）时，按单件模式处理。</li>
 * </ol>
 *
 * <p>烘焙走 {@link GeckoLibGsonLoader#bakeGeckoLibModelFile} —— 与生产端完全同一条路径，
 * 所以预览里看到的几何就是玩家身上会看到的几何。
 */
final class CosmeticSource {

    /** 包目录（或单件目录）。 */
    final Path packDir;
    /** 包 id（pack.json 的 {@code id}，缺省取目录名）。 */
    final String packId;
    /** 时装 local id。 */
    final String cosmeticId;

    final Path modelFile;
    final Path textureFile;
    /** 可能为 {@code null}（这件时装没有动画）。 */
    final Path animationFile;
    /** 发光层贴图，可能为 {@code null}（预览不渲染发光，只在报告里注明）。 */
    final Path emissiveFile;

    /** pack.json 里的展示名 / 分类 / 挂载骨骼 / 缩放（可能缺省）。 */
    final String displayName;
    final String category;
    final String attachedBone;
    final float declaredScale;

    /** 烘焙后的模型。 */
    final BakedGeoModel model;
    /** 贴图像素（ARGB，索引 {@code y * width + x}，y=0 是贴图顶边，与 UV 的 v=0 对应）。 */
    final int[] texturePixels;
    final int textureWidth;
    final int textureHeight;

    /** anim.json 里的全部动画（保持声明顺序）。 */
    final Map<String, BedrockAnimation> animations;
    /** 实际用来出动画帧的那条（没有动画时为 {@code null}）。 */
    final BedrockAnimation animation;

    /** 解析过程中的警告（原样打印给用户看）。 */
    final List<String> warnings = new ArrayList<>();

    private CosmeticSource(Path packDir, String packId, String cosmeticId,
                           Path modelFile, Path textureFile, Path animationFile, Path emissiveFile,
                           String displayName, String category, String attachedBone, float declaredScale,
                           BakedGeoModel model, int[] texturePixels, int textureWidth, int textureHeight,
                           Map<String, BedrockAnimation> animations, BedrockAnimation animation) {
        this.packDir = packDir;
        this.packId = packId;
        this.cosmeticId = cosmeticId;
        this.modelFile = modelFile;
        this.textureFile = textureFile;
        this.animationFile = animationFile;
        this.emissiveFile = emissiveFile;
        this.displayName = displayName;
        this.category = category;
        this.attachedBone = attachedBone;
        this.declaredScale = declaredScale;
        this.model = model;
        this.texturePixels = texturePixels;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.animations = animations;
        this.animation = animation;
    }

    /** 包里的全部时装 id（用于"找不到时提示有哪些"）。 */
    static List<String> listCosmetics(Path packDir) {
        List<String> ids = new ArrayList<>();
        Path packJson = packDir.resolve("pack.json");
        if (Files.isRegularFile(packJson)) {
            JsonObject root = readJson(packJson);
            if (root != null && root.has("cosmetics") && root.get("cosmetics").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("cosmetics")) {
                    if (element != null && element.isJsonObject()) {
                        String id = optString(element.getAsJsonObject(), "id", "");
                        if (!id.isBlank()) {
                            ids.add(id);
                        }
                    }
                }
            }
        }
        Path cosmeticsDir = packDir.resolve("cosmetics");
        if (Files.isDirectory(cosmeticsDir)) {
            try (Stream<Path> stream = Files.list(cosmeticsDir)) {
                stream.filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .forEach(id -> {
                            if (!ids.contains(id)) {
                                ids.add(id);
                            }
                        });
            } catch (IOException ignored) {
                // 列不出来就算了，只是提示信息
            }
        }
        return ids;
    }

    /**
     * 定位并烘焙一件时装。
     *
     * @param packDir    包目录，或直接是单件时装目录
     * @param cosmeticId 时装 id；单件模式下可传目录名
     */
    static CosmeticSource load(Path packDir, String cosmeticId) throws IOException {
        Path dir = packDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IOException("包目录不存在: " + dir);
        }

        JsonObject packJson = readJson(dir.resolve("pack.json"));
        String packId = packJson == null ? dir.getFileName().toString() : optString(packJson, "id", dir.getFileName().toString());

        // cosmeticId 允许写成 "包id:localId"（生产端的全局 ID 形式），这里拆出 localId
        String requestedId = cosmeticId;
        int colon = requestedId.indexOf(':');
        if (colon > 0) {
            String namespace = requestedId.substring(0, colon);
            String local = requestedId.substring(colon + 1);
            if (namespace.equalsIgnoreCase(packId) || !local.isBlank()) {
                cosmeticId = local.isBlank() ? requestedId : local;
            }
        }

        // ---- 条目元数据（pack.json 的 cosmetics[]） ----
        JsonObject meta = null;
        if (packJson != null && packJson.has("cosmetics") && packJson.getAsJsonArray("cosmetics") != null) {
            for (JsonElement element : packJson.getAsJsonArray("cosmetics")) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject candidate = element.getAsJsonObject();
                if (cosmeticId.equals(optString(candidate, "id", ""))) {
                    meta = candidate;
                    break;
                }
            }
        }

        String localId = meta != null ? optString(meta, "id", cosmeticId) : cosmeticId;

        // ---- 单件目录模式：传进来的目录自己就含 .geo.json ----
        boolean singleDirMode = meta == null && findFirst(dir, name -> name.endsWith(".geo.json")) != null
                && !Files.isDirectory(dir.resolve("cosmetics"));

        Path entryDir = singleDirMode ? dir : dir.resolve("cosmetics").resolve(localId);

        Path model = meta == null ? null : resolveSafe(dir, optString(meta, "model", ""));
        if (model == null) {
            model = standardFile(entryDir, "model.geo.json");
        }
        if (model == null) {
            model = findFirst(entryDir, name -> name.endsWith(".geo.json"));
        }
        if (model == null || !Files.isRegularFile(model)) {
            throw new IOException("找不到模型文件（" + entryDir + " 下没有 *.geo.json）");
        }

        Path texture = meta == null ? null : resolveSafe(dir, optString(meta, "texture", ""));
        if (texture == null) {
            texture = standardFile(entryDir, "texture.png");
        }
        if (texture == null) {
            // 与 PackLoader 一致：排除 emissive.png，否则发光层会被当成主贴图
            texture = findFirst(entryDir, name -> name.endsWith(".png") && !name.equals("emissive.png"));
        }
        if (texture == null || !Files.isRegularFile(texture)) {
            throw new IOException("找不到贴图文件（" + entryDir + " 下没有 texture.png）");
        }

        Path emissive = meta == null ? null : resolveSafe(dir, optString(meta, "emissive", ""));
        if (emissive == null) {
            emissive = standardFile(entryDir, "emissive.png");
        }
        if (emissive != null && !Files.isRegularFile(emissive)) {
            emissive = null;
        }

        String animationName = meta == null ? "" : optString(meta, "animationName", "");
        Path animation = meta == null ? null : resolveSafe(dir, optString(meta, "animation", ""));
        if (animation == null) {
            animation = findFirst(entryDir, CosmeticSource::isAnimationFileName);
        }
        if (animation != null && !Files.isRegularFile(animation)) {
            animation = null;
        }

        // ---- 烘焙模型（生产同款路径） ----
        JsonObject modelJson = readJson(model);
        if (modelJson == null) {
            throw new IOException("模型 JSON 解析失败: " + model);
        }
        BakedGeoModel baked = new GeckoLibGsonLoader().bakeGeckoLibModelFile(
                Identifier.fromNamespaceAndPath(sanitizeNamespace(packId), sanitizePath(localId)), modelJson);

        // ---- 读贴图（JDK 自带 ImageIO，不需要第三方依赖） ----
        BufferedImage image = ImageIO.read(texture.toFile());
        if (image == null) {
            throw new IOException("贴图读取失败（不是有效 PNG？）: " + texture);
        }
        int texW = image.getWidth();
        int texH = image.getHeight();
        int[] pixels = new int[texW * texH];
        for (int y = 0; y < texH; y++) {
            for (int x = 0; x < texW; x++) {
                pixels[y * texW + x] = image.getRGB(x, y);
            }
        }

        // ---- 解析动画 ----
        Map<String, BedrockAnimation> animations = Map.of();
        BedrockAnimation chosen = null;
        if (animation != null) {
            JsonObject animJson = readJson(animation);
            if (animJson != null) {
                List<String> animWarnings = new ArrayList<>();
                animations = BedrockAnimations.parse(animJson, animWarnings::add);
                chosen = pickAnimation(baked, animations, animationName);
            }
        }

        CosmeticSource source = new CosmeticSource(dir, packId, localId, model, texture, animation, emissive,
                meta == null ? localId : optString(meta, "name", localId),
                meta == null ? "" : optString(meta, "category", ""),
                meta == null ? "" : optString(meta, "attachedBone", ""),
                meta == null ? 1.0F : (float) optDouble(meta, "scale", 1.0),
                baked, pixels, texW, texH, animations, chosen);
        if (animations.isEmpty() && animation != null) {
            source.warnings.add("anim.json 里没有可用动画（只有 molang / timeline？），动画帧将按静态姿态输出");
        }
        if (chosen == null && !animations.isEmpty()) {
            source.warnings.add("动画里的骨骼名与模型对不上，动画帧将按静态姿态输出");
        }
        return source;
    }

    /**
     * 选一条动画来出动画帧：优先"骨骼名在模型里真的存在"的那条（与生产端
     * {@code AnimationCache} 的"取第一个"语义一致，但跳过明显对不上的）。
     */
    private static BedrockAnimation pickAnimation(BakedGeoModel model, Map<String, BedrockAnimation> animations,
                                                  String preferredName) {
        if (animations.isEmpty()) {
            return null;
        }
        // pack.json 的 animationName 是作者明确指定的（游戏内 AnimationCache 也优先用它）
        if (preferredName != null && !preferredName.isBlank()) {
            BedrockAnimation preferred = animations.get(preferredName);
            if (preferred != null) {
                return preferred;
            }
        }
        Map<String, Boolean> boneNames = new LinkedHashMap<>();
        for (var bone : model.topLevelBones()) {
            collectBoneNames(bone, boneNames);
        }
        for (BedrockAnimation animation : animations.values()) {
            for (String boneName : animation.bones().keySet()) {
                if (boneNames.containsKey(boneName.toLowerCase(Locale.ROOT))) {
                    return animation;
                }
            }
        }
        return null;
    }

    private static void collectBoneNames(com.geckolib.cache.model.GeoBone bone, Map<String, Boolean> out) {
        if (bone == null) {
            return;
        }
        if (bone.name() != null) {
            out.put(bone.name().toLowerCase(Locale.ROOT), Boolean.TRUE);
        }
        for (var child : bone.children()) {
            collectBoneNames(child, out);
        }
    }

    /** 贴图上某个 texel（越界按边缘钳制）。 */
    int texel(int x, int y) {
        int tx = x < 0 ? 0 : Math.min(x, textureWidth - 1);
        int ty = y < 0 ? 0 : Math.min(y, textureHeight - 1);
        return texturePixels[ty * textureWidth + tx];
    }

    // ==================== 小工具（与 PackLoader 同语义） ====================

    static boolean isAnimationFileName(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.equals("anim.json") || name.endsWith(".anim.json") || name.endsWith(".animation.json");
    }

    private static Path standardFile(Path dir, String fileName) {
        if (dir == null) {
            return null;
        }
        Path candidate = dir.resolve(fileName);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static Path findFirst(Path dir, java.util.function.Predicate<String> nameFilter) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> nameFilter.test(path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** 解析 pack.json 里的相对路径，禁止跳出包目录（和 PackLoader#resolveSafe 同语义）。 */
    private static Path resolveSafe(Path base, String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        try {
            Path resolved = base.resolve(relative).normalize();
            if (!resolved.startsWith(base)) {
                return null;
            }
            return Files.isRegularFile(resolved) ? resolved : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject readJson(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String optString(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        return element.getAsString();
    }

    private static double optDouble(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        double value = element.getAsDouble();
        return Double.isFinite(value) ? value : fallback;
    }

    /** 资源 ID 的命名空间只允许 [a-z0-9_.-]，其它字符换成下划线（Identifier 会校验）。 */
    private static String sanitizeNamespace(String raw) {
        String lower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        String cleaned = lower.replaceAll("[^a-z0-9_.-]", "_");
        return cleaned.isBlank() ? "vmfashion_preview" : cleaned;
    }

    /** 资源 ID 的 path 只允许 [a-z0-9/._-]。 */
    private static String sanitizePath(String raw) {
        String lower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        String cleaned = lower.replaceAll("[^a-z0-9/._-]", "_");
        return cleaned.isBlank() ? "preview" : cleaned;
    }
}
