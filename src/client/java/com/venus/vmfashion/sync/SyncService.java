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
            api = null;
            auth = null;
            provider = null;
            LoadoutService.getInstance().setProvider(null);
            // 只有"根本没配后端地址"的纯本地模式才允许回退到本地穿戴（单机预览渲染链路）。
            // 配了后端但把同步关掉时绝不能回退：回退会把"自己的"穿戴渲染到所有玩家身上，
            // 带 hideParts 的时装甚至会让别人模型部位消失。
            LoadoutService.getInstance().setFallbackToLocal(config == null || config.isLocalOnly());
            return;
        }

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
     * 后端错误码 → 玩家能看懂的中文提示。
     *
     * <p>旧实现把这些错误只打进日志：玩家换装没生效，却完全不知道是"时装被禁用"还是
     * "槽位超限"还是"没绑定网站"。抽成包可见的静态方法，方便单测。
     *
     * @return 提示文案；{@code null} 表示不值得打扰玩家（例如纯网络抖动，聊天栏刷这个没用）
     */
    static String describeApiError(Throwable throwable) {
        FashionApi.ApiException apiException = findApiException(throwable);
        if (apiException == null) {
            return null;
        }
        String code = apiException.code() == null ? "" : apiException.code().toLowerCase(Locale.ROOT);
        String detail = apiException.getMessage() == null ? "" : apiException.getMessage();
        return switch (code) {
            case "unknown_cosmetic" -> "§c时装不存在或已下架：" + detail;
            case "cosmetic_disabled" -> "§c这件时装已被管理员禁用：" + detail;
            case "invalid_cosmetic" -> "§c后端不认识这件时装：" + detail;
            case "too_many_items" -> "§c穿戴槽位超过后端上限，请先卸下几件再试";
            case "not_joined" -> "§e这个账号还没绑定网站，执行 §b/vmfashion bind §e完成绑定";
            case "no_token" -> "§e尚未登录时装后端，执行 §b/vmfashion login";
            case "rate_limited", "too_many_requests", "http_429" -> "§e操作太频繁，请稍后再试";
            case "invalid_request", "bad_request", "http_400" -> "§c请求被后端拒绝：" + detail;
            case "forbidden", "http_403" -> "§c没有权限执行这个操作：" + detail;
            default -> null;
        };
    }

    /** 把后端错误码转成聊天栏提示（同一条消息去重，避免刷屏）。 */
    private void notifyError(Throwable throwable) {
        String message = describeApiError(throwable);
        if (message == null || message.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (message.equals(lastChatMessage) && now - lastChatAtMs < CHAT_DEDUPE_MS) {
            return;
        }
        lastChatMessage = message;
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
                ChatUtil.showLocalMessage("§e未配置时装后端地址，当前是纯本地模式");
            }
            return;
        }
        FashionAuth currentAuth = auth;
        if (currentAuth == null) {
            return;
        }
        ModConfig config = VMFashionClient.getInstance().getConfig();
        if (notifyChat) {
            ChatUtil.showLocalMessage("正在登录时装系统…");
        }
        currentAuth.authenticate(config.getAuthServer()).whenComplete((session, throwable) -> {
            if (throwable != null) {
                VMFashionClient.LOGGER.warn("时装认证失败: {}", throwable.toString());
                if (notifyChat) {
                    ChatUtil.showLocalMessage("§c登录失败：" + rootMessage(throwable));
                }
                return;
            }
            if (notifyChat) {
                ChatUtil.showLocalMessage("§a登录成功：§b" + session.mcName()
                        + "§r（" + session.authType() + "）");
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
                ChatUtil.showLocalMessage("§e未配置时装后端地址，当前是纯本地模式");
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
            ChatUtil.showLocalMessage("§7尚未登录时装后端，正在先完成认证…");
        }
        ModConfig config = VMFashionClient.getInstance().getConfig();
        currentAuth.authenticate(config.getAuthServer()).whenComplete((newSession, throwable) -> {
            if (throwable != null) {
                VMFashionClient.LOGGER.warn("绑定前认证失败: {}", throwable.toString());
                if (notifyChat) {
                    ChatUtil.showLocalMessage("§c认证失败，无法绑定：" + rootMessage(throwable));
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
                    ChatUtil.showLocalMessage("§c申请绑定码失败：" + rootMessage(throwable));
                }
                return;
            }
            String userCode = stringField(json, "userCode");
            String deviceCode = stringField(json, "deviceCode");
            int expiresIn = intField(json, "expiresIn", 600);
            int interval = Math.max(1, intField(json, "interval", 3));
            if (userCode.isBlank() || deviceCode.isBlank()) {
                if (notifyChat) {
                    ChatUtil.showLocalMessage("§c后端没有返回绑定码，请确认站点已升级到含时装模块的版本");
                }
                return;
            }
            ChatUtil.showLocalMessage("§6绑定到网站：§e§l" + userCode);
            ChatUtil.showLocalMessage("§7在网站「时装绑定」页输入这个 6 位码（"
                    + Math.max(1, expiresIn / 60) + " 分钟内有效）");
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
                    ChatUtil.showLocalMessage("§e绑定码已过期，请重新执行 /vmfashion bind");
                }
                return;
            }
            String status = stringField(json, "status");
            if ("approved".equals(status)) {
                ChatUtil.showLocalMessage("§a已绑定到网站账号，现在可以在网页衣柜里换装了");
                // 绑定完成后云端衣柜成为权威来源，避免用本地状态覆盖网站已保存的穿戴
                refreshFromRemote(true);
                return;
            }
            if ("expired".equals(status) || System.currentTimeMillis() >= deadline) {
                if (notifyChat) {
                    ChatUtil.showLocalMessage("§e绑定码已过期，请重新执行 /vmfashion bind");
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
     */
    public void uploadLocalLoadout() {
        HttpLoadoutProvider currentProvider = provider;
        FashionAuth currentAuth = auth;
        if (!enabled || currentProvider == null || currentAuth == null) {
            return;
        }
        FashionAuth.Session session = currentAuth.session();
        if (session == null || session.expired()) {
            return;
        }
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

    /** 给命令用的状态摘要。 */
    public String describeStatus() {
        if (!enabled) {
            return "纯本地模式（未配置后端）";
        }
        FashionAuth.Session session = auth == null ? null : auth.session();
        if (session == null) {
            return "已配置后端 " + api.baseUrl() + "，尚未登录";
        }
        return "已登录 " + session.mcName() + "（" + session.authType() + "）→ " + api.baseUrl();
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
