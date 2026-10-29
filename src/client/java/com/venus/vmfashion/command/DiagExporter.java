package com.venus.vmfashion.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.cosmetic.CosmeticPack;
import com.venus.vmfashion.cosmetic.CosmeticRegistry;
import com.venus.vmfashion.cosmetic.LocalLoadout;
import com.venus.vmfashion.cosmetic.LoadoutStore;
import com.venus.vmfashion.cosmetic.PackLoader;
import com.venus.vmfashion.render.CosmeticAssets;
import com.venus.vmfashion.sync.AssetSyncService;
import com.venus.vmfashion.sync.SyncService;
import net.fabricmc.loader.api.FabricLoader;

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
 *   <li>时装包清单 + 每件时装的解析结果；</li>
 *   <li><b>烘焙</b>：对每件时装调一次 {@link CosmeticAssets#get}，失败的那条会把原因
 *       {@code warn} 进日志；</li>
 *   <li>本地穿戴 / 同步状态 / 资产下发状态；</li>
 *   <li><b>最后</b>读 {@code logs/latest.log} 的尾巴 —— 必须放在烘焙之后，
 *       否则刚产生的失败原因读不到。</li>
 * </ol>
 *
 * <p>本类只做"读 + 写一个文本文件"，不碰 GL 之外的 Minecraft 状态；烘焙必须由调用方
 * 保证在主线程执行（命令本来就跑在主线程）。
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
        out.append("==================== 时装清单 / 烘焙结果 ====================\n");
        List<CosmeticDefinition> cosmetics = CosmeticRegistry.getInstance().all();
        out.append("总件数: ").append(cosmetics.size()).append('\n');
        int failures = 0;
        for (CosmeticDefinition cosmetic : cosmetics) {
            // 这里会真正烘焙 + 上传贴图（主线程），失败原因由 CosmeticAssets 写进日志，
            // 本方法末尾的"日志尾巴"段会把它带出来
            boolean ok;
            try {
                ok = CosmeticAssets.get(cosmetic) != null;
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) {
                failures++;
            }
            out.append(ok ? "[OK]   " : "[FAIL] ").append(cosmetic.id())
                    .append(" 分类=").append(cosmetic.category().key())
                    .append(" 骨骼=").append(cosmetic.attachedBone())
                    .append(" hideParts=").append(cosmetic.hideParts())
                    .append(" scale=").append(cosmetic.scale())
                    .append(" 模型=").append(exists(cosmetic.modelFile()))
                    .append(" 贴图=").append(exists(cosmetic.textureFile()))
                    .append('\n');
        }
        out.append("烘焙失败: ").append(failures).append(" / ").append(cosmetics.size()).append('\n');
        out.append("资源缓存: ").append(CosmeticAssets.cachedCount())
                .append(" 件，失败记录: ").append(CosmeticAssets.failureCount()).append('\n');
        out.append('\n');
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
            out.append("状态: ").append(sync.describeStatus()).append('\n');
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
            out.append("状态: ").append(assets.describeStatus()).append('\n');
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
