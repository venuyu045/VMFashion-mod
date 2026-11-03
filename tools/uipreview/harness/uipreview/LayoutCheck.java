package uipreview;

import java.util.ArrayList;
import java.util.List;

/**
 * 布局自检：从录制到的绘制命令里找"人眼容易漏、但一定是 bug"的模式。
 *
 * <p>为什么需要它：UI 预览器解决了"看得见"，但看得见不等于看得懂 ——
 * 一张 1280×720 的图里，"第三行状态文字被面板底边切掉 2px"这种问题很容易被忽略，
 * 而它在游戏里就是"文字糊成一团"。这个检查把可判定的问题<b>变成一条明确的告警</b>，
 * 让每次改完界面都能顺手确认没有回归。
 *
 * <p>当前检查项（都是"确定是问题"而非"可能有问题"）：
 * <ol>
 *   <li><b>越界绘制</b>：命令矩形超出屏幕（{@code x < 0} / {@code x + w > width} 等）；</li>
 *   <li><b>零尺寸命令</b>：宽或高为 0 的 fill / blit（画了等于没画，通常是算错了）；</li>
 *   <li><b>文字画出屏幕</b>：文字起点或整行落在屏幕外（被裁掉是"该有字的地方空着"）。</li>
 * </ol>
 *
 * <p><b>scissor 感知（v2.0.1）</b>：可滚动网格里，视野外的卡片本来就会被裁剪，
 * 那是正确行为而不是 bug。但绘制命令里记录的是<b>未裁剪</b>的矩形，所以"卡片画到屏幕外"
 * 这类命令会假警报（480×270 的衣柜场景一次刷 18 条，全是被 {@code enableScissor} 裁掉的
 * 卡片行）。现在每个命令都带着生效中的 {@link DrawCommand.Clip}：
 * <ul>
 *   <li>命令在裁剪区内 → 只检查裁剪区本身是否在屏幕内（裁剪区越界才是真 bug），
 *       命令自身的越界跳过（它根本不会被画出来）；</li>
 *   <li>没有裁剪 → 照旧检查命令矩形是否越出屏幕。</li>
 * </ul>
 *
 * <p>不做的事：不做像素级基线对比（那需要真机截图，见 README「双轨验证」），
 * 也不判断"颜色好不好看"。
 */
public final class LayoutCheck {

    /** 一条告警。 */
    public record Issue(String kind, String detail) {
    }

    /** 屏幕宽高（逻辑像素）。 */
    private final int width;
    private final int height;
    private final List<Issue> issues = new ArrayList<>();

    public LayoutCheck(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** 扫描一帧的命令列表。 */
    public LayoutCheck check(List<DrawCommand> commands) {
        issues.clear();
        int fillCount = 0;
        int textCount = 0;
        int blitCount = 0;

        for (DrawCommand command : commands) {
            switch (command) {
                case DrawCommand.Fill fill -> {
                    fillCount++;
                    checkRect("fill", fill.x0(), fill.y0(), fill.x1(), fill.y1(), fill.clip());
                }
                case DrawCommand.Gradient gradient -> {
                    fillCount++;
                    checkRect("fillGradient", gradient.x0(), gradient.y0(), gradient.x1(), gradient.y1(),
                            gradient.clip());
                }
                case DrawCommand.Blit blit -> {
                    blitCount++;
                    checkRect("blit", blit.x0(), blit.y0(), blit.x1(), blit.y1(), blit.clip());
                }
                case DrawCommand.Text text -> {
                    textCount++;
                    checkText(text);
                }
                case DrawCommand.Tooltip tooltip -> {
                    if (tooltip.x() < 0 || tooltip.x() >= width || tooltip.y() < 0 || tooltip.y() >= height) {
                        issues.add(new Issue("tooltip-越界",
                                String.format("tooltip 定位点 (%d,%d) 在屏幕外", tooltip.x(), tooltip.y())));
                    }
                }
            }
        }

        if (fillCount == 0 && textCount == 0 && blitCount == 0) {
            issues.add(new Issue("空帧", "这一帧没有任何绘制命令 —— 界面可能没初始化或数据为空"));
        }
        return this;
    }

    private void checkRect(String kind, int x0, int y0, int x1, int y1, DrawCommand.Clip clip) {
        if (x1 == x0 || y1 == y0) {
            issues.add(new Issue(kind + "-零尺寸",
                    String.format("矩形 (%d,%d)-(%d,%d) 宽或高为 0", x0, y0, x1, y1)));
            return;
        }
        if (clip != null) {
            // 有裁剪：命令本身越界是正常的（滚动区外的卡片），只检查裁剪区是否落在屏幕内
            checkClip(kind, clip);
            return;
        }
        if (x0 < 0 || y0 < 0 || x1 > width || y1 > height) {
            issues.add(new Issue(kind + "-越界",
                    String.format("矩形 (%d,%d)-(%d,%d) 超出屏幕 %dx%d", x0, y0, x1, y1, width, height)));
        }
    }

    /** 裁剪区必须落在屏幕内（裁剪区越界 = 那段内容永远看不见，是真 bug）。 */
    private void checkClip(String kind, DrawCommand.Clip clip) {
        if (clip.x0() < 0 || clip.y0() < 0 || clip.x1() > width || clip.y1() > height) {
            String key = kind + "-裁剪区越界";
            for (Issue issue : issues) {
                if (issue.kind().equals(key)) {
                    return; // 同一帧同一个裁剪区只报一次
                }
            }
            issues.add(new Issue(key, String.format("裁剪区 (%d,%d)-(%d,%d) 超出屏幕 %dx%d",
                    clip.x0(), clip.y0(), clip.x1(), clip.y1(), width, height)));
        }
    }

    private void checkText(DrawCommand.Text text) {
        if (text.text().isEmpty()) {
            return;
        }
        if (text.clip() != null) {
            // 文字被 scissor 裁掉是正确行为（滚动区外的卡片），只检查裁剪区
            checkClip("文字", text.clip());
            return;
        }
        int textTop = text.y();
        int textBottom = text.y() + 9;
        if (textTop < 0 || textBottom > height) {
            issues.add(new Issue("文字-纵向出屏",
                    String.format("「%s」纵向范围 %d..%d 超出屏幕高 %d",
                            truncate(text.text()), textTop, textBottom, height)));
        }
    }

    private static String truncate(String text) {
        return text.length() <= 16 ? text : text.substring(0, 16) + "…";
    }

    public List<Issue> issues() {
        return issues;
    }

    /** 生成人类可读报告。 */
    public String report() {
        if (issues.isEmpty()) {
            return "布局自检：未发现问题";
        }
        StringBuilder sb = new StringBuilder("布局自检：发现 " + issues.size() + " 条\n");
        for (Issue issue : issues) {
            sb.append("  - [").append(issue.kind()).append("] ").append(issue.detail()).append('\n');
        }
        return sb.toString();
    }
}
