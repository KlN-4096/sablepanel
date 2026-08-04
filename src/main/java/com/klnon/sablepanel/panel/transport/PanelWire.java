package com.klnon.sablepanel.panel.transport;

import com.google.gson.JsonObject;
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
    static final int MAX_REQUEST_BODY = 1024 * 1024;
    private static final int COMPRESS_AFTER = 1024;

    private PanelWire() {
    }

    static PanelFrame request(long id, PanelRequest request) {
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
        byte[] body = response.body();
        boolean compressed = response.compressible() && body.length > COMPRESS_AFTER;
        if (compressed) body = gzip(body);
        JsonObject meta = new JsonObject();
        meta.addProperty("status", response.status());
        meta.addProperty("content_type", response.contentType());
        meta.addProperty("gzip", compressed);
        return new PanelFrame(PanelFrame.RESPONSE, id, meta, body);
    }

    static PanelResponse response(PanelFrame frame) throws IOException {
        JsonObject meta = frame.meta();
        byte[] body = frame.body();
        if (meta.has("gzip") && meta.get("gzip").getAsBoolean()) body = gunzip(body);
        if (body.length > MAX_FRAME_BYTES) throw new IOException("response body too large");
        return new PanelResponse(meta.get("status").getAsInt(), string(meta, "content_type"), body, false);
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
