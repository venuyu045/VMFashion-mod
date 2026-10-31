package com.venus.vmfashion.sync;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssetSyncService#isEvictable} 单测（audit v1.2 N04）。
 *
 * <p>背景：LRU 淘汰在 worker 线程删目录，安装/更新在另一个 worker 线程"目录先就位、稍后才登记"。
 * 淘汰线程如果按旧记录删，会把刚装好的新版本目录删掉，留下"installed 有记录、磁盘没目录"的
 * 不可自愈状态。这里断言两道闸：记录未被替换 + 不在 inFlight。
 *
 * <p>纯逻辑，不加载任何 Minecraft 类（{@link AssetSyncService} 的静态初始化只建内存容器）。
 */
class AssetSyncServiceEvictionTest {

    private static AssetSyncService.InstalledPack pack(String id) {
        return new AssetSyncService.InstalledPack(id, "1.0.0", "a".repeat(64), 100L, 1L, 1L);
    }

    @Test
    void evictsIdleInstalledPack() {
        AssetSyncService.InstalledPack candidate = pack("wings");
        assertTrue(AssetSyncService.isEvictable(candidate, candidate, Set.of()));
        assertTrue(AssetSyncService.isEvictable(candidate, candidate, null));
    }

    @Test
    void skipsPackBeingInstalled() {
        AssetSyncService.InstalledPack candidate = pack("wings");
        // inFlight 的 key 是小写 packId
        assertFalse(AssetSyncService.isEvictable(candidate, candidate, Set.of("wings")));
        assertTrue(AssetSyncService.isEvictable(candidate, candidate, Set.of("other")));
    }

    @Test
    void skipsRecordReplacedByNewInstall() {
        AssetSyncService.InstalledPack old = pack("wings");
        // 内容相同但不是同一个实例 = 记录已被新安装替换，此时目录里是新版本，绝不能删
        AssetSyncService.InstalledPack fresh = pack("wings");
        assertFalse(AssetSyncService.isEvictable(fresh, old, Set.of()));
        assertFalse(AssetSyncService.isEvictable(null, old, Set.of()));
        assertFalse(AssetSyncService.isEvictable(old, null, Set.of()));
    }
}
