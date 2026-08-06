package com.klnon.sablepanel.panel.web;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import com.klnon.sablepanel.panel.client.ClientPanelConfig;
import com.klnon.sablepanel.panel.transport.CertificatePinException;
import com.klnon.sablepanel.panel.transport.PanelEndpoint;
import com.klnon.sablepanel.panel.transport.PanelEvent;
import com.klnon.sablepanel.panel.transport.PanelNet;
import com.klnon.sablepanel.panel.transport.PanelTcpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PanelWebGateway implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PanelWebGateway.class);
    private static final int RECONNECT_SECONDS = 2;
    private static final long RECONNECT_LOG_INTERVAL_MS = 60_000L;
    private final boolean clientMode;
    private final String bind;
    private final int port;
    private final ClientPanelConfig clientConfig;
    private final PanelEndpoint fixedEndpoint;
    private final String fixedFingerprint;
    private volatile HttpServer http;
    private volatile ExecutorService httpPool;
    private volatile ExecutorService forwardPool;
    private volatile ScheduledExecutorService reconnectExecutor;
    private volatile PanelEventStreams eventStreams;
    private volatile PanelTcpClient upstream;
    private volatile PanelEndpoint endpoint;
    private volatile PanelEndpoint pendingEndpoint;
    private volatile String pendingFingerprint;
    private volatile boolean closed = true;
    private volatile long lastReconnectLogMs;
    private volatile String eventToken;
    private final AtomicBoolean reconnectPending = new AtomicBoolean();

    private PanelWebGateway(boolean clientMode, String bind, int port, ClientPanelConfig clientConfig,
                            PanelEndpoint fixedEndpoint, String fixedFingerprint) {
        this.clientMode = clientMode;
        this.bind = bind;
        this.port = port;
        this.clientConfig = clientConfig;
        this.fixedEndpoint = fixedEndpoint;
        this.fixedFingerprint = fixedFingerprint;
    }

    public static PanelWebGateway server(PanelConfig config, String fingerprint) {
        return new PanelWebGateway(false, config.webBind, config.webPort, null,
                new PanelEndpoint(localApiHost(config.apiBind), config.apiPort), fingerprint);
    }

    public static PanelWebGateway client(ClientPanelConfig config) {
        return new PanelWebGateway(true, "127.0.0.1", config.webPort, config, null, null);
    }

    public void start() throws Exception {
        synchronized (this) {
            if (!this.closed || this.http != null) throw new IllegalStateException("网页网关已启动");
            this.closed = false;
            this.reconnectPending.set(false);
            // 满了拒绝而不是 CallerRuns:后者把 HttpServer 的 dispatcher 线程拖进业务,
            // 公网上 68 个慢请求就能让整个端口停止接客
            this.httpPool = PanelNet.boundedPool("sablepanel-web", 4, 64);
            // 跨服转发单独一个池(bulkhead):一个卡住的远端不能占用本机自己的 HTTP 线程
            this.forwardPool = PanelNet.boundedPool("sablepanel-web-forward", 4, 32);
            this.eventStreams = new PanelEventStreams();
        }
        try {
            if (!this.clientMode) {
                try {
                    connect(this.fixedEndpoint, this.fixedFingerprint);
                } catch (Exception error) {
                    logReconnectFailure(error);
                }
            }
            HttpServer created = HttpServer.create(new InetSocketAddress(this.bind, this.port), 0);
            created.setExecutor(this.httpPool);
            created.createContext("/", this::handle);
            created.start();
            this.http = created;
            if (!this.clientMode) startReconnectLoop();
            LOGGER.info("sablepanel: {} web gateway at http://{}:{}/",
                    this.clientMode ? "client" : "server", this.bind, this.port);
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        boolean closeExchange = true;
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/vendor/three.min.js")) {
                HttpIo.sendResource(exchange, "/web/vendor/three.min.js", "text/javascript; charset=utf-8", true);
                return;
            }
            if (HttpIo.STATIC_ASSET.matcher(path).matches()) {
                HttpIo.sendResource(exchange, "/web" + path,
                        path.endsWith(".css") ? "text/css; charset=utf-8" : "text/javascript; charset=utf-8", false);
                return;
            }
            if (path.equals("/") || path.equals("/index.html")) {
                HttpIo.sendResource(exchange, "/web/index.html", "text/html; charset=utf-8", false);
                return;
            }
            if (path.equals("/gateway/state")) {
                sendState(exchange);
                return;
            }
            if (path.equals("/gateway/connect")) {
                HttpIo.requirePost(exchange);
                if (!allowLocalControlRequest(exchange)) return;
                connectClient(exchange);
                return;
            }
            if (path.equals("/gateway/disconnect")) {
                HttpIo.requirePost(exchange);
                if (!allowLocalControlRequest(exchange)) return;
                disconnectClient(exchange);
                return;
            }
            if (path.equals("/api/events")) {
                closeExchange = !openEventStream(exchange);
                return;
            }
            if (path.startsWith("/api/")) {
                closeExchange = !offloadApi(exchange);
                return;
            }
            sendError(exchange, 404, "not found");
        } catch (Exception error) {
            LOGGER.warn("sablepanel: web gateway error {}", exchange.getRequestURI(), error);
            sendError(exchange, 500, messageOf(error));
        } finally {
            if (closeExchange) exchange.close();
        }
    }

    private boolean openEventStream(HttpExchange exchange) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "需要 GET");
            return false;
        }
        PanelTcpClient client = this.upstream;
        if (client == null || !client.isActive()) {
            requestReconnect();
            sendError(exchange, 503, "尚未连接服务器");
            return false;
        }
        // 订阅等待不许拿实例锁:一个慢上游能把 connect/disconnect/close 全堵 10 秒
        String token = exchange.getRequestHeaders().getFirst("X-Token");
        PanelResponse response = client.subscribeEvents(token).get(10, TimeUnit.SECONDS);
        if (response.status() != 200) {
            HttpIo.send(exchange, response.status(), response.contentType(), response.body(), false);
            return false;
        }
        PanelEventStreams streams = adoptEventToken(client, token);
        if (streams == null) {
            sendError(exchange, 503, "服务器连接已变化");
            return false;
        }
        if (!streams.open(exchange)) {
            sendError(exchange, 503, "事件连接数已满");
            return false;
        }
        return true;
    }

    /** 订阅成功后的记账。短锁:只比对上游身份并交换 eventToken,不做任何网络等待 */
    private synchronized PanelEventStreams adoptEventToken(PanelTcpClient client, String token) {
        if (client != this.upstream || !client.isActive()) return null;
        PanelEventStreams streams = this.eventStreams;
        if (streams == null) return null;
        String previousToken = this.eventToken;
        if (previousToken != null && !previousToken.equals(token)) streams.closeStreams();
        this.eventToken = token;
        return streams;
    }

    /**
     * 带 {@code server=} 的跨服请求交给独立池,返回 true 表示 exchange 的关闭由那个池负责。
     * <p>
     * 转发要在上游同步等最多 30 秒。HTTP 池只有 4 个线程,一个卡住的远端服务器可以让本机
     * 自己的接口和网页请求一起排队 —— 快请求必须有自己的线程,不能和转发抢同一批。
     */
    private boolean offloadApi(HttpExchange exchange) throws Exception {
        Map<String, String> query = new java.util.HashMap<>(HttpIo.query(exchange.getRequestURI()));
        String target = query.remove("server");
        ExecutorService pool = this.forwardPool;
        if (target == null || target.isEmpty() || pool == null) {
            proxyApi(exchange, query, target);
            return false;
        }
        try {
            pool.execute(() -> {
                try {
                    proxyApi(exchange, query, target);
                } catch (Exception error) {
                    LOGGER.warn("sablepanel: cross-server proxy failed {}", exchange.getRequestURI(), error);
                    try {
                        sendError(exchange, 502, messageOf(error));
                    } catch (IOException ignored) {
                    }
                } finally {
                    exchange.close();
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            sendError(exchange, 503, "跨服请求排队已满,请稍后重试");
            return false;
        }
    }

    private void proxyApi(HttpExchange exchange, Map<String, String> query, String target) throws Exception {
        // 公网口最小前置:连 token 都不带的请求不读体、不占上游,直接 401(上游对空 token 同样 401)
        String token = exchange.getRequestHeaders().getFirst("X-Token");
        if (token == null || token.isBlank()) {
            sendError(exchange, 401, "token 无效");
            return;
        }
        PanelTcpClient client = this.upstream;
        if (client == null || !client.isActive()) {
            requestReconnect();
            sendError(exchange, 503, "尚未连接服务器");
            return;
        }
        byte[] body = "GET".equalsIgnoreCase(exchange.getRequestMethod()) ? new byte[0] : HttpIo.readBody(exchange);
        PanelRequest request = new PanelRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                query, body, token, target == null ? "" : target);
        PanelResponse response = client.request(request).get(PanelNet.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        HttpIo.send(exchange, response.status(), response.contentType(), response.body(),
                response.contentType().startsWith("application/json"));
    }

    private void sendState(HttpExchange exchange) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty("mode", this.clientMode ? "client" : "server");
        state.addProperty("connected", this.upstream != null && this.upstream.isActive());
        // 地址只回给客户端模式的本机页面(预填上次集群地址);服务端模式这是公网口,不泄露内部上游端点
        if (this.clientMode) {
            state.addProperty("address", this.endpoint != null ? this.endpoint.toString()
                    : this.clientConfig.lastAddress);
        }
        HttpIo.send(exchange, 200, "application/json", state.toString().getBytes(StandardCharsets.UTF_8), false);
    }

    private synchronized void connectClient(HttpExchange exchange) throws Exception {
        if (!this.clientMode) {
            sendError(exchange, 409, "服务端网关目标固定");
            return;
        }
        JsonObject body = HttpIo.readJsonBody(exchange);
        PanelEndpoint requested = PanelEndpoint.parse(body.has("address") ? body.get("address").getAsString() : "", 25581);
        if (this.upstream != null && this.upstream.isActive() && !requested.equals(this.endpoint)) {
            sendError(exchange, 409, "请先断开当前集群");
            return;
        }
        closeUpstream();
        String key = requested.toString();
        String accepted = body.has("accept_fingerprint") ? body.get("accept_fingerprint").getAsString() : "";
        if (!accepted.isBlank()) {
            if (!requested.equals(this.pendingEndpoint)
                    || this.pendingFingerprint == null
                    || !accepted.equalsIgnoreCase(this.pendingFingerprint)) {
                sendError(exchange, 409, "指纹确认已失效,请重新连接");
                return;
            }
        }
        String expected = accepted.isBlank() ? this.clientConfig.certificatePins.get(key) : accepted;
        try {
            connect(requested, expected);
            if (!accepted.isBlank()) this.clientConfig.certificatePins.put(key, accepted);
            this.clientConfig.lastAddress = key;
            this.clientConfig.save();
            clearPendingFingerprint();
            HttpIo.send(exchange, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), false);
        } catch (CertificatePinException pin) {
            this.pendingEndpoint = requested;
            this.pendingFingerprint = pin.fingerprint();
            JsonObject response = new JsonObject();
            response.addProperty("error", pin.changed() ? "certificate_changed" : "certificate_confirmation_required");
            response.addProperty("fingerprint", pin.fingerprint());
            HttpIo.send(exchange, 409, "application/json", response.toString().getBytes(StandardCharsets.UTF_8), false);
        }
    }

    private synchronized void disconnectClient(HttpExchange exchange) throws IOException {
        if (!this.clientMode) {
            sendError(exchange, 409, "服务端网关不能断开本机 API");
            return;
        }
        closeUpstream();
        clearPendingFingerprint();
        HttpIo.send(exchange, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), false);
    }

    private void connect(PanelEndpoint endpoint, String fingerprint) throws Exception {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw connectForFingerprint(endpoint);
        }
        PanelTcpClient connected = PanelTcpClient.connectManager(endpoint, fingerprint, this::publishEvent);
        PanelTcpClient previous;
        synchronized (this) {
            if (this.closed) {
                connected.close();
                throw new IllegalStateException("网页网关已关闭");
            }
            previous = this.upstream;
            this.upstream = connected;
            this.endpoint = endpoint;
        }
        if (previous != null && previous != connected) previous.close();
        restoreEventSubscription(connected);
    }

    private CertificatePinException connectForFingerprint(PanelEndpoint endpoint) throws Exception {
        try {
            PanelTcpClient.connectManager(endpoint, "").close();
            throw new IllegalStateException("未取得服务器证书");
        } catch (CertificatePinException pin) {
            return pin;
        }
    }

    @Override
    public void close() {
        HttpServer currentHttp;
        PanelTcpClient currentUpstream;
        ExecutorService currentHttpPool;
        ExecutorService currentForwardPool;
        ScheduledExecutorService currentReconnect;
        PanelEventStreams currentStreams;
        synchronized (this) {
            if (this.closed && this.http == null && this.httpPool == null && this.reconnectExecutor == null
                    && this.eventStreams == null) return;
            this.closed = true;
            currentHttp = this.http;
            currentUpstream = this.upstream;
            currentHttpPool = this.httpPool;
            currentForwardPool = this.forwardPool;
            currentReconnect = this.reconnectExecutor;
            currentStreams = this.eventStreams;
            this.http = null;
            this.upstream = null;
            this.endpoint = null;
            this.httpPool = null;
            this.forwardPool = null;
            this.reconnectExecutor = null;
            this.eventStreams = null;
            this.eventToken = null;
        }
        if (currentHttp != null) currentHttp.stop(0);
        if (currentStreams != null) currentStreams.close();
        PanelNet.shutdown(currentReconnect);
        if (currentUpstream != null) currentUpstream.close();
        PanelNet.shutdown(currentForwardPool);
        PanelNet.shutdown(currentHttpPool);
    }

    private void closeUpstream() {
        PanelTcpClient current;
        synchronized (this) {
            current = this.upstream;
            this.upstream = null;
            this.endpoint = null;
            this.eventToken = null;
        }
        if (current != null) current.close();
        PanelEventStreams streams = this.eventStreams;
        if (streams != null) streams.closeStreams();
    }

    private void startReconnectLoop() {
        ScheduledExecutorService reconnect = Executors.newSingleThreadScheduledExecutor(
                PanelNet.daemonThreads("sablepanel-web-reconnect"));
        synchronized (this) {
            if (this.closed) {
                reconnect.shutdownNow();
                return;
            }
            this.reconnectExecutor = reconnect;
        }
        reconnect.scheduleWithFixedDelay(this::ensureServerUpstream,
                RECONNECT_SECONDS, RECONNECT_SECONDS, TimeUnit.SECONDS);
    }

    private void requestReconnect() {
        if (this.clientMode || this.closed) return;
        ScheduledExecutorService reconnect = this.reconnectExecutor;
        if (reconnect == null || reconnect.isShutdown()) return;
        if (!this.reconnectPending.compareAndSet(false, true)) return;
        try {
            reconnect.execute(() -> {
                try {
                    ensureServerUpstream();
                } finally {
                    this.reconnectPending.set(false);
                }
            });
        } catch (RejectedExecutionException rejected) {
            this.reconnectPending.set(false);
        }
    }

    private void ensureServerUpstream() {
        if (this.clientMode || this.closed) return;
        PanelTcpClient current = this.upstream;
        if (current != null && current.isActive()) return;

        PanelTcpClient connected;
        try {
            connected = PanelTcpClient.connectManager(this.fixedEndpoint, this.fixedFingerprint, this::publishEvent);
        } catch (Exception error) {
            logReconnectFailure(error);
            return;
        }

        PanelTcpClient stale = null;
        boolean keep;
        synchronized (this) {
            keep = !this.closed && !this.clientMode
                    && (this.upstream == null || !this.upstream.isActive());
            if (keep) {
                stale = this.upstream;
                this.upstream = connected;
                this.endpoint = this.fixedEndpoint;
                this.lastReconnectLogMs = 0;
            }
        }
        if (!keep) {
            connected.close();
            return;
        }
        if (stale != null) stale.close();
        restoreEventSubscription(connected);
        LOGGER.info("sablepanel: server web gateway reconnected to {}", this.fixedEndpoint);
    }

    /** 只读 volatile 字段,不拿实例锁:10 秒的订阅等待不能挡住 connect/close */
    private void restoreEventSubscription(PanelTcpClient client) {
        String token = this.eventToken;
        if (token == null || token.isBlank()) return;
        try {
            PanelResponse response = client.subscribeEvents(token).get(10, TimeUnit.SECONDS);
            if (response.status() == 200) {
                PanelEventStreams streams = this.eventStreams;
                if (streams != null) streams.resync();
            }
        } catch (Exception error) {
            LOGGER.warn("sablepanel: event subscription restore failed: {}", messageOf(error));
        }
    }

    private void publishEvent(PanelEvent event) {
        PanelEventStreams streams = this.eventStreams;
        if (streams != null) streams.publish(event);
    }

    private void logReconnectFailure(Throwable error) {
        long now = System.currentTimeMillis();
        if (now - this.lastReconnectLogMs < RECONNECT_LOG_INTERVAL_MS) return;
        this.lastReconnectLogMs = now;
        LOGGER.warn("sablepanel: server web gateway upstream unavailable: {}", messageOf(error));
    }

    private boolean allowLocalControlRequest(HttpExchange exchange) throws IOException {
        if (!this.clientMode) return true;
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            sendError(exchange, 403, "网关控制请求来源无效");
            return false;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        URI value;
        try {
            value = URI.create(origin == null ? "" : origin);
        } catch (IllegalArgumentException error) {
            sendError(exchange, 403, "网关控制请求来源无效");
            return false;
        }
        String host = value.getHost();
        int originPort = value.getPort() < 0 ? 80 : value.getPort();
        boolean localHost = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        if (!"http".equalsIgnoreCase(value.getScheme()) || !localHost || originPort != this.port) {
            sendError(exchange, 403, "网关控制请求来源无效");
            return false;
        }
        return true;
    }

    private void clearPendingFingerprint() {
        this.pendingEndpoint = null;
        this.pendingFingerprint = null;
    }

    private static String localApiHost(String bind) {
        if ("0.0.0.0".equals(bind)) return "127.0.0.1";
        if ("::".equals(bind) || "[::]".equals(bind)) return "::1";
        return bind;
    }

    /** 错误响应统一走 PanelResponse.error 的 JSON 形状,和 TLS 侧同一张脸 */
    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        PanelResponse error = PanelResponse.error(status, message);
        HttpIo.send(exchange, error.status(), error.contentType(), error.body(), false);
    }

}
