package com.venus.vmfashion.sync;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.cosmetic.LocalLoadout;
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
