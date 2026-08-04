package com.klnon.sablepanel.panel.api;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;

public record PanelResponse(int status, String contentType, byte[] body, boolean compressible) {

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
