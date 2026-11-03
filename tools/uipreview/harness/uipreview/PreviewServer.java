package uipreview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 可交互预览服务：把真实 {@code WardrobeScreen} 变成一个"能在浏览器里点的界面"。
 *
 * <p><b>为什么值得做这一层：</b>静态出图只能看"某一帧长什么样"，
 * 但 UI 的问题大多出在<b>状态转移</b>上 —— 点分类后网格有没有重排、打字后过滤对不对、
 * 滚到底部会不会越界、点"穿上"后按钮禁用态有没有跟着变。这些只有真的把事件喂进去、
 * 再看下一帧才能验证。有了这个服务，改一行代码 → 刷新页面 → 点两下就知道对不对。
 *
 * <p><b>事件是"真"的：</b>网页上的点击会变成 {@code MouseButtonEvent} 塞进
 * {@code Screen#mouseClicked}，和游戏里走的是同一个方法、同一份布局计算。
 *
 * <p>接口（都给 {@code GET} 用浏览器调试，事件用 {@code POST}）：
 * <pre>
 * GET  /                 交互页面
 * GET  /frame.png        当前帧
 * GET  /info             会话状态（JSON）
 * POST /event?type=...   投递事件，返回新的一帧 PNG
 * POST /reset?scenario=  重置场景
 * </pre>
 */
public final class PreviewServer {

    private final PreviewSession session;
    private final Object lock = new Object();
    private final String html;

    private PreviewServer(PreviewSession session, String html) {
        this.session = session;
        this.html = html;
    }

    public static void main(String[] args) throws Exception {
        Path modDir = Path.of(System.getProperty("vmf.modDir", ".")).toAbsolutePath().normalize();
        int width = 640;
        int height = 360;
        int scale = 2;
        String locale = "zh_cn";
        int port = 8765;
        String adapterClass = System.getProperty("uipreview.adapter",
                "com.venus.vmfashion.uipreview.VmfashionAdapter");

        for (String arg : args) {
            if (arg.startsWith("--width=")) {
                width = Integer.parseInt(arg.substring(8));
            } else if (arg.startsWith("--height=")) {
                height = Integer.parseInt(arg.substring(9));
            } else if (arg.startsWith("--scale=")) {
                scale = Integer.parseInt(arg.substring(8));
            } else if (arg.startsWith("--locale=")) {
                locale = arg.substring(9);
            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring(7));
            } else if (arg.startsWith("--adapter=")) {
                adapterClass = arg.substring("--adapter=".length());
            }
        }

        UiPreviewAdapter adapter = UiPreview.loadAdapter(adapterClass);
        PreviewSession session = new PreviewSession(modDir, width, height, scale, locale, adapter);
        Path pagePath = modDir.resolve("tools/uipreview/web/index.html");
        String html = Files.isRegularFile(pagePath)
                ? Files.readString(pagePath, StandardCharsets.UTF_8)
                : "<h1>缺少 tools/uipreview/web/index.html</h1>";

        PreviewServer preview = new PreviewServer(session, html);
        preview.start(port);
    }

    private void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/frame.png", this::handleFrame);
        server.createContext("/info", this::handleInfo);
        server.createContext("/event", this::handleEvent);
        server.createContext("/reset", this::handleReset);
        server.setExecutor(null);
        server.start();
        System.out.println("UI 预览服务已启动: http://127.0.0.1:" + port + "/");
        System.out.println("  " + session.adapter().displayName()
                + "（" + session.adapter().getClass().getSimpleName() + "）");
        System.out.println("  逻辑分辨率 " + session.width() + "x" + session.height()
                + " × " + session.scale() + " 倍 | 语言 " + session.locale());
        System.out.println("  场景: " + session.adapter().scenarios().keySet());
        System.out.println("  按 Ctrl+C 停止");
    }

    // ==================== 路由 ====================

    private void handleIndex(HttpExchange exchange) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        send(exchange, 200, body);
    }

    private void handleFrame(HttpExchange exchange) throws IOException {
        byte[] png;
        synchronized (lock) {
            png = session.renderPng();
        }
        exchange.getResponseHeaders().add("Content-Type", "image/png");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        send(exchange, 200, png);
    }

    private void handleInfo(HttpExchange exchange) throws IOException {
        Map<String, Object> info;
        synchronized (lock) {
            info = session.info();
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        send(exchange, 200, toJson(info).getBytes(StandardCharsets.UTF_8));
    }

    private void handleEvent(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        byte[] png;
        synchronized (lock) {
            applyEvent(params);
            png = session.renderPng();
        }
        exchange.getResponseHeaders().add("Content-Type", "image/png");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        send(exchange, 200, png);
    }

    private void handleReset(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        synchronized (lock) {
            session.reset(params.getOrDefault("scenario", "default"));
        }
        handleInfo(exchange);
    }

    private void applyEvent(Map<String, String> params) {
        String type = params.getOrDefault("type", "");
        int x = parseInt(params.get("x"), 0);
        int y = parseInt(params.get("y"), 0);
        switch (type) {
            case "move" -> session.mouseMove(x, y);
            case "click" -> session.mouseClick(x, y, parseInt(params.get("button"), 0));
            case "release" -> session.mouseRelease(x, y, parseInt(params.get("button"), 0));
            case "scroll" -> session.scroll(x, y, parseDouble(params.get("delta"), 0));
            case "char" -> session.typeChar(parseInt(params.get("cp"), 0));
            case "text" -> session.typeText(params.getOrDefault("text", ""));
            case "key" -> session.keyPress(parseInt(params.get("code"), 0));
            default -> {
            }
        }
    }

    // ==================== 工具 ====================

    /**
     * 递归把 Map / List / 标量写成 JSON。
     *
     * <p>为什么不用 Gson：预览器刻意只依赖 JDK（和 {@code MiniJson} 同一原则）。
     * 但注意<b>必须递归</b> —— 早先的版本只处理顶层标量，把 {@code layout} 这种嵌套 Map
     * 直接 {@code String.valueOf(...)} 成了字符串，前端 {@code info.layout.tabs[0].centerX}
     * 取到的是 {@code undefined}，自动化脚本定位元素会静默失败。
     */
    private static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        appendJson(sb, value);
        return sb.toString();
    }

    private static void appendJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJsonString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                appendJson(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJson(sb, item);
            }
            sb.append(']');
        } else {
            appendJsonString(sb, String.valueOf(value));
        }
    }

    private static void appendJsonString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
