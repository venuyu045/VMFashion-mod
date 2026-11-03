package net.minecraft.client.gui;

import net.minecraft.client.gui.font.McAsciiFont;
import net.minecraft.network.chat.FormattedText;

/**
 * UI 预览器用的 {@code net.minecraft.client.gui.Font} 精简替身。
 *
 * <p><b>为什么字宽必须是真的：</b>UI 里几乎每一处布局都依赖文字宽度 ——
 * 卡片角标要"贴着右边画"、标题要居中、状态行要截断加省略号、按钮文字要居中等。
 * 字宽差 1px，整块布局就会跟着错位。所以这里的宽度**逐字符**取自游戏内
 * {@code ascii.png} 的真实字形步进（见 {@link McAsciiFont}），而不是用某个近似字体量出来。
 *
 * <p><b>非 ASCII 字符</b>（中文、emoji、特殊符号）在游戏里会落到 unifont，
 * 每个字符固定 9px 宽（16px 位图按 0.5 缩放 + 1px 间距）；这里按同一规则处理。
 *
 * <p>游戏内 {@code Font} 还有双向文本整形、连字、粗体加宽等细节，UI 预览不涉及：
 * 界面文案都是中英混排短句，按码位顺序累加即可。
 */
public class Font {

    /** 行高（游戏内默认字体 ascent 7 + descent 2）。 */
    public final int lineHeight = 9;

    /** unifont 回退字符的固定步进。 */
    private static final int UNIFONT_ADVANCE = 9;

    /**
     * 单段文本的像素宽度（不含阴影）。
     *
     * @param text 要量的文本，{@code null} 视为空串
     */
    public int width(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            total += advance(cp);
            i += Character.charCount(cp);
        }
        return total;
    }

    public int width(FormattedText text) {
        return text == null ? 0 : width(text.getString());
    }

    /** 某个码位在游戏里的水平步进。 */
    public static int advance(int codepoint) {
        if (codepoint < 256) {
            int embedded = McAsciiFont.ADVANCE[codepoint];
            if (embedded > 0) {
                return embedded;
            }
            if (codepoint < 32 || codepoint == 127) {
                // 控制字符不占位（游戏里也不会画出来）
                return 0;
            }
        }
        return UNIFONT_ADVANCE;
    }

    /**
     * 取"放得下的最长前缀"—— 与游戏内 {@code Font#plainSubstrByWidth} 语义一致。
     *
     * <p>卡片名称、状态行、Toast 文本都靠它做省略号截断，所以必须和游戏里逐字符判定一致。
     */
    public String plainSubstrByWidth(String text, int maxWidth) {
        return plainSubstrByWidth(text, maxWidth, false);
    }

    public String plainSubstrByWidth(String text, int maxWidth, boolean reverse) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (!reverse) {
            int used = 0;
            int i = 0;
            while (i < text.length()) {
                int cp = text.codePointAt(i);
                int next = used + advance(cp);
                if (next > maxWidth) {
                    break;
                }
                used = next;
                i += Character.charCount(cp);
            }
            return text.substring(0, i);
        }
        int used = 0;
        int i = text.length();
        while (i > 0) {
            int cp = text.codePointBefore(i);
            int next = used + advance(cp);
            if (next > maxWidth) {
                break;
            }
            used = next;
            i -= Character.charCount(cp);
        }
        return text.substring(i);
    }

    /**
     * 按给定宽度折行后的总高度。
     *
     * <p>与 {@code GuiGraphicsExtractor#textWithWordWrap} 用同一套折行规则（见
     * {@code uipreview.TextLayout#wrap}），否则"界面算高度"和"实际画行"会不一致。
     */
    public int wordWrapHeight(FormattedText text, int width) {
        if (text == null || width <= 0) {
            return lineHeight;
        }
        return TextLayout.wrap(text.getString(), width, this).size() * lineHeight;
    }
}
