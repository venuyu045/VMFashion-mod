package uipreview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Lang;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次"可交互预览"会话：持有一个真实 {@link Screen}，接收鼠标/键盘事件，
 * 每收到一个事件就重新录制一帧并渲染成 PNG。
 *
 * <p><b>与具体 mod 无关</b>：界面由 {@link UiPreviewAdapter#createScreen()} 造，
 * 场景由适配器定义，内核只负责"喂事件 + 出图"。
 *
 * <p>会话是有状态的：焦点、搜索词、滚动位置、选中项全部保存在界面实例里，
 * 跨事件累积 —— 与真实界面一致。
 */
public final class PreviewSession {

    private final Path modDir;
    private final int width;
    private final int height;
    private final int scale;
    private final String locale;
    private final UiPreviewAdapter adapter;
    private final Map<String, String> langTable;

    private Screen screen;
    private Java2DRenderer renderer;
    private int mouseX;
    private int mouseY;
    private int lastCommandCount;

    public PreviewSession(Path modDir, int width, int height, int scale, String locale,
                          UiPreviewAdapter adapter) throws IOException {
        this.modDir = modDir;
        this.width = width;
        this.height = height;
        this.scale = scale;
        this.locale = locale;
        this.adapter = adapter;

        Path langFile = modDir.resolve("src/main/resources/assets/" + adapter.modId()
                + "/lang/" + locale + ".json");
        if (!Files.isRegularFile(langFile)) {
            throw new IllegalStateException("找不到语言文件: " + langFile);
        }
        this.langTable = MiniJson.readFlatObject(Files.readString(langFile));
        Lang.install(langTable);

        Minecraft minecraft = Minecraft.ensureInstance();
        minecraft.level = new net.minecraft.client.multiplayer.ClientLevel();
        minecraft.window = new com.mojang.blaze3d.platform.Window(width, height);

        PreviewTextures.loadMcSprites(modDir.resolve("tools/uipreview/assets/mc"));
        this.renderer = new Java2DRenderer(scale);

        reset("default");
    }

    /** 重置成指定场景（重新造数据 + 重建界面 + 执行场景动作）。 */
    public void reset(String scenarioName) {
        String name = scenarioName == null ? "default" : scenarioName.toLowerCase();
        UiPreviewAdapter.Scenario scenario = adapter.scenarios().get(name);
        if (scenario == null) {
            scenario = adapter.scenarios().get("default");
        }
        try {
            adapter.installFixtures(scenario == null ? "default" : scenario.name(),
                    modDir.resolve("build/uipreview/fixtures"));
        } catch (Exception e) {
            throw new IllegalStateException("造测试数据失败", e);
        }

        this.screen = adapter.createScreen();
        this.screen.init(width, height);
        if (scenario != null) {
            scenario.setup().accept(screen);
        }
        int[] mouse = scenario == null || scenario.mouse() == null
                ? null : scenario.mouse().locate(screen, width, height);
        if (mouse == null) {
            this.mouseX = width - 1;
            this.mouseY = height - 1;
        } else {
            this.mouseX = clampX(mouse[0]);
            this.mouseY = clampY(mouse[1]);
        }
    }

    public Screen screen() {
        return screen;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int scale() {
        return scale;
    }

    public String locale() {
        return locale;
    }

    public UiPreviewAdapter adapter() {
        return adapter;
    }

    public int lastCommandCount() {
        return lastCommandCount;
    }

    // ==================== 事件 ====================

    /** 鼠标移动（只影响悬停态）。 */
    public void mouseMove(int x, int y) {
        this.mouseX = clampX(x);
        this.mouseY = clampY(y);
    }

    /**
     * 鼠标点击。
     *
     * <p>{@code button} 用 <b>SDL3 编号</b>（左 1 / 中 2 / 右 3）—— MC 26.3 换用 SDL3 后
     * 按钮编号是 1-based，原版 {@code AbstractWidget#isValidClickButton} 只认 1。
     * 网页端把浏览器的 0-based {@code MouseEvent.button} 加 1 再传过来。
     */
    public void mouseClick(int x, int y, int button) {
        this.mouseX = clampX(x);
        this.mouseY = clampY(y);
        screen.mouseClicked(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), false);
    }

    /**
     * 鼠标松开。
     *
     * <p>很多界面把"点击"拆成两步：{@code mouseClicked} 里只记下"开始拖拽"，
     * 真正的"没拖动 → 当作点击"逻辑在 {@code mouseReleased} 里（分组窗口的折叠/展开就是）。
     * 只发 down 不发 up，这些操作在预览里会一直卡在拖拽状态。
     */
    public void mouseRelease(int x, int y, int button) {
        this.mouseX = clampX(x);
        this.mouseY = clampY(y);
        screen.mouseReleased(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)));
    }

    /** 滚轮。{@code delta} 正数 = 向上滚（与游戏内 {@code mouseScrolled} 一致）。 */
    public void scroll(int x, int y, double delta) {
        this.mouseX = clampX(x);
        this.mouseY = clampY(y);
        screen.mouseScrolled(mouseX, mouseY, 0.0, delta);
    }

    /** 输入一个字符（走 EditBox 的真实输入链）。 */
    public void typeChar(int codepoint) {
        screen.charTyped(new CharacterEvent(codepoint));
    }

    /** 输入一串文本。 */
    public void typeText(String text) {
        if (text == null) {
            return;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            screen.charTyped(new CharacterEvent(cp));
            i += Character.charCount(cp);
        }
    }

    /** 按键（GLFW 键码，如 259 = Backspace、256 = Esc）。 */
    public void keyPress(int keyCode) {
        screen.keyPressed(new KeyEvent(keyCode, 0, 0));
    }

    // ==================== 渲染 ====================

    /** 录制一帧并渲染成 PNG 字节。 */
    public byte[] renderPng() {
        BufferedImage image = renderImage();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG 编码失败", e);
        }
    }

    public BufferedImage renderImage() {
        GuiGraphicsExtractor context = new GuiGraphicsExtractor(width, height);
        screen.extractRenderState(context, mouseX, mouseY, 0.0f);
        lastCommandCount = context.commands().size();
        return renderer.render(context.commands(), width, height);
    }

    /** 会话信息（网页状态栏 + 自动化脚本用）。 */
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        GuiGraphicsExtractor context = new GuiGraphicsExtractor(width, height);
        screen.extractRenderState(context, mouseX, mouseY, 0.0f);
        lastCommandCount = context.commands().size();

        info.put("title", adapter.displayName());
        info.put("modId", adapter.modId());
        info.put("width", width);
        info.put("height", height);
        info.put("scale", scale);
        info.put("locale", locale);
        info.put("langEntries", langTable.size());
        info.put("commands", lastCommandCount);
        info.put("fallbackFont", renderer.fallbackFontName());
        info.put("textures", PreviewTextures.size());

        Map<String, Object> scenarios = new LinkedHashMap<>();
        adapter.scenarios().forEach((name, scenario) -> scenarios.put(name, scenario.label()));
        info.put("scenarios", scenarios);

        info.put("state", adapter.describeState(screen));
        info.put("issues", new LayoutCheck(width, height).check(context.commands()).issues().stream()
                .map(issue -> "[" + issue.kind() + "] " + issue.detail())
                .toList());
        return info;
    }

    private int clampX(int x) {
        return Math.max(0, Math.min(width - 1, x));
    }

    private int clampY(int y) {
        return Math.max(0, Math.min(height - 1, y));
    }
}
