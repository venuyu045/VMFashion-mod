package com.venus.vmfashion.uipreview;

import com.venus.vmfashion.cosmetic.CosmeticCategory;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.cosmetic.CosmeticPack;
import com.venus.vmfashion.cosmetic.LocalLoadout;
import com.venus.vmfashion.cosmetic.RegistryInjector;
import com.venus.vmfashion.cosmetic.WardrobeCollection;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 预览器的测试数据 —— 造一套"看起来像真玩家衣柜"的时装。
 *
 * <p>为什么不用真实包加载（{@code PackLoader}）：那条路要读 zip、解析 {@code pack.json}、
 * 依赖资源管理器，跑起来重且脆。预览器要的是<b>可控、可重复</b>的数据：
 * 每件时装有几件、什么分类、穿没穿、有没有下载失败，全部由场景决定，
 * 这样"6 件时网格排几列"、"13 件时滚动条在哪"这类布局问题才能稳定复现。
 *
 * <p>贴图是现生成的（渐变 + 边框 + 序号），不是纯色块 —— 纯色块看不出
 * UV 拉伸和缩放问题，有图案才看得出缩略图有没有被压扁。
 */
public final class Fixtures {

    /** 一件测试时装的定义。 */
    public record Spec(String localId, String name, CosmeticCategory category, int rgb, boolean installed) {
    }

    private Fixtures() {
    }

    /** 默认场景：9 件时装，覆盖全部 7 个分类，其中 1 件"未下载"。 */
    public static List<Spec> defaultSpecs() {
        return List.of(
                new Spec("dragon_wings", "深渊龙翼", CosmeticCategory.WINGS, 0x7C3AED, true),
                new Spec("royal_cape", "皇家披风", CosmeticCategory.CAPE, 0xDC2626, true),
                new Spec("crystal_crown", "水晶皇冠", CosmeticCategory.HAT, 0xFBBF24, true),
                new Spec("angel_wings", "炽天使之翼", CosmeticCategory.WINGS, 0xF8FAFC, true),
                new Spec("shadow_backpack", "暗影背饰", CosmeticCategory.BACK, 0x334155, true),
                new Spec("mini_dragon", "迷你幼龙", CosmeticCategory.PET, 0x22C55E, true),
                new Spec("holy_aura", "圣光光环", CosmeticCategory.AURA, 0x38BDF8, true),
                new Spec("pumpkin_hat", "南瓜帽", CosmeticCategory.HAT, 0xF97316, true),
                // 这一件故意"本地没装包"，用来验证卡片上的「未下载」角标 + 点一下触发下发
                new Spec("phoenix_cloak", "凤凰斗篷", CosmeticCategory.CAPE, 0xEF4444, false)
        );
    }

    /** 空衣柜场景：一件都没有。 */
    public static List<Spec> emptySpecs() {
        return List.of();
    }

    /**
     * 把测试数据灌进生产代码的全局状态里。
     *
     * @param workDir 贴图生成目录（会被创建）
     * @param specs   要造的时装
     * @param worn    预先"穿上"的时装 localId（{@code null} = 什么都没穿）
     */
    public static void install(Path workDir, List<Spec> specs, String worn) throws IOException {
        Path textureDir = workDir.resolve("textures");
        Files.createDirectories(textureDir);

        List<CosmeticDefinition> definitions = new ArrayList<>();
        List<WardrobeCollection.Entry> collectionEntries = new ArrayList<>();

        for (int i = 0; i < specs.size(); i++) {
            Spec spec = specs.get(i);
            Path texture = textureDir.resolve(spec.localId() + ".png");
            if (spec.installed()) {
                writeTexture(texture, spec.rgb(), i);
            }
            CosmeticDefinition definition = new CosmeticDefinition(
                    "preview",
                    spec.localId(),
                    spec.name(),
                    spec.category(),
                    null,
                    spec.installed() ? texture : null,
                    null,
                    null,
                    spec.category().defaultBone(),
                    Set.of(),
                    1.0f);
            if (spec.installed()) {
                definitions.add(definition);
            }
            // 网站衣柜收藏里两件都有：装了的显示缩略图，没装的显示「未下载」角标
            collectionEntries.add(new WardrobeCollection.Entry(
                    definition.id(), spec.name(), spec.category().key(), "preview", !spec.installed()));
        }

        CosmeticPack pack = new CosmeticPack("preview", "预览时装包", "1.0.0",
                "ui-preview", "MIT", workDir, definitions);
        RegistryInjector.inject(List.of(pack));

        // 模拟"已绑定网站账号 + 衣柜收藏已加载"
        WardrobeCollection.getInstance().replaceAll(true, collectionEntries);

        LocalLoadout.getInstance().clear();
        if (worn != null) {
            for (CosmeticDefinition definition : definitions) {
                if (definition.localId().equals(worn)) {
                    LocalLoadout.getInstance().wear(definition);
                    break;
                }
            }
        }
    }

    /**
     * 生成一张 64×64 的测试贴图：对角渐变 + 2px 外框 + 中间一块高亮，
     * 角落再画一个序号 —— 缩略图被拉伸/裁切时一眼能看出来。
     */
    private static void writeTexture(Path path, int rgb, int index) throws IOException {
        int size = 64;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            Color base = new Color(0xFF000000 | rgb, true);
            for (int y = 0; y < size; y++) {
                float t = (float) y / (size - 1);
                Color row = blend(base, Color.WHITE, t * 0.35f);
                g.setColor(row);
                g.fillRect(0, y, size, 1);
            }
            g.setColor(blend(base, Color.BLACK, 0.45f));
            g.fillRect(0, 0, size, 2);
            g.fillRect(0, size - 2, size, 2);
            g.fillRect(0, 0, 2, size);
            g.fillRect(size - 2, 0, 2, size);

            g.setColor(new Color(0x55FFFFFF, true));
            g.fillRect(size / 4, size / 4, size / 2, size / 2);

            // 序号：用 8×8 的方块拼，纯像素风，和游戏内观感一致
            g.setColor(new Color(0xFFFFFFFF, true));
            int digit = index % 10;
            drawDigit(g, digit, 6, 6);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "PNG", path.toFile());
    }

    /** 3×5 点阵数字（放大到 4px 一格）。 */
    private static void drawDigit(Graphics2D g, int digit, int x, int y) {
        String[] patterns = {
                "111101101101111", "010110010010111", "111001111100111", "111001111001111",
                "101101111001001", "111100111001111", "111100111101111", "111001001001001",
                "111101111101111", "111101111001111"
        };
        String pattern = patterns[Math.max(0, Math.min(9, digit))];
        int cell = 4;
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 3; col++) {
                if (pattern.charAt(row * 3 + col) == '1') {
                    g.fillRect(x + col * cell, y + row * cell, cell, cell);
                }
            }
        }
    }

    private static Color blend(Color from, Color to, float t) {
        int r = Math.round(from.getRed() + (to.getRed() - from.getRed()) * t);
        int gr = Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t);
        int b = Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t);
        return new Color(r, gr, b, from.getAlpha());
    }
}
