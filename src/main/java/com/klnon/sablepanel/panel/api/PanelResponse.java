package com.klnon.sablepanel.panel.api;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public record PanelResponse(int status, String contentType, byte[] body, boolean compressible,
                            Map<String, String> headers) {

    public static final String BODIES_SNAPSHOT_HEADER = "X-SablePanel-Bodies-Snapshot";

    public PanelResponse(int status, String contentType, byte[] body, boolean compressible) {
        this(status, contentType, body, compressible, Map.of());
    }

    public PanelResponse {
        contentType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        body = body == null ? new byte[0] : body;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    private static final byte[] OK_BODY = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

    /** 纯确认响应 {"ok":true}:四个端点共用这一份,别再各自手拼字符串(每次新建,body 不共享) */
    public static PanelResponse ok() {
        return new PanelResponse(200, "application/json", OK_BODY.clone(), false);
    }
    /** 每次都要现算的接口共用的响应头(4 处同款 Map.of) */
    public static final Map<String, String> NO_STORE = Map.of("Cache-Control", "no-store");

    public static PanelResponse json(int status, JsonObject body, boolean compressible) {
        return new PanelResponse(status, "application/json",
                body.toString().getBytes(StandardCharsets.UTF_8), compressible);
    }

    public static PanelResponse error(int status, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        return json(status, error, false);
    }

    /** 全仓统一的异常文案提取:钻到根因取 message。从前 7 个类各写一份、3 处内联,语义还不一致 */
    public static String messageOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }
}
