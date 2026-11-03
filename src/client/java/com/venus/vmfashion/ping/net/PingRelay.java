package com.venus.vmfashion.ping.net;

import com.google.gson.JsonObject;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.PingSettings;
import com.venus.vmfashion.ping.PingService;
import com.venus.vmfashion.ping.core.ClientContext;
import com.venus.vmfashion.ping.core.PingClock;
import com.venus.vmfashion.ping.core.PingData;
import com.venus.vmfashion.ping.core.PingKind;
import com.venus.vmfashion.ping.core.PingStore;
import com.venus.vmfashion.ping.core.RoomKey;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 标点中继：认证 → 长轮询 → 发送，一套完整的客户端状态机。
 *
 * <p>线程模型（重要）：
 * <ul>
 *   <li>{@link #start}/{@link #stop}/{@link #sendPing} 都在**客户端主线程**调用；</li>
 *   <li>HTTP 回调在 JDK 的公共线程池里跑，只写 {@link PingStore}（并发安全）与 volatile 状态；</li>
 *   <li>渲染线程只读 {@link #store()} 的快照与 volatile 状态，不会被网络阻塞。</li>
 * </ul>
 *
 * <p>状态机：
 * <pre>
 *   OFF ──进服──► AUTHENTICATING ──成功──► ONLINE ──连续失败──► DEGRADED
 *    ▲                 │                     │                    │
 *    └──退服───────────┴─────401────────────┴──────恢复────────────┘
 * </pre>
 * {@code DEGRADED} 时标点仍然本地可见（{@link PingStore} 里 {@code local=true}），
 * 只是别人看不见；界面状态栏会说明原因，不弹窗、不刷屏。
 *
 * <p><b>合订版适配（相对独立版 VMPing）</b>：
 * <ul>
 *   <li>构造器接收 {@link PingAuthBridge}，认证不再自己跑 challenge/verify，也不再维护自己的会话文件
 *       —— 直接复用时装会话（同一个 MC 账号、同一份 token）；</li>
 *   <li>配置从 {@link PingSettings} 读（原 ping ModConfig 的替代），后端地址由
 *       {@link PingAuthBridge#apiBaseUrl()} 统一给出（留空时跟随时装后端推导）；</li>
 *   <li>收到别人的标点通过 {@link PingService#onRemotePings} 播提示音，不再直接依赖客户端入口类；</li>
 *   <li>401 / token 失效时调 {@link PingAuthBridge#invalidateSession()} 再重新 ensureSession；
 *       其余状态机、长轮询、去重、退避、降级逻辑与独立版保持一致。</li>
 * </ul>
 */
public final class PingRelay {

    /** 状态机状态。 */
    public enum State {
        /** 未连接（未进服 / 配置关闭）。 */
        OFF,
        /** 正在认证。 */
        AUTHENTICATING,
        /** 在线：能收能发。 */
        ONLINE,
        /** 降级：后端不可用，只做本地标点。 */
        DEGRADED
    }

    /** 连续失败后重试的退避序列（毫秒），封顶 30s。 */
    private static final long[] BACKOFF_MS = {1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L};
    /**
     * 两次"作废共享时装会话"之间的最小间隔。
     *
     * <p>401 可能是**本模组后端配置问题**（自建旧后端不认时装 token）而不是 token 真过期；
     * 如果每次 401 都清一次时装会话，会连带把时装同步的 token 删掉，并触发
     * challenge/join/verify 全量重认证 —— 持续 401 时就是每秒一轮的认证风暴。
     * 60 秒内最多清一次，其余 401 按普通失败退避。
     */
    private static final long AUTH_RESET_COOLDOWN_MS = 60_000L;
    /** 长轮询两次之间的最小间隔（防止后端立即返回 200 时变成全速请求风暴）。 */
    private static final long MIN_POLL_INTERVAL_MS = 200L;

    private final PingStore store = new PingStore();
    private final PingClock clock = store.clock();
    /** 认证桥：复用时装层的会话（token / 后端地址 / 同步开关都由它给）。 */
    private final PingAuthBridge bridge;

    private volatile PingSettings settings = new PingSettings();
    private volatile PingApi api;

    private volatile State state = State.OFF;
    private volatile String roomId = "";
    private volatile String dimension = "";
    private volatile String lastErrorKey = "";
    private volatile String lastErrorDetail = "";

    /** 是否还有一个长轮询在飞（同一时刻只允许一个）。 */
    private final AtomicBoolean polling = new AtomicBoolean(false);
    /** 会话代号：每次 start/stop 自增，异步回调发现代号变了就丢弃结果（防止退服后旧响应写进新房间）。 */
    private final AtomicLong generation = new AtomicLong(0L);
    /** 连续失败次数（决定退避）。 */
    private final AtomicLong failures = new AtomicLong(0L);
    /** 下次允许重试的时间戳。 */
    private volatile long nextRetryAtMs = 0L;
    /** 是否已经启动过（防止重复 start）。 */
    private final AtomicBoolean started = new AtomicBoolean(false);
    /** 上一次作废共享时装会话的时间戳（见 {@link #AUTH_RESET_COOLDOWN_MS}）。 */
    private volatile long lastAuthResetAtMs;

    /**
     * @param bridge 认证桥（由 {@link PingService} 持有并注入；测试可传替身）
     */
    public PingRelay(PingAuthBridge bridge) {
        this.bridge = bridge;
    }

    public PingStore store() {
        return store;
    }

    public State state() {
        return state;
    }

    public String roomId() {
        return roomId;
    }

    public String dimension() {
        return dimension;
    }

    /** 最近一次错误的语言 key（空串 = 没有错误）。 */
    public String lastErrorKey() {
        return lastErrorKey;
    }

    /** 最近一次错误的原始信息（诊断用，只进日志/调试命令）。 */
    public String lastErrorDetail() {
        return lastErrorDetail;
    }

    public boolean online() {
        return state == State.ONLINE;
    }

    /** 是否已经为当前世界启动过（tick 里做"进服事件没触发"的兜底判断用）。 */
    public boolean isStarted() {
        return started.get();
    }

    /** 配置热更新：重建 HTTP 客户端并重启中继。 */
    public synchronized void reload(PingSettings newSettings) {
        this.settings = newSettings;
        boolean wasActive = started.get();
        if (wasActive) {
            stop();
            start(newSettings);
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 进服：计算房间、恢复/发起认证、启动长轮询。
     *
     * <p>由 {@link PingService} 在 {@code ClientPlayConnectionEvents.JOIN} 与每 tick 兜底里调用。
     */
    public synchronized void start(PingSettings activeSettings) {
        this.settings = activeSettings;
        if (activeSettings == null || !activeSettings.isEnabled()) {
            state = State.OFF;
            return;
        }
        String newRoomId = ClientContext.roomId();
        String newDimension = ClientContext.dimensionId();
        if (newRoomId.isBlank()) {
            // 单人世界/尚未加载完：不启用中继（本地标点仍然可用）
            state = State.OFF;
            roomId = "";
            return;
        }
        long gen = generation.incrementAndGet();
        // 旧会话可能还留着一个"长轮询在飞"的标志：不清掉的话，新会话的第一次 pollLoop
        // 会 CAS 失败直接返回，而旧请求回来时代次已变也不会再发起下一次 —— 表现就是
        // 状态显示 ONLINE 但永远收不到别人的标点。
        polling.set(false);
        lastAuthResetAtMs = 0L;
        started.set(true);
        roomId = newRoomId;
        dimension = newDimension;
        store.clear();
        clock.reset();
        failures.set(0L);
        nextRetryAtMs = 0L;
        lastErrorKey = "";
        lastErrorDetail = "";

        // 单机 / 局域网不同步（协议 v1.1 §3）：所有单机玩家共用一个房间会造成隐私问题，
        // 而且"同服玩家"的语义本身就模糊。只做本地标点（tick 仍然要跑，负责过期清理）。
        String normalized = RoomKey.normalize(ClientContext.rawServerAddress());
        if (RoomKey.SINGLEPLAYER.equals(normalized) || RoomKey.LAN.equals(normalized)) {
            roomId = "";
            state = State.DEGRADED;
            lastErrorKey = "vmfashion.ping.status.singleplayer";
            VMFashionClient.LOGGER.debug("单机/局域网世界不同步标点（本地模式）");
            return;
        }

        // 纯本地模式：没有可用的后端地址（未配置时装后端 / 玩家自己关掉了标点后端）
        if (!activeSettings.relayEnabled(bridge.fashionApiBaseUrl())) {
            state = State.DEGRADED;
            lastErrorKey = "vmfashion.ping.status.local_only";
            return;
        }
        // 同步层没开 → 拿不到时装会话，标点只能本地可见
        if (!bridge.syncEnabled()) {
            state = State.DEGRADED;
            lastErrorKey = "vmfashion.ping.status.local_only";
            return;
        }
        // 明文 http 的后端不发送凭据（与下载/认证的口径一致）
        if (!bridge.secure()) {
            state = State.DEGRADED;
            lastErrorKey = "vmfashion.ping.error.insecure_backend";
            return;
        }
        if (!bridge.available()) {
            // 兜底：地址 / 同步状态在两次读取之间被改掉（热更新竞态）
            state = State.DEGRADED;
            lastErrorKey = "vmfashion.ping.status.local_only";
            return;
        }

        // 重建 HTTP 客户端（后端地址可能变了）
        closeClients();
        PingApi newApi = new PingApi(bridge.apiBaseUrl());
        this.api = newApi;

        state = State.AUTHENTICATING;
        VMFashionClient.LOGGER.info("标点中继启动：room={} dimension={} backend={}",
                roomId, dimension, bridge.apiBaseUrl());

        // 认证：直接复用时装会话（已有未过期会话时 ensureSession 会立刻完成，不用等一轮握手）
        bridge.ensureSession().whenComplete((session, throwable) -> {
            if (generation.get() != gen) {
                return;
            }
            if (throwable != null) {
                onFailure(gen, authError(throwable), true);
                return;
            }
            if (session == null || session.token() == null || session.token().isBlank()) {
                onFailure(gen, new PingApi.ApiException(-1, "no_session", "未获取到时装会话"), true);
                return;
            }
            newApi.setToken(session.token());
            state = State.ONLINE;
            failures.set(0L);
            VMFashionClient.LOGGER.info("标点中继已上线：{}", session.mcName());
            pollLoop(gen);
        });
    }

    /** 退服：停轮询、清标点、关客户端。 */
    public synchronized void stop() {
        generation.incrementAndGet();
        started.set(false);
        polling.set(false);
        state = State.OFF;
        roomId = "";
        dimension = "";
        store.clear();
        clock.reset();
        closeClients();
    }

    /** 切维度：更新当前维度并清掉其它维度的旧标点（保留自己的短时标点）。 */
    public void onDimensionChanged(String newDimension) {
        if (newDimension == null || newDimension.isBlank() || newDimension.equals(dimension)) {
            return;
        }
        dimension = newDimension;
        store.clearOthers(ClientContext.selfUuid());
    }

    private void closeClients() {
        PingApi currentApi = api;
        api = null;
        if (currentApi != null) {
            currentApi.close();
        }
    }

    // ==================== 发送 ====================

    /**
     * 发一个标点。
     *
     * <p>先本地插入（立即渲染，0 延迟），再异步 POST；POST 成功用服务端返回的真实 id/seq
     * 替换本地占位；失败则把标点降级为"仅本地"（{@code local=true}）并记录错误 key。
     *
     * @return 本地占位标点（调用方可用于播放音效/动画）
     */
    public PingData sendPing(double x, double y, double z, PingKind kind,
                             UUID targetUuid, String targetName) {
        UUID self = ClientContext.selfUuid();
        String selfName = ClientContext.selfName();
        String currentDimension = dimension.isBlank() ? ClientContext.dimensionId() : dimension;
        PingSettings currentSettings = settings;
        long ttl = Math.max(1_000L, currentSettings.getPingTtlSeconds() * 1000L);

        PingData localPing = PingData.local(self, selfName, currentDimension, x, y, z, kind,
                targetUuid, targetName, clock.now(), ttl);

        PingApi currentApi = api;
        boolean canSend = state == State.ONLINE && currentApi != null && currentApi.hasToken()
                && roomId != null && !roomId.isBlank();

        if (!canSend) {
            // 降级：只本地可见
            store.put(localPing);
            if (currentSettings.isDegradeToLocal()) {
                lastErrorKey = state == State.OFF
                        ? "vmfashion.ping.status.disconnected"
                        : "vmfashion.ping.status.local_only";
            }
            return localPing;
        }

        // 本地先显示（用 local=true 的占位，POST 回来再替换）
        store.put(localPing);

        JsonObject body = PingCodec.encodeSend(currentDimension, x, y, z, kind, targetUuid, targetName,
                System.currentTimeMillis());
        long gen = generation.get();
        currentApi.sendPing(roomId, body).whenComplete((response, throwable) -> {
            if (generation.get() != gen) {
                return;
            }
            if (throwable != null) {
                PingApi.ApiException apiError = PingApi.unwrap(throwable);
                VMFashionClient.LOGGER.debug("标点发送失败: {} {}", apiError.code(), apiError.getMessage());
                store.put(localPing); // 保留本地占位（local=true）
                if (apiError.needsReauth()) {
                    // token 失效：按冷却策略作废共享会话并重新认证（见 AUTH_RESET_COOLDOWN_MS）
                    handleUnauthorized(gen, failures.incrementAndGet());
                } else {
                    lastErrorKey = errorKey(apiError);
                    lastErrorDetail = apiError.code() + ": " + apiError.getMessage();
                }
                return;
            }
            // 成功：用服务端返回的 id/seq/serverTime/expiresAt 合并出"权威标点"替换本地占位。
            // 注意 POST 响应只有 id/seq/时间（没有坐标/玩家），所以不能直接 decodePing 整条，
            // 必须拿本地那条的数据 + 服务端分配的元数据拼一条。
            String serverId = PingCodec.optString(response, "id", "");
            if (serverId.isBlank()) {
                serverId = localPing.id();
            }
            long seq = PingCodec.optLong(response, "seq", localPing.seq());
            long serverTime = PingCodec.optLong(response, "serverTime", localPing.serverTime());
            long expiresAt = PingCodec.optLong(response, "expiresAt", localPing.expiresAt());
            PingData confirmed = new PingData(
                    serverId, seq, localPing.playerUuid(), localPing.playerName(),
                    localPing.dimension(), localPing.x(), localPing.y(), localPing.z(),
                    localPing.kind(), localPing.targetUuid(), localPing.targetName(),
                    serverTime, expiresAt, false);
            store.remove(localPing.id());
            store.put(confirmed);
            lastErrorKey = "";
            failures.set(0L);
        });
        return localPing;
    }

    // ==================== 长轮询 ====================

    private void pollLoop(long gen) {
        if (generation.get() != gen || !started.get()) {
            return;
        }
        PingApi currentApi = api;
        if (currentApi == null || !currentApi.hasToken()) {
            return;
        }
        if (!polling.compareAndSet(false, true)) {
            return; // 已有请求在飞
        }
        long since = store.lastSeq();
        int timeout = settings.getPollTimeoutSeconds();
        String filterDimension = ""; // 不按维度过滤：同服不同维度的标点也收，渲染时再筛
        long pollStartedAt = System.currentTimeMillis();
        currentApi.pollPings(roomId, since, timeout, filterDimension)
                .whenComplete((response, throwable) -> {
                    polling.set(false);
                    if (generation.get() != gen || !started.get()) {
                        return;
                    }
                    if (throwable != null) {
                        PingApi.ApiException apiError = PingApi.unwrap(throwable);
                        onFailure(gen, apiError, false);
                        return;
                    }
                    PingCodec.PollResult result = PingCodec.decodePollResponse(response);
                    if (result.serverTime() > 0L) {
                        store.syncClock(result.serverTime());
                    }
                    // 房间被回收后重建：seq 归零。如果还拿着旧 since，会永远收不到新标点。
                    if (result.seq() > 0L && result.seq() < store.lastSeq()) {
                        VMFashionClient.LOGGER.debug("检测到房间序号回退（{} < {}），重置游标",
                                result.seq(), store.lastSeq());
                        // 房间被回收后 seq 归零：游标必须一起归零，否则之后每次都带旧的高位
                        // since，服务端永远不返回新标点（只能退服恢复）。
                        store.resetCursor();
                        store.clearOthers(ClientContext.selfUuid());
                    }
                    List<PingData> received = result.pings();
                    if (settings.isDebugLogging()) {
                        // 调试日志开关（设置界面里的"调试日志"）：只打摘要，不刷屏、不含凭据
                        VMFashionClient.LOGGER.info("标点长轮询：since={} seq={} 收到 {} 条",
                                since, result.seq(), received.size());
                    }
                    if (!received.isEmpty()) {
                        store.putAll(received);
                        // 别人刚标的点：给个提示音（由 UI 层消费）
                        PingService.getInstance().onRemotePings(received);
                    }
                    failures.set(0L);
                    nextRetryAtMs = 0L;
                    lastErrorKey = "";
                    // 下一次长轮询：正常情况下服务端会阻塞到有数据/超时；但如果后端/反代
                    // 立刻返回 200（忽略 timeout），这里没有地板就会变成全速请求风暴。
                    long elapsed = System.currentTimeMillis() - pollStartedAt;
                    long delay = Math.max(0L, MIN_POLL_INTERVAL_MS - elapsed);
                    if (delay > 0L) {
                        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                                .execute(() -> pollLoop(gen));
                    } else {
                        pollLoop(gen);
                    }
                });
    }

    /** 认证（重新）成功后继续轮询。 */
    private void authenticateAndPoll(long gen) {
        PingApi currentApi = api;
        if (currentApi == null) {
            return;
        }
        bridge.ensureSession().whenComplete((session, throwable) -> {
            if (generation.get() != gen) {
                return;
            }
            if (throwable != null) {
                onFailure(gen, authError(throwable), true);
                return;
            }
            if (session == null || session.token() == null || session.token().isBlank()) {
                onFailure(gen, new PingApi.ApiException(-1, "no_session", "未获取到时装会话"), true);
                return;
            }
            currentApi.setToken(session.token());
            state = State.ONLINE;
            // 不在这里清零 failures：如果认证成功但随后的请求仍然 401/失败，清零会让退避
            // 永远从 1 秒重新开始（持续 401 时变成每秒一轮）。真正恢复由 pollLoop 成功时清零。
            nextRetryAtMs = 0L;
            pollLoop(gen);
        });
    }

    /**
     * 处理 401：按冷却策略作废共享时装会话并重新认证。
     *
     * <p>冷却窗口内的 401 不再清会话（避免认证风暴 + 连带清掉时装同步的 token），
     * 而是按普通失败退避；退避序列会继续上升，不会每秒重试。
     */
    private void handleUnauthorized(long gen, long count) {
        long now = System.currentTimeMillis();
        if (now - lastAuthResetAtMs < AUTH_RESET_COOLDOWN_MS) {
            state = State.DEGRADED;
            scheduleRetry(gen, count, () -> {
                if (hasToken()) {
                    pollLoop(gen);
                } else {
                    authenticateAndPoll(gen);
                }
            });
            return;
        }
        lastAuthResetAtMs = now;
        bridge.invalidateSession();
        state = State.AUTHENTICATING;
        scheduleRetry(gen, count, () -> authenticateAndPoll(gen));
    }

    /** 失败处理：记录错误、退避、必要时重新认证。 */
    private void onFailure(long gen, PingApi.ApiException error, boolean fromAuth) {
        long count = failures.incrementAndGet();
        lastErrorKey = errorKey(error);
        lastErrorDetail = error.code() + ": " + error.getMessage();
        VMFashionClient.LOGGER.debug("标点中继失败（第 {} 次，auth={}）: {} {}", count, fromAuth,
                error.code(), error.getMessage());

        if (error.needsReauth()) {
            handleUnauthorized(gen, count);
            return;
        }
        state = State.DEGRADED;
        // 429（限流 / 房间满）和 5xx 一样是"过一会儿再试"：不排重试的话一次限流就会
        // 永久降级到本地，直到退服或手动 reload。
        if (error.backendUnavailable() || error.status() == 429) {
            // 认证阶段失败（网络抖动 / 时装层暂时不可用）要重试的是**认证**，不是轮询：
            // 没有 token 时 pollLoop 会直接返回，旧写法会让中继永远卡在降级态。
            scheduleRetry(gen, count, () -> {
                if (hasToken()) {
                    pollLoop(gen);
                } else {
                    authenticateAndPoll(gen);
                }
            });
        }
    }

    /** 当前 HTTP 客户端是否已拿到 token（认证失败后的重试路径选择用）。 */
    private boolean hasToken() {
        PingApi currentApi = api;
        return currentApi != null && currentApi.hasToken();
    }

    private void scheduleRetry(long gen, long failuresSoFar, Runnable action) {
        long delay = BACKOFF_MS[(int) Math.min(BACKOFF_MS.length - 1, Math.max(0, failuresSoFar - 1))];
        nextRetryAtMs = System.currentTimeMillis() + delay;
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
            if (generation.get() != gen || !started.get()) {
                return;
            }
            action.run();
        });
    }

    // ==================== 每 tick 维护 ====================

    /**
     * 每客户端 tick 调用（20 次/秒）：
     * <ul>
     *   <li>清理过期标点；</li>
     *   <li>检测维度变化；</li>
     *   <li>如果处于降级/认证中且退避时间到了，补一次重试（兜底：delayedExecutor 万一被吞）。</li>
     * </ul>
     */
    public void tick() {
        if (!started.get()) {
            return;
        }
        String currentDimension = ClientContext.dimensionId();
        if (!currentDimension.isBlank() && !currentDimension.equals(dimension)) {
            onDimensionChanged(currentDimension);
        }
        store.expire(clock.now());

        if (state != State.ONLINE && nextRetryAtMs > 0L && System.currentTimeMillis() >= nextRetryAtMs) {
            long gen = generation.get();
            nextRetryAtMs = 0L;
            if (state == State.AUTHENTICATING) {
                authenticateAndPoll(gen);
            } else if (state == State.DEGRADED && settings.relayEnabled(bridge.fashionApiBaseUrl())) {
                // 同上：没有 token 时补认证，有 token 才继续轮询
                if (hasToken()) {
                    pollLoop(gen);
                } else {
                    authenticateAndPoll(gen);
                }
            }
        }
    }

    /**
     * 把认证桥的失败翻译成标点层的 {@link PingApi.ApiException}。
     *
     * <p>合订版认证走时装层（{@code FashionApi.ApiException}），这里把它的 status/code 原样带过来，
     * 这样 {@link #errorKey} 才能映射出准确的语言 key（no_session / no_token / join_failed …），
     * 而不是把一切都归成"未知错误"。
     */
    private static PingApi.ApiException authError(Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof PingApi.ApiException api) {
            return api;
        }
        if (cause instanceof com.venus.vmfashion.net.FashionApi.ApiException fashion) {
            return new PingApi.ApiException(fashion.status(), fashion.code(), fashion.getMessage());
        }
        return PingApi.unwrap(throwable);
    }

    // ==================== 错误文案 ====================

    /**
     * 协议错误码 → 语言文件 key。
     *
     * <p>约定：这里只返回 key，真正的文案在 {@code assets/vmfashion/lang/*.json}
     * （{@code vmfashion.ping.error.*} / {@code vmfashion.ping.status.*}）；
     * 新增错误码时必须同步补语言文件，否则玩家会看到 key 本身。
     */
    private static String errorKey(PingApi.ApiException error) {
        String code = error == null ? "" : error.code();
        return switch (code) {
            case "no_session", "no_profile" -> "vmfashion.ping.error.no_session";
            case "no_token", "unauthorized", "bad_token" -> "vmfashion.ping.error.no_token";
            case "insecure_backend" -> "vmfashion.ping.error.insecure_backend";
            case "rate_limited" -> "vmfashion.ping.error.rate_limited";
            case "join_failed", "bad_challenge", "bad_verify", "bad_session_url" ->
                    "vmfashion.ping.error.join_failed";
            case "circuit_open", "network_error", "sync_disabled", "no_base_url" ->
                    "vmfashion.ping.status.local_only";
            default -> "vmfashion.ping.error.unknown";
        };
    }
}
