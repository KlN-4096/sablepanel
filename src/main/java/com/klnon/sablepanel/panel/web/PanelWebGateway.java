package com.klnon.sablepanel.panel.web;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import com.klnon.sablepanel.panel.client.ClientPanelConfig;
import com.klnon.sablepanel.panel.transport.CertificatePinException;
import com.klnon.sablepanel.panel.transport.PanelEndpoint;
import com.klnon.sablepanel.panel.transport.PanelEvent;
import com.klnon.sablepanel.panel.transport.PanelTcpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PanelWebGateway implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PanelWebGateway.class);
    private static final int HTTP_THREADS = 4;
    private static final int HTTP_QUEUE_CAPACITY = 64;
    private static final int RECONNECT_SECONDS = 2;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final long RECONNECT_LOG_INTERVAL_MS = 60_000L;
    private static final AtomicLong HTTP_THREAD_IDS = new AtomicLong();
    private final boolean clientMode;
    private final String bind;
    private final int port;
    private final ClientPanelConfig clientConfig;
    private final PanelEndpoint fixedEndpoint;
    private final String fixedFingerprint;
    private volatile HttpServer http;
    private volatile ExecutorService httpPool;
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
            this.httpPool = new ThreadPoolExecutor(
                    HTTP_THREADS, HTTP_THREADS, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(HTTP_QUEUE_CAPACITY), runnable -> {
                        Thread thread = new Thread(runnable,
                                "sablepanel-web-" + HTTP_THREAD_IDS.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.CallerRunsPolicy());
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
                proxyApi(exchange);
                return;
            }
            HttpIo.send(exchange, 404, "application/json", "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8), false);
        } catch (Exception error) {
            LOGGER.warn("sablepanel: web gateway error {}", exchange.getRequestURI(), error);
            HttpIo.send(exchange, 500, "application/json", errorJson(messageOf(error)), false);
        } finally {
            if (closeExchange) exchange.close();
        }
    }

    private synchronized boolean openEventStream(HttpExchange exchange) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpIo.send(exchange, 405, "application/json", errorJson("需要 GET"), false);
            return false;
        }
        PanelTcpClient client = this.upstream;
        if (client == null || !client.isActive()) {
            requestReconnect();
            HttpIo.send(exchange, 503, "application/json", errorJson("尚未连接服务器"), false);
            return false;
        }
        String token = exchange.getRequestHeaders().getFirst("X-Token");
        PanelResponse response = client.subscribeEvents(token).get(10, TimeUnit.SECONDS);
        if (response.status() != 200) {
            HttpIo.send(exchange, response.status(), response.contentType(), response.body(), false);
            return false;
        }
        if (client != this.upstream || !client.isActive()) {
            HttpIo.send(exchange, 503, "application/json", errorJson("服务器连接已变化"), false);
            return false;
        }
        PanelEventStreams streams = this.eventStreams;
        if (streams == null) {
            HttpIo.send(exchange, 503, "application/json", errorJson("事件服务不可用"), false);
            return false;
        }
        String previousToken = this.eventToken;
        if (previousToken != null && !previousToken.equals(token)) streams.closeStreams();
        this.eventToken = token;
        if (!streams.open(exchange)) {
            HttpIo.send(exchange, 503, "application/json", errorJson("事件连接数已满"), false);
            return false;
        }
        return true;
    }

    private void proxyApi(HttpExchange exchange) throws Exception {
        PanelTcpClient client = this.upstream;
        if (client == null || !client.isActive()) {
            requestReconnect();
            HttpIo.send(exchange, 503, "application/json", errorJson("尚未连接服务器"), false);
            return;
        }
        Map<String, String> query = new java.util.HashMap<>(HttpIo.query(exchange.getRequestURI()));
        String target = query.remove("server");
        String token = exchange.getRequestHeaders().getFirst("X-Token");
        byte[] body = "GET".equalsIgnoreCase(exchange.getRequestMethod()) ? new byte[0] : HttpIo.readBody(exchange);
        PanelRequest request = new PanelRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                query, body, token, target);
        PanelResponse response = client.request(request).get(30, TimeUnit.SECONDS);
        HttpIo.send(exchange, response.status(), response.contentType(), response.body(),
                response.contentType().startsWith("application/json"));
    }

    private void sendState(HttpExchange exchange) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty("mode", this.clientMode ? "client" : "server");
        state.addProperty("connected", this.upstream != null && this.upstream.isActive());
        String address = this.endpoint != null ? this.endpoint.toString()
                : this.clientConfig != null ? this.clientConfig.lastAddress : "";
        state.addProperty("address", address);
        HttpIo.send(exchange, 200, "application/json", state.toString().getBytes(StandardCharsets.UTF_8), false);
    }

    private synchronized void connectClient(HttpExchange exchange) throws Exception {
        if (!this.clientMode) {
            HttpIo.send(exchange, 409, "application/json", errorJson("服务端网关目标固定"), false);
            return;
        }
        JsonObject body = HttpIo.readJsonBody(exchange);
        PanelEndpoint requested = PanelEndpoint.parse(body.has("address") ? body.get("address").getAsString() : "", 25581);
        if (this.upstream != null && this.upstream.isActive() && !requested.equals(this.endpoint)) {
            HttpIo.send(exchange, 409, "application/json", errorJson("请先断开当前集群"), false);
            return;
        }
        closeUpstream();
        String key = requested.toString();
        String accepted = body.has("accept_fingerprint") ? body.get("accept_fingerprint").getAsString() : "";
        if (!accepted.isBlank()) {
            if (!requested.equals(this.pendingEndpoint)
                    || this.pendingFingerprint == null
                    || !accepted.equalsIgnoreCase(this.pendingFingerprint)) {
                HttpIo.send(exchange, 409, "application/json", errorJson("指纹确认已失效,请重新连接"), false);
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
            HttpIo.send(exchange, 409, "application/json", errorJson("服务端网关不能断开本机 API"), false);
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
        ScheduledExecutorService currentReconnect;
        PanelEventStreams currentStreams;
        synchronized (this) {
            if (this.closed && this.http == null && this.httpPool == null && this.reconnectExecutor == null
                    && this.eventStreams == null) return;
            this.closed = true;
            currentHttp = this.http;
            currentUpstream = this.upstream;
            currentHttpPool = this.httpPool;
            currentReconnect = this.reconnectExecutor;
            currentStreams = this.eventStreams;
            this.http = null;
            this.upstream = null;
            this.endpoint = null;
            this.httpPool = null;
            this.reconnectExecutor = null;
            this.eventStreams = null;
            this.eventToken = null;
        }
        if (currentHttp != null) currentHttp.stop(0);
        if (currentStreams != null) currentStreams.close();
        shutdownExecutor(currentReconnect);
        if (currentUpstream != null) currentUpstream.close();
        shutdownExecutor(currentHttpPool);
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
        ScheduledExecutorService reconnect = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sablepanel-web-reconnect");
            thread.setDaemon(true);
            return thread;
        });
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

    private synchronized void restoreEventSubscription(PanelTcpClient client) {
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

    private static void shutdownExecutor(ExecutorService executor) {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean allowLocalControlRequest(HttpExchange exchange) throws IOException {
        if (!this.clientMode) return true;
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            HttpIo.send(exchange, 403, "application/json", errorJson("网关控制请求来源无效"), false);
            return false;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        URI value;
        try {
            value = URI.create(origin == null ? "" : origin);
        } catch (IllegalArgumentException error) {
            HttpIo.send(exchange, 403, "application/json", errorJson("网关控制请求来源无效"), false);
            return false;
        }
        String host = value.getHost();
        int originPort = value.getPort() < 0 ? 80 : value.getPort();
        boolean localHost = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        if (!"http".equalsIgnoreCase(value.getScheme()) || !localHost || originPort != this.port) {
            HttpIo.send(exchange, 403, "application/json", errorJson("网关控制请求来源无效"), false);
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

    private static byte[] errorJson(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        return error.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String messageOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }
}
