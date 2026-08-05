package com.klnon.sablepanel.panel.transport;

import com.google.gson.JsonObject;

/** Small invalidation notice; clients fetch the authoritative API snapshot after receiving it. */
public record PanelEvent(String serverId, long revision) {
    private static final int MAX_SERVER_ID_LENGTH = 256;

    public PanelEvent {
        if (serverId == null || serverId.isBlank() || serverId.length() > MAX_SERVER_ID_LENGTH) {
            throw new IllegalArgumentException("invalid event server id");
        }
        if (revision < 0) throw new IllegalArgumentException("invalid event revision");
    }

    public JsonObject toJson() {
        JsonObject meta = new JsonObject();
        meta.addProperty("server", this.serverId);
        meta.addProperty("revision", this.revision);
        return meta;
    }

    static PanelEvent fromMeta(JsonObject meta) {
        if (!meta.has("server") || !meta.has("revision")) {
            throw new IllegalArgumentException("event metadata incomplete");
        }
        return new PanelEvent(meta.get("server").getAsString(), meta.get("revision").getAsLong());
    }

    public PanelEvent fromServer(String authoritativeServerId) {
        return new PanelEvent(authoritativeServerId, this.revision);
    }
}
