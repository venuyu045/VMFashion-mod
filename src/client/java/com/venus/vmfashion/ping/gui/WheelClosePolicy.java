package com.venus.vmfashion.ping.gui;

/**
 * 轮盘的「兜底关闭」判定（纯逻辑，不碰 Minecraft，可直接单测）。
 *
 * <p><b>为什么需要单独一个类</b>：v2.0.0 的兜底写法是「标点键不再按下就当松开」，
 * 而 26.3 的 {@code Gui#setScreen()} 在设置非空界面时会调用 {@code KeyMapping.releaseAll()}
 * （{@code javap -c} 核对：{@code release()} 把 {@code isDown} 置 false、{@code clickCount} 清零）。
 * 也就是说轮盘<b>一打开</b> {@code isDown()} 就是 false，旧判定会在第 3 tick 直接结算 ——
 * 轮盘"按住选类型"的交互彻底失效（必现）。{@code MouseHandler#isMiddlePressed()} 同样不可用：
 * 它只在没有界面时更新，松开事件被轮盘吃掉之后仍然是 true。
 *
 * <p>所以兜底改成只看<b>窗口失焦</b>：真正收不到 {@code mouseReleased} 的现实场景就是
 * Alt+Tab / 鼠标被系统抢走；正常松开走 {@link PingWheelScreen#mouseReleased}，
 * 卡住时玩家还可以按 Esc（{@link PingWheelScreen#keyPressed}）。
 *
 * <p>把判定抽出来（而不是留在 {@code Screen#tick()} 里）是为了让这段逻辑能被单测覆盖 ——
 * {@link PingWheelScreen} 继承 Minecraft 的 {@code Screen}，在纯 JVM 单测里加载不了。
 */
public final class WheelClosePolicy {

    /** 打开后的宽限 tick 数：开界面那一下的输入抖动不能被当成"该关了"。 */
    public static final int GRACE_TICKS = 2;

    private WheelClosePolicy() {
    }

    /**
     * 是否应当兜底取消轮盘（不发标点、直接关界面）。
     *
     * @param expectKeyRelease 是否由"按住按键"打开（命令打开时为 false，永远不兜底）
     * @param finished         是否已经结算过（防止重复关闭）
     * @param ticksOpen        已经过了多少 tick
     * @param windowActive     游戏窗口当前是否有焦点（{@code Minecraft#isWindowActive()}）
     */
    public static boolean shouldAutoCancel(boolean expectKeyRelease, boolean finished,
                                           int ticksOpen, boolean windowActive) {
        if (!expectKeyRelease || finished) {
            return false;
        }
        if (ticksOpen <= GRACE_TICKS) {
            return false;
        }
        return !windowActive;
    }
}
