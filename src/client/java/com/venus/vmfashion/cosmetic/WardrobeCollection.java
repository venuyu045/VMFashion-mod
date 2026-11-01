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
 * <p><b>数据来源</b>：{@code GET /api/fashion/wardrobe/me}（见 {@code docs/fashion-api.md} §3.5），
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
 *
 * <p><b>防串号</b>：文件里记了这份收藏属于哪个身份（{@code owner} = MC UUID + 后端地址，
 * 见 {@link #adoptOwner(String)}）。换号 / 换后端时旧收藏必须作废，否则 B 会看到 A 的衣柜
 * （和 {@code FashionAuth#revalidateProfile()} 防的是同一类问题）。
 */
public final class WardrobeCollection {

    private static final WardrobeCollection INSTANCE = new WardrobeCollection();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("vmfashion");

    /** 数据格式版本，将来结构变化时用来做兼容。 */
    public static final int FORMAT_VERSION = 1;

    /**
     * 单份收藏最多接受的条目数。
     *
     * <p>为什么要上限：这份列表会进内存、进界面、进磁盘缓存，后端异常（或恶意后端）返回十万条
     * 足以把渲染线程和堆拖死。{@code PackCatalog} 对清单也有同样的上限（{@code MAX_PACKS}）。
     */
    static final int MAX_ENTRIES = 4096;

    /** 单个字段（cosmeticId / name / category / packId）的长度上限，防止一条脏数据把界面撑爆。 */
    static final int MAX_FIELD_LENGTH = 200;

    private static final String FILE_NAME = "wardrobe.json";
    private static final String FORMAT_VERSION_KEY = "formatVersion";
    private static final String LINKED_KEY = "linked";
    private static final String FETCHED_AT_KEY = "fetchedAt";
    private static final String ITEMS_KEY = "items";
    private static final String COSMETIC_ID_KEY = "cosmeticId";
    private static final String NAME_KEY = "name";
    private static final String CATEGORY_KEY = "category";
    private static final String PACK_ID_KEY = "packId";
    private static final String MISSING_KEY = "missing";
    private static final String OWNER_KEY = "owner";
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
     * @param cosmeticId  全局 ID，非空
     * @param name        展示名（服务端可能为空串，界面回落到 cosmeticId）
     * @param categoryKey 分类 key（{@code cape}/{@code wings}/…，未知值界面按 other 处理）
     * @param packId      所属时装包 ID（可能为空串）
     * @param missing     {@code true} = 这件时装在网站目录里<b>已删除 / 已停用</b>：
     *                    界面要显示（让玩家知道它还在衣柜里）但<b>禁止穿戴 / 禁止触发下发</b>，
     *                    因为后端一定会拒绝（{@code unknown_cosmetic} / {@code cosmetic_disabled}）。
     *                    注意它<b>不是</b>"本地没装包"——那个由客户端自己比对
     *                    {@link CosmeticRegistry} 得出，与这个字段无关。
     */
    public record Entry(String cosmeticId, String name, String categoryKey, String packId, boolean missing) {

        /**
         * 兼容 4 参构造（没有 missing 信息）：按 {@code missing=false} 处理。
         *
         * <p>保留它是为了不让"手里没有服务端字段"的调用方（测试、旧代码）被迫写第 5 个参数。
         */
        public Entry(String cosmeticId, String name, String categoryKey, String packId) {
            this(cosmeticId, name, categoryKey, packId, false);
        }
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
    /** 这份收藏属于哪个身份（{@code mcUuid|apiBaseUrl}）；空串 = 未知（老缓存 / 还没登录）。 */
    private volatile String owner = "";

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

    /** 这份收藏属于哪个身份（{@code mcUuid|apiBaseUrl}）；空串表示未知。 */
    public String owner() {
        return owner;
    }

    /** 当前收藏快照（不可变列表，可以安全地在渲染线程遍历）。 */
    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    /** 收藏里有多少件是"目录已删除 / 已停用"（界面状态行与 diag 用）。 */
    public int missingCount() {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.missing()) {
                count++;
            }
        }
        return count;
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
        return findEntry(cosmeticId) != null;
    }

    /**
     * 这件时装是否"已下架"（服务端目录里已删除 / 已停用）。
     *
     * <p>口径与 {@link #contains(String)} 完全一致（全局 ID 或唯一短 ID，大小写不敏感）。
     * <b>没收藏不算已下架</b>：找不到条目时返回 {@code false}。
     *
     * <p>调用方（界面 / 命令）拿到 {@code true} 时应当：显示但禁止穿戴、禁止触发时装包下发 ——
     * 后端对已下架时装一定会拒绝，硬穿只会给玩家一条"穿上失败"的报错。
     */
    public boolean isMissing(String cosmeticId) {
        Entry entry = findEntry(cosmeticId);
        return entry != null && entry.missing();
    }

    /**
     * 按 ID 查收藏条目（全局 ID 或唯一短 ID，大小写不敏感）。
     *
     * @return 命中的条目；没收藏 / 参数非法 / 短 ID 有歧义时返回 {@code null}
     */
    public Entry findEntry(String cosmeticId) {
        if (cosmeticId == null || cosmeticId.isBlank()) {
            return null;
        }
        String needle = cosmeticId.trim();
        List<Entry> current = entries;
        if (current.isEmpty()) {
            return null;
        }
        for (Entry entry : current) {
            if (entry.cosmeticId().equalsIgnoreCase(needle)) {
                return entry;
            }
        }
        String alias = shortAliases.get(needle.toLowerCase(Locale.ROOT));
        if (alias == null) {
            return null;
        }
        for (Entry entry : current) {
            if (entry.cosmeticId().equalsIgnoreCase(alias)) {
                return entry;
            }
        }
        return null;
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
     * 契约见 {@code docs/fashion-api.md} §3.5：
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
            int limit = Math.min(array.size(), MAX_ENTRIES);
            if (array.size() > MAX_ENTRIES) {
                LOGGER.warn("网站衣柜返回了 {} 条收藏，超过上限 {}，多余的已忽略", array.size(), MAX_ENTRIES);
            }
            for (int i = 0; i < limit; i++) {
                JsonElement element = array.get(i);
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                Entry entry = entryFromJson(element.getAsJsonObject());
                if (entry != null) {
                    parsed.add(entry);
                }
            }
        }
        List<Entry> normalized = normalize(parsed);
        // 契约要求 linked=false 时 items 必须为空。老后端 / 自建后端可能压根不返回 linked 字段，
        // 这时如果给了条目，按"已绑定"处理：否则界面会退回"本地全部"，
        // 玩家在网站加的衣柜看起来完全没生效（比"多显示几件"难排查得多）。
        if (!linked && !normalized.isEmpty() && !hasNonNull(json, LINKED_KEY)) {
            LOGGER.debug("网站衣柜响应没有 linked 字段但有 {} 条收藏，按已绑定处理", normalized.size());
            linked = true;
        }
        return new Parsed(linked, normalized);
    }

    /** 清空（测试 / 切账号时用）。不影响磁盘文件，需要的话再 {@link #saveNow()}。 */
    public void clear() {
        synchronized (WRITE_LOCK) {
            this.entries = List.of();
            this.shortAliases = Map.of();
            this.linked = false;
            this.loaded = false;
            this.fetchedAtMs = 0L;
            this.owner = "";
        }
    }

    /**
     * 采纳当前身份（{@code mcUuid|apiBaseUrl}），身份变了就丢弃旧收藏。
     *
     * <p>为什么必须做：{@code wardrobe.json} 是<b>整机</b>共享的，换 MC 账号 / 换后端地址之后
     * 旧账号的收藏仍然在内存和磁盘里 —— 新账号在断网时会看到上一个账号的衣柜（隐私 + 误导）。
     * 这和 {@code FashionAuth#revalidateProfile()} 防的"把 A 的穿戴写进 B"是同一类问题。
     *
     * <p>三种情况：
     * <ul>
     *   <li>{@code currentOwner} 为空（客户端还没准备好 / 单测）：什么都不做；</li>
     *   <li>旧缓存没有 owner 字段（v1.3.0 升级上来的）：只补记 owner，<b>不清理</b>，免得玩家白丢一次缓存；</li>
     *   <li>owner 确实变了：清内存 + 删磁盘缓存（下次进服重新拉）。</li>
     * </ul>
     *
     * @return {@code true} = 因为换了身份而丢弃了旧收藏
     */
    public boolean adoptOwner(String currentOwner) {
        String next = currentOwner == null ? "" : currentOwner.trim();
        if (next.isEmpty()) {
            return false;
        }
        synchronized (WRITE_LOCK) {
            String previous = owner;
            if (next.equalsIgnoreCase(previous)) {
                return false;
            }
            owner = next;
            if (!shouldDiscardForOwner(previous, next)) {
                LOGGER.info("衣柜收藏缓存没有身份标记，已归属当前身份（不清理，下次刷新会覆盖）: {}", next);
                return false;
            }
            boolean hadData = loaded || !entries.isEmpty();
            entries = List.of();
            shortAliases = Map.of();
            linked = false;
            loaded = false;
            fetchedAtMs = 0L;
            if (hadData) {
                LOGGER.info("检测到时装衣柜属于另一个身份（{} → {}），已丢弃旧收藏", previous, next);
            }
            deleteFileQuietly();
            return hadData;
        }
    }

    /**
     * 是否应当因为身份变化丢弃缓存（纯逻辑，可单测）。
     *
     * <p>只有"旧身份和新身份都知道、且确实不同"时才丢：旧缓存没有身份标记（升级上来的）
     * 或者新身份未知（客户端还没初始化）都不丢 —— 丢错的代价是玩家白等一次网络刷新。
     */
    static boolean shouldDiscardForOwner(String storedOwner, String currentOwner) {
        if (storedOwner == null || storedOwner.isBlank()) {
            return false;
        }
        if (currentOwner == null || currentOwner.isBlank()) {
            return false;
        }
        return !storedOwner.trim().equalsIgnoreCase(currentOwner.trim());
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
            int formatVersion = (int) asLong(root.get(FORMAT_VERSION_KEY), FORMAT_VERSION);
            if (formatVersion > FORMAT_VERSION) {
                // 未来版本写的文件：字段名是向后兼容的（只增不改），照常读，只提示一句
                LOGGER.warn("衣柜收藏缓存的 formatVersion={} 高于本 mod 支持的 {}，可能有字段无法识别: {}",
                        formatVersion, FORMAT_VERSION, path);
            }
            boolean fileLinked = asBoolean(root.get(LINKED_KEY), false);
            long fileFetchedAt = asLong(root.get(FETCHED_AT_KEY), 0L);
            String fileOwner = asString(root.get(OWNER_KEY));
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
                this.owner = fileOwner;
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

    /** 删掉默认缓存文件（换身份 / 清理时用）；失败只记 debug。 */
    private static void deleteFileQuietly() {
        Path path = file();
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.debug("删除衣柜收藏缓存失败（忽略）: {} ({})", path, e.toString());
        }
    }

    // ==================== 内部 ====================

    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty(FORMAT_VERSION_KEY, FORMAT_VERSION);
        root.addProperty(LINKED_KEY, linked);
        root.addProperty(FETCHED_AT_KEY, fetchedAtMs);
        root.addProperty(OWNER_KEY, owner);
        JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty(COSMETIC_ID_KEY, entry.cosmeticId());
            item.addProperty(NAME_KEY, entry.name());
            item.addProperty(CATEGORY_KEY, entry.categoryKey());
            item.addProperty(PACK_ID_KEY, entry.packId());
            // missing 必须落盘：否则重启后"已下架"变成"可穿戴"，界面又会允许穿一件穿不上的时装
            item.addProperty(MISSING_KEY, entry.missing());
            array.add(item);
        }
        root.add(ITEMS_KEY, array);
        return root;
    }

    /** 单条解析：{@code cosmeticId} 为空的一律丢弃（没有 ID 的条目没法穿戴 / 匹配）。 */
    private static Entry entryFromJson(JsonObject item) {
        String id = truncate(asString(item.get(COSMETIC_ID_KEY)));
        if (id.isBlank()) {
            return null;
        }
        return new Entry(
                id,
                truncate(asString(item.get(NAME_KEY))),
                truncate(asString(item.get(CATEGORY_KEY))).toLowerCase(Locale.ROOT),
                truncate(asString(item.get(PACK_ID_KEY))),
                asBoolean(item.get(MISSING_KEY), false));
    }

    /**
     * 规范化：去空白、去重（同一 cosmeticId 只留第一条）、丢掉空 ID、限条数与字段长度。
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
            if (unique.size() >= MAX_ENTRIES) {
                break;
            }
            String id = truncate(entry.cosmeticId().trim());
            if (id.isEmpty()) {
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            if (unique.containsKey(key)) {
                continue;
            }
            String name = truncate(entry.name() == null ? "" : entry.name().trim());
            String category = truncate(entry.categoryKey() == null ? "" : entry.categoryKey().trim());
            String packId = truncate(entry.packId() == null ? "" : entry.packId().trim());
            unique.put(key, new Entry(id, name, category, packId, entry.missing()));
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

    /** 截断超长字段（保留前缀，够界面展示 / 匹配用）；不做 trim。 */
    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_FIELD_LENGTH ? value : value.substring(0, MAX_FIELD_LENGTH);
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

    private static boolean hasNonNull(JsonObject json, String key) {
        return json != null && json.has(key) && !json.get(key).isJsonNull();
    }

    private static boolean asBoolean(JsonElement element, boolean fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (Exception e) {
            // 后端把 linked / missing 写成 "true" 字符串时也认
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
