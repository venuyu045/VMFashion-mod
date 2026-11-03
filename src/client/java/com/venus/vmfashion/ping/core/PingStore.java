package com.venus.vmfashion.ping.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标点仓库 —— 当前屏幕上"活着"的标点集合。
 *
 * <p>数据来源有两个：
 * <ol>
 *   <li>自己标点（本地立即插入，标记 {@code local=false} 的走中继确认，{@code local=true} 的只在本地）；</li>
 *   <li>长轮询从服务端拉回来的**别人的**标点。</li>
 * </ol>
 *
 * <p>线程模型：HTTP 回调线程写、渲染线程读，所以底层用 {@link ConcurrentHashMap}；
 * 渲染侧拿到的是快照列表（{@link #snapshot(long)}），不会边遍历边被改。
 *
 * <p>容量：{@link #MAX_PINGS} 条，超出时先清过期、仍超出就丢最早的（服务端也有同样的上限，
 * 这里是客户端侧的第二道保险，防止被恶意房间刷爆内存）。
 *
 * <p>合订版适配：只改包名（{@code com.venus.vmfashion.ping.core}），存储逻辑与独立版 VMPing 逐行一致。
 */
public final class PingStore {

    /** 客户端最多同时显示的标点数量。 */
    public static final int MAX_PINGS = 512;

    /** id → 标点。 */
    private final Map<String, PingData> pings = new ConcurrentHashMap<>();
    /** 已消费到的最大服务端序号（长轮询游标）。 */
    private volatile long lastSeq;
    /** 服务端时钟。 */
    private final PingClock clock = new PingClock();

    public PingClock clock() {
        return clock;
    }

    public long lastSeq() {
        return lastSeq;
    }

    /** 插入/替换一条标点（同 id 覆盖，保证长轮询重放不会出现重复标记）。 */
    public void put(PingData data) {
        if (data == null) {
            return;
        }
        pings.put(data.id(), data);
        if (data.seq() > lastSeq) {
            lastSeq = data.seq();
        }
        trimToLimit();
    }

    /** 批量插入（长轮询一帧返回多条）。 */
    public void putAll(List<PingData> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (PingData data : batch) {
            if (data != null) {
                pings.put(data.id(), data);
                if (data.seq() > lastSeq) {
                    lastSeq = data.seq();
                }
            }
        }
        trimToLimit();
    }

    /** 移除一条（例如服务端撤回，v1 暂未使用，保留给后续版本）。 */
    public void remove(String id) {
        if (id != null) {
            pings.remove(id);
        }
    }

    /** 清理所有已过期的标点；返回清理条数（诊断用）。 */
    public int expire(long nowMs) {
        int before = pings.size();
        pings.entrySet().removeIf(entry -> entry.getValue().expired(nowMs));
        return before - pings.size();
    }

    /**
     * 渲染快照：按 {@code seq} 升序（先来的先画，后来的压在上面），已过期的顺手剔除。
     *
     * @param nowMs 当前服务端时间（{@link PingClock#now()}）
     */
    public List<PingData> snapshot(long nowMs) {
        List<PingData> out = new ArrayList<>(pings.size());
        for (PingData data : pings.values()) {
            if (!data.expired(nowMs)) {
                out.add(data);
            }
        }
        out.sort(Comparator.comparingLong(PingData::serverTime).thenComparingLong(PingData::seq));
        return out;
    }

    /** 同一维度的快照（渲染只用当前维度的标点）。 */
    public List<PingData> snapshot(String dimension, long nowMs) {
        List<PingData> all = snapshot(nowMs);
        if (dimension == null || dimension.isBlank()) {
            return all;
        }
        List<PingData> out = new ArrayList<>(all.size());
        for (PingData data : all) {
            if (dimension.equals(data.dimension())) {
                out.add(data);
            }
        }
        return out;
    }

    /** 当前存活条数。 */
    public int size() {
        return pings.size();
    }

    /** 清空（退服 / 换服 / 换房间时调用）。 */
    public void clear() {
        pings.clear();
        lastSeq = 0L;
    }

    /** 只清别人的标点，保留自己的（切维度时自己的标记还能看见一会儿）。 */
    public void clearOthers(UUID self) {
        pings.entrySet().removeIf(entry -> !entry.getValue().isOwn(self));
    }

    /**
     * 只把长轮询游标归零（不清标点）。
     *
     * <p>房间被服务端回收重建后 {@code seq} 会从 0 重新开始；如果本地还拿着旧的高位
     * {@code since}，之后每次长轮询服务端都不会返回任何标点（只能退服恢复）。
     * 所以检测到序号回退时调用本方法，让下一次轮询从 0 开始补齐当前房间的存活标点。
     */
    public void resetCursor() {
        lastSeq = 0L;
    }

    /** 服务端时钟校准（长轮询响应里带 serverTime）。 */
    public void syncClock(long serverTimeMs) {
        clock.sync(serverTimeMs, System.currentTimeMillis());
    }

    /** 当前时间（服务端时间轴）。 */
    public long now() {
        return clock.now();
    }

    /** 容量上限保护。 */
    private void trimToLimit() {
        if (pings.size() <= MAX_PINGS) {
            return;
        }
        long now = clock.now();
        pings.entrySet().removeIf(entry -> entry.getValue().expired(now));
        if (pings.size() <= MAX_PINGS) {
            return;
        }
        // 仍然超限：按 serverTime 从旧到新删，直到回到上限
        List<PingData> sorted = new ArrayList<>(pings.values());
        sorted.sort(Comparator.comparingLong(PingData::serverTime));
        int toRemove = sorted.size() - MAX_PINGS;
        for (int i = 0; i < toRemove && i < sorted.size(); i++) {
            pings.remove(sorted.get(i).id());
        }
    }
}
