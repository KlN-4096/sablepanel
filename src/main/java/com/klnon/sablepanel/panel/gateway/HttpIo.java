package com.klnon.sablepanel.panel.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    private static final int MAX_REQUEST_BODY = 1024 * 1024;
    /** 面板自身的 js/css:仅放行受限字符集的固定前缀路径,杜绝 ".." 与编码穿越 */
    static final java.util.regex.Pattern STATIC_ASSET =
            java.util.regex.Pattern.compile("/(?:css|js)(?:/[A-Za-z0-9_-]+)*/[A-Za-z0-9_-]+\\.(?:css|js)");

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
            byte[] body = input.readNBytes(MAX_REQUEST_BODY + 1);
            if (body.length > MAX_REQUEST_BODY) throw new IOException("请求体超过 1 MiB");
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

    /** cacheable 资源(three.min.js 669KB)的字节缓存:原始与 gzip 各一份,不再每请求读盘+实时压缩 */
    private static final java.util.concurrent.ConcurrentHashMap<String, byte[][]> CACHED =
            new java.util.concurrent.ConcurrentHashMap<>();

    static void sendResource(HttpExchange ex, String res, String type, boolean cacheable) throws IOException {
        if (cacheable) {
            byte[][] entry = CACHED.get(res);
            if (entry == null) {
                try (InputStream in = HttpIo.class.getResourceAsStream(res)) {
                    if (in == null) {
                        send(ex, 404, "text/plain", "resource missing".getBytes(StandardCharsets.UTF_8), false);
                        return;
                    }
                    byte[] raw = in.readAllBytes();
                    entry = new byte[][]{raw, gzip(raw)};
                    CACHED.putIfAbsent(res, entry);
                }
            }
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            sendPrebuilt(ex, type, entry[0], entry[1]);
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

    private static byte[] gzip(byte[] raw) throws IOException {
        var buffer = new java.io.ByteArrayOutputStream(Math.max(64, raw.length / 3));
        try (GZIPOutputStream gz = new GZIPOutputStream(buffer)) {
            gz.write(raw);
        }
        return buffer.toByteArray();
    }

    private static void sendPrebuilt(HttpExchange ex, String type, byte[] raw, byte[] gz) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (ae != null && ae.contains("gzip")) {
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            ex.getResponseHeaders().set("Vary", "Accept-Encoding");
            ex.sendResponseHeaders(200, gz.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(gz);
            }
            return;
        }
        ex.sendResponseHeaders(200, raw.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(raw);
        }
    }

    static void send(HttpExchange ex, int code, String type, byte[] body, boolean tryGzip) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (tryGzip && ae != null && ae.contains("gzip") && body.length > 1024) {
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
