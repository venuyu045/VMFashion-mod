package net.minecraft.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * UI 预览器用的 {@code net.minecraft.client.Minecraft} 替身。
 *
 * <p>游戏里这个类是整个客户端的单例，构造过程要开窗口、建 OpenGL 上下文、加载资源包 ——
 * 无显卡环境根本不可能。预览器只用到它的<b>三样东西</b>：
 * {@link #font}（量文字宽度）、{@link #gui}（切界面）、{@link #level}（判断是否在游戏内）。
 * 所以这里做成一个可以直接 new 的轻量对象，由预览器启动时 {@link #setInstance} 注入。
 *
 * <p>字段名与游戏内保持一致（{@code minecraft.font} / {@code minecraft.gui} /
 * {@code minecraft.level}），生产代码不用改就能编过。
 */
public class Minecraft {

    private static Minecraft instance;

    public final Font font = new Font();
    public final Font fontFilterFishy = font;
    public final Gui gui = new Gui();
    /** 当前世界；{@code null} 表示在主菜单（界面会走"非游戏内"分支）。 */
    public ClientLevel level;

    /** 本地玩家；{@code null} 表示不在世界里（界面会走"不能执行命令"的分支）。 */
    public net.minecraft.client.player.LocalPlayer player;

    private net.minecraft.client.multiplayer.ClientPacketListener connection =
            new net.minecraft.client.multiplayer.ClientPacketListener();

    public net.minecraft.client.multiplayer.ClientPacketListener getConnection() {
        return connection;
    }

    /**
     * 主线程执行器。
     *
     * <p>游戏里把任务丢到渲染线程队列；预览器本来就是单线程同步渲染，
     * 直接执行即可（延迟执行反而会让"点了按钮界面没反应"）。
     */
    public void execute(Runnable task) {
        if (task != null) {
            task.run();
        }
    }

    /** 窗口（只提供 GUI 缩放后的逻辑尺寸）。由预览器在渲染前设置。 */
    public com.mojang.blaze3d.platform.Window window = new com.mojang.blaze3d.platform.Window(640, 360);

    public com.mojang.blaze3d.platform.Window getWindow() {
        return window;
    }

    public static Minecraft getInstance() {
        return instance;
    }

    public static void setInstance(Minecraft minecraft) {
        instance = minecraft;
    }

    /** 预览器便捷方法：确保单例存在并返回。 */
    public static Minecraft ensureInstance() {
        if (instance == null) {
            instance = new Minecraft();
        }
        return instance;
    }
}
