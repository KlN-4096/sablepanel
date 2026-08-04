package com.klnon.sablepanel.panel.transport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.api.PanelApiService;
import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import net.neoforged.fml.loading.FMLPaths;

import java.util.concurrent.TimeUnit;

public final class PanelClusterNode implements AutoCloseable {
    public static final int HEARTBEAT_SECONDS = 15;

    private final PanelConfig config;
    private final PanelApiService api;
    private final TlsIdentity identity;
    private volatile PanelTcpServer host;
    private volatile PanelTcpClient peer;

    public PanelClusterNode(PanelConfig config, PanelApiService api) throws Exception {
        this.config = config;
        this.api = api;
        this.identity = TlsIdentity.loadOrCreate(FMLPaths.CONFIGDIR.get(), api.selfId());
    }

    public void start() throws Exception {
        if (!tryBecomeHost()) connectPeerQuietly();
    }

    /** 返回本轮是否刚晋升为 HOST。 */
    public boolean clusterTick() {
        if (this.host != null) return false;
        if (this.peer != null && this.peer.isActive()) return false;
        closePeer();
        if (tryBecomeHost()) return true;
        connectPeerQuietly();
        return false;
    }

    public PanelResponse handle(PanelRequest request) {
        if (!this.api.authorized(request.token())) return PanelResponse.error(401, "token 无效");
        try {
            if (request.path().equals("/api/servers")) return servers();
            if (request.path().equals("/api/cluster/token")) return changeToken(request);
            String target = request.targetServer();
            if (!target.isEmpty() && !target.equals(this.api.selfId())) {
                PanelTcpServer currentHost = this.host;
                if (currentHost == null) return PanelResponse.error(409, "当前节点不是 HOST");
                PanelRequest forwarded = new PanelRequest(request.method(), request.path(), request.query(),
                        request.body(), this.api.token(), "");
                return currentHost.requestPeer(target, forwarded).get(30, TimeUnit.SECONDS);
            }
            return this.api.dispatch(request);
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: cluster request failed {}", request.path(), error);
            return PanelResponse.error(502, messageOf(error));
        }
    }

    public boolean isHost() {
        return this.host != null;
    }

    public boolean isActive() {
        return this.api.isActive();
    }

    public TlsIdentity identity() {
        return this.identity;
    }

    @Override
    public void close() {
        closePeer();
        PanelTcpServer currentHost = this.host;
        this.host = null;
        if (currentHost != null) currentHost.close();
    }

    private boolean tryBecomeHost() {
        PanelTcpServer candidate = new PanelTcpServer(this.api.selfId(), this::handle, this.api::token);
        try {
            candidate.start(this.config.apiBind, this.config.apiPort, this.identity.serverContext());
            this.host = candidate;
            SablePanel.LOGGER.info("sablepanel: [{}] TLS API HOST at {}:{} (fingerprint {})",
                    this.api.selfId(), this.config.apiBind, this.config.apiPort, this.identity.fingerprint());
            return true;
        } catch (Exception bindFailed) {
            candidate.close();
            return false;
        }
    }

    private void connectPeer() throws Exception {
        this.peer = PanelTcpClient.connectPeer(new PanelEndpoint("127.0.0.1", this.config.apiPort),
                this.api.selfId(), this.api::dispatch, this::adoptToken);
        SablePanel.LOGGER.info("sablepanel: [{}] joined TLS API HOST on 127.0.0.1:{}",
                this.api.selfId(), this.config.apiPort);
    }

    private void connectPeerQuietly() {
        try {
            connectPeer();
        } catch (Exception ignored) {
        }
    }

    private PanelResponse changeToken(PanelRequest request) throws Exception {
        if (!"POST".equals(request.method())) return PanelResponse.error(500, "需要 POST");
        JsonObject body = request.jsonBody();
        String next = body.has("token") ? body.get("token").getAsString().trim() : "";
        if (next.isEmpty() || next.length() > 64 || !next.matches("[A-Za-z0-9._~-]+")) {
            return PanelResponse.error(400, "token 只能用字母、数字和 . - _ ~,长度 1~64");
        }
        JsonObject result = this.api.setToken(next);
        JsonArray failed = new JsonArray();
        PanelTcpServer currentHost = this.host;
        if (currentHost != null) {
            for (String id : currentHost.peerIds()) {
                try {
                    PanelResponse response = currentHost.updatePeerToken(id, next).get(10, TimeUnit.SECONDS);
                    if (response.status() != 200) {
                        failed.add(id);
                        currentHost.disconnectPeer(id);
                    }
                } catch (Exception error) {
                    failed.add(id);
                    currentHost.disconnectPeer(id);
                }
            }
        }
        if (!failed.isEmpty()) result.add("failed", failed);
        return PanelResponse.json(200, result, false);
    }

    private PanelResponse adoptToken(String authoritative) {
        try {
            if (!authoritative.equals(this.api.token())) this.api.setToken(authoritative);
            return PanelResponse.json(200, "{\"ok\":true}", false);
        } catch (Exception error) {
            return PanelResponse.error(500, messageOf(error));
        }
    }

    private PanelResponse servers() {
        JsonArray servers = new JsonArray();
        JsonObject self = new JsonObject();
        self.addProperty("id", this.api.selfId());
        self.addProperty("self", true);
        self.addProperty("host", true);
        servers.add(self);
        PanelTcpServer currentHost = this.host;
        if (currentHost != null) {
            for (String id : currentHost.peerIds()) {
                JsonObject peer = new JsonObject();
                peer.addProperty("id", id);
                peer.addProperty("self", false);
                peer.addProperty("host", false);
                servers.add(peer);
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("self", this.api.selfId());
        result.addProperty("using_default_token", this.api.usingDefaultToken());
        result.add("servers", servers);
        return PanelResponse.json(200, result, false);
    }

    private void closePeer() {
        PanelTcpClient current = this.peer;
        this.peer = null;
        if (current != null) current.close();
    }

    private static String messageOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }
}
