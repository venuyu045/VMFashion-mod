package com.venus.vmfashion.cosmetic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 网站「我的衣柜」收藏列表的本地镜像。
 *
 * <p><b>为什么需要它</b>：衣柜界面原来列的是 {@link CosmeticRegistry}（本地已装的全部时装包），
 * 玩家在网站商店里加进衣柜的时装和本地装了哪些包没有关系 —— 想让"游戏内衣柜 = 网站衣柜"，
 * 就必须有一份服务端下发的收藏清单。这个类就是那份清单的内存 + 磁盘镜像。
 *
 * <p><b>数据来源</b>：{@code GET /api/fashion/wardrobe/me}（见 {@code docs/fashion-api.md} §5.5），
 * 由同步层（{@code sync/SyncService}）拉取后调用 {@link #replaceAll(boolean, List)}。
 *
 * <p><b>三种状态</b>（界面据此决定显示什么，绝不会让玩家"衣柜突然空了"）：
 * <ul>
 *   <li>{@link #linked()} {@code = false}：这个 MC 身份没绑定网站账号 → 界面退回"本地全部"；</li>
 *   <li>{@link #loaded()} {@code = false}：还没成功拉过（断网 / 后端没实现 / 纯本地模式）→ 同样退回"本地全部"；</li>
 *   <li>两者都为 {@code true}：只显示收藏里的时装（{@link #entries()}），本地没装包的也能列出来
 *       （点穿上会自动触发时装包下发）。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：网络回调线程写、渲染线程读。字段都是 volatile 的不可变快照，
 * 读路径不加锁；写路径用 {@link #WRITE_LOCK} 串行化（内存 + 磁盘）。
 *
 * <p><b>持久化</b>：{@code config/vmfashion/wardrobe.json}，原子写（写临时文件 → fsync → ATOMIC_MOVE）。
 * 有了它，断网 / 后端挂掉时进游戏仍然能按上次的收藏过滤，而不是突然显示全部。
 * 文件缺失 / 损坏 / 字段非法一律静默降级为"未加载"，绝不影响客户端启动。
 */
public final class WardrobeCollection {

    private static final WardrobeCollection INSTANCE = new WardrobeCollection();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("vmfashion");

    /** 数据格式版本，将来结构变化时用来做兼容。 */
    public static final int FORMAT_VERSION = 1;

    private static final String FILE_NAME = "wardrobe.json";
    private static final String FORMAT_VERSION_KEY = "formatVersion";
    private static final String LINKED_KEY = "linked";
    private static final String FETCHED_AT_KEY = "fetchedAt";
    private static final String ITEMS_KEY = "items";
    private static final String COSMETIC_ID_KEY = "cosmeticId";
    private static final String NAME_KEY = "name";
    private static final String CATEGORY_KEY = "category";
    private static final String PACK_ID_KEY = "packId";
    private static final String TEMP_PREFIX = "wardrobe-";
    private static final String TEMP_SUFFIX = ".json.tmp";

    /** 落盘 + 内存替换的串行锁。 */
    private static final Object WRITE_LOCK = new Object();

    /**
     * 一条收藏条目。
     *
     * <p>{@code cosmeticId} 是服务端的全局 ID（{@code <packId>:<localId>}）；本地可能<b>没有</b>
     * 装对应的包 —— 那种情况下界面仍然要列出它（名字 / 分类来自这里），点穿上会自动下载。
     *
     * @param cosmeticId 全局 ID，非空
     * @param name       展示名（服务端可能为空串，界面回落到 cosmeticId）
     * @param categoryKey 分类 key（{@code cape}/{@code wings}/…，未知值界面按 other 处理）
     * @param packId     所属时装包 ID（可能为空串）
     */
    public record Entry(String cosmeticId, String name, String categoryKey, String packId) {
    }

    /** {@link #parse(JsonObject)} 的结果。 */
    public record Parsed(boolean linked, List<Entry> entries) {
    }

    /** 服务端是否明确表示"该身份已绑定网站账号"。 */
    private volatile boolean linked;
    /** 是否至少成功加载过一次（内存或磁盘缓存）。 */
    private volatile boolean loaded;
    /** 收藏快照（不可变，按服务端顺序）。 */
    private volatile List<Entry> entries = List.of();
    /** 短 ID（localId）→ 全局 ID 的别名表，仅收录**唯一**的短 ID；{@link #contains(String)} 用。 */
    private volatile Map<String, String> shortAliases = Map.of();
    /** 最近一次成功拉取的时间（毫秒），0 = 从磁盘读的 / 没拉过。 */
    private volatile long fetchedAtMs;

    private WardrobeCollection() {
    }

    public static WardrobeCollection getInstance() {
        return INSTANCE;
    }

    // ==================== 读 ====================

    public boolean linked() {
        return linked;
    }

    public boolean loaded() {
        return loaded;
    }

    /** 最近一次成功从服务端拉取的时间（毫秒）；0 表示还没拉过（可能来自磁盘缓存）。 */
    public long fetchedAtMs() {
        return fetchedAtMs;
    }

    /** 当前收藏快照（不可变列表，可以安全地在渲染线程遍历）。 */
    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    /** 界面是否应该按收藏过滤：已绑定 + 已加载。配置开关由调用方（界面）再判一次。 */
    public boolean usable() {
        return linked && loaded;
    }

    /**
     * 是否收藏了某件时装。
     *
     * <p>同时接受全局 ID（{@code packId:localId}）和唯一短 ID（{@code localId}）：
     * 服务端目录里可能存在历史"简单 ID"条目，而 {@link CosmeticDefinition#id()} 永远是全局 ID，
     * 直接字符串比较会漏掉这种数据。
     */
    public boolean contains(String cosmeticId) {
        if (cosmeticId == null || cosmeticId.isBlank()) {
            return false;
        }
        String needle = cosmeticId.trim();
        List<Entry> current = entries;
        if (current.isEmpty()) {
            return false;
        }
        for (Entry entry : current) {
            if (entry.cosmeticId().equalsIgnoreCase(needle)) {
                return true;
            }
        }
        String alias = shortAliases.get(needle.toLowerCase(Locale.ROOT));
        return alias != null;
    }

    // ==================== 写 ====================

    /**
     * 用服务端响应整体替换收藏。
     *
     * @param linked  服务端返回的"是否已绑定网站账号"
     * @param entries 收藏条目；{@code null} 等价于空列表
     */
    public void replaceAll(boolean linked, List<Entry> entries) {
        List<Entry> normalized = normalize(entries);
        synchronized (WRITE_LOCK) {
            this.entries = normalized;
            this.shortAliases = buildShortAliases(normalized);
            this.linked = linked;
            this.loaded = true;
            this.fetchedAtMs = System.currentTimeMillis();
        }
    }

    /**
     * 解析 {@code GET /api/fashion/wardrobe/me} 的响应体。
     *
     * <p>防御式解析：缺字段 / 类型不对的条目直接跳过，绝不让一条坏数据毁掉整份收藏。
     * 契约见 {@code docs/fashion-api.md} §5.5：
     * <pre>
     * { "linked": true,
     *   "items": [ { "cosmeticId": "lw_angel:angel", "name": "Angel Wings",
     *                "category": "wings", "packId": "lw_angel", "missing": false } ] }
     * </pre>
     */
    public static Parsed parse(JsonObject json) {
        if (json == null) {
            return new Parsed(false, List.of());
        }
        boolean linked = asBoolean(json.get(LINKED_KEY), false);
        List<Entry> parsed = new ArrayList<>();
        JsonElement items = json.get(ITEMS_KEY);
        if (items != null && items.isJsonArray()) {
            JsonArray array = items.getAsJsonArray();
            for (JsonElement element : array) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                Entry entry = entryFromJson(element.getAsJsonObject());
                if (entry != null) {
                    parsed.add(entry);
                }
            }
        }
        return new Parsed(linked, normalize(parsed));
    }

    /** 清空（测试 / 切账号时用）。不影响磁盘文件，需要的话再 {@link #saveNow()}。 */
    public void clear() {
        synchronized (WRITE_LOCK) {
            this.entries = List.of();
            this.shortAliases = Map.of();
            this.linked = false;
            this.loaded = false;
            this.fetchedAtMs = 0L;
        }
    }

    // ==================== 磁盘 ====================

    /** 数据文件路径；非 Fabric 环境（单测 / 诊断工具）返回 {@code null}。 */
    public static Path file() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("vmfashion").resolve(FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 启动时读磁盘缓存（文件缺失 / 损坏时静默跳过，保持当前内存状态）。 */
    public void loadFromDisk() {
        loadFromDisk(file());
    }

    /**
     * 从指定文件读入收藏。
     *
     * @return 实际恢复的条目数；文件不存在或损坏时返回 {@code -1} 且不改动内存状态
     */
    public int loadFromDisk(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return -1;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                LOGGER.warn("衣柜收藏缓存不是 JSON 对象，已忽略: {}", path);
                return -1;
            }
            JsonObject root = parsed.getAsJsonObject();
            boolean fileLinked = asBoolean(root.get(LINKED_KEY), false);
            long fileFetchedAt = asLong(root.get(FETCHED_AT_KEY), 0L);
            List<Entry> restored = new ArrayList<>();
            JsonElement items = root.get(ITEMS_KEY);
            if (items != null && items.isJsonArray()) {
                for (JsonElement element : items.getAsJsonArray()) {
                    if (element == null || !element.isJsonObject()) {
                        continue;
                    }
                    Entry entry = entryFromJson(element.getAsJsonObject());
                    if (entry != null) {
                        restored.add(entry);
                    }
                }
            }
            List<Entry> normalized = normalize(restored);
            synchronized (WRITE_LOCK) {
                this.entries = normalized;
                this.shortAliases = buildShortAliases(normalized);
                this.linked = fileLinked;
                this.loaded = true;
                // 磁盘上的 fetchedAt 只用于"多久没刷新"的判断，不代表这次真的拉成功了
                this.fetchedAtMs = fileFetchedAt;
            }
            LOGGER.info("已从 {} 恢复 {} 件衣柜收藏", path, normalized.size());
            return normalized.size();
        } catch (Exception e) {
            // 防御式解析：坏文件只记日志，绝不让客户端起不来
            LOGGER.warn("衣柜收藏缓存损坏，已忽略: {} ({})", path, e.toString());
            return -1;
        }
    }

    /** 原子写磁盘缓存；失败只记日志（丢了下次进游戏会重新拉）。 */
    public void saveNow() {
        saveNow(file());
    }

    /**
     * 写指定文件（测试用）。
     *
     * @return 是否写成功
     */
    public boolean saveNow(Path path) {
        if (path == null) {
            return false;
        }
        String content;
        synchronized (WRITE_LOCK) {
            if (!loaded) {
                // 从没加载过就不要写：否则会用一份空收藏覆盖掉上一次的真实缓存
                return false;
            }
            content = GSON.toJson(toJson());
        }
        Path temp = null;
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temp = Files.createTempFile(parent, TEMP_PREFIX, TEMP_SUFFIX);
            writeAndFlush(temp, content);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 某些文件系统（网络盘 / 部分容器挂载）不支持原子重命名，退化成普通替换
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("保存衣柜收藏失败: {} ({})", path, e.toString());
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

    // ==================== 内部 ====================

    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty(FORMAT_VERSION_KEY, FORMAT_VERSION);
        root.addProperty(LINKED_KEY, linked);
        root.addProperty(FETCHED_AT_KEY, fetchedAtMs);
        JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty(COSMETIC_ID_KEY, entry.cosmeticId());
            item.addProperty(NAME_KEY, entry.name());
            item.addProperty(CATEGORY_KEY, entry.categoryKey());
            item.addProperty(PACK_ID_KEY, entry.packId());
            array.add(item);
        }
        root.add(ITEMS_KEY, array);
        return root;
    }

    /** 单条解析：{@code cosmeticId} 为空的一律丢弃（没有 ID 的条目没法穿戴 / 匹配）。 */
    private static Entry entryFromJson(JsonObject item) {
        String id = asString(item.get(COSMETIC_ID_KEY));
        if (id.isBlank()) {
            return null;
        }
        return new Entry(
                id,
                asString(item.get(NAME_KEY)),
                asString(item.get(CATEGORY_KEY)).toLowerCase(Locale.ROOT),
                asString(item.get(PACK_ID_KEY)));
    }

    /**
     * 规范化：去空白、去重（同一 cosmeticId 只留第一条）、丢掉空 ID。
     *
     * <p>去重是必须的：服务端理论上唯一，但历史数据 / 手工改过的缓存可能重复，
     * 重复条目会让界面出现两张一样的卡。
     */
    private static List<Entry> normalize(List<Entry> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (Entry entry : input) {
            if (entry == null || entry.cosmeticId() == null) {
                continue;
            }
            String id = entry.cosmeticId().trim();
            if (id.isEmpty()) {
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            if (unique.containsKey(key)) {
                continue;
            }
            String name = entry.name() == null ? "" : entry.name().trim();
            String category = entry.categoryKey() == null ? "" : entry.categoryKey().trim();
            String packId = entry.packId() == null ? "" : entry.packId().trim();
            unique.put(key, new Entry(id, name, category, packId));
        }
        return List.copyOf(unique.values());
    }

    /**
     * 短 ID 别名表：只收录**唯一**的 localId。
     *
     * <p>同名 localId 分布在多个包时不能猜（会匹配到错误的那件），和
     * {@link CosmeticRegistry#find(String)} 的歧义规则保持一致。
     */
    private static Map<String, String> buildShortAliases(List<Entry> entries) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String localId = localIdOf(entry.cosmeticId());
            if (localId.isEmpty()) {
                continue;
            }
            String key = localId.toLowerCase(Locale.ROOT);
            counts.merge(key, 1, Integer::sum);
            aliases.put(key, entry.cosmeticId());
        }
        aliases.entrySet().removeIf(e -> counts.getOrDefault(e.getKey(), 0) != 1);
        return Map.copyOf(aliases);
    }

    /** {@code packId:localId} → {@code localId}；没有冒号时原样返回。 */
    private static String localIdOf(String cosmeticId) {
        int index = cosmeticId.indexOf(':');
        return index > 0 && index < cosmeticId.length() - 1
                ? cosmeticId.substring(index + 1)
                : cosmeticId;
    }

    private static String asString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        try {
            return element.getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean asBoolean(JsonElement element, boolean fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (Exception e) {
            // 后端把 linked 写成 "true" 字符串时也认
            try {
                return Boolean.parseBoolean(element.getAsString().trim());
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }

    private static long asLong(JsonElement element, long fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (Exception e) {
            return fallback;
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
}
