package com.venus.vmfashion.cosmetic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code packs/server/<packId>/} 命名空间的加载行为（自动下发时装包用）。
 *
 * <p>为什么单独一个测试类：{@link PackLoader} 原本只扫 {@code packs/} 的直接子目录，
 * 服务端下发的包放在 {@code packs/server/} 下一层，必须显式加一层扫描，
 * 否则自动下发"装好了但永远加载不到"。
 */
class PackLoaderServerDirTest {

    @TempDir
    Path root;

    /** 在指定目录造一个最小可加载的包。 */
    private static void writePack(Path packDir, String packId, String localId) throws Exception {
        Path cosmeticDir = packDir.resolve("cosmetics").resolve(localId);
        Files.createDirectories(cosmeticDir);
        Files.writeString(packDir.resolve("pack.json"),
                "{\"formatVersion\":1,\"id\":\"" + packId + "\",\"name\":\"" + packId + "\"}",
                StandardCharsets.UTF_8);
        Files.writeString(cosmeticDir.resolve("model.geo.json"),
                "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":"
                        + "{\"identifier\":\"geometry." + localId + "\",\"texture_width\":64,"
                        + "\"texture_height\":64,\"visible_bounds_width\":1,\"visible_bounds_height\":1,"
                        + "\"visible_bounds_offset\":[0,0,0]},\"bones\":[{\"name\":\"body\","
                        + "\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}",
                StandardCharsets.UTF_8);
        // 1x1 合法 PNG
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        Files.write(cosmeticDir.resolve("texture.png"), png);
    }

    @Test
    void loadsPacksFromServerNamespace() throws Exception {
        writePack(root.resolve("server").resolve("remote"), "remote", "wings");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.packs().size(), "packs/server/<id>/ 下的包应该被加载");
        assertEquals("remote", result.packs().get(0).id());
        assertNotNull(CosmeticRegistry.getInstance().find("remote:wings"));
    }

    @Test
    void serverDirectoryItselfIsNotTreatedAsPack() throws Exception {
        // 只有 server/ 目录、没有 pack.json 时不应该产生"加载失败"的噪音
        Files.createDirectories(root.resolve("server"));
        writePack(root.resolve("server").resolve("remote"), "remote", "wings");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.packs().size());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("server 失败")),
                "server 目录本身不应被当成包去加载");
    }

    @Test
    void userPackWinsOverServerPackWithSameId() throws Exception {
        writePack(root.resolve("user"), "shared", "mine");
        writePack(root.resolve("server").resolve("shared"), "shared", "theirs");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.packs().size(), "同 packId 只能保留一个");
        assertEquals(root.resolve("user").toRealPath(), result.packs().get(0).root().toRealPath(),
                "用户自己装的包必须优先于服务端下发的包");
        assertNotNull(CosmeticRegistry.getInstance().find("shared:mine"));
        assertNull(CosmeticRegistry.getInstance().find("shared:theirs"));
    }

    @Test
    void missingServerDirectoryIsFine() throws Exception {
        writePack(root.resolve("user"), "user", "crown");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.packs().size());
    }
}
