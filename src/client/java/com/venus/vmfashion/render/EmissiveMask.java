package com.venus.vmfashion.render;

import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.GeoQuad;
import com.geckolib.cache.model.GeoVertex;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.mojang.blaze3d.platform.NativeImage;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * emissive 遮罩：把 {@code emissive.png} 里<strong>非透明</strong>的区域映射到模型的四边形上。
 *
 * <p>为什么需要它：原来的做法是"整个模型换 emissive 贴图再提交一遍"，于是
 * emissive 贴图里只要有不透明像素，整块模型都走一遍发光管线；Bedrock 的语义是
 * <strong>逐面</strong>发光（UV 指向的区域透明就不该发光）。
 *
 * <p>实现取舍（无显卡环境无法逐像素验证，所以选了"纯 CPU、可解释"的方案）：
 * <ul>
 *   <li>贴图像素从磁盘读一次（{@link NativeImage}，不上 GPU），把 alpha &gt;
 *       {@link #ALPHA_THRESHOLD} 的 texel 记成位图；</li>
 *   <li>遍历模型一次，把每个 quad 的 UV 包围盒投到贴图像素上：<strong>整块都透明就丢掉这个 quad</strong>，
 *       只要有一个不透明 texel 就保留；</li>
 *   <li>保留的 quad 仍然整块提交 —— 部分透明的 quad 里，透明 texel 在混合时贡献 0，
 *       视觉效果正确，代价只是多几个顶点。<strong>不做逐 texel 裁剪</strong>：
 *       那需要拆分四边形 + 重建 UV，收益远小于风险（会破坏法线/描边/顶点数一致性）。</li>
 *   <li>整张贴图全透明 → 返回 {@link #EMPTY}，调用方直接跳过整趟 emissive 提交
 *       （多数包根本没有 emissive，这一步等于白赚一次 pass）。</li>
 * </ul>
 *
 * <p>线程约束：只在渲染线程调用。首次构建会做一次磁盘 IO（和 {@code CosmeticAssets}
 * 首次烘焙一样是"第一次见到这件时装卡一帧"），之后每帧只做哈希查表。
 */
public final class EmissiveMask {

    /** 整张 emissive 贴图都没有不透明像素时的结果：什么都不发光。 */
    public static final EmissiveMask EMPTY = new EmissiveMask(Collections.emptySet());

    /**
     * alpha 阈值（0–255）。取 8 而不是 0：PNG 里常见的 1/255 边缘噪声不该让整面发光，
     * 而原版 cutout 的阈值是 0.1（≈26），这里取更宽松的值以免漏掉半透明发光。
     */
    private static final int ALPHA_THRESHOLD = 8;

    /** 渲染线程独占的缓存：时装 ID → 遮罩。 */
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    /** 只有"确实有非透明区域"的 quad 才在这个集合里（空集合表示整件时装不发光）。 */
    private final Set<GeoQuad> quads;

    private EmissiveMask(Set<GeoQuad> quads) {
        this.quads = quads;
    }

    /**
     * 取（或首次构建）某件时装的 emissive 遮罩。
     *
     * @return {@code null} 表示这件时装没有 emissive 层；{@link #EMPTY} 表示有贴图但全透明
     */
    public static EmissiveMask get(CosmeticAssets.Entry entry, CosmeticDefinition definition) {
        if (entry == null || entry.emissive() == null || definition == null) {
            return null;
        }
        Path file = definition.emissiveFile();
        if (file == null) {
            return null;
        }
        String key = definition.id();
        Cached cached = CACHE.get(key);
        // 用模型实例做版本号：/vmfashion reload 会重新烘焙出新的 BakedGeoModel，
        // 身份不同就重建遮罩，不需要额外通知（CosmeticAssets 归 core-packs，不能加钩子）。
        if (cached != null && cached.model == entry.model()) {
            return cached.mask;
        }
        EmissiveMask mask = build(entry.model(), file, definition.id());
        CACHE.put(key, new Cached(entry.model(), mask));
        return mask;
    }

    /** 该 quad 是否落在 emissive 贴图的不透明区域上。 */
    public boolean isEmissive(GeoQuad quad) {
        return quads.contains(quad);
    }

    /** 没有任何发光四边形（调用方应当整趟跳过 emissive 提交）。 */
    public boolean isEmpty() {
        return quads.isEmpty();
    }

    /** 发光四边形数量，用于日志 / 自检。 */
    public int size() {
        return quads.size();
    }

    // ==================== 内部 ====================

    private static EmissiveMask build(BakedGeoModel model, Path file, String cosmeticId) {
        NativeImage image = null;
        try (InputStream stream = Files.newInputStream(file)) {
            image = NativeImage.read(stream);
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return EMPTY;
            }
            boolean[] opaque = new boolean[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // getPixel 返回 ARGB
                    if (((image.getPixel(x, y) >>> 24) & 0xFF) > ALPHA_THRESHOLD) {
                        opaque[y * width + x] = true;
                    }
                }
            }
            return fromAlpha(model, opaque, width, height);
        } catch (Throwable t) {
            // 防御式：坏 PNG / 文件被删 / 内存不足都不该让渲染崩，退化成"不发光"
            VMFashionClient.LOGGER.warn("时装 {} 的 emissive 遮罩构建失败，本次不发光: {}",
                    cosmeticId, t.toString());
            return EMPTY;
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    /**
     * 纯数学部分：给定"哪些 texel 不透明"，算出发光四边形集合。
     *
     * <p>和 IO 分开是为了能在无 GL / 无 LWJGL 原生库的环境里验证
     * （{@code NativeImage} 需要 LWJGL 的原生内存分配器，纯命令行跑不起来）。
     *
     * @param opaque 长度 {@code width * height} 的位图，索引 = {@code y * width + x}
     */
    static EmissiveMask fromAlpha(BakedGeoModel model, boolean[] opaque, int width, int height) {
        if (model == null || opaque == null || width <= 0 || height <= 0
                || opaque.length < width * height) {
            return EMPTY;
        }
        boolean any = false;
        for (boolean value : opaque) {
            if (value) {
                any = true;
                break;
            }
        }
        if (!any) {
            // 整张 emissive 全透明 → 什么都不发光，调用方会跳过整趟 pass
            return EMPTY;
        }
        // GeoQuad 是 record，但组件里只有数组 → equals/hashCode 退化成"引用相等"，
        // 用 IdentityHashMap 语义上完全一致，还省掉数组比较
        Set<GeoQuad> quads = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(model.topLevelBones(), opaque, width, height, quads);
        return quads.isEmpty() ? EMPTY : new EmissiveMask(quads);
    }

    private static void collect(GeoBone[] bones, boolean[] opaque, int width, int height, Set<GeoQuad> out) {
        for (GeoBone bone : bones) {
            if (bone instanceof CuboidGeoBone cuboid) {
                for (var cube : cuboid.cubes) {
                    GeoQuad[] quads = cube.quads();
                    if (quads == null) {
                        continue;
                    }
                    for (GeoQuad quad : quads) {
                        if (quad != null && anyOpaque(quad, opaque, width, height)) {
                            out.add(quad);
                        }
                    }
                }
            }
            collect(bone.children(), opaque, width, height, out);
        }
    }

    /** quad 的 UV 包围盒里是否有不透明 texel。 */
    private static boolean anyOpaque(GeoQuad quad, boolean[] opaque, int width, int height) {
        GeoVertex[] vertices = quad.vertices();
        if (vertices == null || vertices.length == 0) {
            return false;
        }
        float minU = Float.MAX_VALUE;
        float maxU = -Float.MAX_VALUE;
        float minV = Float.MAX_VALUE;
        float maxV = -Float.MAX_VALUE;
        for (GeoVertex vertex : vertices) {
            minU = Math.min(minU, vertex.texU());
            maxU = Math.max(maxU, vertex.texU());
            minV = Math.min(minV, vertex.texV());
            maxV = Math.max(maxV, vertex.texV());
        }
        // UV 是归一化的（GeckoLib 烘焙时已经除以 texture_width/height），这里再乘回去
        int x0 = clamp((int) Math.floor(minU * width), 0, width - 1);
        int x1 = clamp((int) Math.ceil(maxU * width) - 1, 0, width - 1);
        int y0 = clamp((int) Math.floor(minV * height), 0, height - 1);
        int y1 = clamp((int) Math.ceil(maxV * height) - 1, 0, height - 1);
        for (int y = y0; y <= y1; y++) {
            int row = y * width;
            for (int x = x0; x <= x1; x++) {
                if (opaque[row + x]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private record Cached(BakedGeoModel model, EmissiveMask mask) {
    }
}
