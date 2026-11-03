package net.minecraft.client.input;

/**
 * 鼠标点击/移动事件。
 *
 * @param x          逻辑坐标 X
 * @param y          逻辑坐标 Y
 * @param buttonInfo 按钮信息
 */
public record MouseButtonEvent(double x, double y, MouseButtonInfo buttonInfo) implements InputWithModifiers {

    public int button() {
        return buttonInfo.button();
    }

    @Override
    public int input() {
        return buttonInfo.button();
    }

    @Override
    public int modifiers() {
        return buttonInfo.modifiers();
    }
}
