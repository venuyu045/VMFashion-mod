package net.minecraft.client.multiplayer;

/**
 * 客户端世界 —— UI 预览器里只是"存在与否"的占位。
 *
 * <p>界面里常见的判断是 {@code minecraft.level != null}（决定是不是"在游戏内"）。
 * 预览器按需把它设成非 {@code null} 来模拟"游戏内打开界面"，从而走到和游戏里同一条分支。
 */
public class ClientLevel {
}
