package com.venus.vmfashion.sync;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.cosmetic.LocalLoadout;
import com.venus.vmfashion.cosmetic.WardrobeCollection;
import com.venus.vmfashion.loadout.LoadoutService;
import com.venus.vmfashion.net.FashionApi;
import com.venus.vmfashion.net.FashionAuth;
import com.venus.vmfashion.net.HttpLoadoutProvider;
import com.venus.vmfashion.util.ChatUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 云端同步的总开关与生命周期。
 *
 * <p>职责边界：
 * <ul>
 *   <li>持有 {@link FashionApi} / {@link FashionAuth} / {@link HttpLoadoutProvider}；</li>
 *   <li>进服时完成认证并把 provider 装进 {@link LoadoutService}；</li>
 *   <li>玩家改穿戴时把 loadout 上报；</li>
 *   <li>没有配置后端（或认证失败）时安静地退化为纯本地模式，不影响渲染。</li>
 * </ul>
 */
public final class SyncService {

    private static final SyncService INSTANCE = new SyncService();

    /** tick 检查频率：约每秒一次即可，不必每 tick 读配置（tick 在主线程）。 */
    private static final int TICK_CHECK_INTERVAL_TICKS = 20;
    /** 运行中复核 Minecraft 账号的间隔（换号后旧 token 必须尽快作废）。 */
    private static final long PROFILE_RECHECK_MS = 30_000L;
    /** 401 重认证的冷却窗口：一批查询被切成多块时，避免每块都触发一次认证。 */
    private static final long REAUTH_COOLDOWN_MS = 5_000L;
    /**
     * "本地时钟判定会话已过期 → 主动补一次认证"的冷却窗口。
     *
     * <p>为什么需要冷却：后端如果返回了极短的 {@code expiresIn}（小于 {@link FashionAuth.Session#expired()}
     * 的 60 秒安全边界），认证成功后的回调会再次发现"已过期"并再认证一次 —— 没有冷却就是
     * 每分钟几十次 challenge/join/verify 的认证风暴。60 秒一次把最坏情况压到可接受范围。
     */
    private static final long EXPIRY_REAUTH_COOLDOWN_MS = 60_000L;
    /** 同一条错误提示的聊天栏去重窗口，避免刷屏。 */
    private static final long CHAT_DEDUPE_MS = 15_000L;
    /** 网站衣柜收藏的定时刷新间隔：5 分钟（tick 每 20 tick 检查一次，到了才真的发请求）。 */
    private static final long WARDROBE_REFRESH_INTERVAL_MS = 5 * 60 * 1000L;
    /** 衣柜收藏拉取失败后的退避起点（连续失败按 2 倍递增，封顶 {@link #WARDROBE_REFRESH_INTERVAL_MS}）。 */
    private static final long WARDROBE_BACKOFF_BASE_MS = 30_000L;

    /**
     * {@link #refreshWardrobe(boolean)} 的结果。
     *
     * <p>为什么要返回值：玩家主动执行 {@code /vmfashion wardrobe refresh} 时，旧实现无论
     * "没登录 / 后端没这个端点 / 正在退避 / 已有请求在飞"都静默返回 —— 命令只打印了
     * "正在刷新…"，然后什么都不会发生，玩家以为卡住了。命令层据此给出真实原因。
     */
    public enum WardrobeRefreshResult {
        /** 已经发出请求（成功 / 失败都会在回调里处理，玩家会收到后续提示）。 */
        DISPATCHED,
        /** 已有请求在飞，这次没有重复发（玩家视角等价于"正在刷新"）。 */
        IN_FLIGHT,
        /** 同步层没启用（纯本地模式 / 未配置后端）。 */
        NOT_ENABLED,
        /** 还没登录时装后端（没有 token）。 */
        NOT_LOGGED_IN,
        /** 后端没有 {@code /wardrobe/me} 端点，本会话不再尝试。 */
        ENDPOINT_UNSUPPORTED,
        /** 上次失败后的退避窗口内。 */
        BACKING_OFF
    }

    private volatile FashionApi api;
    private volatile FashionAuth auth;
    private volatile HttpLoadoutProvider provider;
    private volatile boolean enabled;

    /** 绑定轮询的代数：发起 / 取消时 +1，旧轮询发现代数变了就自行停止。 */
    private volatile long bindEpoch;

    /** tick 计数：把"每 tick 都跑"降频成"每秒一次"。 */
    private int tickCounter;
    /** 上一次刷新其他玩家穿戴的时间戳。 */
    private volatile long lastRefreshAtMs;
    /** 上一次复核 Minecraft 账号的时间戳。 */
    private volatile long lastProfileCheckAtMs;
    /** 上一次触发 401 重认证的时间戳。 */
    private volatile long lastReauthAtMs;
    /** 上一次因"本地时钟判定会话已过期"而补认证的时间戳（见 {@link #EXPIRY_REAUTH_COOLDOWN_MS}）。 */
    private volatile long lastExpiryReauthAtMs;
    /** 最近一条错误提示（聊天栏去重用）。 */
    private volatile String lastChatMessage = "";
    private volatile long lastChatAtMs;

    // ==================== 网站衣柜收藏（WardrobeCollection）的状态 ====================

    /** 防重入：同一时刻只允许一个 {@code GET /wardrobe/me} 在飞。 */
    private final AtomicBoolean wardrobeRefreshInFlight = new AtomicBoolean();
    /** 连续失败次数（决定退避时长）；成功一次清零。 */
    private final AtomicInteger wardrobeFailureStreak = new AtomicInteger();
    /** 后端已确认没有 {@code /wardrobe/me} 端点 → 本次会话不再重试（避免每 5 分钟刷一次 404）。 */
    private volatile boolean wardrobeEndpointUnsupported;
    /** 那次"没有该端点"的 HTTP 状态码（404/405/501），0 = 还没遇到过；只用于给玩家/诊断看具体原因。 */
    private volatile int wardrobeUnsupportedStatus;
    /** 上一次发起衣柜收藏拉取的时间戳（tick 用它做 5 分钟节流）。 */
    private volatile long lastWardrobeRefreshAtMs;
    /** 退避截止时间戳：在此之前不发请求。 */
    private volatile long wardrobeBackoffUntilMs;
    /** 同步层代数：{@link #initialize(ModConfig)} 时 +1，用来丢弃"旧后端"的迟到回调。 */
    private volatile long wardrobeEpoch;

    private SyncService() {
    }

    public static SyncService getInstance() {
        return INSTANCE;
    }

    /**
     * 根据配置初始化。可以在任何时候重复调用（配置改了之后重新初始化）。
     */
    public void initialize(ModConfig config) {
        if (config == null || !config.canSync()) {
            enabled = false;
            releaseSyncStack();
            resetWardrobeSyncState();
            LoadoutService.getInstance().setProvider(null);
            // 只有"根本没配后端地址"的纯本地模式才允许回退到本地穿戴（单机预览渲染链路）。
            // 配了后端但把同步关掉时绝不能回退：回退会把"自己的"穿戴渲染到所有玩家身上，
            // 带 hideParts 的时装甚至会让别人模型部位消失。
            LoadoutService.getInstance().setFallbackToLocal(config == null || config.isLocalOnly());
            return;
        }

        // 旧同步层（及其 HttpClient）必须先释放：本方法会被 /vmfashion config reload 反复调用，
        // 不释放的话每次重载都会泄漏两套 HttpClient 的选择器线程 + 线程池。
        releaseSyncStack();
        FashionApi newApi = new FashionApi(config.getApiBaseUrl());
        FashionAuth newAuth = new FashionAuth(newApi);
        HttpLoadoutProvider newProvider = new HttpLoadoutProvider(newApi);
        // 401 重认证钩子：provider 不能直接依赖本类（本类持有 provider，会形成循环）
        newProvider.setReauthHook(this::reauthenticateForProvider);

        newAuth.restoreSession();

        api = newApi;
        auth = newAuth;
        provider = newProvider;
        enabled = true;
        lastReauthAtMs = 0L;
        lastExpiryReauthAtMs = 0L;
        lastRefreshAtMs = 0L;
        lastProfileCheckAtMs = 0L;
        resetWardrobeSyncState();
        // 读盘之后（VMFashionClient 已在 initialize 之前 loadFromDisk）核对这份收藏还是不是当前身份的：
        // 换号 / 换后端后旧收藏必须作废，否则断网时会把上一个账号的衣柜显示给新账号
        adoptWardrobeOwner();

        LoadoutService.getInstance().setProvider(newProvider);
        // 后端可用时以远端数据为准：没有远端记录的玩家 = 没穿时装。
        // 绝不能回退到"本地玩家自己的穿戴"，否则自己的时装会出现在所有其他玩家身上，
        // 带 hideParts 的时装甚至会让别人模型部位消失。
        // 纯本地模式（未配置后端）才允许回退，用于单机预览渲染链路。
        LoadoutService.getInstance().setFallbackToLocal(false);

        VMFashionClient.LOGGER.info("时装同步已启用：{}", config.getApiBaseUrl());
    }

    /**
     * 释放当前同步层（{@link FashionApi} / {@link FashionAuth}）并清空引用。
     *
     * <p>为什么必须有：{@code HttpClient} 一旦使用就持有选择器线程与线程池，
     * 而 {@link #initialize(ModConfig)} 会被 {@code /vmfashion config reload} 反复调用
     * （玩家改 {@code apiBaseUrl} 的官方路径）。不释放的话每次重载泄漏两套，
     * 反复重载几次就会多出一堆永不退出的线程。
     *
     * <p>只关闭 HTTP 层、不动 {@link LoadoutService} 的 provider —— 由调用方决定换成
     * 新 provider 还是回退到 {@code LocalOnlyProvider}。
     */
    private void releaseSyncStack() {
        FashionAuth previousAuth = auth;
        FashionApi previousApi = api;
        auth = null;
        api = null;
        provider = null;
        if (previousAuth != null) {
            try {
                previousAuth.close();
            } catch (Throwable t) {
                VMFashionClient.LOGGER.debug("释放时装认证层失败（忽略）: {}", t.toString());
            }
        }
        if (previousApi != null) {
            try {
                previousApi.close();
            } catch (Throwable t) {
                VMFashionClient.LOGGER.debug("释放时装 API 层失败（忽略）: {}", t.toString());
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public FashionApi api() {
        return api;
    }

    public FashionAuth auth() {
        return auth;
    }

    public HttpLoadoutProvider provider() {
        return provider;
    }

    /**
     * 每客户端 tick 调用（由 {@code VMFashionClient} 注册）。
     *
     * <p>用途：周期性刷新其他玩家的穿戴（{@code ModConfig#getRefreshIntervalSeconds()}），
     * 让"别人换装"不用重进游戏就能看到；顺带低频复核 Minecraft 账号（换号竞态）。
     *
     * <p>tick 跑在主线程：这里只做计数、读配置、收集 UUID，真正的网络请求全在 IO 线程，
     * 不能在这里做任何阻塞操作。
     */
    public void tick() {
        if (!enabled) {
            return;
        }
        // 每 tick 都读配置太浪费；20 tick ≈ 1 秒检查一次足够
        if (++tickCounter < TICK_CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        long now = System.currentTimeMillis();
        revalidateProfileIfDue(now);
        refreshOthersIfDue(now);
        refreshWardrobeIfDue(now);
    }

    /** 运行中复核账号：换号后旧 token 会把 A 的穿戴写进 B，必须清掉重认证。 */
    private void revalidateProfileIfDue(long now) {
        if (now - lastProfileCheckAtMs < PROFILE_RECHECK_MS) {
            return;
        }
        lastProfileCheckAtMs = now;
        FashionAuth currentAuth = auth;
        if (currentAuth == null) {
            return;
        }
        if (!currentAuth.revalidateProfile()) {
            VMFashionClient.LOGGER.info("时装会话已失效（Minecraft 账号已切换），重新认证");
            authenticateAsync(false);
        }
    }

    /** 到了 {@code refreshIntervalSeconds} 就刷新一次其他玩家；0 表示关闭。 */
    private void refreshOthersIfDue(long now) {
        ModConfig config = currentConfig();
        if (config == null) {
            return;
        }
        int intervalSeconds = config.getRefreshIntervalSeconds();
        if (intervalSeconds <= 0) {
            return;
        }
        if (now - lastRefreshAtMs < intervalSeconds * 1000L) {
            return;
        }
        lastRefreshAtMs = now;
        refreshOthers();
    }

    /**
     * 刷新当前世界里其他玩家的穿戴。
     *
     * <p>必须先打掉 provider 自己的条目：{@link HttpLoadoutProvider} 有 5 分钟 TTL，
     * 不打掉的话"定时刷新"拿到的还是旧数据，等于没刷新。
     */
    private void refreshOthers() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        List<AbstractClientPlayer> players = minecraft.level.players();
        if (players.isEmpty()) {
            return;
        }
        UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        HttpLoadoutProvider currentProvider = provider;
        List<UUID> uuids = new ArrayList<>(players.size());
        for (AbstractClientPlayer player : players) {
            if (player == null) {
                continue;
            }
            UUID uuid = player.getUUID();
            if (uuid == null || uuid.equals(self)) {
                continue;
            }
            if (currentProvider != null) {
                currentProvider.invalidate(uuid);
            }
            uuids.add(uuid);
        }
        if (uuids.isEmpty()) {
            return;
        }
        // 交给 LoadoutService 的节流队列：它会把这一批合并成一次批量请求。
        // force=true：周期刷新本身就是低频的，不能被 1 秒节流窗口吞掉再等一个周期。
        LoadoutService.getInstance().requestRemote(uuids, true);
    }

    /** 当前配置（客户端实例可能尚未初始化，必须判空）。 */
    private static ModConfig currentConfig() {
        VMFashionClient client = VMFashionClient.getInstance();
        return client == null ? null : client.getConfig();
    }

    /** 进入服务器：如果还没认证或 token 过期，后台完成一次认证。 */
    public void onJoin() {
        AssetSyncService.getInstance().onJoin();
        long now = System.currentTimeMillis();
        // 进服立即复核一次账号，并让周期刷新从进服时刻重新计时
        lastRefreshAtMs = now;
        lastProfileCheckAtMs = now;
        // 衣柜收藏同理：进服时拉一次，随后由 tick 每 5 分钟续命
        lastWardrobeRefreshAtMs = now;
        // 进服是"换号"最可能发生的时刻：先把收藏归属核对一遍，再谈刷新
        adoptWardrobeOwner();
        if (!enabled) {
            return;
        }
        if (provider != null) {
            provider.invalidateAll();
        }
        // LoadoutService 自己还有一层"UUID → 穿戴"缓存，仅清 provider 不够
        LoadoutService.getInstance().invalidate();
        FashionAuth currentAuth = auth;
        if (currentAuth == null) {
            return;
        }
        // 进服先复核账号：上一个账号的 token 绝不能用在当前账号上
        currentAuth.revalidateProfile();
        FashionAuth.Session session = currentAuth.session();
        if (session != null && !session.expired()) {
            // 进服时以网站衣柜为准拉取自己的穿戴；绝不拿本地空状态去覆盖云端
            refreshFromRemote(false);
            // 网站衣柜收藏：进服即拉一次（界面第一次打开就有最新数据；
            // 失败/断网时界面继续用磁盘缓存，绝不清空）
            refreshWardrobe(false);
            return;
        }
        authenticateAsync(true);
    }

    /** 离开服务器：清掉远端缓存，避免串服；正在等待的绑定码也作废。 */
    public void onDisconnect() {
        AssetSyncService.getInstance().onDisconnect();
        cancelBinding();
        lastRefreshAtMs = 0L;
        lastProfileCheckAtMs = 0L;
        // 下次进服立刻重拉衣柜收藏（内存镜像保留：断服期间界面照常按收藏过滤）
        lastWardrobeRefreshAtMs = 0L;
        if (provider != null) {
            provider.invalidateAll();
        }
        LoadoutService.getInstance().invalidate();
    }

    /** 取消正在进行的「绑定到网站」轮询（换账号 / 断线时调用）。 */
    public void cancelBinding() {
        bindEpoch++;
    }

    /**
     * 供 {@link HttpLoadoutProvider} 在批量查询返回 401 时调用：重新认证一次。
     *
     * <p>为什么做成钩子：本类持有 provider，provider 再反过来依赖本类就是循环依赖。
     * 冷却窗口用来兜住"一批查询被切成多块、每块都 401"的情况，避免认证风暴。
     *
     * @return 完成时 {@code true} 表示已有可用 token，provider 可以重放请求
     */
    private CompletableFuture<Boolean> reauthenticateForProvider() {
        FashionAuth currentAuth = auth;
        if (!enabled || currentAuth == null) {
            return CompletableFuture.completedFuture(false);
        }
        long now = System.currentTimeMillis();
        if (now - lastReauthAtMs < REAUTH_COOLDOWN_MS) {
            // 冷却中：别的分块已经在认证，这里只回答"现在有没有可用 token"
            FashionAuth.Session existing = currentAuth.session();
            return CompletableFuture.completedFuture(existing != null && !existing.expired());
        }
        lastReauthAtMs = now;
        ModConfig config = currentConfig();
        String authServer = config == null ? "" : config.getAuthServer();
        VMFashionClient.LOGGER.info("穿戴查询返回 401，重新认证时装后端");
        currentAuth.clearSession();

        CompletableFuture<FashionAuth.Session> future;
        try {
            future = currentAuth.authenticate(authServer);
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("401 后重新认证失败: {}", t.toString());
            return CompletableFuture.completedFuture(false);
        }
        return future.handle((session, throwable) -> {
            if (throwable != null) {
                VMFashionClient.LOGGER.warn("401 后重新认证失败: {}", throwable.toString());
                return false;
            }
            boolean ok = session != null && !session.expired();
            if (ok) {
                VMFashionClient.LOGGER.info("重新认证成功，准备重放穿戴查询");
            }
            return ok;
        });
    }

    // ==================== 错误可读化 ====================

    /**
     * 后端错误码 → 玩家能看懂的提示。
     *
     * <p>旧实现把这些错误只打进日志：玩家换装没生效，却完全不知道是"时装被禁用"还是
     * "槽位超限"还是"没绑定网站"。抽成包可见的静态方法，方便单测。
     *
     * <p>i18n：文案全部在语言文件里（{@code vmfashion.error.*}），这里只做错误码到 key 的映射。
     * 认证链路（{@link FashionAuth}）抛的 {@code ApiException} 也在这里翻译 ——
     * 它自带的 message 是中文兜底，只进日志，不再直接给玩家看。
     *
     * @return 提示文案；{@code null} 表示不值得打扰玩家（例如纯网络抖动，聊天栏刷这个没用）
     */
    static Component describeApiError(Throwable throwable) {
        FashionApi.ApiException apiException = findApiException(throwable);
        if (apiException == null) {
            return null;
        }
        String code = apiException.code() == null ? "" : apiException.code().toLowerCase(Locale.ROOT);
        String detail = apiException.getMessage() == null ? "" : apiException.getMessage();
        return switch (code) {
            case "unknown_cosmetic" -> Component.translatable("vmfashion.error.unknown_cosmetic", detail);
            case "cosmetic_disabled" -> Component.translatable("vmfashion.error.cosmetic_disabled", detail);
            case "invalid_cosmetic" -> Component.translatable("vmfashion.error.invalid_cosmetic", detail);
            case "too_many_items" -> Component.translatable("vmfashion.error.too_many_items");
            case "not_joined" -> Component.translatable("vmfashion.error.not_joined");
            // no_token 有两个来源：status=-1 是启动器没给登录令牌，401 是时装后端没登录
            case "no_token" -> apiException.status() == -1
                    ? Component.translatable("vmfashion.error.no_access_token")
                    : Component.translatable("vmfashion.error.no_token");
            case "rate_limited", "too_many_requests", "http_429" ->
                    Component.translatable("vmfashion.error.rate_limited");
            case "invalid_request", "bad_request", "http_400", "http_422",
                 // 后端的参数级错误码（正常客户端不会触发，触发了说明客户端有 bug）：
                 // 映射到同一条提示，至少让玩家/排查者看到"后端拒绝了这次请求"而不是静默
                 "invalid_slot", "duplicate_slot", "too_many_uuids", "invalid_scale",
                 "invalid_cosmetic_id" ->
                    Component.translatable("vmfashion.error.bad_request", detail);
            case "forbidden", "http_403" -> Component.translatable("vmfashion.error.forbidden", detail);
            // —— 认证链路（FashionAuth）——
            case "no_session" -> Component.translatable("vmfashion.error.no_session");
            case "no_profile" -> Component.translatable("vmfashion.error.no_profile");
            case "bad_challenge" -> Component.translatable("vmfashion.error.bad_challenge");
            case "bad_verify" -> Component.translatable("vmfashion.error.bad_verify");
            case "bad_session_url" -> Component.translatable("vmfashion.error.bad_session_url");
            case "join_failed" -> Component.translatable("vmfashion.error.join_failed", apiException.status());
            // 后端 hasJoined 判定：一次性 serverId 过期 / 已被消费
            case "challenge_expired", "challenge_not_found" ->
                    Component.translatable("vmfashion.error.challenge_expired");
            // 声明与 challenge 不一致，或 hasJoined 返回的 UUID 与声明不符（安全校验）
            case "challenge_mismatch", "source_mismatch", "uuid_mismatch" ->
                    Component.translatable("vmfashion.error.auth_mismatch");
            // 上游认证服务（Mojang / 皮肤站）故障：可重试
            case "upstream_unavailable" ->
                    Component.translatable("vmfashion.error.upstream_unavailable");
            // 后端没配 JWT 密钥等部署问题：玩家自己解决不了，只能提示联系管理员
            case "server_misconfigured" ->
                    Component.translatable("vmfashion.error.server_misconfigured");
            // —— 请求层（FashionApi / PackCatalog）——
            case "no_base_url" -> Component.translatable("vmfashion.error.no_base_url");
            case "bad_base_url" -> Component.translatable("vmfashion.error.bad_base_url", detail);
            case "network_error" -> Component.translatable("vmfashion.error.network_error", detail);
            case "circuit_open" -> Component.translatable("vmfashion.error.circuit_open");
            case "bad_json" -> Component.translatable("vmfashion.error.bad_json");
            case "bad_asset_key" -> Component.translatable("vmfashion.error.bad_asset_key");
            // 明文 http 后端：客户端拒绝发送登录凭据（audit v1.2 N05）
            case "insecure_backend" -> Component.translatable("vmfashion.error.insecure_backend");
            // 兜底（audit v1.2 N01）：5xx 与后端新增的未知错误码以前会落 null → 玩家侧完全静默，
            // 换装"提示成功"却没保存。现在至少给一条"可能没保存、可重试"，去重由 notifyError 负责。
            default -> apiException.status() >= 500
                    ? Component.translatable("vmfashion.error.server_unavailable", apiException.status())
                    : Component.translatable("vmfashion.error.unexpected", code.isBlank() ? "?" : code);
        };
    }

    /**
     * 把异常翻成玩家能看懂的"失败原因"。
     *
     * <p>后端错误码优先走语言文件；拿不到（纯网络异常、系统异常等）就退化成异常原文 ——
     * 那类文本本来就是英文 / 系统语言，强行翻译反而更难懂。
     */
    private static Component failureDetail(Throwable throwable) {
        Component mapped = describeApiError(throwable);
        return mapped != null ? mapped : Component.literal(rootMessage(throwable));
    }

    /** 把后端错误码转成聊天栏提示（同一条消息去重，避免刷屏）。 */
    private void notifyError(Throwable throwable) {
        Component message = describeApiError(throwable);
        if (message == null) {
            return;
        }
        // 去重用纯文本：Component 每次都是新对象，equals 比较的是内容但这里只是去重，取字符串更直观
        String text = message.getString();
        if (text.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (text.equals(lastChatMessage) && now - lastChatAtMs < CHAT_DEDUPE_MS) {
            return;
        }
        lastChatMessage = text;
        lastChatAtMs = now;
        ChatUtil.showLocalMessage(message);
    }

    /** 从异常链里找出后端返回的错误（可能被 CompletableFuture 包了几层）。 */
    private static FashionApi.ApiException findApiException(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof FashionApi.ApiException api) {
                return api;
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return null;
    }

    /**
     * 手动登录（{@code /vmfashion login}）。
     */
    public void authenticateAsync(boolean notifyChat) {
        if (!enabled) {
            if (notifyChat) {
                ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.local_only"));
            }
            return;
        }
        FashionAuth currentAuth = auth;
        if (currentAuth == null) {
            return;
        }
        ModConfig config = VMFashionClient.getInstance().getConfig();
        if (notifyChat) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.logging_in"));
        }
        currentAuth.authenticate(config.getAuthServer()).whenComplete((session, throwable) -> {
            if (throwable != null) {
                VMFashionClient.LOGGER.warn("时装认证失败: {}", throwable.toString());
                if (notifyChat) {
                    ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.login_failed",
                            failureDetail(throwable)));
                }
                return;
            }
            if (notifyChat) {
                ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.login_success",
                        session.mcName(), session.authType()));
            }
            // 首次登录：云端为空且本地已有穿戴时才推送本地，否则以云端为准
            refreshFromRemote(true);
            // 认证成功后 token 才可用：立刻拉一次网站衣柜收藏（登录前那次会因没 token 直接返回）
            refreshWardrobe(false);
        });
    }

    // ==================== 绑定到网站（设备码） ====================

    /**
     * 绑定到网站：mod 拿 6 位用户码显示给玩家，玩家在网页「时装绑定」页输入。
     *
     * <p>流程（见站点仓库 {@code docs/fashion-api.md} §6）：
     * {@code POST /device/start} → 网页 {@code POST /device/approve} →
     * mod 轮询 {@code GET /device/status} 直到 {@code approved} / {@code expired}。
     */
    public void bindToWebsite(boolean notifyChat) {
        FashionAuth currentAuth = auth;
        if (!enabled || currentAuth == null || api == null) {
            if (notifyChat) {
                ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.local_only"));
            }
            return;
        }
        FashionAuth.Session session = currentAuth.session();
        if (session != null && !session.expired()) {
            startDeviceFlow(notifyChat);
            return;
        }
        // 没登录 / token 过期：先认证，成功后再继续绑定
        if (notifyChat) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.auth_before_bind"));
        }
        ModConfig config = VMFashionClient.getInstance().getConfig();
        currentAuth.authenticate(config.getAuthServer()).whenComplete((newSession, throwable) -> {
            if (throwable != null) {
                VMFashionClient.LOGGER.warn("绑定前认证失败: {}", throwable.toString());
                if (notifyChat) {
                    ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.auth_failed_for_bind",
                            failureDetail(throwable)));
                }
                return;
            }
            startDeviceFlow(notifyChat);
        });
    }

    private void startDeviceFlow(boolean notifyChat) {
        FashionApi currentApi = api;
        if (currentApi == null) {
            return;
        }
        long epoch = ++bindEpoch;
        currentApi.startDeviceFlow().whenComplete((json, throwable) -> {
            if (epoch != bindEpoch) {
                return;
            }
            if (throwable != null) {
                VMFashionClient.LOGGER.warn("申请绑定码失败: {}", throwable.toString());
                if (notifyChat) {
                    ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.bind_request_failed",
                            failureDetail(throwable)));
                }
                return;
            }
            String userCode = stringField(json, "userCode");
            String deviceCode = stringField(json, "deviceCode");
            int expiresIn = intField(json, "expiresIn", 600);
            int interval = Math.max(1, intField(json, "interval", 3));
            if (userCode.isBlank() || deviceCode.isBlank()) {
                if (notifyChat) {
                    ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.bind_no_code"));
                }
                return;
            }
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.bind_code", userCode));
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.bind_code_hint",
                    Math.max(1, expiresIn / 60)));
            pollDeviceStatus(epoch, deviceCode, interval,
                    System.currentTimeMillis() + expiresIn * 1000L, notifyChat);
        });
    }

    private void pollDeviceStatus(long epoch, String deviceCode, int interval,
                                  long deadline, boolean notifyChat) {
        FashionApi currentApi = api;
        if (currentApi == null || epoch != bindEpoch) {
            return;
        }
        currentApi.deviceStatus(deviceCode).whenComplete((json, throwable) -> {
            if (epoch != bindEpoch) {
                return;
            }
            if (throwable != null) {
                // 网络抖动不算失败：没到截止时间就继续轮询
                if (System.currentTimeMillis() < deadline) {
                    schedulePoll(epoch, deviceCode, interval, deadline, notifyChat);
                } else if (notifyChat) {
                    ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.bind_expired"));
                }
                return;
            }
            String status = stringField(json, "status");
            if ("approved".equals(status)) {
                ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.bind_success"));
                // 绑定完成后云端衣柜成为权威来源，避免用本地状态覆盖网站已保存的穿戴
                refreshFromRemote(true);
                // 绑定会改变 wardrobe/me 的 linked 状态：不立刻重拉的话，玩家要重进游戏
                // 衣柜才会从"本地全部"切到"网站收藏"
                refreshWardrobe(false);
                return;
            }
            if ("expired".equals(status) || System.currentTimeMillis() >= deadline) {
                if (notifyChat) {
                    ChatUtil.showLocalMessage(Component.translatable("vmfashion.chat.bind_expired"));
                }
                return;
            }
            schedulePoll(epoch, deviceCode, interval, deadline, notifyChat);
        });
    }

    private void schedulePoll(long epoch, String deviceCode, int interval,
                              long deadline, boolean notifyChat) {
        CompletableFuture.delayedExecutor(interval, TimeUnit.SECONDS).execute(
                () -> pollDeviceStatus(epoch, deviceCode, interval, deadline, notifyChat));
    }

    private static String stringField(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            return json.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int intField(JsonObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * 从后端拉取"我"的穿戴并应用到本地。
     *
     * <p>进服 / 认证成功 / 绑定成功后都必须先走这里：旧实现直接 {@link #uploadLocalLoadout()}
     * 会把本地内存里的空状态全量覆盖到云端，导致玩家在网页衣柜里保存的穿搭一进游戏就被清空。
     *
     * @param allowUploadLocal 云端为空且本地已有穿戴时，是否把本地推上去（首次登录场景）。
     *                         进服时传 {@code false}，避免用上一次会话的本地残留覆盖网站衣柜。
     */
    public void refreshFromRemote(boolean allowUploadLocal) {
        FashionApi currentApi = api;
        FashionAuth currentAuth = auth;
        if (!enabled || currentApi == null || currentAuth == null) {
            return;
        }
        FashionAuth.Session session = currentAuth.session();
        if (session == null || session.expired()) {
            // 旧实现直接 return：本地时钟判定过期后，"云端 → 本地"这条链路就永久静默失效
            // （玩家换装后再也拉不到网站衣柜的设置，也没有任何提示）。
            // 这里补一次认证，认证成功后的回调会重新走 refreshFromRemote。
            if (expiryReauthDue(System.currentTimeMillis())) {
                VMFashionClient.LOGGER.info("时装会话已过期，先重新认证再拉取云端穿戴");
                authenticateAsync(false);
            }
            return;
        }
        Map<String, String> localBefore = LocalLoadout.getInstance().snapshot();
        currentApi.myLoadout().whenComplete((json, throwable) -> {
            if (throwable != null) {
                if (isUnauthorized(throwable)) {
                    // token 被服务端撤销/过期（例如服务端换密钥、超 24h）：清掉旧会话重新认证一次
                    VMFashionClient.LOGGER.info("时装 token 已失效，重新认证");
                    currentAuth.clearSession();
                    authenticateAsync(false);
                } else {
                    VMFashionClient.LOGGER.warn("拉取云端穿戴失败: {}", throwable.toString());
                    notifyError(throwable);
                }
                return;
            }
            try {
                Map<String, String> remote = parseLoadout(json);
                if (allowUploadLocal && remote.isEmpty() && !localBefore.isEmpty()) {
                    uploadLocalLoadout();
                    return;
                }
                LocalLoadout.getInstance().replaceAll(remote);
                VMFashionClient.LOGGER.debug("已应用云端穿戴: {} 个槽位", remote.size());
            } catch (Exception e) {
                VMFashionClient.LOGGER.warn("解析云端穿戴失败: {}", e.toString());
            }
        });
    }

    private static Map<String, String> parseLoadout(JsonObject json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || !json.has("items") || !json.get("items").isJsonArray()) {
            return result;
        }
        for (JsonElement element : json.getAsJsonArray("items")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            try {
                String slot = stringField(item, "slot");
                String cosmeticId = stringField(item, "cosmeticId");
                boolean missing = item.has("missing") && !item.get("missing").isJsonNull()
                        && item.get("missing").getAsBoolean();
                if (!slot.isBlank() && !cosmeticId.isBlank() && !missing) {
                    result.put(slot.toLowerCase(Locale.ROOT), cosmeticId);
                }
            } catch (Exception ignored) {
                // 单条脏数据不影响其它槽位
            }
        }
        return result;
    }

    // ==================== 网站衣柜收藏（我的衣柜） ====================

    /**
     * 拉取网站「我的衣柜」收藏并替换本地镜像（{@link WardrobeCollection}）。
     *
     * <p><b>为什么 mod 要拉这份列表</b>：游戏内衣柜现在只显示玩家在网站商店里「加入衣柜」的时装，
     * 而不是"本地装了哪些包"。列表里可能有本地还没装包的时装 —— 界面照样列出来，穿上时再下发。
     *
     * <p><b>分支行为</b>：
     * <ul>
     *   <li>未启用 / 没有 api / 没有 token / 已有请求在飞 / 正在退避 → 静默返回（不打扰玩家）；</li>
     *   <li>成功 → {@link WardrobeCollection#parse} → {@link WardrobeCollection#replaceAll} → 落盘缓存；</li>
     *   <li>401 → 走 {@link #reauthenticateForProvider()}（与穿戴查询同一条重新认证路径，自带冷却），
     *       认证成功后再拉一次；重试后仍 401 就退避，不会形成认证风暴；</li>
     *   <li>404 / 405 / 501（后端版本没有这个端点）→ 记一次 info，本次会话不再重试；</li>
     *   <li>其它失败 → 指数退避 + 首次 warn / 之后 debug，<b>绝不清空</b>已有收藏。</li>
     * </ul>
     *
     * <p><b>线程</b>：全程异步；回调里只碰 {@link WardrobeCollection}（内部 volatile + 锁，线程安全），
     * 需要提示玩家时走 {@link ChatUtil}（内部投递回主线程）。回调里不碰任何 Minecraft 对象。
     *
     * @param notifyChat 失败时是否在聊天栏提示（玩家主动触发的刷新传 {@code true}，
     *                   进服 / 定时刷新传 {@code false}）
     * @return 这次调用到底做了什么（{@link WardrobeRefreshResult#DISPATCHED} 表示请求已发出）；
     *         玩家主动刷新时命令层用它决定要不要提示"为什么没刷新"
     */
    public WardrobeRefreshResult refreshWardrobe(boolean notifyChat) {
        return refreshWardrobe(notifyChat, true);
    }

    /**
     * 界面打开时用的节流版本：距上次成功拉取不足 {@code minIntervalMs} 就跳过，
     * 避免每开一次衣柜就打一次请求。
     *
     * @param minIntervalMs 最小间隔（毫秒）；{@code <= 0} 表示不节流
     */
    public void refreshWardrobeIfStale(long minIntervalMs) {
        long fetchedAt = WardrobeCollection.getInstance().fetchedAtMs();
        if (fetchedAt > 0 && minIntervalMs > 0) {
            long age = System.currentTimeMillis() - fetchedAt;
            // age < 0 = 系统时间被回拨：按"过期"处理，宁可多拉一次
            if (age >= 0 && age < minIntervalMs) {
                return;
            }
        }
        refreshWardrobe(false);
    }

    /** 每 {@link #WARDROBE_REFRESH_INTERVAL_MS} 最多刷新一次衣柜收藏（和 {@link #refreshOthersIfDue} 同风格）。 */
    private void refreshWardrobeIfDue(long now) {
        long elapsed = now - lastWardrobeRefreshAtMs;
        // elapsed < 0 = 系统时间被回拨，当作"到期"处理，避免永远不再刷新
        if (elapsed >= 0 && elapsed < WARDROBE_REFRESH_INTERVAL_MS) {
            return;
        }
        lastWardrobeRefreshAtMs = now;
        refreshWardrobe(false);
    }

    /**
     * 这次刷新会不会真的发请求（纯逻辑，可单测）。
     *
     * <p>抽成静态方法是为了让单测直接断言"未登录 / 端点不支持 / 退避中 / 在飞"这四种跳过原因，
     * 不用起 HTTP、也不用碰 Minecraft 类。
     *
     * @param enabled             同步层是否启用
     * @param hasApi              是否有 {@link FashionApi}
     * @param hasAuth             是否有 {@link FashionAuth}
     * @param hasToken            是否已有 token
     * @param endpointUnsupported 后端是否已确认没有 {@code /wardrobe/me}
     * @param now                 当前时间戳
     * @param backoffUntilMs      退避截止时间戳
     * @param inFlight            是否已有请求在飞
     */
    static WardrobeRefreshResult evaluateWardrobeRefresh(boolean enabled, boolean hasApi, boolean hasAuth,
                                                         boolean hasToken, boolean endpointUnsupported,
                                                         long now, long backoffUntilMs, boolean inFlight) {
        if (!enabled || !hasApi || !hasAuth) {
            return WardrobeRefreshResult.NOT_ENABLED;
        }
        if (!hasToken) {
            // 认证成功的回调会重新触发，这里不排队
            return WardrobeRefreshResult.NOT_LOGGED_IN;
        }
        if (endpointUnsupported) {
            // 后端版本没有这个端点：本会话不再重试（info 已经记过一次）
            return WardrobeRefreshResult.ENDPOINT_UNSUPPORTED;
        }
        if (inFlight) {
            // 已有请求在飞（tick / 界面 / 进服可能同时触发）
            return WardrobeRefreshResult.IN_FLIGHT;
        }
        if (now < backoffUntilMs) {
            // 退避窗口内：失败后不刷屏
            return WardrobeRefreshResult.BACKING_OFF;
        }
        return WardrobeRefreshResult.DISPATCHED;
    }

    /**
     * @param allowReauthRetry 401 时是否允许"重新认证 → 再拉一次"（重试的那次传 {@code false}）
     */
    private WardrobeRefreshResult refreshWardrobe(boolean notifyChat, boolean allowReauthRetry) {
        FashionApi currentApi = api;
        FashionAuth currentAuth = auth;
        long now = System.currentTimeMillis();
        WardrobeRefreshResult precheck = evaluateWardrobeRefresh(enabled, currentApi != null,
                currentAuth != null, currentApi != null && currentApi.hasToken(),
                wardrobeEndpointUnsupported, now, wardrobeBackoffUntilMs,
                wardrobeRefreshInFlight.get());
        if (precheck != WardrobeRefreshResult.DISPATCHED) {
            return precheck;
        }
        if (!wardrobeRefreshInFlight.compareAndSet(false, true)) {
            // 上面那次 get() 与这次 CAS 之间被别人抢了
            return WardrobeRefreshResult.IN_FLIGHT;
        }
        lastWardrobeRefreshAtMs = now;
        long epoch = wardrobeEpoch;
        CompletableFuture<JsonObject> future;
        try {
            future = currentApi.wardrobeMine();
        } catch (Throwable t) {
            // wardrobeMine 内部已经 guarded，这里只是兜底，保证防重入标志一定被释放
            wardrobeRefreshInFlight.set(false);
            handleWardrobeFailure(t, notifyChat, allowReauthRetry);
            return WardrobeRefreshResult.DISPATCHED;
        }
        future.whenComplete((json, throwable) -> {
            wardrobeRefreshInFlight.set(false);
            if (epoch != wardrobeEpoch) {
                // 配置热更新换了后端：旧后端的迟到响应直接丢弃
                return;
            }
            if (throwable != null) {
                handleWardrobeFailure(throwable, notifyChat, allowReauthRetry);
                return;
            }
            applyWardrobe(json, notifyChat);
        });
        return WardrobeRefreshResult.DISPATCHED;
    }

    /**
     * 成功分支：解析 → 整体替换内存镜像 → 落盘缓存。
     *
     * @param notifyChat 玩家主动触发的刷新（{@code /vmfashion wardrobe refresh}）为 {@code true}：
     *                   成功后在聊天栏回一句件数，否则命令敲下去没任何反馈
     */
    private void applyWardrobe(JsonObject json, boolean notifyChat) {
        try {
            WardrobeCollection.Parsed parsed = WardrobeCollection.parse(json);
            WardrobeCollection collection = WardrobeCollection.getInstance();
            // 落盘前先把这份收藏归属到当前身份：换号 / 换后端后旧收藏不能继续生效（见 adoptOwner）
            collection.adoptOwner(currentWardrobeOwnerKey());
            collection.replaceAll(parsed.linked(), parsed.entries());
            // 落盘缓存：断网 / 后端挂掉时下次进游戏仍能按收藏过滤。
            // 失败只记日志（saveNow 内部吞异常），内存镜像已经生效
            collection.saveNow();
            wardrobeFailureStreak.set(0);
            wardrobeBackoffUntilMs = 0L;
            VMFashionClient.LOGGER.debug("已同步网站衣柜收藏: {} 件（linked={}，已下架 {} 件）",
                    parsed.entries().size(), parsed.linked(), collection.missingCount());
            if (notifyChat) {
                // 玩家主动刷新：成功也要有反馈，否则命令敲下去像没生效
                ChatUtil.showLocalMessage(parsed.linked()
                        ? Component.translatable("vmfashion.chat.wardrobe_refreshed",
                                parsed.entries().size())
                        : Component.translatable("vmfashion.chat.wardrobe_unlinked"));
            }
        } catch (Throwable t) {
            // 解析/落盘出问题也不能影响游戏：失败时内存镜像保持替换前的快照
            VMFashionClient.LOGGER.warn("应用网站衣柜收藏失败: {}", t.toString());
        }
    }

    /** 失败分支：区分"端点不存在 / 401 / 其它"。 */
    private void handleWardrobeFailure(Throwable throwable, boolean notifyChat, boolean allowReauthRetry) {
        FashionApi.ApiException apiException = findApiException(throwable);
        int status = apiException == null ? -1 : apiException.status();
        if (isWardrobeEndpointUnsupported(status)) {
            // 后端版本没实现这个端点：只记一次，本会话不再重试（否则每 5 分钟一条 404）
            wardrobeEndpointUnsupported = true;
            wardrobeUnsupportedStatus = status;
            VMFashionClient.LOGGER.info("时装后端没有 /wardrobe/me 端点（HTTP {}），本会话不再刷新衣柜收藏", status);
            if (notifyChat) {
                // 玩家手动刷新时不能静默：否则"正在刷新…"之后再无下文，看起来像卡死
                ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.wardrobe.refresh_failed",
                        Component.translatable("vmfashion.error.unexpected", "http_" + status)));
            }
            return;
        }
        if (isUnauthorized(throwable)) {
            if (!allowReauthRetry) {
                // 重新认证后仍然 401：不是 token 的问题（后端权限 / 部署），退避，避免认证风暴
                VMFashionClient.LOGGER.warn("衣柜收藏拉取在重新认证后仍返回 401，暂停刷新");
                backOffWardrobe(throwable);
                return;
            }
            VMFashionClient.LOGGER.info("衣柜收藏拉取返回 401，重新认证后再拉一次");
            reauthenticateForProvider().whenComplete((ok, error) -> {
                if (error != null || !Boolean.TRUE.equals(ok)) {
                    // 认证没成功：保留现有收藏（内存或磁盘缓存），等下次 tick / 进服再试
                    VMFashionClient.LOGGER.debug("衣柜收藏：重新认证未成功，本次跳过: {}",
                            error == null ? "无可用 token" : error.toString());
                    return;
                }
                // 重试那次不再允许重新认证，避免"401 → 认证 → 401 → 认证"的循环
                refreshWardrobe(notifyChat, false);
            });
            return;
        }
        // 其它网络 / 后端错误：退避 + 不清空已有收藏
        backOffWardrobe(throwable);
        if (notifyChat) {
            notifyError(throwable);
        }
    }

    /**
     * 后端是否明确表示"没有 /wardrobe/me 这个端点"。
     *
     * <p>404 = 路由不存在（后端版本没实现），405 = 方法不允许（路由存在但只接了别的动词），
     * 501 = 未实现。这三种都说明"重试也没用"，本会话不再试。
     */
    private static boolean isWardrobeEndpointUnsupported(int status) {
        return status == 404 || status == 405 || status == 501;
    }

    /**
     * 失败退避：30s 起、每次翻倍、封顶 5 分钟。
     *
     * <p>只有连续失败的第 1 次记 warn，之后降到 debug —— 后端挂掉时一条 warn 足够定位问题，
     * 不该每 5 分钟刷满日志。
     */
    private void backOffWardrobe(Throwable throwable) {
        int streak = wardrobeFailureStreak.incrementAndGet();
        long delay = Math.min(WARDROBE_REFRESH_INTERVAL_MS,
                WARDROBE_BACKOFF_BASE_MS << Math.min(streak - 1, 5));
        wardrobeBackoffUntilMs = System.currentTimeMillis() + delay;
        if (streak == 1) {
            VMFashionClient.LOGGER.warn("拉取网站衣柜收藏失败，{}ms 后重试: {}", delay, throwable.toString());
        } else {
            VMFashionClient.LOGGER.debug("拉取网站衣柜收藏再次失败（连续 {} 次），{}ms 后重试: {}",
                    streak, delay, throwable.toString());
        }
    }

    /**
     * 重置衣柜收藏的刷新状态（初始化 / 配置热更新时调用）。
     *
     * <p>递增 {@link #wardrobeEpoch} 让旧后端上还在飞的响应作废：否则 {@code /vmfashion config reload}
     * 换后端之后，旧后端迟到的收藏会把新后端的镜像覆盖掉。
     */
    private void resetWardrobeSyncState() {
        wardrobeEpoch++;
        wardrobeEndpointUnsupported = false;
        wardrobeUnsupportedStatus = 0;
        lastWardrobeRefreshAtMs = 0L;
        wardrobeBackoffUntilMs = 0L;
        wardrobeFailureStreak.set(0);
    }

    /**
     * 当前身份 key（{@code mcUuid|apiBaseUrl}），用来判断"这份衣柜收藏还是不是我的"。
     *
     * <p>为什么把后端地址也算进去：同一个 MC 身份在不同后端上对应<b>不同的网站账号</b>，
     * 换后端后旧后端拉下来的收藏必须作废，否则玩家会看到另一套账号的衣柜。
     *
     * @return 身份 key；客户端还没准备好 / 纯本地模式（没有后端地址）时返回空串（调用方不做清理）
     */
    private static String currentWardrobeOwnerKey() {
        UUID profileId = FashionAuth.currentProfileId();
        if (profileId == null) {
            return "";
        }
        String baseUrl = apiBaseUrlOfCurrentConfig();
        if (baseUrl.isEmpty()) {
            return "";
        }
        return profileId.toString().toLowerCase(Locale.ROOT) + "|" + baseUrl;
    }

    /** 当前配置里的后端地址（小写，去尾部斜杠）；拿不到时返回空串。 */
    private static String apiBaseUrlOfCurrentConfig() {
        ModConfig config = currentConfig();
        String baseUrl = config == null ? "" : config.getApiBaseUrl();
        if (baseUrl == null) {
            return "";
        }
        String normalized = baseUrl.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 把衣柜收藏归属到当前身份（换号 / 换后端时丢弃旧收藏）。
     *
     * <p>调用点：{@link #initialize(ModConfig)}（启动时读盘后）与 {@link #onJoin()}（换号后）。
     * 为什么不能只在成功拉取后做：断网时永远拉不到，旧账号的收藏就会一直显示给新账号。
     */
    private void adoptWardrobeOwner() {
        try {
            WardrobeCollection.getInstance().adoptOwner(currentWardrobeOwnerKey());
        } catch (Throwable t) {
            VMFashionClient.LOGGER.debug("校验衣柜收藏归属失败（忽略）: {}", t.toString());
        }
    }

    /**
     * 衣柜收藏同步的内部状态摘要（纯文本，只给 {@code /vmfashion diag} 用）。
     *
     * <p>为什么单独给一个方法：{@code /vmfashion wardrobe} 是玩家看的（要 i18n），
     * diag 是开发者看的（纯文本，直接读字段最直观），两者不共用文案。
     */
    public String describeWardrobeSyncState() {
        long now = System.currentTimeMillis();
        long backoffRemaining = Math.max(0L, wardrobeBackoffUntilMs - now);
        return "enabled=" + enabled
                + ", token=" + (api != null && api.hasToken())
                + ", endpointUnsupported=" + wardrobeEndpointUnsupported
                + ", unsupportedStatus=" + wardrobeUnsupportedStatus
                + ", inFlight=" + wardrobeRefreshInFlight.get()
                + ", failureStreak=" + wardrobeFailureStreak.get()
                + ", backoffRemainingMs=" + backoffRemaining
                + ", lastAttemptAt=" + lastWardrobeRefreshAtMs
                + ", epoch=" + wardrobeEpoch;
    }

    /**
     * 后端"没有 {@code /wardrobe/me} 端点"的状态码（404 / 405 / 501）；{@code 0} = 还没遇到过。
     *
     * <p>给命令层把原因说清楚用（{@code /vmfashion wardrobe refresh} 不能只说"失败了"）。
     */
    public int wardrobeUnsupportedStatus() {
        return wardrobeUnsupportedStatus;
    }

    /**
     * 把本地穿戴上报到后端（全量覆盖）。失败只记日志。
     *
     * <p>token 过期（默认 24h）时旧实现直接 return：玩家在游戏里换装永远不会上报，
     * 也没有任何提示，看起来就是"换装不生效"。现在先补一次认证，成功后把<b>当前</b>
     * 快照补报一次（用当前快照而不是调用时的快照，避免认证期间又换装导致报旧值）。
     */
    public void uploadLocalLoadout() {
        HttpLoadoutProvider currentProvider = provider;
        FashionAuth currentAuth = auth;
        if (!enabled || currentProvider == null || currentAuth == null) {
            return;
        }
        FashionAuth.Session session = currentAuth.session();
        if (session == null || session.expired()) {
            if (expiryReauthDue(System.currentTimeMillis())) {
                VMFashionClient.LOGGER.info("时装会话已过期，先重新认证再上报穿戴");
                reauthenticateThenUpload(currentAuth);
            }
            return;
        }
        uploadSnapshot(currentProvider, currentAuth);
    }

    /**
     * "会话已过期 → 补一次认证"的闸门（带冷却）。
     *
     * <p>冷却的意义见 {@link #EXPIRY_REAUTH_COOLDOWN_MS}：没有它，后端给一个极短的
     * {@code expiresIn} 就会让"认证成功 → 回调发现已过期 → 再认证"形成风暴。
     *
     * @return {@code true} = 本次允许触发（调用方应发起认证并直接返回）
     */
    private boolean expiryReauthDue(long now) {
        if (now - lastExpiryReauthAtMs < EXPIRY_REAUTH_COOLDOWN_MS) {
            return false;
        }
        lastExpiryReauthAtMs = now;
        return true;
    }

    /**
     * 会话过期时：认证成功后再把当前穿戴上报一次。
     *
     * <p>{@link FashionAuth#authenticate(String)} 自带"并发调用复用同一个 future"，
     * 所以这里和 {@link #authenticateAsync(boolean)} 同时触发也只会真的认证一次。
     */
    private void reauthenticateThenUpload(FashionAuth currentAuth) {
        ModConfig config = currentConfig();
        String authServer = config == null ? "" : config.getAuthServer();
        CompletableFuture<FashionAuth.Session> future;
        try {
            future = currentAuth.authenticate(authServer);
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("上报穿戴前重新认证失败: {}", t.toString());
            return;
        }
        future.whenComplete((session, error) -> {
            if (error != null || session == null) {
                VMFashionClient.LOGGER.warn("上报穿戴前重新认证失败: {}",
                        error == null ? "后端没有返回会话" : error.toString());
                return;
            }
            HttpLoadoutProvider currentProvider = provider;
            if (currentProvider == null || !enabled) {
                return;
            }
            uploadSnapshot(currentProvider, currentAuth);
        });
    }

    /** 真正发一次全量上报；失败按错误码给玩家提示，401 走重新认证。 */
    private void uploadSnapshot(HttpLoadoutProvider currentProvider, FashionAuth currentAuth) {
        Map<String, String> loadout = LocalLoadout.getInstance().snapshot();
        currentProvider.upload(loadout).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                if (isUnauthorized(throwable)) {
                    VMFashionClient.LOGGER.info("时装 token 已失效，重新认证");
                    currentAuth.clearSession();
                    authenticateAsync(false);
                } else {
                    VMFashionClient.LOGGER.warn("上报穿戴失败: {}", throwable.toString());
                    notifyError(throwable);
                }
            }
        });
    }

    /**
     * 状态摘要（{@code /vmfashion status} 与衣柜底部状态行共用）。
     *
     * <p>返回 {@link Component} 而不是 String：这段文字会直接显示给玩家，
     * 必须跟着客户端语言走（文案在 {@code vmfashion.status.*}）。
     */
    public Component describeStatus() {
        if (!enabled) {
            return Component.translatable("vmfashion.status.local_only");
        }
        FashionAuth.Session session = auth == null ? null : auth.session();
        if (session == null) {
            return Component.translatable("vmfashion.status.not_logged_in", api.baseUrl());
        }
        return Component.translatable("vmfashion.status.logged_in",
                session.mcName(), session.authType(), api.baseUrl());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /** 判断异常链里是否是 401（token 失效），用于触发一次重新认证。 */
    private static boolean isUnauthorized(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof FashionApi.ApiException api && api.status() == 401) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
