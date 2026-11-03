package net.minecraft.client.multiplayer;

/**
 * 客户端连接 —— UI 预览器里只记录"发过什么命令"，不真的发出去。
 *
 * <p>界面里点路径点会走 {@code mc.getConnection().sendCommand(...)}。预览器没有服务端，
 * 但需要让这条路径<b>能跑通不崩</b>，并把发过的命令留下来 —— 这样"点了路径点到底发了什么"
 * 在预览里可验证（{@link #lastCommand()}）。
 */
public class ClientPacketListener {

    private String lastCommand;

    /** 预览器专用：最近一次发出的命令（没有则 {@code null}）。 */
    public String lastCommand() {
        return lastCommand;
    }

    public void sendCommand(String command) {
        this.lastCommand = command;
    }

    public void sendChat(String message) {
        this.lastCommand = message;
    }
}
