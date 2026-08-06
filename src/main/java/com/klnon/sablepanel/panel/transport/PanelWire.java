package com.klnon.sablepanel.panel.transport;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class PanelWire {
    static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;
    /**
     * 单个响应体的硬上限,给帧头和 meta 留一档余量。
     * <p>
     * 这是唯一不可绕过的那道。两边的 {@code sendResponse} 全部经过
     * {@link #response(long, PanelResponse)}:集群节点自己应答的 {@code /api/servers} 和
     * {@code /api/cluster/token}、跨服转发、401、以及各处 catch 出来的 500 都在内。
     * 各构建点的字节预算只是"内容目标",算的是自己那一部分——管不到调用方随后追加的字段,
     * 也管不到将来新增的端点,所以那些都不能当成上限。
     * <p>
     * 判的是压缩前的字节数:接收侧 gunzip 同样按 {@link #MAX_FRAME_BYTES} 封顶,
     * 只看压缩后的大小,一份压得动的巨型响应会改在对端解压时炸掉。
     */
    static final int MAX_BODY_BYTES = 30 * 1024 * 1024;
    static final int MAX_REQUEST_BODY = 1024 * 1024;
    static final int MAX_META_BYTES = 64 * 1024;
    static final int FRAME_HEADER_BYTES = 13;
    static final int LENGTH_FIELD_BYTES = 4;
    static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    static final int HANDSHAKE_TIMEOUT_SECONDS = 10;
    private static final int COMPRESS_AFTER = 1024;

    private PanelWire() {
    }

    static PanelFrame request(long id, PanelRequest request) {
        if (request.body().length > MAX_REQUEST_BODY) {
            throw new IllegalArgumentException("request body too large");
        }
        JsonObject meta = new JsonObject();
        meta.addProperty("method", request.method());
        meta.addProperty("path", request.path());
        meta.addProperty("token", request.token());
        if (!request.targetServer().isEmpty()) meta.addProperty("target", request.targetServer());
        JsonObject query = new JsonObject();
        request.query().forEach(query::addProperty);
        meta.add("query", query);
        return new PanelFrame(PanelFrame.REQUEST, id, meta, request.body());
    }

    static PanelRequest request(PanelFrame frame) {
        if (frame.body().length > MAX_REQUEST_BODY) throw new IllegalArgumentException("request body too large");
        JsonObject meta = frame.meta();
        Map<String, String> query = new HashMap<>();
        if (meta.has("query")) {
            meta.getAsJsonObject("query").entrySet().forEach(entry -> query.put(entry.getKey(), entry.getValue().getAsString()));
        }
        return new PanelRequest(string(meta, "method"), string(meta, "path"), query, frame.body(),
                string(meta, "token"), string(meta, "target"));
    }

    static PanelFrame response(long id, PanelResponse response) throws IOException {
        PanelResponse capped = cap(response);
        byte[] body = capped.body();
        boolean compressed = capped.compressible() && body.length > COMPRESS_AFTER;
        if (compressed) body = gzip(body);
        JsonObject meta = new JsonObject();
        meta.addProperty("status", capped.status());
        meta.addProperty("content_type", capped.contentType());
        meta.addProperty("gzip", compressed);
        int metaBytes = meta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (LENGTH_FIELD_BYTES + metaBytes + (long) FRAME_HEADER_BYTES + body.length > MAX_FRAME_BYTES) {
            throw new IOException("response frame too large");
        }
        return new PanelFrame(PanelFrame.RESPONSE, id, meta, body);
    }

    /**
     * 超限时换成小体积的 500,而不是缺字段的 200,也不是把连接断掉。
     * <p>
     * 从前这里抛 IOException,调用方只剩 {@code context.close()} 一条路:面板表现为请求莫名
     * 其妙没了、连接重连,用户看不出是"响应太大",通常的反应是继续刷新,再把同样的压力造一遍。
     * 走到这儿就说明某个端点漏了记账,所以日志是 error 级。
     */
    private static PanelResponse cap(PanelResponse response) {
        int size = response.body().length;
        if (size <= MAX_BODY_BYTES) return response;
        SablePanel.LOGGER.error("sablepanel: {} 响应 {} 字节超过帧上限 {},已改为 500;这是需要修的 bug",
                response.status(), size, MAX_BODY_BYTES);
        return PanelResponse.error(500, "响应 " + size + " 字节超过服务器内部上限,详见服务端日志");
    }

    /** 响应出口的唯一收口:null 兜底 500;响应帧自己编不出去时只剩断连一条路 */
    static void sendResponse(io.netty.channel.ChannelHandlerContext context, long id, PanelResponse response) {
        try {
            context.writeAndFlush(response(id,
                    response != null ? response : PanelResponse.error(500, "请求处理未返回响应")));
        } catch (Exception error) {
            context.close();
        }
    }

    static PanelResponse response(PanelFrame frame) throws IOException {
        JsonObject meta = frame.meta();
        byte[] body = frame.body();
        if (meta.has("gzip") && meta.get("gzip").getAsBoolean()) body = gunzip(body);
        if (body.length > MAX_FRAME_BYTES) throw new IOException("response body too large");
        return new PanelResponse(meta.get("status").getAsInt(), string(meta, "content_type"), body, false);
    }

    static PanelFrame eventSubscribe(long id, String token) {
        JsonObject meta = new JsonObject();
        meta.addProperty("token", token == null ? "" : token);
        return new PanelFrame(PanelFrame.EVENT_SUBSCRIBE, id, meta, new byte[0]);
    }

    static PanelFrame event(PanelEvent event) {
        return new PanelFrame(PanelFrame.EVENT, 0, event.toJson(), new byte[0]);
    }

    static PanelEvent event(PanelFrame frame) {
        if (frame.type() != PanelFrame.EVENT || frame.body().length != 0) {
            throw new IllegalArgumentException("invalid event frame");
        }
        return PanelEvent.fromMeta(frame.meta());
    }

    private static String string(JsonObject value, String key) {
        return value.has(key) ? value.get(key).getAsString() : "";
    }

    private static byte[] gzip(byte[] value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value);
        }
        return output.toByteArray();
    }

    private static byte[] gunzip(byte[] value) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(value))) {
            byte[] decoded = input.readNBytes(MAX_FRAME_BYTES + 1);
            if (decoded.length > MAX_FRAME_BYTES) throw new IOException("response body too large");
            return decoded;
        }
    }
}
