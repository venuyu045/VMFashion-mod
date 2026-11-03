package net.minecraft.client.input;

/**
 * 键盘事件。
 *
 * @param key       按键码（GLFW 常量，与 {@code org.lwjgl.glfw.GLFW} 一致）
 * @param scancode  扫描码
 * @param modifiers 修饰键位掩码
 */
public record KeyEvent(int key, int scancode, int modifiers) implements InputWithModifiers {
    @Override
    public int input() {
        return key;
    }

    /** 与游戏内一致：返回主按键码。 */
    public int keycode() {
        return key;
    }
}
