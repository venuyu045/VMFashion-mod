package com.venus.vmfashion.render;

import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import net.minecraft.resources.Identifier;
import uipreview.PreviewTextures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * UI 预览器专用的 {@code CosmeticAssets} <b>替身</b>（编译时替换真实类）。
 *
 * <p>真实实现要烘焙 GeckoLib 模型、上传 GPU 纹理 —— 无显卡环境做不到，而且 UI 预览
 * 根本不需要 3D 模型，只需要<b>一张缩略图</b>。所以这里退化成：
 * 读时装包里的 {@code texture.png}，注册到 {@link PreviewTextures}，
 * 返回一个指向它的 {@link Identifier}。
 *
 * <p>效果是预览图上每张卡片真的显示该时装的贴图（而不是统一的占位色块），
 * 于是"缩略图拉伸 / 错位 / 没加载"这类问题在预览里就能看出来。
 *
 * <p><b>与真实类的契约保持一致</b>：{@link #get} 返回 {@code null} 表示失败，
 * {@link #failureCount()} 记录失败次数 —— {@code WardrobeScreen} 会据此画 "!" 角标并稍后重试。
 */
public final class CosmeticAssets {

    /** 缩略图句柄（真实类里还有 model / emissive / animations，UI 用不到）。 */
    public record Entry(Identifier texture) {
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final Map<String, Boolean> FAILED = new HashMap<>();
    private static int failures;

    private CosmeticAssets() {
    }

    public static Entry get(CosmeticDefinition definition) {
        if (definition == null) {
            return null;
        }
        String key = definition.id();
        Entry cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (FAILED.containsKey(key)) {
            return null;
        }
        Entry loaded = load(definition);
        if (loaded == null) {
            FAILED.put(key, Boolean.TRUE);
            failures++;
        } else {
            CACHE.put(key, loaded);
        }
        return loaded;
    }

    private static Entry load(CosmeticDefinition definition) {
        try {
            Path texture = definition.textureFile();
            if (texture == null || !Files.isRegularFile(texture)) {
                return null;
            }
            BufferedImage image = ImageIO.read(texture.toFile());
            if (image == null) {
                return null;
            }
            String path = definition.id().replace(':', '/');
            Identifier id = Identifier.fromNamespaceAndPath("vmfashion_preview", path);
            PreviewTextures.register(id, image);
            return new Entry(id);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void invalidate() {
        CACHE.clear();
        FAILED.clear();
        failures = 0;
    }

    public static int cachedCount() {
        return CACHE.size();
    }

    public static int failureCount() {
        return failures;
    }
}
