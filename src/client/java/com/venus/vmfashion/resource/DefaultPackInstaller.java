package com.venus.vmfashion.resource;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.cosmetic.PackLoader;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 首次启动时把 mod 内置的示例时装包释放到 {@code config/vmfashion/packs/example/}。
 *
 * <p>示例包本身放在 jar 的 {@code assets/vmfashion/default_pack/} 下（用 {@code findPath} 读，
 * dev 环境和正式 jar 都能用），这样即使没有任何真实资产，也能验证整条渲染链路。
 *
 * <p>只在 {@code pack.json} 不存在时释放，不会覆盖用户改过的包。
 */
public final class DefaultPackInstaller {

    private static final String RESOURCE_ROOT = "assets/vmfashion/default_pack";
    private static final String TARGET_DIR = "example";

    private DefaultPackInstaller() {
    }

    public static void installIfMissing() {
        Path target = PackLoader.packsDir().resolve(TARGET_DIR);
        if (Files.isRegularFile(target.resolve("pack.json"))) {
            return;
        }

        Optional<Path> source = FabricLoader.getInstance()
                .getModContainer(VMFashionClient.MOD_ID)
                .flatMap(container -> container.findPath(RESOURCE_ROOT));
        if (source.isEmpty()) {
            VMFashionClient.LOGGER.warn("找不到内置示例时装包（{}），跳过释放", RESOURCE_ROOT);
            return;
        }

        try {
            copyRecursively(source.get(), target);
            VMFashionClient.LOGGER.info("已释放示例时装包到 {}", target);
        } catch (IOException e) {
            VMFashionClient.LOGGER.warn("释放示例时装包失败: {}", e.toString());
        }
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
