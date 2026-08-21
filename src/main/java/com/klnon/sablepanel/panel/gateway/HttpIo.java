package com.klnon.sablepanel.panel.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klnon.sablepanel.panel.transport.PanelNet;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** web 层公共 IO:请求解析与响应发送(无业务状态,纯静态工具) */
final class HttpIo {
    /** 面板自身的 js/css/字体/图片:仅放行受限字符集的固定前缀路径,杜绝 ".." 与编码穿越 */
    static final java.util.regex.Pattern STATIC_ASSET =
            java.util.regex.Pattern.compile("/(?:css|js|fonts|img)(?:/[A-Za-z0-9_-]+)*/[A-Za-z0-9_-]+\\.(?:css|js|ttf|png)");

    private HttpIo() {
    }

    static void requirePost(HttpExchange ex) {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            throw new IllegalArgumentException("需要 POST");
        }
    }

    static JsonObject readJsonBody(HttpExchange ex) throws IOException {
        return JsonParser.parseString(new String(readBody(ex), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream input = ex.getRequestBody()) {
            byte[] body = input.readNBytes(PanelNet.MAX_REQUEST_BODY + 1);
            if (body.length > PanelNet.MAX_REQUEST_BODY) throw new IOException("请求体超过 1 MiB");
            return body;
        }
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> map = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null) return map;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                map.put(java.net.URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    static boolean acceptsGzip(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        return value != null && value.contains("gzip");
    }

    /** cacheable 资源(three.min.js 669KB)的字节缓存:原始与 gzip 各一份,不再每请求读盘+实时压缩 */
    private record Asset(byte[] raw, byte[] gz) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Asset> CACHED =
            new java.util.concurrent.ConcurrentHashMap<>();

    static void sendResource(HttpExchange ex, String res, String type, boolean cacheable) throws IOException {
        if (cacheable) {
            Asset asset;
            try {
                // 映射函数返回 null 即不入表:资源缺失照旧每次 404,不缓存否定结果
                asset = CACHED.computeIfAbsent(res, key -> {
                    try (InputStream in = HttpIo.class.getResourceAsStream(key)) {
                        if (in == null) return null;
                        byte[] raw = in.readAllBytes();
                        return new Asset(raw, PanelNet.gzip(raw));
                    } catch (IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                });
            } catch (java.io.UncheckedIOException error) {
                throw error.getCause();
            }
            if (asset == null) {
                send(ex, 404, "text/plain", "resource missing".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            send(ex, 200, type, asset.raw(), false, Map.of(), asset.gz());
            return;
        }
        try (InputStream in = HttpIo.class.getResourceAsStream(res)) {
            if (in == null) {
                send(ex, 404, "text/plain", "resource missing".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            // 面板自身页面与 js/css:禁止启发式缓存,否则 mod 更新后浏览器可能继续用旧前端
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, 200, type, in.readAllBytes(), true);
        }
    }

    static void send(HttpExchange ex, int code, String type, byte[] body, boolean tryGzip) throws IOException {
        send(ex, code, type, body, tryGzip, Map.of());
    }

    static void send(HttpExchange ex, int code, String type, byte[] body, boolean tryGzip,
                     Map<String, String> headers) throws IOException {
        send(ex, code, type, body, tryGzip, headers, null);
    }

    /** 唯一发送路径。{@code preGz} 非空 = 预压字节可用(对端接受即发,精确 Content-Length,无阈值)。 */
    static void send(HttpExchange ex, int code, String type, byte[] body, boolean tryGzip,
                     Map<String, String> headers, byte[] preGz) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        headers.forEach((key, value) -> ex.getResponseHeaders().set(key, value));
        boolean acceptsGzip = acceptsGzip(ex);
        if (acceptsGzip && preGz != null) {
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            ex.getResponseHeaders().set("Vary", "Accept-Encoding");
            ex.sendResponseHeaders(code, preGz.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(preGz);
            }
            return;
        }
        if (tryGzip && acceptsGzip && body.length > 1024) {
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            ex.getResponseHeaders().set("Vary", "Accept-Encoding");
            ex.sendResponseHeaders(code, 0);
            try (OutputStream output = ex.getResponseBody();
                 GZIPOutputStream gz = new GZIPOutputStream(output)) {
                gz.write(body);
            }
            return;
        }
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
