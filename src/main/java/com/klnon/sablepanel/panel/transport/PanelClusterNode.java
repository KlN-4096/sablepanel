package com.klnon.sablepanel.panel.transport;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.api.PanelApiService;
import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import net.neoforged.fml.loading.FMLPaths;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class PanelClusterNode implements AutoCloseable {
    public static final int HEARTBEAT_SECONDS = 15;

    private final PanelConfig config;
    private final PanelApiService api;
    private final TlsIdentity identity;
    private final Object lifecycleLock = new Object();
    private final Object tokenLock = new Object();
    private volatile PanelTcpServer host;
    private volatile PanelTcpClient peer;
    private final AtomicLong bodiesRevision = new AtomicLong();
    private volatile boolean closed;

    public PanelClusterNode(PanelConfig config, PanelApiService api) throws Exception {
        this.config = config;
        this.api = api;
        this.identity = TlsIdentity.loadOrCreate(FMLPaths.CONFIGDIR.get(), api.selfId());
    }

    public void start() throws Exception {
        synchronized (this.lifecycleLock) {
            ensureOpen();
            if (tryBecomeHostLocked()) return;
        }
        connectPeerQuietly();
    }

    /** 返回本轮是否刚晋升为 HOST。 */
    public boolean clusterTick() {
        PanelTcpServer staleHost;
        PanelTcpClient stalePeer;
        boolean peerActive;
        synchronized (this.lifecycleLock) {
            if (this.closed) return false;
            if (this.host != null && this.host.isActive()) return false;
            staleHost = this.host;
            this.host = null;
            peerActive = this.peer != null && this.peer.isActive();
            stalePeer = peerActive ? null : this.peer;
            if (!peerActive) this.peer = null;
        }
        if (staleHost != null) staleHost.close();
        if (peerActive) return false;
        if (stalePeer != null) stalePeer.close();
        synchronized (this.lifecycleLock) {
            if (this.closed) return false;
            if (tryBecomeHostLocked()) return true;
        }
        connectPeerQuietly();
        return false;
    }

    public PanelResponse handle(PanelRequest request) {
        if (this.closed) return PanelResponse.error(503, "面板节点已关闭");
        if (!this.api.authorized(request.token())) return PanelResponse.error(401, "token 无效");
        try {
            if (request.path().equals("/api/servers")) return servers();
            if (request.path().equals("/api/cluster/token")) return changeToken(request);
            String target = request.targetServer();
            if (!target.isEmpty() && !target.equals(this.api.selfId())) {
                PanelTcpServer currentHost = this.host;
                if (currentHost == null || !currentHost.isActive()) {
                    return PanelResponse.error(409, "当前节点不是 HOST");
                }
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
        PanelTcpServer current = this.host;
        return !this.closed && current != null && current.isActive();
    }

    public boolean isActive() {
        return this.api.isActive();
    }

    public void publishBodiesChanged(long revision) {
        if (this.closed) return;
        long latestRevision = this.bodiesRevision.accumulateAndGet(revision, Math::max);
        PanelEvent event = new PanelEvent(this.api.selfId(), latestRevision);
        PanelTcpServer currentHost = this.host;
        if (currentHost != null && currentHost.isActive()) {
            currentHost.publishEvent(event);
            return;
        }
        PanelTcpClient currentPeer = this.peer;
        if (currentPeer != null && currentPeer.isActive()) currentPeer.publishEvent(event);
    }

    public TlsIdentity identity() {
        return this.identity;
    }

    @Override
    public void close() {
        PanelTcpServer currentHost;
        PanelTcpClient currentPeer;
        synchronized (this.lifecycleLock) {
            if (this.closed) return;
            this.closed = true;
            currentHost = this.host;
            currentPeer = this.peer;
            this.host = null;
            this.peer = null;
        }
        if (currentPeer != null) currentPeer.close();
        if (currentHost != null) currentHost.close();
    }

    private boolean tryBecomeHostLocked() {
        PanelTcpServer candidate = new PanelTcpServer(this.api.selfId(), this::handle, this::clusterToken);
        candidate.setPeerEvents(this::forwardPeerEvent);
        try {
            candidate.start(this.config.apiBind, this.config.apiPort, this.identity.serverContext());
            if (this.closed) {
                candidate.close();
                return false;
            }
            this.host = candidate;
            SablePanel.LOGGER.info("sablepanel: [{}] TLS API HOST at {}:{} (fingerprint {})",
                    this.api.selfId(), this.config.apiBind, this.config.apiPort, this.identity.fingerprint());
            return true;
        } catch (Exception bindFailed) {
            candidate.close();
            return false;
        }
    }

    private void connectPeerQuietly() {
        synchronized (this.lifecycleLock) {
            if (this.closed || isHostLocked() || this.peer != null && this.peer.isActive()) return;
        }
        PanelTcpClient connected;
        try {
            connected = PanelTcpClient.connectPeer(new PanelEndpoint("127.0.0.1", this.config.apiPort),
                    this.api.selfId(), this.api::dispatch, this::adoptToken);
        } catch (Exception ignored) {
            return;
        }
        boolean keep;
        synchronized (this.lifecycleLock) {
            keep = !this.closed && !isHostLocked() && (this.peer == null || !this.peer.isActive());
            if (keep) this.peer = connected;
        }
        if (!keep) {
            connected.close();
            return;
        }
        SablePanel.LOGGER.info("sablepanel: [{}] joined TLS API HOST on 127.0.0.1:{}",
                this.api.selfId(), this.config.apiPort);
        connected.publishEvent(new PanelEvent(this.api.selfId(), this.bodiesRevision.get()));
    }

    private void forwardPeerEvent(String peerId, PanelEvent event) {
        PanelTcpServer currentHost = this.host;
        if (currentHost != null && currentHost.isActive()) {
            currentHost.publishEvent(event.fromServer(peerId));
        }
    }

    private PanelResponse changeToken(PanelRequest request) throws Exception {
        if (!"POST".equals(request.method())) return PanelResponse.error(500, "需要 POST");
        JsonObject body = request.jsonBody();
        String next = body.has("token") ? body.get("token").getAsString().trim() : "";
        if (next.isEmpty() || next.length() > 64 || !next.matches("[A-Za-z0-9._~-]+")) {
            return PanelResponse.error(400, "token 只能用字母、数字和 . - _ ~,长度 1~64");
        }
        synchronized (this.tokenLock) {
            JsonObject result = this.api.setToken(next);
            JsonArray failed = new JsonArray();
            PanelTcpServer currentHost = this.host;
            if (currentHost != null && currentHost.isActive()) {
                currentHost.revokeEventSubscriptions();
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
    }

    private PanelResponse adoptToken(String authoritative) {
        synchronized (this.tokenLock) {
            try {
                if (!authoritative.equals(this.api.token())) this.api.setToken(authoritative);
                return PanelResponse.json(200, "{\"ok\":true}", false);
            } catch (Exception error) {
                return PanelResponse.error(500, messageOf(error));
            }
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
        if (currentHost != null && currentHost.isActive()) {
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

    private boolean isHostLocked() {
        return this.host != null && this.host.isActive();
    }

    private String clusterToken() {
        synchronized (this.tokenLock) {
            return this.api.token();
        }
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("面板节点已关闭");
    }

}
