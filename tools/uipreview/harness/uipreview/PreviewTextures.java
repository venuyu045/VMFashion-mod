package uipreview;

import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 预览器的纹理表：{@link Identifier} → 位图。
 *
 * <p>游戏里 {@code blit(id, ...)} 由 {@code TextureManager} 解析 id 找到 GPU 纹理；
 * 预览器用这张表代替。两处来源：
 * <ol>
 *   <li><b>MC 原生 GUI 精灵图</b>（按钮、输入框底图）—— 从
 *       {@code tools/uipreview/assets/mc/} 读，都是从客户端 jar 里原样拷出来的；</li>
 *   <li><b>时装缩略图</b> —— 由 {@code fake} 的 {@code CosmeticAssets} 读时装包里的
 *       {@code texture.png} 注册进来，所以预览图上真的能看到时装贴图。</li>
 * </ol>
 *
 * <p>找不到纹理时不抛异常：渲染器画一个洋红/黑的"缺失纹理"棋盘格，
 * 让"贴图没注册上"在预览图里一眼可见（而不是悄悄画成空白）。
 */
public final class PreviewTextures {

    private static final Map<Identifier, BufferedImage> TEXTURES = new HashMap<>();
    private static final Map<String, Identifier> MC_SPRITES = new HashMap<>();

    private PreviewTextures() {
    }

    public static void register(Identifier id, BufferedImage image) {
        if (id != null && image != null) {
            TEXTURES.put(id, image);
        }
    }

    public static BufferedImage get(Identifier id) {
        return id == null ? null : TEXTURES.get(id);
    }

    public static int size() {
        return TEXTURES.size();
    }

    /** 注册一个 MC 原生精灵图（{@code namespace:path} 形式）。 */
    public static Identifier registerMcSprite(String id, BufferedImage image) {
        Identifier identifier = Identifier.parse(id);
        register(identifier, image);
        MC_SPRITES.put(id, identifier);
        return identifier;
    }

    /**
     * 从目录批量加载 MC 原生精灵图。
     *
     * <p>目录结构就是资源包里的 {@code textures/gui/sprites/...}，
     * 加载后 id 形如 {@code minecraft:widget/button}（去掉了
     * {@code textures/} 前缀和 {@code .png} 后缀），与游戏内 {@code blit} 用的 id 一致。
     *
     * @param assetsRoot 形如 {@code tools/uipreview/assets/mc}
     * @return 加载成功的张数
     */
    public static int loadMcSprites(Path assetsRoot) throws IOException {
        Path spritesRoot = assetsRoot.resolve("gui/sprites");
        if (!Files.isDirectory(spritesRoot)) {
            return 0;
        }
        int count = 0;
        try (var stream = Files.walk(spritesRoot)) {
            for (Path path : stream.filter(p -> p.toString().endsWith(".png")).toList()) {
                String relative = spritesRoot.relativize(path).toString().replace('\\', '/');
                String id = "minecraft:" + relative.substring(0, relative.length() - ".png".length());
                try (InputStream in = Files.newInputStream(path)) {
                    BufferedImage image = ImageIO.read(in);
                    if (image != null) {
                        registerMcSprite(id, image);
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
