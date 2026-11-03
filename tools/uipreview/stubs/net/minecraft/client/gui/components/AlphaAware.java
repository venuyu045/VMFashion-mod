package net.minecraft.client.gui.components;

/**
 * 可调透明度的控件。
 *
 * <p>游戏里 {@code Screen#fadeWidgets} 会在打开动画期间给每个控件 {@code setAlpha}，
 * 让控件跟着面板一起淡入。预览器要复现这个效果，所以把这个能力抽成接口 ——
 * 比用反射找 {@code setAlpha} 稳，也比让预览器认识每个具体控件类型干净。
 */
public interface AlphaAware {
    void setAlpha(float alpha);
}
