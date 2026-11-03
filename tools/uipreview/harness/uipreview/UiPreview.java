package uipreview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Lang;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * UI 预览器主入口：跑真实 {@code Screen}，把界面渲染成 PNG。
 *
 * <p><b>这个类不认识任何具体 mod</b> —— "预览哪个界面、造什么数据、有哪些场景"
 * 全部通过 {@link UiPreviewAdapter} 问出来。换项目复用时只需要写一个新的适配器，
 * 见 {@code tools/uipreview/PORTING.md}。
 *
 * <p>整条链路（除了最后的"画像素"，全部是生产代码）：
 * <pre>
 * 语言文件 ──Lang──▶ Component.translatable 解析
 * adapter.installFixtures ──▶ 你的 registry / fake 状态
 * Minecraft 单例（轻量替身）──▶ Screen 的 font / gui / level
 * Screen#init(w, h)                 ← 真实布局计算
 * Screen#extractRenderState(...)    ← 真实绘制逻辑，产出 DrawCommand 列表
 * Java2DRenderer                    ← 唯一的"预览器自己的代码"：照单画图
 * </pre>
 */
public final class UiPreview {

    private UiPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path modDir = Path.of(System.getProperty("vmf.modDir", ".")).toAbsolutePath().normalize();
        Options options = Options.parse(args, modDir);
        Files.createDirectories(options.outDir());

        // 0) 项目适配层（反射实例化，内核不依赖任何项目类）
        UiPreviewAdapter adapter = loadAdapter(options.adapterClass());

        // 1) 语言：让 Component.translatable 解析出真实文案（含中文标点 / emoji）
        Path langFile = modDir.resolve("src/main/resources/assets/" + adapter.modId()
                + "/lang/" + options.locale() + ".json");
        if (!Files.isRegularFile(langFile)) {
            throw new IllegalStateException("找不到语言文件: " + langFile);
        }
        Map<String, String> langTable = MiniJson.readFlatObject(Files.readString(langFile));
        Lang.install(langTable);

        // 2) Minecraft 单例：Screen 的构造会读 font / gui / level
        Minecraft minecraft = Minecraft.ensureInstance();
        minecraft.level = new net.minecraft.client.multiplayer.ClientLevel();
        minecraft.window = new com.mojang.blaze3d.platform.Window(options.width(), options.height());

        // 3) MC 原生 GUI 精灵图（按钮 / 输入框底图）
        int spriteCount = PreviewTextures.loadMcSprites(modDir.resolve("tools/uipreview/assets/mc"));
        System.out.printf("适配器 %s（modId=%s）| 语言 %s（%d 条）| MC 精灵图 %d 张%n",
                adapter.getClass().getSimpleName(), adapter.modId(),
                options.locale(), langTable.size(), spriteCount);

        // 4) 逐场景出图（--all 时遍历适配器声明的全部场景，脚本里不再硬编码场景名）
        if (options.all()) {
            for (String name : adapter.scenarios().keySet()) {
                renderScenario(adapter, options, name);
            }
        } else {
            renderScenario(adapter, options, options.scenario());
        }
    }

    /** 渲染单个场景并写 PNG（+ 可选布局自检）。 */
    private static void renderScenario(UiPreviewAdapter adapter, Options options, String scenarioName)
            throws Exception {
        UiPreviewAdapter.Scenario scenario = adapter.scenarios().get(scenarioName);
        if (scenario == null) {
            throw new IllegalArgumentException("未知场景: " + options.scenario()
                    + "（可用：" + adapter.scenarios().keySet() + "）");
        }

        // 5) 按场景灌测试数据 + 造界面
        Path workDir = options.outDir().resolve("fixtures");
        adapter.installFixtures(scenario.name(), workDir);

        Screen screen = adapter.createScreen(scenario.name());
        screen.init(options.width(), options.height());

        // 6) 场景前置操作（搜索词 / 分类切换 / 切状态）
        scenario.setup().accept(screen);

        // 7) 鼠标位置：场景可以按界面算出来的布局定位，也可以固定坐标
        int[] mouse = scenario.mouse() == null
                ? new int[]{options.width() - 1, options.height() - 1}
                : scenario.mouse().locate(screen, options.width(), options.height());
        if (mouse == null) {
            mouse = new int[]{options.width() - 1, options.height() - 1};
        }

        // 8) 录制 + 渲染
        GuiGraphicsExtractor context = new GuiGraphicsExtractor(options.width(), options.height());
        screen.extractRenderState(context, mouse[0], mouse[1], 0.0f);

        Java2DRenderer renderer = new Java2DRenderer(options.scale());
        BufferedImage image = renderer.render(context.commands(), options.width(), options.height());

        LayoutCheck layoutCheck = new LayoutCheck(options.width(), options.height())
                .check(context.commands());

        String fileName = "preview-" + scenario.name().toLowerCase() + "-"
                + options.locale() + "-" + options.width() + "x" + options.height() + ".png";
        Path outFile = options.outDir().resolve(fileName);
        ImageIO.write(image, "PNG", outFile.toFile());

        System.out.printf("场景 %s | 逻辑 %dx%d × %d 倍 | 命令 %d 条 | 回退字体 %s | 输出 %s%n",
                scenario.name(), options.width(), options.height(), options.scale(),
                context.commands().size(), renderer.fallbackFontName(), outFile);
        System.out.println("界面尺寸: " + screen.width + "x" + screen.height);
        Map<String, Object> state = adapter.describeState(screen);
        if (!state.isEmpty()) {
            System.out.println("状态: " + state);
        }
        if (options.report()) {
            String report = layoutCheck.report();
            System.out.print(report);
            Path reportFile = options.outDir().resolve(
                    "report-" + scenario.name().toLowerCase() + "-" + options.locale() + ".txt");
            Files.writeString(reportFile, report + "\n");
            System.out.println("自检报告: " + reportFile);
        }
    }

    /**
     * 反射实例化项目适配器。
     *
     * <p>用反射而不是编译期依赖，是因为内核源码集<b>不能</b> import 任何项目类 ——
     * 否则"复制到另一个项目"就得改内核代码，复用就无从谈起。
     */
    static UiPreviewAdapter loadAdapter(String className) throws ReflectiveOperationException {
        Class<?> type = Class.forName(className);
        if (!UiPreviewAdapter.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(className + " 没有实现 uipreview.UiPreviewAdapter");
        }
        return (UiPreviewAdapter) type.getDeclaredConstructor().newInstance();
    }

    /** 命令行参数。 */
    public record Options(Path outDir, int width, int height, int scale, String locale,
                          String scenario, String adapterClass, boolean report, boolean all) {

        public static Options parse(String[] args, Path modDir) {
            Path out = modDir.resolve("build/uipreview");
            int width = 640;
            int height = 360;
            int scale = 2;
            String locale = "zh_cn";
            String scenario = "default";
            String adapterClass = System.getProperty("uipreview.adapter",
                    "com.venus.vmfashion.uipreview.VmfashionAdapter");
            boolean report = false;
            boolean all = false;

            for (String arg : args) {
                if (arg.startsWith("--out=")) {
                    out = Path.of(arg.substring("--out=".length()));
                } else if (arg.startsWith("--width=")) {
                    width = Integer.parseInt(arg.substring("--width=".length()));
                } else if (arg.startsWith("--height=")) {
                    height = Integer.parseInt(arg.substring("--height=".length()));
                } else if (arg.startsWith("--scale=")) {
                    scale = Integer.parseInt(arg.substring("--scale=".length()));
                } else if (arg.startsWith("--locale=")) {
                    locale = arg.substring("--locale=".length());
                } else if (arg.startsWith("--scenario=")) {
                    scenario = arg.substring("--scenario=".length()).toLowerCase();
                } else if (arg.startsWith("--adapter=")) {
                    adapterClass = arg.substring("--adapter=".length());
                } else if (arg.equals("--report")) {
                    report = true;
                } else if (arg.equals("--all")) {
                    all = true;
                } else {
                    throw new IllegalArgumentException("未知参数: " + arg);
                }
            }
            if (width < 320 || height < 180) {
                throw new IllegalArgumentException("逻辑分辨率太小：" + width + "x" + height);
            }
            if (scale < 1 || scale > 6) {
                throw new IllegalArgumentException("放大倍数需在 1..6：" + scale);
            }
            return new Options(out, width, height, scale, locale, scenario, adapterClass, report, all);
        }
    }
}
