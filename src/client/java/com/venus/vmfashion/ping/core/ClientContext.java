package com.venus.vmfashion.ping.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;

import java.util.UUID;

/**
 * 当前客户端上下文（唯一碰 Minecraft 客户端的"小工具层"）。
 *
 * <p>把 {@code Minecraft.getInstance()} 的取值集中在这里，好处是：
 * <ul>
 *   <li>其它类（中继、渲染、命令）不直接依赖 Minecraft 单例，纯逻辑部分可以单测；</li>
 *   <li>所有方法都只允许在**客户端主线程**调用（Fabric 的 tick / 渲染回调里），
 *       异步回调里要用的话先 {@code Minecraft.getInstance().execute(...)} 切回来。</li>
 * </ul>
 *
 * <p>合订版适配：包名迁入 {@code com.venus.vmfashion.ping.core}，逻辑与独立版 VMPing 逐行一致；
 * 这里只读原版状态，不持有任何业务对象，所以不需要额外的合订改动。
 */
public final class ClientContext {

    private ClientContext() {
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /** 当前登录的玩家 UUID；未登录返回 {@code null}。 */
    public static UUID selfUuid() {
        User user = mc().getUser();
        return user == null ? null : user.getProfileId();
    }

    /** 当前登录的玩家名；未登录返回空串。 */
    public static String selfName() {
        User user = mc().getUser();
        return user == null || user.getName() == null ? "" : user.getName();
    }

    /** 是否在游戏世界里（主菜单/加载中返回 false）。 */
    public static boolean inWorld() {
        return mc().level != null && mc().player != null;
    }

    /**
     * 当前服务器地址（原始值，未归一化）。
     *
     * <p>取值顺序：
     * <ol>
     *   <li>多人服务器：{@link ServerData#ip}（含端口时保留端口）；</li>
     *   <li>单机：{@code "singleplayer"}（不把存档名放进房间键，避免泄露）；</li>
     *   <li>局域网 / Realms 也走 {@link ServerData}，{@code isLan()} 时用 {@code "lan"}。</li>
     * </ol>
     *
     * @return 原始地址；不在世界/无法判定时返回空串
     */
    public static String rawServerAddress() {
        if (!inWorld()) {
            return "";
        }
        ServerData server = mc().getCurrentServer();
        if (server == null) {
            // 没有 ServerData = 单机世界（集成服务器）
            return RoomKey.SINGLEPLAYER;
        }
        if (server.isLan()) {
            return RoomKey.LAN;
        }
        String ip = server.ip;
        return ip == null ? "" : ip;
    }

    /** 当前房间 id（归一化 + 哈希）；不在可标点的场景返回空串。 */
    public static String roomId() {
        String raw = rawServerAddress();
        if (raw.isBlank()) {
            return "";
        }
        return RoomKey.roomId(RoomKey.normalize(raw));
    }

    /** 当前维度 id（{@code minecraft:overworld}）；不在世界返回空串。 */
    public static String dimensionId() {
        ClientLevel level = mc().level;
        if (level == null) {
            return "";
        }
        return level.dimension().identifier().toString();
    }

    /** 把任务切回客户端主线程执行（网络回调里更新界面状态必须走这里）。 */
    public static void runOnClientThread(Runnable task) {
        Minecraft minecraft = mc();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(task);
    }

    /** 当前是否按住了玩家列表键（Tab）—— 按住时强制显示标点者名字。 */
    public static boolean isPlayerListKeyDown() {
        try {
            return mc().options.keyPlayerList.isDown();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前游戏语言是否是中文（文案兜底用）。 */
    public static boolean isChineseLocale() {
        try {
            String code = mc().options.languageCode;
            return code != null && code.toLowerCase(java.util.Locale.ROOT).startsWith("zh");
        } catch (Throwable t) {
            return false;
        }
    }
}
