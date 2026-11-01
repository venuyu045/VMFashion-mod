package com.venus.vmfashion.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssetSyncService#shouldClearCatalogFor} 单测。
 *
 * <p>背景（回归）：{@code AssetSyncService.initialize()} 旧实现把 {@code lastBaseUrl} 初始化为
 * {@code ""}，再用 {@code !baseUrl.equalsIgnoreCase(lastBaseUrl)} 判断"后端地址变了"——
 * 于是<b>每次启动的第一次初始化</b>都会被当成"换了后端"，直接
 * {@code PackCatalog#clear()}（会删掉 {@code cache/pack-catalog.json}），
 * 紧接着的 {@code loadFromDiskIfNeeded()} 就再也读不到磁盘清单：
 * 文档承诺的"后端暂时不可达时沿用上次清单"从来没生效过。
 *
 * <p>纯逻辑，不加载任何 Minecraft 类（{@link AssetSyncService} 的静态初始化只建内存容器）。
 */
class AssetSyncServiceCatalogTest {

    @Test
    void keepsDiskCatalogOnFirstInitialize() {
        assertFalse(AssetSyncService.shouldClearCatalogFor(null, "https://api.example.com/api/fashion"),
                "本进程第一次初始化：磁盘清单是唯一的离线数据源，绝不能清");
        assertFalse(AssetSyncService.shouldClearCatalogFor("", "https://api.example.com/api/fashion"));
        assertFalse(AssetSyncService.shouldClearCatalogFor("   ", "https://api.example.com/api/fashion"));
    }

    @Test
    void keepsDiskCatalogWhenBaseUrlUnchanged() {
        assertFalse(AssetSyncService.shouldClearCatalogFor(
                "https://api.example.com/api/fashion", "https://api.example.com/api/fashion"));
        // 配置热更新：大小写 / 首尾空白差异不算换后端（FashionApi 自己会 trim + 去尾斜杠）
        assertFalse(AssetSyncService.shouldClearCatalogFor(
                "https://API.example.com/api/fashion ", "https://api.example.com/api/fashion"));
    }

    @Test
    void clearsDiskCatalogWhenBaseUrlChanges() {
        assertTrue(AssetSyncService.shouldClearCatalogFor(
                "https://a.example.com/api/fashion", "https://b.example.com/api/fashion"),
                "换后端后旧清单里的 sha256 / 下载地址全部作废，必须清");
        assertTrue(AssetSyncService.shouldClearCatalogFor(
                "https://a.example.com/api/fashion", "http://localhost:8080/api/fashion"));
    }

    @Test
    void keepsDiskCatalogWhenNewBaseUrlIsBlank() {
        // 纯本地模式（没配后端）：不该因为"地址被清空"去删玩家的离线清单
        assertFalse(AssetSyncService.shouldClearCatalogFor("https://a.example.com/api/fashion", ""));
        assertFalse(AssetSyncService.shouldClearCatalogFor("https://a.example.com/api/fashion", null));
    }
}
