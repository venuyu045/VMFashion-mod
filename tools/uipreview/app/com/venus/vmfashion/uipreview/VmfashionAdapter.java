package com.venus.vmfashion.uipreview;

import com.venus.vmfashion.cosmetic.LocalLoadout;
import com.venus.vmfashion.cosmetic.WardrobeCollection;
import com.venus.vmfashion.gui.PingSettingsScreen;
import com.venus.vmfashion.gui.SettingsScreen;
import com.venus.vmfashion.gui.WardrobeScreen;
import com.venus.vmfashion.sync.AssetSyncService;
import com.venus.vmfashion.sync.SyncService;
import com.venus.vmfashion.ui.UIState;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import uipreview.UiPreviewAdapter;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Venus Mc Fashion 的 UI 预览适配器 —— 预览器内核里<b>唯一</b>知道这个 mod 的地方。
 *
 * <p>换一个 mod 复用时，照着这个类写一份新的（改类名、{@link #modId()}、
 * {@link #createScreen()}、场景表），内核代码一行都不用动。详见
 * {@code tools/uipreview/PORTING.md}。
 *
 * <p>这个类做三件事：
 * <ol>
 *   <li>告诉内核"预览哪个界面"（{@link #createScreen()}）；</li>
 *   <li>按场景造测试数据（{@link #installFixtures}）；</li>
 *   <li>定义场景：每个场景要在界面上做什么操作、鼠标放哪（{@link #scenarios()}）。</li>
 * </ol>
 *
 * <p><b>为什么这里会出现反射：</b>场景要"点第 3 个分类 tab"，但 tab 的坐标是界面内部
 * 算出来的私有字段（{@code tabs}），没有公开 API。反射读它比在测试里硬编码坐标稳 ——
 * 布局改了预览会跟着走，而硬编码的坐标会静默点空。
 */
public final class VmfashionAdapter implements UiPreviewAdapter {

    /** 鼠标左键编号（MC 26.3 用 SDL3，1-based；见 WardrobeScreen#LEFT_BUTTON）。 */
    private static final int LEFT_BUTTON = 1;

    @Override
    public String modId() {
        return "vmfashion";
    }

    @Override
    public String displayName() {
        return "VMFashion · 衣柜界面";
    }

    @Override
    public Screen createScreen() {
        return new WardrobeScreen(null);
    }

    @Override
    public Screen createScreen(String scenario) {
        // v2.0 起一个 mod 有三个界面：衣柜（主界面）+ 时装设置 + 标点设置。
        // 预览器按场景名决定创建哪个，内核不需要知道这些类。
        return switch (scenario) {
            case "settings" -> new SettingsScreen(null);
            case "ping_settings" -> new PingSettingsScreen(null);
            default -> new WardrobeScreen(null);
        };
    }

    @Override
    public void installFixtures(String scenario, Path workDir) throws Exception {
        // 场景之间必须完全隔离：UIState 是"记住上次选中的分类"，同一 JVM 里连续跑多个场景时
        // 上一个场景点的 tab 会被下一个场景继承（--all 时表现为"后面的场景只剩 2 件"）。
        // 内核不知道项目有哪些静态状态，这一步只能由适配器负责。
        UIState.resetForPreview();

        // 设置界面不依赖时装数据：跳过 fixtures 安装，场景之间保持独立
        if ("settings".equals(scenario) || "ping_settings".equals(scenario)) {
            return;
        }

        boolean empty = "empty".equals(scenario);
        List<Fixtures.Spec> specs = empty ? Fixtures.emptySpecs() : Fixtures.defaultSpecs();
        // 预置"已穿深渊龙翼"，这样"穿上/脱下"按钮的初始态是有意义的（而不是两个都禁用）
        Fixtures.install(workDir, specs, empty ? null : "dragon_wings");

        SyncService.setState("not_linked".equals(scenario)
                ? SyncService.State.NOT_LOGGED_IN
                : SyncService.State.LOGGED_IN);

        AssetSyncService.clearState();
        if ("missing_pack".equals(scenario)) {
            // "凤凰斗篷"本地没装包 → 标记成正在下载，验证卡片上的「下载中」角标
            AssetSyncService.setPending("preview:phoenix_cloak");
        }
    }

    @Override
    public Map<String, Scenario> scenarios() {
        Map<String, Scenario> map = new LinkedHashMap<>();
        map.put("default", Scenario.of("default", "默认（9 件时装）"));
        map.put("hover_card", Scenario.of("hover_card", "悬停卡片", screen -> { },
                VmfashionAdapter::firstCardCenter));
        map.put("search", Scenario.of("search", "搜索过滤", screen -> typeIntoSearch(screen, "翼")));
        map.put("search_empty", Scenario.of("search_empty", "搜索无结果",
                screen -> typeIntoSearch(screen, "zzz不存在")));
        map.put("category_wings", Scenario.of("category_wings", "翅膀分类",
                screen -> clickTab(screen, 2)));
        map.put("empty", Scenario.of("empty", "空衣柜"));
        map.put("not_linked", Scenario.of("not_linked", "未绑定网站"));
        map.put("missing_pack", Scenario.of("missing_pack", "未下载时装包"));
        map.put("settings", Scenario.of("settings", "时装设置界面"));
        map.put("ping_settings", Scenario.of("ping_settings", "标点设置界面"));
        return map;
    }

    @Override
    public Map<String, Object> describeState(Screen screen) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("collection", WardrobeCollection.getInstance().size());
        state.put("worn", LocalLoadout.getInstance().snapshot());
        return state;
    }

    // ==================== 场景动作 ====================

    /** 通过界面的真实输入链路打字（走 EditBox#charTyped → responder → 过滤）。 */
    private static void typeIntoSearch(Screen screen, String text) {
        EditBox box = findSearchBox(screen);
        if (box == null) {
            return;
        }
        box.setFocused(true);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            screen.charTyped(new CharacterEvent(cp));
            i += Character.charCount(cp);
        }
    }

    /** 点第 {@code index} 个分类 tab（0 = 全部）。 */
    private static void clickTab(Screen screen, int index) {
        int[] center = tabCenter(screen, index);
        if (center == null) {
            return;
        }
        screen.mouseClicked(new MouseButtonEvent(center[0], center[1],
                new MouseButtonInfo(LEFT_BUTTON, 0)), false);
    }

    /** 第一张卡片的中心点（悬停场景用）。 */
    private static int[] firstCardCenter(Screen screen, int width, int height) {
        try {
            int gridX = (int) readField(screen, "gridX");
            int gridY = (int) readField(screen, "gridY");
            int cardWidth = (int) readField(screen, "cardWidth");
            int cardHeight = (int) readField(screen, "cardHeight");
            return new int[]{gridX + cardWidth / 2, gridY + cardHeight / 2};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new int[]{width / 2, height / 2};
        }
    }

    private static int[] tabCenter(Screen screen, int index) {
        try {
            Object tabsValue = readField(screen, "tabs");
            if (!(tabsValue instanceof List<?> tabs) || index < 0 || index >= tabs.size()) {
                return null;
            }
            Object tab = tabs.get(index);
            int x = (int) invokeMember(tab, "x");
            int y = (int) invokeMember(tab, "y");
            int w = (int) invokeMember(tab, "width");
            int h = (int) invokeMember(tab, "height");
            return new int[]{x + w / 2, y + h / 2};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static EditBox findSearchBox(Screen screen) {
        try {
            Object searchField = readField(screen, "searchField");
            if (searchField == null) {
                return null;
            }
            var getWidget = searchField.getClass().getMethod("getWidget");
            return (EditBox) getWidget.invoke(searchField);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invokeMember(Object target, String name) throws ReflectiveOperationException {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
