package com.venus.vmfashion.sync;

import com.venus.vmfashion.config.ModConfig;

/**
 * 服务端时装包自动下发（「A 穿的时装，B 没装也能看见」）。
 *
 * <p>契约（后端需新增，见 {@code docs/fashion-api.md}）：
 * <pre>
 * GET  /packs                          → { packs: [ {id, version, sha256, bytes, url} ] }
 * GET  /packs/{id}/{version}/download  → zip 字节流（pack.json + cosmetics/...）
 * </pre>
 * {@code /loadouts/resolve} 与 {@code /loadout/me} 的每个 item 补 {@code packId} / {@code packVersion}。
 *
 * <p>客户端安全要求（自动下载 = 新增远程数据入口，必须全部做到）：
 * <ul>
 *   <li>下载流式落盘 + {@code DigestInputStream} 校验 sha256 与字节数；</li>
 *   <li>解压拒绝 {@code ..}、绝对路径、符号链接，限制条目数 / 单文件大小 / 总解压大小 / 压缩比（zip bomb）；</li>
 *   <li>解到 {@code packs/server/.tmp-*}，校验 {@code pack.json.id} 后再原子 move 到 {@code packs/server/<packId>/}；</li>
 *   <li>用户自己装的同名包优先，绝不覆盖；</li>
 *   <li>装完在主线程 {@code PackLoader.reload()} + {@code CosmeticAssets.invalidate()}（贴图上传要 GL 上下文）。</li>
 * </ul>
 *
 * <p>由 {@code VMFashionClient} 初始化、{@code SyncService} 在进服/离服时转发，
 * 渲染层解析不到时装 ID 时调用 {@link #requestMissing(String)}。
 */
public final class AssetSyncService {

    private static final AssetSyncService INSTANCE = new AssetSyncService();

    private AssetSyncService() {
    }

    public static AssetSyncService getInstance() {
        return INSTANCE;
    }

    /** 按配置初始化（可在配置热更新后重复调用）。 */
    public void initialize(ModConfig config) {
        // TODO(assets): 建立 catalog 客户端 / 清理队列
    }

    /** 每客户端 tick 调用：驱动下载队列、把已装好的包在主线程 reload。 */
    public void tick() {
        // TODO(assets)
    }

    /** 进服：刷新 catalog，并预取自己穿戴涉及的包。 */
    public void onJoin() {
        // TODO(assets)
    }

    /** 离服：取消在飞下载，保留已缓存文件（离线可用）。 */
    public void onDisconnect() {
        // TODO(assets)
    }

    /**
     * 请求下发某件时装的包。
     *
     * @param cosmeticId 形如 {@code packId:localId}；{@code null}/空/已存在本地包时直接忽略
     */
    public void requestMissing(String cosmeticId) {
        // TODO(assets)
    }

    /** 清空服务端下发的包缓存（{@code /vmfashion cache clear}）。 */
    public void clearCache() {
        // TODO(assets)
    }

    /** 状态摘要，给 {@code /vmfashion status} 用。 */
    public String describeStatus() {
        return "资产下发未启用"; // TODO(assets)
    }

    /** 已缓存的服务端包占用字节数，给诊断用。 */
    public long cacheBytes() {
        return 0L; // TODO(assets)
    }

    /** 在飞 + 排队中的下载数。 */
    public int pendingCount() {
        return 0; // TODO(assets)
    }
}
