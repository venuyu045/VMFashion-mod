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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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

    private volatile FashionApi api;
    private volatile FashionAuth auth;
    private volatile HttpLoadoutProvider provider;
    private volatile boolean enabled;

    /** 绑定轮询的代数：发起 / 取消时 +1，旧轮询发现代数变了就自行停止。 */
    private volatile long bindEpoch;

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
            LoadoutService.getInstance().setFallbackToLocal(true);
            return;
        }

        FashionApi newApi = new FashionApi(config.getApiBaseUrl());
        FashionAuth newAuth = new FashionAuth(newApi);
        HttpLoadoutProvider newProvider = new HttpLoadoutProvider(newApi);

        newAuth.restoreSession();

        api = newApi;
        auth = newAuth;
        provider = newProvider;
        enabled = true;

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
     * 进入服务器：如果还没认证或 token 过期，后台完成一次认证。
     */
    public void onJoin() {
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
        cancelBinding();
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
