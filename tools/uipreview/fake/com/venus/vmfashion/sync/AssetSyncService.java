package com.venus.vmfashion.sync;

import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * UI 预览器专用的 {@code AssetSyncService} <b>替身</b>（编译时替换真实类）。
 *
 * <p>真实实现负责"别人穿了本地没装的时装 → 按 sha256 下载并解压时装包"。
 * 预览器不下载，但需要能模拟三种可见状态，否则衣柜卡片上的
 * 「未下载 / 下载中 / 下载失败」角标就没法验证：
 * <ul>
 *   <li>{@link #setPending(String)} —— 该包正在下载（卡片显示"下载中"）；</li>
 *   <li>{@link #setFailed(String)} —— 该包下载失败；</li>
 *   <li>都没有 —— 只是"未下载"。</li>
 * </ul>
 */
public class AssetSyncService {

    private static final AssetSyncService INSTANCE = new AssetSyncService();
    private static final Set<String> PENDING = new HashSet<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static boolean enabled = true;
    private static int installedCount;

    public static AssetSyncService getInstance() {
        return INSTANCE;
    }

    public static void setPending(String packId) {
        PENDING.add(packId);
    }

    public static void setFailed(String packId) {
        FAILED.add(packId);
    }

    public static void clearState() {
        PENDING.clear();
        FAILED.clear();
        installedCount = 0;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void setInstalledCount(int count) {
        installedCount = count;
    }

    public boolean isPackPending(String packId) {
        return packId != null && PENDING.contains(packId);
    }

    public void requestMissing(String cosmeticId) {
        // 预览器不下载：点"未下载"的时装只会更新界面状态
    }

    public Component describeStatus() {
        if (!enabled) {
            return Component.translatable("vmfashion.asset.status.disabled_in_config");
        }
        if (PENDING.isEmpty() && FAILED.isEmpty()) {
            return Component.translatable("vmfashion.asset.status.active", installedCount, "0 B");
        }
        return Component.translatable("vmfashion.asset.status.active", installedCount, "12.3 MB")
                .append(Component.translatable("vmfashion.asset.status.queue", PENDING.size()));
    }

    public int pendingCount() {
        return PENDING.size();
    }
}
