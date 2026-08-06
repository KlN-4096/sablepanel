package com.klnon.sablepanel.panel.api;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;

import java.nio.charset.StandardCharsets;

public record PanelResponse(int status, String contentType, byte[] body, boolean compressible) {

    /**
     * 最终响应的硬上限。留一档余量给 {@code PanelWire.MAX_FRAME_BYTES}(32 MiB)的帧头和 meta。
     * <p>
     * 各个构建点的字节预算是"内容目标",用来尽早停止构建;它们算的是自己那部分,
     * 管不到调用方在后面追加的字段(比如 {@code /api/bodies} 的 busy 和 reach),
     * 也管不到将来新增的端点。真正不可绕过的只有这一条:按已经序列化好的字节数判。
     */
    public static final int MAX_BODY_BYTES = 30 << 20;

    /**
     * 超限时返回小体积的 500,而不是缺字段的 200。
     * <p>
     * 从前是把大数组清空后仍以 200 发出,前端拿到一个没有 {@code total_bodies} 的壳,
     * 画出来是 "NaN 体 · Invalid Date",回收站更是直接显示"回收站为空" ——
     * 把"响应超限"误导成"没有数据",用户只会反复刷新,再把同样的压力重造一遍。
     */
    public static PanelResponse capped(String endpoint, PanelResponse response) {
        int size = response.body().length;
        if (size <= MAX_BODY_BYTES) return response;
        SablePanel.LOGGER.error("sablepanel: {} 响应 {} 字节超过最终上限 {},已改为 500;这是需要修的 bug",
                endpoint, size, MAX_BODY_BYTES);
        return error(500, "响应 " + size + " 字节超过服务器内部上限,详见服务端日志");
    }

    public static PanelResponse json(int status, JsonObject body, boolean compressible) {
        return new PanelResponse(status, "application/json",
                body.toString().getBytes(StandardCharsets.UTF_8), compressible);
    }

    public static PanelResponse json(int status, String json, boolean compressible) {
        return new PanelResponse(status, "application/json", json.getBytes(StandardCharsets.UTF_8), compressible);
    }

    public static PanelResponse error(int status, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        return json(status, error, false);
    }
}
