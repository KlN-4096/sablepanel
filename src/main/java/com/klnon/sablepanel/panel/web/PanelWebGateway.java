package com.klnon.sablepanel.panel.web;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import com.klnon.sablepanel.panel.client.ClientPanelConfig;
import com.klnon.sablepanel.panel.transport.CertificatePinException;
import com.klnon.sablepanel.panel.transport.PanelEndpoint;
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
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PanelWebGateway implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PanelWebGateway.class);
    private final boolean clientMode;
    private final String bind;
    private final int port;
    private final ClientPanelConfig clientConfig;
    private final PanelEndpoint fixedEndpoint;
    private final String fixedFingerprint;
    private volatile HttpServer http;
    private volatile ExecutorService httpPool;
    private volatile PanelTcpClient upstream;
    private volatile PanelEndpoint endpoint;
    private volatile PanelEndpoint pendingEndpoint;
    private volatile String pendingFingerprint;

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
        this.httpPool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "sablepanel-web");
            thread.setDaemon(true);
            return thread;
        });
        if (!this.clientMode) connect(this.fixedEndpoint, this.fixedFingerprint);
        this.http = HttpServer.create(new InetSocketAddress(this.bind, this.port), 0);
        this.http.setExecutor(this.httpPool);
        this.http.createContext("/", this::handle);
        this.http.start();
        LOGGER.info("sablepanel: {} web gateway at http://{}:{}/",
                this.clientMode ? "client" : "server", this.bind, this.port);
    }

    private void handle(HttpExchange exchange) throws IOException {
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
            if (path.startsWith("/api/")) {
                proxyApi(exchange);
                return;
            }
            HttpIo.send(exchange, 404, "application/json", "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8), false);
        } catch (Exception error) {
            LOGGER.warn("sablepanel: web gateway error {}", exchange.getRequestURI(), error);
            HttpIo.send(exchange, 500, "application/json", errorJson(messageOf(error)), false);
        } finally {
            exchange.close();
        }
    }

    private void proxyApi(HttpExchange exchange) throws Exception {
        PanelTcpClient client = this.upstream;
        if (client == null || !client.isActive()) {
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
        this.upstream = PanelTcpClient.connectManager(endpoint, fingerprint);
        this.endpoint = endpoint;
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
    public synchronized void close() {
        if (this.http != null) this.http.stop(0);
        this.http = null;
        closeUpstream();
        if (this.httpPool != null) this.httpPool.shutdownNow();
        this.httpPool = null;
    }

    private void closeUpstream() {
        PanelTcpClient current = this.upstream;
        this.upstream = null;
        this.endpoint = null;
        if (current != null) current.close();
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
