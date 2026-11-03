package net.minecraft.network.chat;

/**
 * UI 预览器用的 {@code net.minecraft.network.chat.FormattedText} 精简替身。
 *
 * <p>游戏内的 FormattedText 是"可带样式的内容序列"；UI 预览只关心纯文本，
 * 因此这里退化成 {@link #getString()} 一个方法。{@link Component} 继承它。
 */
public interface FormattedText {
    FormattedText EMPTY = () -> "";

    String getString();

    default boolean isEmpty() {
        return getString().isEmpty();
    }
}
