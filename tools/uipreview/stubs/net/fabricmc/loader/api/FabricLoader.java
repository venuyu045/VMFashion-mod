package net.fabricmc.loader.api;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * UI 预览器用的 {@code FabricLoader} <b>替身</b>。
 *
 * <p>真实实现要解析 {@code fabric.mod.json}、扫 mods 目录、建依赖图 —— 预览器里
 * Fabric 加载器根本没启动，直接调会抛 {@code ExceptionInInitializerError}
 * 或返回 {@code null}（然后生产代码在 {@code null.resolve(...)} 上 NPE）。
 *
 * <p>界面和配置类最常见的一处调用就是 {@code FabricLoader.getInstance().getConfigDir()}，
 * 所以这里只需要把"配置目录"给成一个<b>真实存在、可写</b>的临时目录，
 * 让那些"读配置 / 存状态"的代码能跑通（预览器不关心内容，但会写文件）。
 *
 * <p>目录位置可用系统属性 {@code -Duipreview.configDir=...} 指定，
 * 默认是 {@code build/uipreview/fakeconfig}。
 */
public interface FabricLoader {

    FabricLoader INSTANCE = new PreviewFabricLoader();

    static FabricLoader getInstance() {
        return INSTANCE;
    }

    /** 配置目录（预览器给的是临时目录，保证可写）。 */
    Path getConfigDir();

    /** 游戏目录（预览器里和配置目录同级）。 */
    Path getGameDir();

    /** 是否装了某个 mod —— 预览器里只认 fabric-loader 自己。 */
    default boolean isModLoaded(String modId) {
        return "fabricloader".equals(modId);
    }

    /** 替身实现。 */
    final class PreviewFabricLoader implements FabricLoader {

        private Path root;

        private synchronized Path root() {
            if (root == null) {
                String configured = System.getProperty("uipreview.configDir");
                root = configured != null && !configured.isBlank()
                        ? Path.of(configured)
                        : Path.of("build", "uipreview", "fakeconfig");
                try {
                    Files.createDirectories(root);
                } catch (Exception ignored) {
                    // 建不出来就退到系统临时目录，绝不让预览因为"配置目录"挂掉
                    root = Path.of(System.getProperty("java.io.tmpdir"), "uipreview-fakeconfig");
                    try {
                        Files.createDirectories(root);
                    } catch (Exception ignoredToo) {
                        // 连临时目录都建不出来时只能放弃，后续调用会抛 —— 但那是环境问题
                    }
                }
            }
            return root;
        }

        @Override
        public Path getConfigDir() {
            return root().resolve("config");
        }

        @Override
        public Path getGameDir() {
            return root();
        }
    }
}
