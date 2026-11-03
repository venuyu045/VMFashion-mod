package com.venus.vmfashion.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UI 预览器专用的 {@code UIState} <b>替身</b>（编译时替换真实类）。
 *
 * <p><b>为什么必须替掉：</b>真实类的静态字段会调
 * {@code FabricLoader.getInstance().getConfigDir()} —— 预览器跑在普通 JVM 里，
 * Fabric 加载器根本没启动，于是类初始化直接抛 {@code ExceptionInInitializerError}。
 * 界面代码会 catch 住（衣柜不会崩），但每次打开都往日志里刷一条 WARN，
 * 而且"记住上次选的分类"这个功能在预览里<b>永远失效</b>，没法验证。
 *
 * <p>替身把状态放在内存里：同一个 JVM 内 {@link #load()} 返回同一份，
 * 所以"切到翅膀分类 → 重开界面 → 还是翅膀"这条行为在预览里能真实复现。
 */
public class UIState {

    /** 窗口位置/尺寸（预览器不需要，保留类型以满足引用）。 */
    public static class WindowState {
        public int x;
        public int y;
        public boolean expanded;
        public int width = -1;
        public int maxHeight = -1;

        public WindowState() {
        }

        public WindowState(int x, int y, boolean expanded) {
            this.x = x;
            this.y = y;
            this.expanded = expanded;
        }
    }

    private static UIState instance;

    private final Map<String, WindowState> windowStates = new HashMap<>();
    private final List<String> renderOrder = new ArrayList<>();
    private String wardrobeCategory = "all";

    private UIState() {
    }

    public static UIState load() {
        if (instance == null) {
            instance = new UIState();
        }
        return instance;
    }

    /** 预览器不落盘：{@code save()} 是空操作，状态活在内存里。 */
    public void save() {
    }

    public String getWardrobeCategory() {
        return wardrobeCategory;
    }

    public void setWardrobeCategory(String wardrobeCategory) {
        this.wardrobeCategory = wardrobeCategory == null ? "all" : wardrobeCategory;
    }

    /**
     * 取窗口状态。
     *
     * <p><b>没有记录时返回 {@code null}</b>（与真实类一致）：界面靠 {@code null} 判断
     * "这是新窗口，要分配默认位置"。写成 {@code computeIfAbsent} 会让所有窗口叠在 (0,0)。
     */
    public WindowState getWindowState(String windowId) {
        return windowStates.get(windowId);
    }

    public void setWindowState(String windowId, int x, int y, boolean expanded, int width, int maxHeight) {
        WindowState state = windowStates.computeIfAbsent(windowId, key -> new WindowState());
        state.x = x;
        state.y = y;
        state.expanded = expanded;
        state.width = width;
        state.maxHeight = maxHeight;
    }

    public void removeWindowState(String windowId) {
        windowStates.remove(windowId);
    }

    public Map<String, WindowState> getWindowStates() {
        return windowStates;
    }

    public List<String> getRenderOrder() {
        return renderOrder;
    }

    public void setRenderOrder(List<String> order) {
        renderOrder.clear();
        if (order != null) {
            renderOrder.addAll(order);
        }
    }

    /** 预览器内部：重置成"全新安装"的状态。 */
    public static void resetForPreview() {
        instance = null;
    }
}
