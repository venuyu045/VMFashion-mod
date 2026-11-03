package com.venus.vmfashion.ping.core;

import java.util.UUID;

/**
 * 一条标点（不可变数据）。
 *
 * <p>字段与标点协议 v1 一一对应（沿用独立版 VMPing 的 {@code /api/ping} 契约）：
 * <ul>
 *   <li>{@code id} / {@code seq}：服务端分配的标点 id 与房间内单调序号；</li>
 *   <li>{@code playerUuid} / {@code playerName}：标点发起者（服务端从 token 里取，客户端伪造不了）；</li>
 *   <li>{@code dimension}：维度 id 字符串（{@code minecraft:overworld}）；</li>
 *   <li>{@code x/y/z}：世界坐标；</li>
 *   <li>{@code kind}：标点类型；</li>
 *   <li>{@code targetUuid/targetName}：点到实体时带上（可选）；</li>
 *   <li>{@code serverTime} / {@code expiresAt}：服务端时间戳与过期时间（毫秒）；</li>
 *   <li>{@code local}：是否是"只在自己这边显示"的降级标点（中继不可用时）。</li>
 * </ul>
 *
 * <p>为什么用 record：标点每帧都可能被读取，不可变对象不需要防御性拷贝，也不会有
 * "渲染线程读到一半被改了"的并发问题；构造后即完整。
 *
 * <p>合订版适配：只改包名（{@code com.venus.vmfashion.ping.core}）；数据形状与服务端协议保持一致，
 * 不随合订而改变。
 */
public record PingData(
        String id,
        long seq,
        UUID playerUuid,
        String playerName,
        String dimension,
        double x,
        double y,
        double z,
        PingKind kind,
        UUID targetUuid,
        String targetName,
        long serverTime,
        long expiresAt,
        boolean local
) {

    /** 本地标点（降级模式）的构造：没有服务端 id/seq，过期时间由客户端自己算。 */
    public static PingData local(UUID playerUuid, String playerName, String dimension,
                                 double x, double y, double z, PingKind kind,
                                 UUID targetUuid, String targetName,
                                 long nowMs, long ttlMs) {
        return new PingData(
                "local-" + Long.toHexString(nowMs) + "-" + Integer.toHexString((int) (x * 31 + z * 17 + y)),
                -1L, playerUuid, playerName, dimension, x, y, z, kind,
                targetUuid, targetName, nowMs, nowMs + Math.max(1_000L, ttlMs), true);
    }

    /** 是否已过期（用服务端时间与本地时钟的差值判断，见 {@link PingClock}）。 */
    public boolean expired(long nowMs) {
        return nowMs >= expiresAt;
    }

    /** 剩余存活毫秒数（已过期返回 0）。 */
    public long remainingMs(long nowMs) {
        return Math.max(0L, expiresAt - nowMs);
    }

    /**
     * 淡出系数：最后 {@code fadeMs} 毫秒线性淡出，返回 {@code [0,1]}。
     *
     * <p>抽成纯函数是为了能直接单测（渲染层只负责把 alpha 乘进去）。
     */
    public float fadeAlpha(long nowMs, long fadeMs) {
        long remaining = remainingMs(nowMs);
        if (remaining <= 0L) {
            return 0f;
        }
        if (fadeMs <= 0L || remaining >= fadeMs) {
            return 1f;
        }
        return (float) remaining / (float) fadeMs;
    }

    /** 是否是自己的标点（渲染上通常自己的标记更淡一点，避免挡视线）。 */
    public boolean isOwn(UUID self) {
        return playerUuid != null && playerUuid.equals(self);
    }
}
