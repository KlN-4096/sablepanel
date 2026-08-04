package com.klnon.sablepanel.panel.web;

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
        return JsonParser.parseString(
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
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

    static void sendResource(HttpExchange ex, String res, String type, boolean cacheable) throws IOException {
        try (InputStream in = HttpIo.class.getResourceAsStream(res)) {
            if (in == null) {
                send(ex, 404, "text/plain", "resource missing".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            if (cacheable) {
                ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            }
            send(ex, 200, type, in.readAllBytes(), true);
        }
    }

    static void send(HttpExchange ex, int code, String type, byte[] body, boolean tryGzip) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (tryGzip && ae != null && ae.contains("gzip") && body.length > 1024) {
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            var buf = new java.io.ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(buf)) {
                gz.write(body);
            }
            body = buf.toByteArray();
        }
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
