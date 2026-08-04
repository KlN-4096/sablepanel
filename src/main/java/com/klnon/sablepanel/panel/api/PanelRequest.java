package com.klnon.sablepanel.panel.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public record PanelRequest(String method, String path, Map<String, String> query,
                           byte[] body, String token, String targetServer) {

    public PanelRequest {
        method = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
        path = path == null ? "/" : path;
        query = query == null ? Map.of() : Map.copyOf(query);
        body = body == null ? new byte[0] : body;
        token = token == null ? "" : token;
        targetServer = targetServer == null ? "" : targetServer;
    }

    public JsonObject jsonBody() {
        if (this.body.length == 0) return new JsonObject();
        return JsonParser.parseString(new String(this.body, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
