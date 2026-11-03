package com.venus.vmfashion.ping.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WheelClosePolicy} 的单测 —— 覆盖 v2.0.0 的一个必现缺陷。
 *
 * <p>背景：轮盘原本的兜底是「标点键不再按下就当松开」，但 26.3 的 {@code Gui#setScreen()}
 * 打开界面时会调用 {@code KeyMapping.releaseAll()}，按键的 {@code isDown()} 在界面打开后恒为
 * false —— 轮盘会在第 3 tick 自己关掉（玩家几乎来不及选类型）。修复后兜底只看窗口失焦，
 * 这几条用例把新语义钉住，防止有人"优化"回旧写法。
 *
 * <p>纯逻辑，不加载任何 Minecraft 类。
 */
class WheelClosePolicyTest {

    @Test
    void doesNotCancelDuringGraceTicks() {
        // 开界面那一下的输入抖动不能被当成"该关了"
        assertFalse(WheelClosePolicy.shouldAutoCancel(true, false, 0, true));
        assertFalse(WheelClosePolicy.shouldAutoCancel(true, false, 1, true));
        assertFalse(WheelClosePolicy.shouldAutoCancel(true, false, WheelClosePolicy.GRACE_TICKS, true));
    }

    @Test
    void keepsWheelOpenWhileWindowIsActive() {
        // 窗口有焦点时永远不兜底关闭：松开由 mouseReleased 处理。
        // 这条就是 v2.0.0 缺陷的回归用例 —— 旧逻辑在这里会返回 true（因为 isDown() 恒为 false）。
        for (int ticks = 0; ticks < 200; ticks++) {
            assertFalse(WheelClosePolicy.shouldAutoCancel(true, false, ticks, true),
                    "tick=" + ticks + " 时窗口有焦点，轮盘不能被兜底关掉");
        }
    }

    @Test
    void cancelsWhenWindowLosesFocus() {
        // 真正收不到 mouseReleased 的现实场景：Alt+Tab / 鼠标被系统抢走
        assertTrue(WheelClosePolicy.shouldAutoCancel(true, false, WheelClosePolicy.GRACE_TICKS + 1, false));
        assertTrue(WheelClosePolicy.shouldAutoCancel(true, false, 100, false));
    }

    @Test
    void neverCancelsForCommandOpenedWheel() {
        // /vmfashion ping open 打开时玩家并没有按住按键：窗口失焦也不该替玩家做决定
        assertFalse(WheelClosePolicy.shouldAutoCancel(false, false, 100, false));
    }

    @Test
    void neverCancelsTwice() {
        // finished 之后不能再触发（防止重复关闭 / 重复发标点）
        assertFalse(WheelClosePolicy.shouldAutoCancel(true, true, 100, false));
    }
}
