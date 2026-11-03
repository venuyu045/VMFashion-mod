package net.minecraft.network.chat;

/** UI 预览器用的 {@code TextColor} 精简替身。 */
public final class TextColor {
    private final int value;

    private TextColor(int value) {
        this.value = value & 0xFFFFFF;
    }

    public static TextColor fromRgb(int rgb) {
        return new TextColor(rgb);
    }

    public int getValue() {
        return value;
    }
}
