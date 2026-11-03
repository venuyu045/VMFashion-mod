package com.venus.vmfashion.gui.component;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Toast 通知组件 - 显示操作反馈
 *
 * <p>文案支持 {@link Component}（推荐传 {@code Component.translatable(...)}，随语言切换），
 * 同时保留 {@code String} 重载给旧调用点。
 *
 * <p>是否显示受 {@link ModConfig#isToastEnabled()} 控制（路线图 §10 #2 的「死配置」之一）：
 * 关闭后所有 Toast 静默丢弃，调用方不需要自己判断。
 */
public class ToastWidget {

    /**
     * 正在显示的 Toast。
     *
     * <p>用 {@link CopyOnWriteArrayList} 而不是 {@code ArrayList}：Toast 可能从任意线程投递
     * （同步层的回调线程也会弹提示），而这里是渲染线程每帧遍历 —— 普通 ArrayList 在
     * "遍历时另一个线程 add" 会抛 {@link java.util.ConcurrentModificationException}，
     * 那是在渲染回调里，等于客户端崩溃。写入极少（一次点击一条），COW 的复制成本可以忽略。
     */
    private static final List<Toast> toasts = new CopyOnWriteArrayList<>();
    private static final int MAX_TOASTS = 5;
    private static final int TOAST_WIDTH = 200;
    private static final int TOAST_HEIGHT = 30;
    private static final int TOAST_MARGIN = 10;
    private static final long TOAST_DURATION = 3000; // 3秒
    private static final int FADE_DURATION = 500; // 淡入淡出时间
    /** Toast 面板的最大宽度（窄窗口下会被再夹一次，不能超出屏幕）。 */
    private static final int MAX_TOAST_WIDTH = 350;
    /** 留给图标 + 左右边距的宽度。 */
    private static final int TEXT_INSET = 50;
    /** 文字最大宽度（超过就换行）。 */
    private static final int MAX_TEXT_WIDTH = MAX_TOAST_WIDTH - TEXT_INSET;

    /**
     * 显示成功提示
     */
    public static void showSuccess(String message) {
        showSuccess(Component.literal(message));
    }

    /** 显示成功提示（可翻译文案）。 */
    public static void showSuccess(Component message) {
        addToast(message, ToastType.SUCCESS);
    }

    /**
     * 显示错误提示
     */
    public static void showError(String message) {
        showError(Component.literal(message));
    }

    /** 显示错误提示（可翻译文案）。 */
    public static void showError(Component message) {
        addToast(message, ToastType.ERROR);
    }

    /**
     * 显示警告提示
     */
    public static void showWarning(String message) {
        showWarning(Component.literal(message));
    }

    /** 显示警告提示（可翻译文案）。 */
    public static void showWarning(Component message) {
        addToast(message, ToastType.WARNING);
    }

    /**
     * 显示信息提示
     */
    public static void showInfo(String message) {
        showInfo(Component.literal(message));
    }

    /** 显示信息提示（可翻译文案）。 */
    public static void showInfo(Component message) {
        addToast(message, ToastType.INFO);
    }

    private static void addToast(Component message, ToastType type) {
        if (message == null || !isToastEnabled()) {
            return;
        }
        Toast toast = new Toast(message, type, System.currentTimeMillis());
        toasts.add(toast);

        // 限制最大数量（多线程投递时可能同时进来，用 while 收敛）
        while (toasts.size() > MAX_TOASTS) {
            toasts.remove(0);
        }
    }

    /**
     * Toast 总开关。
     *
     * <p>配置实例可能还没创建（初始化早期 / 单元测试），拿不到时按"显示"处理，
     * 否则一个空指针会让所有反馈都消失。
     */
    private static boolean isToastEnabled() {
        VMFashionClient client = VMFashionClient.getInstance();
        if (client == null) {
            return true;
        }
        ModConfig config = client.getConfig();
        return config == null || config.isToastEnabled();
    }

    /**
     * 在当前屏幕尺寸下渲染所有 Toast。
     *
     * 供各 Screen 在 extractRenderState 末尾统一调用：
     * Toast 是全局静态列表，只有在每个 Screen 的渲染末尾调用一次，
     * 各界面里的提示才都能显示出来。
     *
     * @param context 渲染上下文
     */
    public static void render(GuiGraphicsExtractor context) {
        Minecraft client = Minecraft.getInstance();
        if (client.getWindow() == null) return;
        // GUI 逻辑坐标 = 实际窗口像素 / GUI 缩放比例
        int guiWidth = client.getWindow().getGuiScaledWidth();
        int guiHeight = client.getWindow().getGuiScaledHeight();
        render(context, guiWidth, guiHeight);
    }

    /**
     * 渲染所有 Toast
     */
    public static void render(GuiGraphicsExtractor context, int screenWidth, int screenHeight) {
        Minecraft client = Minecraft.getInstance();
        if (client.font == null) return;

        long currentTime = System.currentTimeMillis();
        List<Toast> expired = null;
        int yOffset = 0;

        // 遍历的是 COW 快照：这里 remove 不能走 iterator（COW 的迭代器不支持），
        // 过期项先收集、循环结束后一次性摘掉（removeAll 也是 COW 的原子操作）。
        for (Toast toast : toasts) {
            long age = currentTime - toast.createdAt;

            // 检查是否过期
            if (age > TOAST_DURATION) {
                if (expired == null) {
                    expired = new ArrayList<>(2);
                }
                expired.add(toast);
                continue;
            }

            // 计算透明度
            float alpha;
            if (age < FADE_DURATION) {
                alpha = (float) age / FADE_DURATION;
            } else if (age > TOAST_DURATION - FADE_DURATION) {
                alpha = (float) (TOAST_DURATION - age) / FADE_DURATION;
            } else {
                alpha = 1.0f;
            }

            // 换行结果按 Toast 缓存（每条只算一次）：旧实现每帧都重新 O(n²) 量宽度 + 切字符串
            List<String> lines = toast.lines(client.font);

            // 计算实际宽度
            int actualTextWidth = 0;
            for (String line : lines) {
                actualTextWidth = Math.max(actualTextWidth, client.font.width(line));
            }
            // 面板宽度不能超出屏幕：窄窗口（GUI 缩放很大）下 old 代码 x 会变成负数，整条 Toast 跑到屏幕外
            int toastWidth = Math.min(Math.min(MAX_TOAST_WIDTH, screenWidth - 2 * TOAST_MARGIN),
                    Math.max(150, actualTextWidth + TEXT_INSET));
            toastWidth = Math.max(1, toastWidth);
            int toastHeight = lines.size() * 12 + 12; // 每行12px + 上下padding

            // 计算位置（右上角）
            int x = Math.max(TOAST_MARGIN, screenWidth - toastWidth - TOAST_MARGIN);
            int y = TOAST_MARGIN + yOffset;

            // 渲染背景
            int backgroundColor = toast.type.getBackgroundColor(alpha);
            context.fill(x, y, x + toastWidth, y + toastHeight, backgroundColor);

            // 渲染边框
            int borderColor = toast.type.getBorderColor(alpha);
            context.fill(x, y, x + toastWidth, y + 1, borderColor);
            context.fill(x, y + toastHeight - 1, x + toastWidth, y + toastHeight, borderColor);
            context.fill(x, y, x + 1, y + toastHeight, borderColor);
            context.fill(x + toastWidth - 1, y, x + toastWidth, y + toastHeight, borderColor);

            // 渲染图标（第一行旁边）；图标也跟着淡入淡出，否则文字透明了图标还是实心
            context.text(client.font, toast.type.getIcon(), x + 8, y + 6, toast.type.getTextColor(alpha));

            // 渲染文字（多行）
            int textColor = toast.type.getTextColor(alpha);
            for (int i = 0; i < lines.size(); i++) {
                context.text(client.font, lines.get(i), x + 28, y + 6 + i * 12, textColor);
            }

            yOffset += toastHeight + TOAST_MARGIN;
        }
        if (expired != null) {
            toasts.removeAll(expired);
        }
    }

    /**
     * Toast 类型枚举
     */
    private enum ToastType {
        // 配色统一到原版 tooltip 的语言：深紫黑底（#100010）+ 亮色文字 + 状态色边框。
        // 旧版是"每个状态一种彩色底"，压在浅灰的原版面板上会像贴纸；原版的通知
        // （成就 / 配方解锁）也是深底 + 亮字，靠图标和边框颜色区分状态。
        SUCCESS(0xFF55FF55, 0xFF55FF55, 0xF0100010, "✓"),
        ERROR(0xFFFF5555, 0xFFFF5555, 0xF0100010, "✗"),
        WARNING(0xFFFFAA00, 0xFFFFAA00, 0xF0100010, "!"),
        INFO(0xFFFFFFFF, 0xFFAAAAAA, 0xF0100010, "i");

        private final int textColor;
        private final int borderColor;
        private final int backgroundColor;
        private final String icon;

        ToastType(int textColor, int borderColor, int backgroundColor, String icon) {
            this.textColor = textColor;
            this.borderColor = borderColor;
            this.backgroundColor = backgroundColor;
            this.icon = icon;
        }

        int getTextColor(float alpha) {
            return applyAlpha(textColor, alpha);
        }

        int getBorderColor(float alpha) {
            return applyAlpha(borderColor, alpha);
        }

        int getBackgroundColor(float alpha) {
            return applyAlpha(backgroundColor, alpha);
        }

        String getIcon() {
            return icon;
        }

        private int applyAlpha(int color, float alpha) {
            int a = (int) (alpha * 255) & 0xFF;
            return (color & 0x00FFFFFF) | (a << 24);
        }
    }

    /**
     * Toast 数据类
     */
    private static class Toast {
        final Component message;
        final ToastType type;
        final long createdAt;
        /** 纯文本缓存：同一条 Toast 每帧都要量宽度，避免反复解析 Component。 */
        private String plain;
        /** 换行结果缓存：按字体宽度算一次，之后每帧复用（旧实现每帧 O(n²) 重算 + 分配）。 */
        private List<String> lines;

        Toast(Component message, ToastType type, long createdAt) {
            this.message = message;
            this.type = type;
            this.createdAt = createdAt;
        }

        String plain() {
            String cached = plain;
            if (cached == null) {
                cached = message == null ? "" : message.getString();
                plain = cached;
            }
            return cached;
        }

        /** 按 {@link ToastWidget#MAX_TEXT_WIDTH} 把纯文本切成若干行（结果缓存）。 */
        List<String> lines(Font font) {
            List<String> cached = lines;
            if (cached != null) {
                return cached;
            }
            String text = plain();
            List<String> built = new ArrayList<>(2);
            if (font.width(text) <= MAX_TEXT_WIDTH) {
                built.add(text);
            } else {
                int start = 0;
                while (start < text.length()) {
                    // plainSubstrByWidth 返回"放得下的最长前缀"，一次调用就是一行（旧实现逐字符试宽度）
                    int fit = font.plainSubstrByWidth(text.substring(start), MAX_TEXT_WIDTH).length();
                    if (fit <= 0) {
                        fit = 1; // 极端字体下一个字符都放不下：至少前进一格，避免死循环
                    }
                    built.add(text.substring(start, start + fit));
                    start += fit;
                }
            }
            this.lines = built;
            return built;
        }
    }
}
