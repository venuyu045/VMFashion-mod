package com.venus.vmfashion.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.cosmetic.CosmeticPack;
import com.venus.vmfashion.cosmetic.CosmeticRegistry;
import com.venus.vmfashion.cosmetic.CosmeticResourceId;
import com.venus.vmfashion.cosmetic.LocalLoadout;
import com.venus.vmfashion.cosmetic.LoadoutStore;
import com.venus.vmfashion.cosmetic.PackLoader;
import com.venus.vmfashion.render.CosmeticAssets;
import com.venus.vmfashion.render.EmissiveMask;
import com.venus.vmfashion.render.FashionRenderState;
import com.venus.vmfashion.render.anim.AnimationCache;
import com.venus.vmfashion.render.anim.BedrockAnimation;
import com.venus.vmfashion.sync.AssetSyncService;
import com.venus.vmfashion.sync.SyncService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * {@code /vmfashion diag} 的实现：把排查问题需要的信息一次性导出到
 * {@code config/vmfashion/diag/diag-<时间戳>.txt}，聊天栏只回一个路径。
 *
 * <p>为什么要落盘而不是全打到聊天栏：时装包 / 烘焙失败 / 配置加起来通常上百行，
 * 聊天栏刷屏根本看不过来，玩家也没法把内容发给开发者。
 *
 * <p>内容（顺序有讲究）：
 * <ol>
 *   <li>环境与配置；</li>
 *   <li>时装包清单 + 每件时装的解析结果（动画 / 发光层状态）；</li>
 *   <li><b>渲染缓存</b>：已烘焙资源数、动画实例数、mixin 是否生效；</li>
 *   <li>本地穿戴 / 同步状态 / 资产下发状态；</li>
 *   <li>最后读 {@code logs/latest.log} 的尾巴。</li>
 * </ol>
 *
 * <p><b>本类绝不触发新的烘焙</b>：{@link CosmeticAssets#get} 在缓存未命中时会烘焙模型并上传贴图，
 * 排障工具不能因为跑一次诊断就把几十件时装全烘焙一遍。每件时装只读"已经加载过"的资源
 * （判定方式见 {@link #cachedEntry}），没加载过的写"未加载"。
 */
public final class DiagExporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 日志尾巴保留多少行原始行（之后再按关键字过滤）。 */
    private static final int LOG_TAIL_LINES = 400;
    /** 过滤后最多写多少行，避免文件过大。 */
    private static final int LOG_KEEP_LINES = 150;
    /** 目录大小统计最多走多少个条目（防止有人在 packs 里塞几万个小文件把命令卡住）。 */
    private static final int MAX_WALK_ENTRIES = 20_000;
    /** {@code CosmeticAssets} 注册主贴图时用的后缀（见它的 registerTexture 调用）。 */
    private static final String TEXTURE_SUFFIX = "texture";
    /** 动画那一列最多列几个名字，再多就用"等 N 个"概括（避免一行几百字符）。 */
    private static final int MAX_ANIMATIONS_LISTED = 3;
    /** 这件时装没配发光层。 */
    private static final int EMISSIVE_NONE = -1;
    /** 发光层存在，但资源还没加载过（diag 不烘焙，读不到）。 */
    private static final int EMISSIVE_NOT_LOADED = -2;
    /** 读遮罩时抛异常。 */
    private static final int EMISSIVE_ERROR = -3;

    private DiagExporter() {
    }

    /**
     * 导出诊断文件。
     *
     * @return 写好的文件路径
     * @throws IOException 目录 / 文件写不进去时抛出，由命令层转成聊天栏提示
     */
    public static Path export() throws IOException {
        Path directory = diagDirectory();
        if (directory == null) {
            throw new IOException("拿不到配置目录");
        }
        Files.createDirectories(directory);
        Path file = directory.resolve("diag-" + LocalDateTime.now().format(STAMP) + ".txt");

        StringBuilder out = new StringBuilder(8 * 1024);
        appendEnvironment(out);
        appendConfig(out);
        appendPacks(out);
        appendCosmetics(out);
        appendRenderCache(out);
        appendLoadout(out);
        appendSync(out);
        appendAssets(out);
        appendLogTail(out);

        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /** 诊断文件目录：{@code config/vmfashion/diag/}。 */
    public static Path diagDirectory() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("vmfashion").resolve("diag");
        } catch (Throwable t) {
            // 非 Fabric 环境（单测 / 工具）拿不到配置目录
            return null;
        }
    }

    // ==================== 各段内容 ====================

    private static void appendEnvironment(StringBuilder out) {
        out.append("VMFashion 诊断信息\n");
        out.append("生成时间: ").append(LocalDateTime.now()).append('\n');
        out.append("模组版本: ").append(modVersion()).append('\n');
        out.append("操作系统: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append('\n');
        out.append("Java: ").append(System.getProperty("java.version")).append('\n');
        try {
            out.append("游戏目录: ").append(FabricLoader.getInstance().getGameDir()).append('\n');
        } catch (Throwable ignored) {
            // 拿不到就不写，不影响其它段落
        }
        out.append('\n');
    }

    private static String modVersion() {
        try {
            return FabricLoader.getInstance().getModContainer(VMFashionClient.MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("?");
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void appendConfig(StringBuilder out) {
        out.append("==================== 配置 ====================\n");
        ModConfig config = VMFashionClient.getInstance() == null
                ? null : VMFashionClient.getInstance().getConfig();
        if (config == null) {
            out.append("（配置尚未初始化）\n\n");
            return;
        }
        try {
            // ModConfig 只有私有字段，Gson 通过反射照样能序列化；失败也不该让诊断失败
            out.append(GSON.toJson(config)).append('\n');
        } catch (Throwable t) {
            out.append("配置序列化失败: ").append(t).append('\n');
        }
        out.append('\n');
    }

    private static void appendPacks(StringBuilder out) {
        out.append("==================== 时装包 ====================\n");
        Path packDirectory = packDirectory();
        out.append("包目录: ").append(packDirectory).append('\n');
        out.append("包目录占用: ").append(humanBytes(directorySize(packDirectory))).append('\n');
        List<CosmeticPack> packs = CosmeticRegistry.getInstance().packs();
        out.append("已加载包数: ").append(packs.size()).append('\n');
        for (CosmeticPack pack : packs) {
            out.append("- ").append(pack.id()).append(" v").append(pack.version())
                    .append(" 《").append(pack.name()).append("》 件数=")
                    .append(pack.cosmetics().size()).append('\n');
        }
        out.append('\n');
    }

    private static void appendCosmetics(StringBuilder out) {
        out.append("==================== 时装清单 / 资源状态 ====================\n");
        List<CosmeticDefinition> cosmetics = CosmeticRegistry.getInstance().all();
        out.append("总件数: ").append(cosmetics.size()).append('\n');
        int loaded = 0;
        int animated = 0;
        int emissiveHits = 0;
        for (CosmeticDefinition cosmetic : cosmetics) {
            // 只读已经加载过的资源：本方法绝不触发新的烘焙（见类注释）
            CosmeticAssets.Entry entry = cachedEntry(cosmetic);
            if (entry != null) {
                loaded++;
            }
            String animation = describeAnimations(entry);
            int emissiveQuads = emissiveQuadCount(entry, cosmetic);
            if (entry != null && entry.hasAnimation()) {
                animated++;
            }
            if (emissiveQuads > 0) {
                emissiveHits++;
            }
            out.append(entry != null ? "[已加载] " : "[未加载] ").append(cosmetic.id())
                    .append(" 分类=").append(cosmetic.category().key())
                    .append(" 骨骼=").append(cosmetic.attachedBone())
                    .append(" hideParts=").append(cosmetic.hideParts())
                    .append(" scale=").append(cosmetic.scale())
                    .append(" 模型=").append(exists(cosmetic.modelFile()))
                    .append(" 贴图=").append(exists(cosmetic.textureFile()))
                    .append(" anim=").append(animation)
                    .append(" emissive=").append(describeEmissive(emissiveQuads))
                    .append('\n');
        }
        out.append("已加载资源: ").append(loaded).append(" / ").append(cosmetics.size())
                .append(" 件（未加载过的不做烘焙；烘焙失败原因见末尾日志段的「资源加载失败」）\n");
        out.append("有动画: ").append(animated).append(" 件 · 发光层命中: ").append(emissiveHits).append(" 件\n");
        out.append('\n');
    }

    /**
     * 只取"已经加载过"的资源，绝不触发新的烘焙。
     *
     * <p>为什么不直接 {@link CosmeticAssets#get}：缓存未命中时它会烘焙模型 + 上传贴图，
     * 排障工具不该顺手把几十件时装全烘焙一遍。
     *
     * <p>怎么判断"已加载"：{@code CosmeticAssets} 加载一件时装时会把主贴图注册进
     * {@link TextureManager}，而贴图 ID 由 {@link CosmeticResourceId#pathFor} 确定性生成
     * （含来源哈希，不会撞车）—— 贴图已注册 ⇔ 资源已缓存。命中后调
     * {@link CosmeticAssets#get} 只会直接返回缓存，不会烘焙。
     *
     * <p>（{@code render/} 不在本轮可改范围，所以用这个只读探针；将来 {@code CosmeticAssets}
     * 若提供 peek API，换成它更直接。）
     *
     * @return 已缓存的资源；没加载过 / 拿不到时返回 {@code null}
     */
    private static CosmeticAssets.Entry cachedEntry(CosmeticDefinition definition) {
        if (definition == null) {
            return null;
        }
        try {
            TextureManager textures = Minecraft.getInstance().getTextureManager();
            if (textures == null) {
                return null;
            }
            Identifier textureId = Identifier.fromNamespaceAndPath(VMFashionClient.MOD_ID,
                    CosmeticResourceId.pathFor(definition.packId(), definition.localId(), TEXTURE_SUFFIX));
            if (textures.getTexture(textureId) == null) {
                return null;
            }
            return CosmeticAssets.get(definition);
        } catch (Throwable t) {
            // 探针失败只影响这一件时装的显示
            return null;
        }
    }

    /** 动画列：{@code 无} / {@code 未加载} / {@code idle(2.0s)，wave(1.5s)}。 */
    private static String describeAnimations(CosmeticAssets.Entry entry) {
        if (entry == null) {
            return "未加载";
        }
        Map<String, BedrockAnimation> animations = entry.animations();
        if (animations == null || animations.isEmpty()) {
            return "无";
        }
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (BedrockAnimation animation : animations.values()) {
            if (shown >= MAX_ANIMATIONS_LISTED) {
                break;
            }
            if (shown > 0) {
                text.append("，");
            }
            text.append(animation.name() == null ? "?" : animation.name())
                    .append('(').append(seconds(animation.length())).append(')');
            shown++;
        }
        if (animations.size() > shown) {
            text.append(" 等 ").append(animations.size()).append(" 个");
        }
        return text.toString();
    }

    /**
     * 发光层遮罩命中的面数。
     *
     * <p>0 面和"没做发光"看起来完全一样，是这次审查点名要暴露的信息。
     * {@link EmissiveMask#get} 内部有缓存（按时装 ID + 模型），命中时不会重建遮罩。
     *
     * @return 命中的面数；{@link #EMISSIVE_NONE} 表示没配发光层，
     *         {@link #EMISSIVE_NOT_LOADED} 表示资源还没加载过，{@link #EMISSIVE_ERROR} 表示读取失败
     */
    private static int emissiveQuadCount(CosmeticAssets.Entry entry, CosmeticDefinition definition) {
        if (definition.emissiveFile() == null) {
            return EMISSIVE_NONE;
        }
        if (entry == null) {
            return EMISSIVE_NOT_LOADED;
        }
        try {
            EmissiveMask mask = EmissiveMask.get(entry, definition);
            return mask == null ? 0 : mask.size();
        } catch (Throwable t) {
            return EMISSIVE_ERROR;
        }
    }

    /** 把 {@link #emissiveQuadCount} 的结果翻成 diag 里那一列的文字。 */
    private static String describeEmissive(int quads) {
        return switch (quads) {
            case EMISSIVE_NONE -> "无发光层";
            case EMISSIVE_NOT_LOADED -> "未加载";
            case EMISSIVE_ERROR -> "读取失败";
            default -> quads == 0 ? "命中 0 面（整趟跳过）" : "命中 " + quads + " 面";
        };
    }

    /** 秒数保留一位小数：{@code 2.0s}。 */
    private static String seconds(double value) {
        return (Math.round(value * 10.0) / 10.0) + "s";
    }

    /**
     * 渲染缓存 / mixin 段。
     *
     * <p>"时装完全不显示"最常见的原因就是 mixin 没生效，而玩家自己没法判断，
     * 所以这里给出唯一能可靠判定的那一条（{@code AvatarRenderStateMixin}），
     * 其余 mixin 只能给行为自检提示。
     */
    private static void appendRenderCache(StringBuilder out) {
        out.append("==================== 渲染缓存 / mixin ====================\n");
        out.append("已烘焙资源: ").append(CosmeticAssets.cachedCount())
                .append(" 件，失败记录: ").append(CosmeticAssets.failureCount()).append(" 件\n");
        out.append("动画实例: ").append(AnimationCache.size()).append(" 个（按 玩家 × 时装 计）\n");
        out.append("AvatarRenderStateMixin: ").append(avatarMixinApplied()
                ? "已生效（AvatarRenderState 实现了 FashionRenderState）"
                : "未生效！时装不会渲染 —— 检查 mod 是否被正确加载、有无 mixin 冲突").append('\n');
        out.append("其它 mixin 运行时无法直接判定，请按下面的行为自检:\n");
        out.append("  - AvatarRendererMixin: 第三人称能看到自己身上的时装 → 生效\n");
        out.append("  - PlayerModelMixin: 穿带 hideParts 的时装后对应身体部位消失 → 生效\n");
        out.append("  - FirstPersonArmMixin: 第一人称能看到手臂上的时装 → 生效\n");
        out.append("  - 层替换类（Cape/Wings/CustomHead/Armor/Deadmau5Ears/ParrotOnShoulder/"
                + "SpinAttackEffect/StuckInBody/PlayerItemInHand）: 对应原版部件被时装替换或隐藏 → 生效\n");
        out.append('\n');
    }

    /**
     * {@code AvatarRenderStateMixin} 是否被应用。
     *
     * <p>该 mixin 让 {@link AvatarRenderState} 实现 {@link FashionRenderState}，
     * 这是运行时能可靠判断"mixin 到底生效没"的那一条（其它 mixin 是行为改变，无法直接探测）。
     */
    private static boolean avatarMixinApplied() {
        try {
            return FashionRenderState.class.isAssignableFrom(AvatarRenderState.class);
        } catch (Throwable t) {
            // 类加载失败（极端环境）时按"未生效"报，比抛异常好
            return false;
        }
    }

    private static void appendLoadout(StringBuilder out) {
        out.append("==================== 本地穿戴 ====================\n");
        Path file = LoadoutStore.file();
        out.append("落盘文件: ").append(file).append(file == null ? "" : ("（存在=" + Files.isRegularFile(file) + "）")).append('\n');
        Map<String, String> loadout = LocalLoadout.getInstance().snapshot();
        if (loadout.isEmpty()) {
            out.append("（没有穿戴任何时装）\n");
        }
        loadout.forEach((slot, id) -> out.append(slot).append(" -> ").append(id).append('\n'));
        out.append('\n');
    }

    private static void appendSync(StringBuilder out) {
        out.append("==================== 同步状态 ====================\n");
        try {
            SyncService sync = SyncService.getInstance();
            out.append("enabled: ").append(sync.isEnabled()).append('\n');
            // 诊断文件是给开发者看的，统一用当前语言的纯文本
            out.append("状态: ").append(sync.describeStatus().getString()).append('\n');
            var auth = sync.auth();
            var session = auth == null ? null : auth.session();
            if (session == null) {
                out.append("会话: 无（未登录）\n");
            } else {
                out.append("会话: ").append(session.mcName()).append(" / ").append(session.mcUuid())
                        .append(" / ").append(session.authType())
                        .append(" / verified=").append(session.verified())
                        .append(" / 过期时间=").append(session.expiresAt())
                        .append(" / 已过期=").append(session.expired()).append('\n');
            }
        } catch (Throwable t) {
            out.append("读取同步状态失败: ").append(t).append('\n');
        }
        out.append('\n');
    }

    private static void appendAssets(StringBuilder out) {
        out.append("==================== 资产下发 ====================\n");
        try {
            AssetSyncService assets = AssetSyncService.getInstance();
            out.append("状态: ").append(assets.describeStatus().getString()).append('\n');
            // 验签策略 / 密钥指纹 / 最近一次结论（audit v1.2 N18）：不输出公钥明文
            out.append("验签: ").append(assets.describeSignaturePolicy().getString()).append('\n');
            out.append("缓存占用: ").append(assets.cacheBytes()).append(" 字节\n");
            out.append("在飞/排队: ").append(assets.pendingCount()).append('\n');
        } catch (Throwable t) {
            out.append("读取资产状态失败: ").append(t).append('\n');
        }
        out.append('\n');
    }

    private static void appendLogTail(StringBuilder out) {
        out.append("==================== 最近日志（vmfashion 相关） ====================\n");
        Path log = logFile();
        if (log == null || !Files.isRegularFile(log)) {
            out.append("（找不到 logs/latest.log）\n");
            return;
        }
        try {
            List<String> tail = readTail(log, LOG_TAIL_LINES);
            int written = 0;
            for (int i = tail.size() - 1; i >= 0 && written < LOG_KEEP_LINES; i--) {
                String line = tail.get(i);
                if (line == null || !isRelevant(line)) {
                    continue;
                }
                out.append(line).append('\n');
                written++;
            }
            if (written == 0) {
                out.append("（最近 ").append(LOG_TAIL_LINES).append(" 行里没有相关日志）\n");
            }
        } catch (Throwable t) {
            out.append("读取日志失败: ").append(t).append('\n');
        }
    }

    /** 只留和本模组有关的行：日志里其它模组 / 原版的内容会把关键信息淹掉。 */
    private static boolean isRelevant(String line) {
        return line.contains("vmfashion") || line.contains("VMFashion") || line.contains("时装");
    }

    /**
     * 读文件最后 N 行（环形缓冲）。
     *
     * <p>日志可能几十 MB，不能 {@code readAllLines} 整个读进内存。
     */
    private static List<String> readTail(Path file, int lines) throws IOException {
        Deque<String> ring = new ArrayDeque<>(lines + 1);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                ring.addLast(line);
                if (ring.size() > lines) {
                    ring.removeFirst();
                }
            }
        }
        return new ArrayList<>(ring);
    }

    // ==================== 工具 ====================

    private static Path packDirectory() {
        try {
            return PackLoader.packsDir();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 递归统计目录占用字节数。
     *
     * <p>不跟随符号链接（{@code Files.walk} 默认行为），并且限制条目数 ——
     * 玩家在 packs 目录里放几万个小文件时不能让命令卡住。
     */
    public static long directorySize(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return 0L;
        }
        long total = 0L;
        int entries = 0;
        try (var stream = Files.walk(directory)) {
            var iterator = stream.iterator();
            while (iterator.hasNext() && entries < MAX_WALK_ENTRIES) {
                Path path = iterator.next();
                entries++;
                try {
                    if (Files.isRegularFile(path)) {
                        total += Files.size(path);
                    }
                } catch (IOException ignored) {
                    // 单个文件读不到（权限 / 正在被替换）不影响整体统计
                }
            }
        } catch (Exception ignored) {
            // 目录遍历失败只影响这一项数字
        }
        return total;
    }

    /** 字节数转人能看懂的大小（KB / MB），给聊天栏和诊断文件用。 */
    public static String humanBytes(long bytes) {
        if (bytes <= 0L) {
            return "0 KB";
        }
        long kb = (bytes + 512L) / 1024L;
        if (kb < 1024L) {
            return kb + " KB";
        }
        double mb = bytes / 1048576.0;
        return (Math.round(mb * 10.0) / 10.0) + " MB";
    }

    private static Path logFile() {
        try {
            return FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String exists(Path path) {
        if (path == null) {
            return "无";
        }
        try {
            return Files.isRegularFile(path) ? "有" : "缺失";
        } catch (Throwable t) {
            return "?";
        }
    }
}
