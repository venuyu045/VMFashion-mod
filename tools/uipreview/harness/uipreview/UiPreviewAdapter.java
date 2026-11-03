package uipreview;

import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <b>项目适配层</b> —— 预览器内核与"某个具体 mod"之间唯一的接缝。
 *
 * <p>预览器内核（stub 类、录制器、Java2D 渲染、HTTP 服务）里<b>不出现任何项目类名</b>，
 * 所有"要预览哪个界面、造什么假数据、有哪些场景"都通过这个接口问出来。
 * 换一个 mod 复用时，只需要写一个新的实现类，内核一行都不用改。
 *
 * <p>实现类通过 {@code --adapter=全限定类名} 或 {@code -Duipreview.adapter=...} 指定，
 * 内核用反射实例化（要求有 public 无参构造）。
 *
 * <p>一个最小实现只需要 {@link #modId()}、{@link #createScreen()} 和
 * {@link #installFixtures(String, Path)} 三个方法 —— 其余都有默认值。
 */
public interface UiPreviewAdapter {

    /**
     * 模组 ID（即 {@code fabric.mod.json} 里的 id）。
     *
     * <p>内核用它拼语言文件路径：{@code src/main/resources/assets/<modId>/lang/<locale>.json}。
     */
    String modId();

    /** 网页顶栏显示的名字；默认用 {@link #modId()}。 */
    default String displayName() {
        return modId();
    }

    /**
     * 创建一个要预览的界面。
     *
     * <p>应当返回"刚从游戏里打开"的状态 —— 通常就是 {@code new YourScreen(null)}。
     * 内核负责调 {@code init(width, height)} 和 {@code extractRenderState(...)}。
     */
    Screen createScreen();

    /**
     * 按场景创建界面（一个 mod 有多个界面时用）。
     *
     * <p>默认实现忽略场景名、退回 {@link #createScreen()}，所以只预览一个界面的适配器
     * 不用改。多界面适配器覆写本方法：例如 {@code "settings"} 返回设置界面。
     */
    default Screen createScreen(String scenario) {
        return createScreen();
    }

    /**
     * 按场景灌测试数据。
     *
     * <p>每次重置场景都会调用一次，实现里要保证<b>幂等</b>（重复调用不会叠加数据）。
     * 典型内容：往你的 registry 塞几件假数据、把 fake 的同步服务切到某个状态、
     * 预置一个"已穿戴"的槽位。
     *
     * @param scenario 场景名（见 {@link #scenarios()}）
     * @param workDir  可写的临时目录（生成测试贴图之类放这里）
     */
    void installFixtures(String scenario, Path workDir) throws Exception;

    /**
     * 场景表：场景名 → 场景定义。
     *
     * <p>场景名会成为命令行参数 {@code --scenario=xxx} 和网页下拉框的值，
     * 建议全小写 + 下划线。至少要有一个 {@code "default"}。
     */
    default Map<String, Scenario> scenarios() {
        Map<String, Scenario> only = new LinkedHashMap<>();
        only.put("default", Scenario.of("default", "默认"));
        return only;
    }

    /**
     * 给 {@code /info} 补充的状态字段（可选）。
     *
     * <p>只用于显示和自动化断言，内核不解释内容。
     */
    default Map<String, Object> describeState(Screen screen) {
        return Map.of();
    }

    /**
     * 一个预览场景。
     *
     * @param name  场景名（命令行 / 网页用）
     * @param label 网页下拉框里显示的中文名
     * @param setup 界面建好之后要做的操作（点某个 tab、打字、切状态…）
     * @param mouse 渲染时的鼠标位置；{@code null} 表示放到屏幕右下角（不触发悬停）
     */
    record Scenario(String name, String label, Consumer<Screen> setup, MouseLocator mouse) {

        /**
         * 鼠标定位器。
         *
         * <p>做成回调而不是固定坐标，是因为"悬停第 1 张卡片"这种位置<b>依赖界面自己算出来的布局</b>
         * （网格起点、卡片尺寸都随窗口大小和缩放变），在场景定义时还不知道。
         */
        @FunctionalInterface
        public interface MouseLocator {
            /**
             * @param screen 已经 {@code init} 过的界面
             * @param width  逻辑宽
             * @param height 逻辑高
             * @return {@code {x, y}}；返回 {@code null} 等同"放到右下角"
             */
            int[] locate(Screen screen, int width, int height);
        }

        public static Scenario of(String name, String label) {
            return new Scenario(name, label, screen -> { }, null);
        }

        public static Scenario of(String name, String label, Consumer<Screen> setup) {
            return new Scenario(name, label, setup, null);
        }

        public static Scenario of(String name, String label, Consumer<Screen> setup,
                                  int mouseX, int mouseY) {
            return new Scenario(name, label, setup, (screen, w, h) -> new int[]{mouseX, mouseY});
        }

        public static Scenario of(String name, String label, Consumer<Screen> setup, MouseLocator mouse) {
            return new Scenario(name, label, setup, mouse);
        }
    }
}
