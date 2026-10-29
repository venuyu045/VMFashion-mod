package com.venus.vmfashion.render;

import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.loading.loader.GeckoLibGsonLoader;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.cosmetic.CosmeticResourceId;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把时装包（磁盘目录，不在资源包里）接入 GeckoLib。
 *
 * <p>方案：不挂资源包，直接读磁盘。
 * <ul>
 *   <li><b>模型</b>：{@code GeckoLibGsonLoader#bakeGeckoLibModelFile(Identifier, JsonObject)}
 *       本身就是纯 JSON → {@link BakedGeoModel}，不经过 ResourceManager，
 *       所以只要把文件读成 {@link JsonObject} 就能烘焙。</li>
 *   <li><b>贴图</b>：PNG 读成 {@link NativeImage}，包成 {@link DynamicTexture} 注册进
 *       {@link TextureManager}，得到一个普通的 {@link Identifier}，之后
 *       {@code RenderTypes.entityCutout(id)} 就能用。</li>
 * </ul>
 * 这样 GeckoLib 的正常资源管线（资源包扫描）不受影响，也不会和别的包冲突。
 *
 * <p>所有方法都必须在客户端主线程调用（贴图上传需要 GL 上下文）。
 */
public final class CosmeticAssets {

    private static final String NAMESPACE = VMFashionClient.MOD_ID;
    private static final GeckoLibGsonLoader LOADER = new GeckoLibGsonLoader();

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    /**
     * 烘焙失败的记录（失败原因 + 时间戳）。
     *
     * <p>为什么要带时间戳：旧实现"失败一次永久拉黑"，玩家把包补好 / 重新下载完也永远不会重试，
     * 只能重启游戏（开发路线图 §10 #16）。现在失败后 {@value #FAILURE_RETRY_MS} 毫秒允许重试一次，
     * 既能挡住"每帧重试刷日志"，又能自愈。
     */
    private static final Map<String, Failure> FAILURES = new ConcurrentHashMap<>();
    /**
     * 失败后多久允许重试（毫秒）。
     *
     * <p>取 5 分钟而不是 30 秒：重试会**在渲染线程上**做磁盘 IO + GeckoLib 烘焙 + 贴图上传
     * （见 {@link #get}），一件永久损坏的时装如果 30 秒重试一次，就是每半分钟卡一帧
     * （发布加固审查发现的）。5 分钟把这个抖动降到可接受范围，同时保留"玩家补好包后
     * 不用重启游戏"的自愈能力；{@code /vmfashion reload} 与 F3+T 会立刻清掉失败记录。
     */
    private static final long FAILURE_RETRY_MS = 5 * 60 * 1000L;

    /** 一条失败记录。 */
    private record Failure(String message, long atMillis) {
        boolean coolingDown(long now) {
            return now - atMillis < FAILURE_RETRY_MS;
        }
    }
    /** 由本模组注册的贴图 ID；reload 时必须主动释放，否则旧图一直占显存且新图不会生效。 */
    private static final java.util.Set<Identifier> REGISTERED_TEXTURES = ConcurrentHashMap.newKeySet();
    /** 贴图注册/上传串行化：TextureManager 的注册不是线程安全的，且同一 ID 不能重复上传。 */
    private static final Object TEXTURE_LOCK = new Object();

    private CosmeticAssets() {
    }

    /**
     * 一件时装烘焙后的可渲染资源。
     *
     * @param animations 解析好的 Bedrock 动画（动画名 → 动画）；没有动画时是空表，不是 {@code null}
     */
    public record Entry(BakedGeoModel model, Identifier texture, Identifier emissive,
                        Map<String, com.venus.vmfashion.render.anim.BedrockAnimation> animations) {

        /** 模型里的四边形总数，用于日志 / 自检。 */
        public int quadCount() {
            return countQuads(model.topLevelBones());
        }

        /** 有没有可播放的动画。 */
        public boolean hasAnimation() {
            return !animations.isEmpty();
        }
    }

    /** 取（或首次烘焙）一件时装的资源；失败返回 {@code null} 且只记一次日志。 */
    public static Entry get(CosmeticDefinition definition) {
        if (definition == null) {
            return null;
        }
        String key = cacheKey(definition);
        Entry cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        long now = System.currentTimeMillis();
        Failure failure = FAILURES.get(key);
        if (failure != null) {
            if (failure.coolingDown(now)) {
                return null;
            }
            // 冷却期过了：允许再试一次（包可能刚被自动下发修好）
            FAILURES.remove(key);
        }
        try {
            Entry loaded = load(definition);
            CACHE.put(key, loaded);
            FAILURES.remove(key);
            return loaded;
        } catch (Throwable t) {
            FAILURES.put(key, new Failure(String.valueOf(t), now));
            VMFashionClient.LOGGER.warn("时装 {} 的资源加载失败: {}", definition.id(), t.toString());
            return null;
        }
    }

    /** 清空缓存（{@code /vmfashion reload} 时调用，让改动过的模型/贴图重新加载）。 */
    public static void invalidate() {
        CACHE.clear();
        FAILURES.clear();
        // 动画状态绑在旧的 BakedGeoModel 上（骨骼对象会变），必须一起清
        com.venus.vmfashion.render.anim.AnimationCache.invalidate();
        // 只释放本模组注册的动态贴图；原版/其它模组的贴图不在这个集合里
        if (!REGISTERED_TEXTURES.isEmpty()) {
            try {
                TextureManager textureManager = Minecraft.getInstance().getTextureManager();
                if (textureManager != null) {
                    for (Identifier id : REGISTERED_TEXTURES) {
                        textureManager.release(id);
                    }
                }
            } catch (Throwable t) {
                VMFashionClient.LOGGER.warn("释放时装贴图失败: {}", t.toString());
            } finally {
                REGISTERED_TEXTURES.clear();
            }
        }
    }

    public static int cachedCount() {
        return CACHE.size();
    }

    public static int failureCount() {
        return FAILURES.size();
    }

    // ==================== 内部 ====================

    private static Entry load(CosmeticDefinition definition) throws Exception {
        BakedGeoModel model = bakeModel(definition);
        Identifier texture = registerTexture(definition, definition.textureFile(), "texture");
        Identifier emissive = definition.emissiveFile() == null
                ? null
                : registerTexture(definition, definition.emissiveFile(), "emissive");
        return new Entry(model, texture, emissive, loadAnimations(definition));
    }

    /**
     * 解析 {@code anim.json}。
     *
     * <p>动画解析失败**不能**让整件时装加载失败（模型和贴图还是能正常显示的），
     * 所以这里自己兜住异常，只记一条警告并返回空表。
     */
    private static Map<String, com.venus.vmfashion.render.anim.BedrockAnimation> loadAnimations(
            CosmeticDefinition definition) {
        Path file = definition.animationFile();
        if (file == null) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                VMFashionClient.LOGGER.warn("时装 {} 的动画文件不是 JSON 对象: {}", definition.id(), file);
                return Map.of();
            }
            Map<String, com.venus.vmfashion.render.anim.BedrockAnimation> animations =
                    com.venus.vmfashion.render.anim.BedrockAnimations.parse(
                            element.getAsJsonObject(),
                            warning -> VMFashionClient.LOGGER.warn("时装 {} 动画: {}", definition.id(), warning));
            if (!animations.isEmpty()) {
                VMFashionClient.LOGGER.debug("时装 {} 解析到 {} 个动画", definition.id(), animations.size());
            }
            return animations;
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("时装 {} 的动画解析失败（模型仍可用）: {}", definition.id(), t.toString());
            return Map.of();
        }
    }

    private static BakedGeoModel bakeModel(CosmeticDefinition definition) throws Exception {
        Path file = definition.modelFile();
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("模型不是 JSON 对象: " + file);
            }
            json = element.getAsJsonObject();
        }

        Identifier id = resourceId(definition, "model");
        BakedGeoModel model = LOADER.bakeGeckoLibModelFile(id, json);
        if (model == null || model.isMissingno()) {
            throw new IllegalStateException("模型烘焙结果为空（检查 minecraft:geometry 结构）: " + file);
        }
        return model;
    }

    private static Identifier registerTexture(CosmeticDefinition definition, Path file, String suffix)
            throws Exception {
        Identifier id = resourceId(definition, suffix);
        synchronized (TEXTURE_LOCK) {
            // 只复用"本次加载里由本模组注册过"的贴图。
            // 旧实现用 getTexture(id) != null 判重 —— 那是别人的图（资源包 / 撞车后的另一件时装）也照单全收，
            // 结果 A 穿上 B 的贴图。现在 ID 里带来源哈希，撞车不可能发生，
            // 这个判断仅用于避免同一件时装被并发烘焙时重复上传。
            if (REGISTERED_TEXTURES.contains(id)) {
                return id;
            }
            NativeImage image;
            try (InputStream stream = Files.newInputStream(file)) {
                image = NativeImage.read(stream);
            }
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            if (textureManager.getTexture(id) != null) {
                // 上一次加载留下的同名贴图（例如 invalidate 时没拿到 TextureManager），先释放避免显存泄漏
                textureManager.release(id);
            }
            // DynamicTexture 接管 image 的所有权，并在构造时上传到 GPU
            textureManager.register(id, new DynamicTexture(
                    () -> "vmfashion/" + definition.id() + "/" + suffix, image));
            REGISTERED_TEXTURES.add(id);
            return id;
        }
    }

    /**
     * 把时装 ID 映射成合法的 {@link Identifier}（只允许 a-z0-9/._-）。
     *
     * <p>路径由 {@link CosmeticResourceId#pathFor} 生成：可读部分 + 来源哈希。
     * 只做字符替换会撞车（{@code A:B} 与 {@code A_B} 都会被压成 {@code a_b}，见路线图 #13），
     * 哈希算的是原始 ID，因此不同来源一定拿到不同 Identifier。
     */
    private static Identifier resourceId(CosmeticDefinition definition, String suffix) {
        String path = CosmeticResourceId.pathFor(definition.packId(), definition.localId(), suffix);
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    /**
     * 缓存键只用时装 ID：mtime 检查会每帧做磁盘 stat，换模型应由
     * {@code /vmfashion reload -> invalidate()} 统一处理，不在渲染热路径做 IO。
     */
    private static String cacheKey(CosmeticDefinition definition) {
        return definition.id();
    }

    private static int countQuads(GeoBone[] bones) {
        int total = 0;
        for (GeoBone bone : bones) {
            if (bone instanceof CuboidGeoBone cuboid) {
                for (var cube : cuboid.cubes) {
                    total += cube.quads().length;
                }
            }
            total += countQuads(bone.children());
        }
        return total;
    }
}
