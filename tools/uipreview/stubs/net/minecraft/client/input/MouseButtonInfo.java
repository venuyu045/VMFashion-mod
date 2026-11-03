package net.minecraft.client.input;

/**
 * 鼠标按钮信息。
 *
 * @param button    按钮编号（0 = 左键，1 = 右键，2 = 中键）
 * @param modifiers 修饰键位掩码
 */
public record MouseButtonInfo(int button, int modifiers) implements InputWithModifiers {
    @Override
    public int input() {
        return button;
    }
}
