package com.venus.vmfashion.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.venus.vmfashion.VMFashionClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UI 状态持久化 - 保存窗口位置、展开状态和渲染顺序
 */
public class UIState {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STATE_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("vmfashion").resolve("ui_state.json");

    // 窗口状态（key 为窗口标识）
    private Map<String, WindowState> windowStates = new HashMap<>();

    // 窗口渲染顺序（最后 = 最上层）
    private List<String> renderOrder = new ArrayList<>();

    /**
     * 衣柜上次选中的分类 key（{@code "all"} 或 {@link com.venus.vmfashion.cosmetic.CosmeticCategory#key()}）。
     *
     * <p>衣柜界面是玩家最常开的面板，每次都回到「全部」等于每次都要再点一次分类，
     * 因此把上次的分类记在这里（和其它窗口状态一起落盘）。
     */
    private String wardrobeCategory = "all";

    public static class WindowState {
        public int x;
        public int y;
        public boolean expanded;
        public int width = -1; // -1 表示使用默认动态宽度
        public int maxHeight = -1; // -1 表示使用默认高度

        public WindowState() {}

        public WindowState(int x, int y, boolean expanded) {
            this.x = x;
            this.y = y;
            this.expanded = expanded;
        }
    }

    /**
     * 加载 UI 状态
     */
    public static UIState load() {
        if (Files.exists(STATE_PATH)) {
            try {
                String json = Files.readString(STATE_PATH);
                UIState state = GSON.fromJson(json, UIState.class);
                if (state != null && state.windowStates != null) {
                    // 确保 renderOrder 不为 null（兼容旧版本数据）
                    if (state.renderOrder == null) {
                        state.renderOrder = new ArrayList<>();
                    }
                    return state;
                }
            } catch (Exception e) {
                VMFashionClient.LOGGER.warn("加载 UI 状态失败", e);
            }
        }
        return new UIState();
    }

    /**
     * 保存 UI 状态
     */
    public void save() {
        try {
            Files.createDirectories(STATE_PATH.getParent());
            Files.writeString(STATE_PATH, GSON.toJson(this));
        } catch (IOException e) {
            VMFashionClient.LOGGER.error("保存 UI 状态失败", e);
        }
    }

    /**
     * 获取窗口状态
     */
    public WindowState getWindowState(String windowId) {
        return windowStates.get(windowId);
    }

    /**
     * 设置窗口状态
     */
    public void setWindowState(String windowId, int x, int y, boolean expanded, int width, int maxHeight) {
        WindowState ws = new WindowState(x, y, expanded);
        ws.width = width;
        ws.maxHeight = maxHeight;
        windowStates.put(windowId, ws);
    }

    /**
     * 删除窗口状态
     */
    public void removeWindowState(String windowId) {
        windowStates.remove(windowId);
    }

    public Map<String, WindowState> getWindowStates() {
        return windowStates;
    }

    /**
     * 获取窗口渲染顺序
     */
    public List<String> getRenderOrder() {
        return renderOrder;
    }

    /** 衣柜上次选中的分类 key；{@code "all"} 表示「全部」。 */
    public String getWardrobeCategory() {
        return wardrobeCategory;
    }

    /** 记录衣柜当前分类（{@code "all"} 或分类 key）。 */
    public void setWardrobeCategory(String wardrobeCategory) {
        this.wardrobeCategory = wardrobeCategory == null || wardrobeCategory.isBlank()
                ? "all" : wardrobeCategory.trim();
    }

    /**
     * 设置窗口渲染顺序
     */
    public void setRenderOrder(List<String> renderOrder) {
        this.renderOrder = renderOrder;
    }
}
