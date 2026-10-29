package com.venus.vmfashion.cosmetic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地穿戴的磁盘持久化（{@code config/vmfashion/loadout.json}）。
 *
 * <p>为什么需要：{@link LocalLoadout} 只有内存状态，纯本地模式（没配 {@code apiBaseUrl}）
 * 下每次重启游戏穿戴全丢；配了后端时靠云端拉回，但断网 / 后端挂了同样丢。
 *
 * <p>实现要点：
 * <ul>
 *   <li><b>原子写</b>：先写 {@code loadout-*.json.tmp} 再 {@code ATOMIC_MOVE}，避免断电写坏文件；</li>
 *   <li><b>debounce</b>：{@link #requestSave()} 只打标记 + 调度一次延迟落盘，
 *       连续换装时不会每件都写一次磁盘（2 秒窗口内的多次请求合并成一次写）；</li>
 *   <li>文件缺失 / JSON 损坏 / 字段非法都必须静默降级为空穿戴，绝不能让客户端起不来；</li>
 *   <li>所有写操作串行化（{@code SAVE_LOCK}），因为命令线程、同步回调线程、退出钩子都会触发保存。</li>
 * </ul>
 *
 * <p>文件格式（{@code formatVersion} 留给将来做迁移，未知字段一律忽略）：
 * <pre>
 * {
 *   "formatVersion": 1,
 *   "loadout": { "hat": "example:crown", "back": "example:backboard" }
 * }
 * </pre>
 */
public final class LoadoutStore {

    /** 数据格式版本，将来结构变化时用来做兼容。 */
    public static final int FORMAT_VERSION = 1;

    /** debounce 窗口：2 秒内的多次变更合并成一次落盘。 */
    private static final long DEBOUNCE_MS = 2_000L;

    private static final String FILE_NAME = "loadout.json";
    private static final String LOADOUT_KEY = "loadout";
    private static final String FORMAT_VERSION_KEY = "formatVersion";
    private static final String TEMP_PREFIX = "loadout-";
    private static final String TEMP_SUFFIX = ".json.tmp";
    private static final String SAVER_THREAD_NAME = "vmfashion-loadout-saver";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("vmfashion");

    /** 落盘串行锁：快照 + 写文件 + 重命名必须是原子的，否则两个线程会互相覆盖。 */
    private static final Object SAVE_LOCK = new Object();
    /** 是否已经排了一次延迟落盘（避免每次换装都往调度器里塞任务）。 */
    private static final AtomicBoolean PENDING = new AtomicBoolean();

    /** 有未落盘的变更。 */
    private static volatile boolean dirty;

    /** 延迟落盘线程；懒创建，纯本地模式下没人换装就不会多一个线程。 */
    private static volatile ScheduledExecutorService saver;

    private LoadoutStore() {
    }

    /** 数据文件路径，供测试与诊断使用。 */
    public static Path file() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("vmfashion").resolve(FILE_NAME);
        } catch (Throwable t) {
            // 非 Fabric 环境（单测 / 诊断工具）下拿不到 config 目录，交给调用方降级
            return null;
        }
    }

    // ==================== 读 ====================

    /** 启动时把磁盘上的穿戴读进内存（文件缺失或损坏时静默跳过，保持内存现状）。 */
    public static void loadInto(LocalLoadout loadout) {
        loadInto(loadout, file());
    }

    /**
     * 从指定文件读入穿戴状态。
     *
     * @return 实际恢复的条目数；文件不存在或损坏时返回 {@code -1} 且不改动内存状态
     */
    public static int loadInto(LocalLoadout loadout, Path file) {
        if (loadout == null || file == null) {
            return -1;
        }
        Map<String, String> loaded = read(file);
        if (loaded == null) {
            return -1;
        }
        loadout.replaceAll(loaded);
        if (!loaded.isEmpty()) {
            LOGGER.info("已从 {} 恢复 {} 项本地穿戴", file, loaded.size());
        }
        return loaded.size();
    }

    /**
     * 读并解析穿戴文件。
     *
     * @return 分类 → 时装 ID；文件缺失或损坏时返回 {@code null}
     */
    public static Map<String, String> read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                LOGGER.warn("本地穿戴文件不是 JSON 对象，已忽略: {}", file);
                return null;
            }
            return parse(parsed.getAsJsonObject());
        } catch (Exception e) {
            // 防御式解析：坏文件只记日志，绝不让客户端起不来
            LOGGER.warn("本地穿戴文件损坏，已忽略: {} ({})", file, e.toString());
            return null;
        }
    }

    /**
     * 解析 {@code loadout.json} 的根对象。
     *
     * <p>同时兼容两种写法：标准格式 {@code {"loadout":{...}}}，
     * 以及手写的裸映射 {@code {"hat":"example:crown"}}（没有 loadout 字段时按裸映射读）。
     */
    private static Map<String, String> parse(JsonObject root) {
        JsonObject source = root;
        if (root.has(LOADOUT_KEY) && root.get(LOADOUT_KEY).isJsonObject()) {
            source = root.getAsJsonObject(LOADOUT_KEY);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String slot = entry.getKey();
            if (slot == null || slot.isBlank() || FORMAT_VERSION_KEY.equals(slot)) {
                continue;
            }
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                continue;
            }
            String id = value.getAsString();
            if (id == null || id.isBlank()) {
                continue;
            }
            result.put(slot.trim().toLowerCase(Locale.ROOT), id.trim());
        }
        return result;
    }

    // ==================== 写 ====================

    /** 标记有变更；实际落盘由 {@link #DEBOUNCE_MS} 窗口合并后执行。 */
    public static void requestSave() {
        dirty = true;
        if (PENDING.compareAndSet(false, true)) {
            try {
                executor().schedule(LoadoutStore::flushPending, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // 调度器不可用（例如正在关服）：退化成同步落盘，不能丢数据
                PENDING.set(false);
                saveNow();
            }
        }
    }

    /** 立即落盘（退出游戏、{@code /vmfashion reload} 前）。 */
    public static void saveNow() {
        saveNow(file());
    }

    /**
     * 把当前内存里的穿戴立即写到指定文件。
     *
     * @return 是否成功（失败只记日志，不抛异常）
     */
    public static boolean saveNow(Path file) {
        if (file == null) {
            return false;
        }
        // 顺序很关键：① 先清脏标记（之后的变更会重新置位，不会丢）② 在 SAVE_LOCK 之外取快照
        // —— LocalLoadout 的监视器与 SAVE_LOCK 不能嵌套反序，否则和后台落盘线程构成 ABBA 死锁。
        dirty = false;
        return write(file, LocalLoadout.getInstance().snapshot());
    }

    /**
     * 把给定穿戴立即写到指定文件（原子写：临时文件 + ATOMIC_MOVE）。
     *
     * <p>显式传入数据而不是只读单例，是为了让单元测试能脱离全局状态验证落盘逻辑。
     *
     * @return 是否成功（失败只记日志，不抛异常）
     */
    public static boolean saveNow(Path file, Map<String, String> loadout) {
        return write(file, loadout);
    }

    private static boolean write(Path file, Map<String, String> loadout) {
        if (file == null) {
            return false;
        }
        synchronized (SAVE_LOCK) {
            Map<String, String> snapshot = loadout == null ? Map.of() : new LinkedHashMap<>(loadout);
            Path temp = null;
            try {
                Path parent = file.getParent();
                if (parent == null) {
                    // 相对路径（只有文件名）时，临时文件要落在同一个目录里才能原子重命名
                    parent = file.toAbsolutePath().getParent();
                }
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                temp = Files.createTempFile(parent, TEMP_PREFIX, TEMP_SUFFIX);
                writeAndFlush(temp, GSON.toJson(toJson(snapshot)));
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    // 某些文件系统（网络盘 / 部分容器挂载）不支持原子重命名，退化成普通替换
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                temp = null;
                return true;
            } catch (Exception e) {
                dirty = true;
                LOGGER.warn("保存本地穿戴失败: {} ({})", file, e.toString());
                return false;
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ignored) {
                        // 临时文件删不掉不影响主流程，下次写会覆盖
                    }
                }
            }
        }
    }

    /**
     * 写临时文件并 fsync。
     *
     * <p>只写不刷盘的话，"重命名成功但数据还在页缓存里"时断电会留下一个空文件 ——
     * 原子重命名只保证"要么旧文件、要么新文件"，保证不了新文件内容是完整的。
     */
    private static void writeAndFlush(Path temp, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static JsonObject toJson(Map<String, String> loadout) {
        JsonObject root = new JsonObject();
        root.addProperty(FORMAT_VERSION_KEY, FORMAT_VERSION);
        JsonObject entries = new JsonObject();
        loadout.forEach((slot, id) -> {
            if (slot == null || slot.isBlank() || id == null || id.isBlank()) {
                return;
            }
            entries.addProperty(slot.trim().toLowerCase(Locale.ROOT), id.trim());
        });
        root.add(LOADOUT_KEY, entries);
        return root;
    }

    private static void flushPending() {
        PENDING.set(false);
        try {
            if (dirty) {
                saveNow();
            }
        } catch (Throwable t) {
            // 后台线程里抛出去没人接（还会打印一堆栈），这里兜住
            LOGGER.warn("延迟保存本地穿戴失败: {}", t.toString());
        }
    }

    private static ScheduledExecutorService executor() {
        ScheduledExecutorService current = saver;
        if (current != null) {
            return current;
        }
        synchronized (SAVE_LOCK) {
            current = saver;
            if (current == null) {
                current = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, SAVER_THREAD_NAME);
                    // 守护线程：游戏退出时不该被它拖住（正常退出由 saveNow 兜底）
                    thread.setDaemon(true);
                    return thread;
                });
                saver = current;
            }
            return current;
        }
    }
}
