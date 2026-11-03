package com.mojang.blaze3d.platform;

/**
 * 窗口 —— UI 预览器里是一个"纯逻辑尺寸"的替身。
 *
 * <p>游戏里 {@code Window} 持有 GLFW 窗口句柄和 OpenGL 上下文；预览器没有窗口，
 * 但界面代码会用它换算 GUI 缩放后的逻辑尺寸（Toast 定位就是这么算的），
 * 所以这里只需要两个数：{@code guiScaledWidth / guiScaledHeight}。
 *
 * <p>数值由预览器按"逻辑分辨率"直接给出（不做 devicePixelRatio 换算）——
 * 因为 {@code Screen.width/height} 本来就是逻辑坐标，两者必须一致。
 */
public class Window {

    private final int guiScaledWidth;
    private final int guiScaledHeight;

    public Window(int guiScaledWidth, int guiScaledHeight) {
        this.guiScaledWidth = guiScaledWidth;
        this.guiScaledHeight = guiScaledHeight;
    }

    public int getGuiScaledWidth() {
        return guiScaledWidth;
    }

    public int getGuiScaledHeight() {
        return guiScaledHeight;
    }

    public int getWidth() {
        return guiScaledWidth;
    }

    public int getHeight() {
        return guiScaledHeight;
    }
}
