package com.venus.vmfashion.loadout;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 穿戴数据来源。
 *
 * <p>渲染层每帧只做内存查表，真正的数据获取（后端 HTTP / 服务器插件 / 资源包）由实现方异步完成。
 * 当前实现：{@link LocalOnlyProvider}（纯本地模式，永远返回空表）和
 * {@code com.venus.vmfashion.net.HttpLoadoutProvider}（VMTools 后端）。
 *
 * <p>返回结构：玩家 UUID → （分类 key → 时装全局 ID）。
 */
public interface LoadoutProvider {

    /**
     * 批量解析若干玩家的穿戴。
     *
     * <p>实现方必须在 IO 线程完成工作，返回的 future 可能永远不完成（离线 / 无后端），
     * 渲染层不会阻塞等待。
     *
     * @param uuids 需要查询的玩家，非空
     * @return 只包含"查到了"的玩家的映射；缺失的玩家视为没有穿戴
     */
    CompletableFuture<Map<UUID, Map<String, String>>> resolve(Collection<UUID> uuids);
}
