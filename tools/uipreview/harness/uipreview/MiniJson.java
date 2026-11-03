package uipreview;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 JSON 读取器 —— 只干一件事：把 Minecraft 语言文件（{@code lang/zh_cn.json}）
 * 这种 <b>扁平的 "键": "值" 表</b>读成 {@code Map<String,String>}。
 *
 * <p>为什么不用 Gson：预览器刻意只依赖 JDK（和 {@code tools/preview} 的软光栅化器同一原则），
 * 语言文件的形状又极其固定，引一个 JSON 库反而多一份要同步的依赖。
 *
 * <p>支持标准 JSON 字符串转义（反斜杠加引号/反斜杠/斜杠/b/f/n/r/t，以及 u 开头的四位十六进制），
 * 不支持嵌套对象/数组 —— 遇到会直接抛异常而不是悄悄读错。
 */
public final class MiniJson {

    private MiniJson() {
    }

    /**
     * 读取扁平对象。
     *
     * @param json 文件内容
     * @return 键值表（保持文件里的顺序）
     */
    public static Map<String, String> readFlatObject(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = 0;
        int n = json.length();
        skipWhitespace(json, i);
        i = skipWhitespace(json, 0);
        expect(json, i, '{');
        i++;
        while (true) {
            i = skipWhitespace(json, i);
            if (i >= n) {
                throw new IllegalArgumentException("JSON 意外结束：缺少右花括号");
            }
            if (json.charAt(i) == '}') {
                return result;
            }
            StringBuilder key = new StringBuilder();
            i = readString(json, i, key);
            i = skipWhitespace(json, i);
            expect(json, i, ':');
            i++;
            i = skipWhitespace(json, i);
            StringBuilder value = new StringBuilder();
            i = readString(json, i, value);
            result.put(key.toString(), value.toString());
            i = skipWhitespace(json, i);
            if (i < n && json.charAt(i) == ',') {
                i++;
            }
        }
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static void expect(String s, int i, char expected) {
        if (i >= s.length() || s.charAt(i) != expected) {
            throw new IllegalArgumentException("JSON 位置 " + i + " 期望 '" + expected + "'，实际 "
                    + (i >= s.length() ? "<结束>" : "'" + s.charAt(i) + "'"));
        }
    }

    private static int readString(String s, int i, StringBuilder out) {
        expect(s, i, '"');
        i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                return i + 1;
            }
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            i++;
            if (i >= s.length()) {
                break;
            }
            char esc = s.charAt(i);
            switch (esc) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 >= s.length()) {
                        throw new IllegalArgumentException("\\u 转义不完整（位置 " + i + "）");
                    }
                    out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("不支持的转义 \\" + esc);
            }
            i++;
        }
        throw new IllegalArgumentException("字符串未闭合");
    }
}
