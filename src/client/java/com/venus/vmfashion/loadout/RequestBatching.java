package com.venus.vmfashion.loadout;

import java.util.ArrayList;
import java.util.List;

/**
 * 大批量请求的切块工具。
 *
 * <p>后端 {@code /loadouts/resolve} 单次最多接受 200 个 UUID，旧实现把超出的部分
 * <b>直接丢掉</b>（玩家多了以后后半批人永远看不到时装）。这里把列表按上限切块，
 * 由调用方串行提交再合并结果 —— 不丢数据，也不会一次把后端打满。
 *
 * <p>纯逻辑、无 Minecraft 依赖，方便单测。
 */
public final class RequestBatching {

    private RequestBatching() {
    }

    /**
     * 按 {@code maxBatch} 把列表切成若干块，保持原顺序。
     *
     * @param items    待切分列表，{@code null} / 空返回空列表
     * @param maxBatch 每块最大条数，必须 {@code >= 1}
     * @return 不可变的块列表（每块也是不可变视图，避免调用方误改原列表）
     * @throws IllegalArgumentException {@code maxBatch < 1}
     */
    public static <T> List<List<T>> chunk(List<T> items, int maxBatch) {
        if (maxBatch < 1) {
            throw new IllegalArgumentException("maxBatch 必须 >= 1，当前 " + maxBatch);
        }
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int chunkCount = (items.size() + maxBatch - 1) / maxBatch;
        List<List<T>> chunks = new ArrayList<>(chunkCount);
        for (int start = 0; start < items.size(); start += maxBatch) {
            int end = Math.min(items.size(), start + maxBatch);
            chunks.add(List.copyOf(items.subList(start, end)));
        }
        return chunks;
    }
}
