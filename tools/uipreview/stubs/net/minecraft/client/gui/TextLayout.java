package net.minecraft.client.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本折行 —— 复刻游戏内 {@code StringSplitter#splitLines} 的<b>可见结果</b>。
 *
 * <p>规则（与游戏一致）：优先在空格处断行；单个"词"比整行还宽时按字符硬切；
 * 显式换行符 {@code \n} 强制断行。不处理双向文本与连字（界面文案用不到）。
 *
 * <p>放在 {@code net.minecraft.client.gui} 包里，是因为 {@link Font#wordWrapHeight}
 * 和 {@code GuiGraphicsExtractor#textWithWordWrap} 必须用<b>同一套</b>折行规则 ——
 * 否则"界面按 A 算法算高度、渲染按 B 算法断行"，预览图就会和游戏里对不上。
 */
public final class TextLayout {

    private TextLayout() {
    }

    /**
     * 把文本按宽度切成若干行。
     *
     * @param text     原始文本（可含 {@code \n}）
     * @param maxWidth 行宽上限（像素）
     * @param font     提供字宽的字体
     * @return 至少一行；{@code null} / 空串返回 {@code [""]}
     */
    public static List<String> wrap(String text, int maxWidth, Font font) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        if (maxWidth <= 0) {
            for (String paragraph : text.split("\n", -1)) {
                lines.add(paragraph);
            }
            return lines;
        }
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            int start = 0;
            while (start < paragraph.length()) {
                int end = start;
                int used = 0;
                int lastSpace = -1;
                while (end < paragraph.length()) {
                    int cp = paragraph.codePointAt(end);
                    int w = Font.advance(cp);
                    if (used + w > maxWidth) {
                        break;
                    }
                    used += w;
                    if (cp == ' ') {
                        lastSpace = end;
                    }
                    end += Character.charCount(cp);
                }
                if (end == start) {
                    // 单个字符都放不下：至少前进一个码位，避免死循环
                    end = start + Character.charCount(paragraph.codePointAt(start));
                } else if (end < paragraph.length() && lastSpace > start) {
                    // 后面还有内容且本行内有空格：断在最后一个空格之后，避免把词拆开
                    end = lastSpace + 1;
                }
                lines.add(paragraph.substring(start, end));
                start = end;
            }
        }
        return lines;
    }
}
