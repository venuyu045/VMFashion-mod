package com.venus.vmfashion.sync;

import java.util.Locale;

/**
 * 「已安装的服务端时装包要不要更新」的判定（纯逻辑，可单测，不加载任何 Minecraft 类）。
 *
 * <p>背景：旧实现只在"本地没有这个包"时才下载，{@code installed} 里一旦有记录就永久短路，
 * 清单刷新也不做版本 diff —— 后端发布了新版本，老玩家会一直停留在旧包，只能
 * {@code /vmfashion cache clear} 才能拿到。这里把判定抽成纯函数，供
 * {@link AssetSyncService} 在三个地方复用（渲染请求、队列出队、安装入口）。
 *
 * <p>判定优先级：<b>sha256 优先</b>（内容身份，最可靠），sha 缺失时退回 version 比较；
 * 两者都没有可比信息时保守地认为"不需要更新"，避免对老包/手放目录反复下载。
 */
public final class PackUpdatePolicy {

    /** 判定结果。 */
    public enum Decision {
        /** 本地已是最新（或信息不足，保守不动）。 */
        UP_TO_DATE,
        /** 清单里的版本 / 内容与本地不同，需要重新下载安装。 */
        UPDATE
    }

    private PackUpdatePolicy() {
    }

    /**
     * 判定是否需要用清单里的包替换本地已安装的包。
     *
     * @param installedVersion 本地 {@code .sha256} 标记里的 version（可能为空：老包 / 无标记）
     * @param installedSha     本地标记里的 sha256（可能为空）
     * @param catalogVersion   清单里的 version（可能为空）
     * @param catalogSha       清单里的 sha256（可能为空）
     */
    public static Decision decide(String installedVersion, String installedSha,
                                  String catalogVersion, String catalogSha) {
        String localSha = normalize(installedSha);
        String remoteSha = normalize(catalogSha);
        if (!localSha.isEmpty() && !remoteSha.isEmpty()) {
            return localSha.equals(remoteSha) ? Decision.UP_TO_DATE : Decision.UPDATE;
        }
        String localVersion = normalize(installedVersion);
        String remoteVersion = normalize(catalogVersion);
        if (!localVersion.isEmpty() && !remoteVersion.isEmpty()) {
            return localVersion.equals(remoteVersion) ? Decision.UP_TO_DATE : Decision.UPDATE;
        }
        // 信息不足（例如本地目录没有 .sha256 标记）：保守认为不需要更新。
        // 否则每个没有标记的老包每次进服都会被重下一遍。
        return Decision.UP_TO_DATE;
    }

    /** 便捷判断。 */
    public static boolean needsUpdate(String installedVersion, String installedSha,
                                      String catalogVersion, String catalogSha) {
        return decide(installedVersion, installedSha, catalogVersion, catalogSha) == Decision.UPDATE;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
