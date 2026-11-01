package com.venus.vmfashion.gui;

/**
 * 时装 ID 的比较规则（纯逻辑，不碰任何 Minecraft 类，可直接单测）。
 *
 * <p>为什么需要单独一个类：衣柜里的条目 ID 有两个来源 ——
 * <ul>
 *   <li>网站衣柜收藏：服务端可能给<b>短 ID</b>（{@code angel}，历史数据 / 简单 ID 条目）；</li>
 *   <li>本地注册表：永远是<b>全局 ID</b>（{@code lw_angel:angel}）。</li>
 * </ul>
 * 而 {@code LocalLoadout} 里存的是"玩家点穿上那一刻"的 ID 原样。于是同一条目在时装包
 * 下载完成前后 ID 会从短名变成全名，如果只做字符串相等比较，就会出现
 * "下载完的一瞬间卡片从已穿变回未穿"、"预览面板被清空"这类问题。
 *
 * <p>规则（与 {@code CosmeticRegistry#find(String)} 的短 ID 语义一致）：
 * <ol>
 *   <li>全名相等（大小写不敏感）→ 同一件；</li>
 *   <li><b>两边都带包名前缀且前缀不同</b> → 一定是两件（不能因为 localId 同名就判成一件，
 *       否则穿 A 会显示成 B）；</li>
 *   <li>其余情况（至少一边没有前缀）比较 {@code localId}。</li>
 * </ol>
 */
final class CosmeticIdMatch {

    private CosmeticIdMatch() {
    }

    /**
     * 两个时装 ID 是否指同一件时装。
     *
     * @param a 时装 ID（全局 {@code packId:localId} 或短名 {@code localId}），可为 {@code null}
     * @param b 同上
     * @return 任一为 {@code null} 时返回 {@code false}
     */
    static boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        String packA = packIdOf(a);
        String packB = packIdOf(b);
        if (!packA.isEmpty() && !packB.isEmpty() && !packA.equalsIgnoreCase(packB)) {
            // 两个不同的包：即使 localId 同名也是两件时装
            return false;
        }
        return localIdOf(a).equalsIgnoreCase(localIdOf(b));
    }

    /**
     * {@code packId:localId} → {@code packId}；没有冒号（或冒号在末尾）时返回空串（= 没有包名前缀）。
     */
    static String packIdOf(String cosmeticId) {
        if (cosmeticId == null) {
            return "";
        }
        int colon = cosmeticId.indexOf(':');
        return colon <= 0 || colon == cosmeticId.length() - 1
                ? ""
                : cosmeticId.substring(0, colon);
    }

    /**
     * {@code packId:localId} → {@code localId}；没有冒号（或冒号在末尾）时原样返回。
     *
     * <p>只看<b>第一个</b>冒号，和 {@code AssetSyncService#requestMissing} 解析包 ID 的口径一致
     * （{@code localId} 里允许再出现冒号）。
     */
    static String localIdOf(String cosmeticId) {
        if (cosmeticId == null) {
            return "";
        }
        int colon = cosmeticId.indexOf(':');
        return colon < 0 || colon == cosmeticId.length() - 1
                ? cosmeticId
                : cosmeticId.substring(colon + 1);
    }
}
