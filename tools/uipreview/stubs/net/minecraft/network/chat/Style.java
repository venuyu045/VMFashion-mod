package net.minecraft.network.chat;

/**
 * UI 预览器用的 {@code net.minecraft.network.chat.Style} 精简替身。
 *
 * <p>只保留 UI 会读的字段：颜色、阴影色、粗体/斜体/下划线/删除线。
 * 颜色按游戏内约定存成 RGB（低 24 位）；{@code 0xFF888888} 这种带 alpha 的写法
 * 会被截断成 RGB —— 与游戏内 {@code Style#withColor(int)} 行为一致。
 */
public final class Style {
    public static final Style EMPTY = new Style(null, null, false, false, false, false);
    /** 游戏内 {@code Style.NO_SHADOW} 的哨兵值。 */
    public static final int NO_SHADOW = 0;

    private final Integer color;
    private final Integer shadowColor;
    private final boolean bold;
    private final boolean italic;
    private final boolean underlined;
    private final boolean strikethrough;

    private Style(Integer color, Integer shadowColor, boolean bold, boolean italic,
                  boolean underlined, boolean strikethrough) {
        this.color = color;
        this.shadowColor = shadowColor;
        this.bold = bold;
        this.italic = italic;
        this.underlined = underlined;
        this.strikethrough = strikethrough;
    }

    public Integer getColor() {
        return color;
    }

    public Integer getShadowColor() {
        return shadowColor;
    }

    public boolean isBold() {
        return bold;
    }

    public boolean isItalic() {
        return italic;
    }

    public boolean isUnderlined() {
        return underlined;
    }

    public boolean isStrikethrough() {
        return strikethrough;
    }

    public boolean isEmpty() {
        return color == null && shadowColor == null && !bold && !italic && !underlined && !strikethrough;
    }

    public Style withColor(int rgb) {
        return new Style(rgb & 0xFFFFFF, shadowColor, bold, italic, underlined, strikethrough);
    }

    public Style withColor(TextColor textColor) {
        return textColor == null ? this : withColor(textColor.getValue());
    }

    public Style withShadowColor(int rgb) {
        return new Style(color, rgb & 0xFFFFFF, bold, italic, underlined, strikethrough);
    }

    public Style withoutShadow() {
        return new Style(color, NO_SHADOW, bold, italic, underlined, strikethrough);
    }

    public Style withBold(Boolean value) {
        return new Style(color, shadowColor, value != null && value, italic, underlined, strikethrough);
    }

    public Style withItalic(Boolean value) {
        return new Style(color, shadowColor, bold, value != null && value, underlined, strikethrough);
    }

    public Style withUnderlined(Boolean value) {
        return new Style(color, shadowColor, bold, italic, value != null && value, strikethrough);
    }

    public Style withStrikethrough(Boolean value) {
        return new Style(color, shadowColor, bold, italic, underlined, value != null && value);
    }

    @Override
    public String toString() {
        return "Style[color=" + (color == null ? "null" : String.format("#%06X", color))
                + ", bold=" + bold + ", italic=" + italic
                + ", underlined=" + underlined + ", strikethrough=" + strikethrough + "]";
    }
}
