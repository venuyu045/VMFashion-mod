package com.venus.vmfashion.cosmetic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link PackLoader} 的解析边界测试（纯逻辑：临时目录 + 文本文件，不加载任何 Minecraft 类）。
 *
 * <p>覆盖：正常包 / 缺 pack.json / 坏 JSON / 声明式 cosmetics 数组 / 自动发现 /
 * meta.json 覆盖 / anim.json 自动发现（#11）/ 路径逃逸 / 重复 id / scale 非法值回退（#14）。
 */
class PackLoaderTest {

    /** 一份最小的合法 Bedrock 几何（PackLoader 只检查 minecraft:geometry 是否存在）。 */
    private static final String MODEL_JSON = """
            {
              "format_version": "1.12.0",
              "minecraft:geometry": [
                {
                  "description": {
                    "identifier": "geometry.test",
                    "texture_width": 16,
                    "texture_height": 16
                  },
                  "bones": []
                }
              ]
            }
            """;

    /** 贴图内容不重要（PackLoader 不解析 PNG，只检查文件存在），给个 PNG 魔数即可。 */
    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    @TempDir
    Path root;

    // ==================== 基础解析 ====================

    @Test
    void loadsDeclaredPackWithAllFields() throws IOException {
        Path pack = createPack("demo", """
                {
                  "formatVersion": 1,
                  "id": "demo",
                  "name": "演示包",
                  "cosmetics": [
                    {
                      "id": "crown",
                      "name": "皇冠",
                      "category": "hat",
                      "attachedBone": "head",
                      "hideParts": ["head", "body"],
                      "scale": 1.5
                    }
                  ]
                }
                """);
        Path crownDir = createStandardCosmetic(pack, "crown");
        writeFile(crownDir.resolve("anim.json"), "{}");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.packs().size());
        assertEquals(1, result.cosmeticCount());
        CosmeticDefinition definition = result.packs().get(0).cosmetics().get(0);
        assertEquals("demo:crown", definition.id());
        assertEquals("皇冠", definition.name());
        assertSame(CosmeticCategory.HAT, definition.category());
        assertEquals("head", definition.attachedBone());
        assertEquals(Set.of("head", "body"), definition.hideParts());
        assertEquals(1.5F, definition.scale(), 1e-6);
        assertNotNull(definition.animationFile());
        assertNull(definition.emissiveFile());
        assertEquals(List.of(), result.warnings());
        // reload 必须同时刷新全局注册表
        assertEquals(1, CosmeticRegistry.getInstance().size());
    }

    @Test
    void ignoresDirectoryWithoutManifest() throws IOException {
        createStandardCosmetic(root.resolve("half"), "crown");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(0, result.packs().size());
    }

    @Test
    void skipsBrokenJsonManifest() throws IOException {
        writeFile(root.resolve("broken").resolve("pack.json"), "{ this is not json ");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(0, result.packs().size());
        assertTrue(hasWarning(result, "pack.json 解析失败"), result.warnings().toString());
    }

    @Test
    void skipsManifestThatIsNotJsonObject() throws IOException {
        writeFile(root.resolve("array").resolve("pack.json"), "[1,2,3]");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(0, result.packs().size());
        assertTrue(hasWarning(result, "不是 JSON 对象"), result.warnings().toString());
    }

    @Test
    void reportsUnsupportedFormatVersionButStillLoads() throws IOException {
        Path pack = createPack("future", """
                {"id": "future", "formatVersion": 99, "cosmetics": [{"id": "crown"}]}
                """);
        createStandardCosmetic(pack, "crown");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.cosmeticCount());
        assertTrue(hasWarning(result, "formatVersion"), result.warnings().toString());
    }

    // ==================== 声明式 cosmetics 数组 ====================

    @Test
    void skipsEntryWithoutModelAndReportsMissingFiles() throws IOException {
        createPack("demo", """
                {"id": "demo", "cosmetics": [
                  {"id": "crown"},
                  {"id": "ghost", "model": "cosmetics/ghost/model.geo.json",
                   "texture": "cosmetics/ghost/texture.png"}
                ]}
                """);
        createStandardCosmetic(root.resolve("demo"), "crown");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.cosmeticCount());
        assertEquals("demo:crown", result.packs().get(0).cosmetics().get(0).id());
        assertTrue(hasWarning(result, "demo/ghost: 模型文件不存在"), result.warnings().toString());
    }

    @Test
    void skipsEntryWithoutId() throws IOException {
        createPack("demo", """
                {"id": "demo", "cosmetics": [{"name": "没有 id"}]}
                """);

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(0, result.cosmeticCount());
        assertTrue(hasWarning(result, "缺少 id"), result.warnings().toString());
    }

    @Test
    void skipsDuplicateCosmeticIdInsideOnePack() throws IOException {
        createPack("demo", """
                {"id": "demo", "cosmetics": [{"id": "crown"}, {"id": "CROWN"}]}
                """);
        createStandardCosmetic(root.resolve("demo"), "crown");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.cosmeticCount());
        assertTrue(hasWarning(result, "重复"), result.warnings().toString());
    }

    @Test
    void skipsDuplicatePackIdAcrossDirectories() throws IOException {
        createPack("a", """
                {"id": "dup", "cosmetics": [{"id": "one"}]}
                """);
        createStandardCosmetic(root.resolve("a"), "one");
        createPack("b", """
                {"id": "dup", "cosmetics": [{"id": "two"}]}
                """);
        createStandardCosmetic(root.resolve("b"), "two");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.packs().size());
        assertEquals("dup:one", result.packs().get(0).cosmetics().get(0).id());
        assertTrue(hasWarning(result, "重复的 packId"), result.warnings().toString());
    }

    // ==================== 自动发现（#14）====================

    @Test
    void autoDiscoversCosmeticsWithoutManifestList() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo", "name": "演示包"}
                """);
        createStandardCosmetic(pack, "wing_demo");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.cosmeticCount());
        CosmeticDefinition definition = result.packs().get(0).cosmetics().get(0);
        assertEquals("demo:wing_demo", definition.id());
        assertEquals("wing_demo", definition.name());
        // 猜分类：wing → wings，默认骨骼 body
        assertSame(CosmeticCategory.WINGS, definition.category());
        assertEquals("body", definition.attachedBone());
        // 关键回归点：不再硬编码成 Set.of(), 1.0f 之后还必须是"有默认值"而不是"没数据"
        assertEquals(Set.of(), definition.hideParts());
        assertEquals(1.0F, definition.scale(), 1e-6);
    }

    @Test
    void autoDiscoveredCosmeticReadsMetaJson() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo"}
                """);
        Path dir = createStandardCosmetic(pack, "crown");
        writeFile(dir.resolve("meta.json"), """
                {
                  "name": "自动发现的皇冠",
                  "category": "hat",
                  "attachedBone": "head",
                  "hideParts": ["head"],
                  "scale": 2.5
                }
                """);

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.cosmeticCount());
        CosmeticDefinition definition = result.packs().get(0).cosmetics().get(0);
        assertEquals("自动发现的皇冠", definition.name());
        assertSame(CosmeticCategory.HAT, definition.category());
        assertEquals("head", definition.attachedBone());
        assertEquals(Set.of("head"), definition.hideParts());
        assertEquals(2.5F, definition.scale(), 1e-6);
    }

    @Test
    void declaredEntryAppliesToAutoDiscoveredDirectory() throws IOException {
        // pack.json 只写元数据，不写 model/texture —— 资源由目录自动发现，元数据由数组声明
        Path pack = createPack("demo", """
                {"id": "demo", "cosmetics": [
                  {"id": "crown", "category": "hat", "hideParts": ["head"], "scale": 3.0}
                ]}
                """);
        createStandardCosmetic(pack, "crown");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.cosmeticCount(), "声明项与目录不能被重复加载成两件");
        CosmeticDefinition definition = result.packs().get(0).cosmetics().get(0);
        assertEquals("head", definition.attachedBone());
        assertEquals(Set.of("head"), definition.hideParts());
        assertEquals(3.0F, definition.scale(), 1e-6);
    }

    @Test
    void metaJsonOverridesDeclaredEntry() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo", "cosmetics": [
                  {"id": "crown", "name": "声明名", "scale": 2.0, "hideParts": ["head"]}
                ]}
                """);
        Path dir = createStandardCosmetic(pack, "crown");
        writeFile(dir.resolve("meta.json"), """
                {"name": "meta 名", "scale": 4.0}
                """);

        PackLoader.LoadResult result = PackLoader.reload(root);

        CosmeticDefinition definition = result.packs().get(0).cosmetics().get(0);
        assertEquals("meta 名", definition.name());
        assertEquals(4.0F, definition.scale(), 1e-6);
        // meta.json 没写的字段仍然来自 pack.json 声明项
        assertEquals(Set.of("head"), definition.hideParts());
    }

    @Test
    void autoDiscoverWarnsWhenCosmeticsDirectoryMissing() throws IOException {
        createPack("demo", """
                {"id": "demo"}
                """);

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(0, result.packs().size());
        assertTrue(hasWarning(result, "既没有 cosmetics 列表"), result.warnings().toString());
        assertTrue(hasWarning(result, "没有解析出任何时装"), result.warnings().toString());
    }

    // ==================== #11 anim.json 自动发现 ====================

    @Test
    void autoDiscoversBareAnimJson() throws IOException {
        assertAnimationDiscovered("bare", "anim.json");
    }

    @Test
    void autoDiscoversDottedAnimJson() throws IOException {
        assertAnimationDiscovered("dotted", "crown.anim.json");
    }

    @Test
    void autoDiscoversAnimationJsonSuffix() throws IOException {
        assertAnimationDiscovered("animation", "crown.animation.json");
    }

    @Test
    void noAnimationFileStaysNull() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo"}
                """);
        createStandardCosmetic(pack, "crown");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertNull(result.packs().get(0).cosmetics().get(0).animationFile());
    }

    @Test
    void explicitAnimationPathWins() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo", "cosmetics": [{"id": "crown", "animation": "shared/idle.animation.json"}]}
                """);
        createStandardCosmetic(pack, "crown");
        writeFile(pack.resolve("shared/idle.animation.json"), "{}");

        PackLoader.LoadResult result = PackLoader.reload(root);

        CosmeticDefinition definition = result.packs().get(0).cosmetics().get(0);
        assertEquals("idle.animation.json", definition.animationFile().getFileName().toString());
    }

    // ==================== 贴图 / 发光层 ====================

    @Test
    void autoDiscoveredEmissiveIsPickedUp() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo"}
                """);
        Path dir = createStandardCosmetic(pack, "crown");
        writeBytes(dir.resolve("emissive.png"));

        PackLoader.LoadResult result = PackLoader.reload(root);

        CosmeticDefinition definition = result.packs().get(0).cosmetics().get(0);
        assertNotNull(definition.emissiveFile());
        assertEquals("emissive.png", definition.emissiveFile().getFileName().toString());
    }

    @Test
    void emissivePngIsNotUsedAsMainTexture() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo"}
                """);
        Path dir = pack.resolve("cosmetics/crown");
        writeFile(dir.resolve("model.geo.json"), MODEL_JSON);
        writeBytes(dir.resolve("emissive.png"));

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(0, result.cosmeticCount(), "只有 emissive.png 时不能把它当主贴图");
        assertTrue(hasWarning(result, "贴图文件不存在"), result.warnings().toString());
    }

    @Test
    void explicitEmissiveInMetaJsonIsHonoured() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo"}
                """);
        Path dir = createStandardCosmetic(pack, "crown");
        writeBytes(pack.resolve("shared/glow.png"));
        writeFile(dir.resolve("meta.json"), """
                {"emissive": "shared/glow.png"}
                """);

        PackLoader.LoadResult result = PackLoader.reload(root);

        CosmeticDefinition definition = result.packs().get(0).cosmetics().get(0);
        assertNotNull(definition.emissiveFile());
        assertEquals("glow.png", definition.emissiveFile().getFileName().toString());
    }

    // ==================== 安全：路径逃逸 ====================

    @Test
    void rejectsPathTraversalInDeclaredEntry() throws IOException {
        writeBytes(root.getParent().resolve("outside.png"));
        writeFile(root.getParent().resolve("outside.geo.json"), MODEL_JSON);
        createPack("demo", """
                {"id": "demo", "cosmetics": [
                  {"id": "evil", "model": "../outside.geo.json", "texture": "../outside.png"}
                ]}
                """);

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(0, result.cosmeticCount(), "不能读到包目录之外的文件");
        assertTrue(hasWarning(result, "模型文件不存在"), result.warnings().toString());
    }

    @Test
    void rejectsAbsolutePathInDeclaredEntry() throws IOException {
        createPack("demo", """
                {"id": "demo", "cosmetics": [
                  {"id": "evil", "model": "/etc/hostname", "texture": "/etc/hostname"}
                ]}
                """);

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(0, result.cosmeticCount());
    }

    // ==================== scale 非法值回退 ====================

    @Test
    void invalidScaleFallsBackToOne() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo", "cosmetics": [{"id": "crown", "scale": -2}]}
                """);
        createStandardCosmetic(pack, "crown");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1.0F, result.packs().get(0).cosmetics().get(0).scale(), 1e-6);
        assertTrue(hasWarning(result, "scale 非法"), result.warnings().toString());
    }

    @Test
    void nonNumericScaleFallsBackToOne() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo", "cosmetics": [{"id": "crown", "scale": "巨大"}]}
                """);
        createStandardCosmetic(pack, "crown");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1.0F, result.packs().get(0).cosmetics().get(0).scale(), 1e-6);
    }

    @Test
    void hugeScaleIsClamped() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo", "cosmetics": [{"id": "crown", "scale": 5000}]}
                """);
        createStandardCosmetic(pack, "crown");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(100.0F, result.packs().get(0).cosmetics().get(0).scale(), 1e-6);
    }

    // ==================== 真实示例包 ====================

    @Test
    void loadsBundledDefaultPack() {
        // reload(root) 的 root 是"包含多个包目录"的父目录，所以这里给 assets/vmfashion
        Path bundledRoot = Path.of("src/main/resources/assets/vmfashion");
        assumeTrue(Files.isDirectory(bundledRoot.resolve("default_pack")), "不在项目根目录下运行，跳过");

        PackLoader.LoadResult result = PackLoader.reload(bundledRoot);

        assertEquals(1, result.packs().size(), result.warnings().toString());
        assertEquals(2, result.cosmeticCount());
        CosmeticDefinition crown = CosmeticRegistry.getInstance().find("example:crown");
        assertNotNull(crown);
        assertSame(CosmeticCategory.HAT, crown.category());
        assertEquals("head", crown.attachedBone());
        assertEquals("example:backboard", CosmeticRegistry.getInstance().find("backboard").id());
    }

    // ==================== 材质 / 动画名（v1.1） ====================

    @Test
    void parsesTranslucentMaterial() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo", "cosmetics": [
                  {"id": "veil", "category": "cape", "material": "translucent"}
                ]}
                """);
        createStandardCosmetic(pack, "veil");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.cosmeticCount(), result.warnings().toString());
        assertTrue(result.packs().get(0).cosmetics().get(0).translucent());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void defaultsToCutoutAndAcceptsLegacyBoolean() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo", "cosmetics": [
                  {"id": "solid", "category": "hat"},
                  {"id": "veil", "category": "cape", "translucent": true}
                ]}
                """);
        createStandardCosmetic(pack, "solid");
        createStandardCosmetic(pack, "veil");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(2, result.cosmeticCount(), result.warnings().toString());
        assertFalse(result.packs().get(0).cosmetics().get(0).translucent());
        assertTrue(result.packs().get(0).cosmetics().get(1).translucent());
    }

    @Test
    void warnsOnUnknownMaterialAndFallsBackToCutout() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo", "cosmetics": [
                  {"id": "crown", "category": "hat", "material": "glassy"}
                ]}
                """);
        createStandardCosmetic(pack, "crown");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.cosmeticCount());
        assertFalse(result.packs().get(0).cosmetics().get(0).translucent());
        assertTrue(hasWarning(result, "material"), result.warnings().toString());
    }

    @Test
    void parsesAnimationName() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo", "cosmetics": [
                  {"id": "crown", "category": "hat", "animationName": "float"}
                ]}
                """);
        Path dir = createStandardCosmetic(pack, "crown");
        writeFile(dir.resolve("anim.json"), "{}");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals("float", result.packs().get(0).cosmetics().get(0).animationName());
    }

    @Test
    void blankAnimationNameMeansFirstAnimation() throws IOException {
        Path pack = createPack("demo", """
                {"id": "demo", "cosmetics": [{"id": "crown", "category": "hat"}]}
                """);
        createStandardCosmetic(pack, "crown");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals("", result.packs().get(0).cosmetics().get(0).animationName());
    }

    // ==================== 工具 ====================

    private void assertAnimationDiscovered(String packDirName, String animationFileName) throws IOException {
        Path pack = createPack(packDirName, """
                {"id": "%s"}
                """.formatted(packDirName));
        Path dir = createStandardCosmetic(pack, "crown");
        writeFile(dir.resolve(animationFileName), "{}");

        PackLoader.LoadResult result = PackLoader.reload(root);

        assertEquals(1, result.cosmeticCount(), result.warnings().toString());
        Path animation = result.packs().get(0).cosmetics().get(0).animationFile();
        assertNotNull(animation, "应能自动发现 " + animationFileName);
        assertEquals(animationFileName, animation.getFileName().toString());
    }

    /** 建一个包目录并写 pack.json，返回包目录。 */
    private Path createPack(String dirName, String manifestJson) throws IOException {
        Path pack = root.resolve(dirName);
        writeFile(pack.resolve("pack.json"), manifestJson);
        return pack;
    }

    /** 建一个标准布局的时装目录（model.geo.json + texture.png）。 */
    private Path createStandardCosmetic(Path packDir, String localId) throws IOException {
        Path dir = packDir.resolve("cosmetics").resolve(localId);
        writeFile(dir.resolve("model.geo.json"), MODEL_JSON);
        writeBytes(dir.resolve("texture.png"));
        return dir;
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void writeBytes(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, PNG_BYTES);
    }

    private static boolean hasWarning(PackLoader.LoadResult result, String needle) {
        return result.warnings().stream().anyMatch(warning -> warning.contains(needle));
    }
}
