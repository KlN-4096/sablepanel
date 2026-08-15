package com.klnon.sablepanel.panel.gateway;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import com.klnon.sablepanel.panel.client.ClientPanelConfig;
import com.klnon.sablepanel.panel.transport.PanelEndpoint;
import com.klnon.sablepanel.panel.transport.PanelEvent;
import com.klnon.sablepanel.panel.transport.PanelNet;
import com.klnon.sablepanel.panel.transport.PanelTcpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 网页网关本体:HTTP 路由/静态资源/SSE 桥。上游 TLS 连接的生命周期在 {@link UpstreamConnection}。 */
public final class PanelWebGateway implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PanelWebGateway.class);
    private static final String CLIENT_MODE = "client";
    private static final String SERVER_MODE = "server";
    private static final String YIELD_HEADER = "X-SablePanel-Gateway-Yield";
    private static final String YIELD_VALUE = "server";
    private static final Duration LOCAL_REQUEST_TIMEOUT = Duration.ofMillis(500);
    private static final long TAKEOVER_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long TAKEOVER_RETRY_MILLIS = 25;
    private static final HttpClient LOCAL_HTTP = HttpClient.newBuilder()
            .connectTimeout(LOCAL_REQUEST_TIMEOUT).build();
    private final boolean clientMode;
    private final String bind;
    private final int port;
    private final UpstreamConnection connection;
    private volatile HttpServer http;
    private volatile ExecutorService httpPool;
    private volatile ExecutorService forwardPool;
    private volatile PanelEventStreams eventStreams;
    private volatile boolean closed = true;
    private final LatestGzip bodiesGzip = new LatestGzip();

    static final class LatestGzip {
        private String snapshot = "";
        private byte[] gzip = new byte[0];

        synchronized byte[] get(String nextSnapshot, byte[] raw) throws IOException {
            if (nextSnapshot.equals(this.snapshot)) return this.gzip;
            byte[] compressed = PanelNet.gzip(raw);
            this.snapshot = nextSnapshot;
            this.gzip = compressed;
            return compressed;
        }

        synchronized void clear() {
            this.snapshot = "";
            this.gzip = new byte[0];
        }
    }

    private PanelWebGateway(boolean clientMode, String bind, int port, ClientPanelConfig clientConfig,
                            PanelEndpoint fixedEndpoint, String fixedFingerprint) {
        this.clientMode = clientMode;
        this.bind = bind;
        this.port = port;
        this.connection = new UpstreamConnection(clientMode, fixedEndpoint, fixedFingerprint,
                clientConfig, this::publishEvent, () -> this.eventStreams);
    }

    public static PanelWebGateway server(PanelConfig config, String fingerprint) {
        return new PanelWebGateway(false, config.webBind, config.webPort, null,
                new PanelEndpoint(localApiHost(config.apiBind), config.apiPort), fingerprint);
    }

    public static PanelWebGateway client(ClientPanelConfig config) {
        return new PanelWebGateway(true, "127.0.0.1", config.webPort, config, null, null);
    }

    public boolean start() throws Exception {
        // 不做启动前置探测:客户端模式撞上服务端网关的判定统一由 bind 失败路径给出
        // (createHttpServerWithPriority 探一次即可),UpstreamConnection.open 在客户端模式是惰性的
        synchronized (this) {
            if (!this.closed || this.http != null) throw new IllegalStateException("网页网关已启动");
            this.closed = false;
            // 满了拒绝而不是 CallerRuns:后者把 HttpServer 的 dispatcher 线程拖进业务,
            // 公网上 68 个慢请求就能让整个端口停止接客
            this.httpPool = PanelNet.boundedPool("sablepanel-web", 4, 64);
            // 跨服转发单独一个池(bulkhead):一个卡住的远端不能占用本机自己的 HTTP 线程
            this.forwardPool = PanelNet.boundedPool("sablepanel-web-forward", 4, 32);
            this.eventStreams = new PanelEventStreams();
        }
        try {
            this.connection.open();
            HttpServer created = createHttpServerWithPriority();
            if (created == null) {
                close();
                LOGGER.info("sablepanel: server web gateway won 127.0.0.1:{}, client gateway skipped",
                        this.port);
                return false;
            }
            created.setExecutor(this.httpPool);
            created.createContext("/", this::handle);
            created.start();
            this.http = created;
            LOGGER.info("sablepanel: {} web gateway at http://{}:{}/",
                    this.clientMode ? CLIENT_MODE : SERVER_MODE, this.bind, this.port);
            return true;
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    private HttpServer createHttpServerWithPriority() throws Exception {
        try {
            return createHttpServer();
        } catch (BindException occupied) {
            if (this.clientMode) {
                if (SERVER_MODE.equals(localGatewayMode(this.port))) return null;
                throw occupied;
            }
            if (!requestClientYield(this.port)) throw occupied;
            return awaitClientYield(occupied);
        }
    }

    private HttpServer awaitClientYield(BindException occupied) throws Exception {
        long deadline = System.nanoTime() + TAKEOVER_TIMEOUT_NANOS;
        BindException last = occupied;
        while (System.nanoTime() < deadline) {
            Thread.sleep(TAKEOVER_RETRY_MILLIS);
            try {
                return createHttpServer();
            } catch (BindException retry) {
                last = retry;
            }
        }
        throw last;
    }

    private HttpServer createHttpServer() throws IOException {
        return HttpServer.create(new InetSocketAddress(this.bind, this.port), 0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        boolean closeExchange = true;
        boolean yieldGateway = false;
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/vendor/three.min.js")) {
                HttpIo.sendResource(exchange, "/web/vendor/three.min.js", "text/javascript; charset=utf-8", true);
                return;
            }
            if (HttpIo.STATIC_ASSET.matcher(path).matches()) {
                // 字体/图片不随版本频繁变化,走与 three.min.js 相同的缓存策略;js/css 保持 no-cache
                boolean stable = path.endsWith(".ttf") || path.endsWith(".png");
                HttpIo.sendResource(exchange, "/web" + path,
                        path.endsWith(".css") ? "text/css; charset=utf-8"
                                : path.endsWith(".ttf") ? "font/ttf"
                                : path.endsWith(".png") ? "image/png"
                                : "text/javascript; charset=utf-8", stable);
                return;
            }
            // 浏览器/收藏夹会盲拉 /favicon.ico,别让它 404:直接给 32px 站点图标(现代浏览器接受 PNG)
            if (path.equals("/favicon.ico")) {
                HttpIo.sendResource(exchange, "/web/img/favicon-32.png", "image/png", true);
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
            if (path.equals("/gateway/yield")) {
                HttpIo.requirePost(exchange);
                yieldGateway = yieldClientGateway(exchange);
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
            if (yieldGateway) {
                Thread.ofPlatform().daemon().name("sablepanel-client-yield").start(this::close);
            }
        }
    }

    private boolean openEventStream(HttpExchange exchange) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "需要 GET");
            return false;
        }
        PanelTcpClient client = this.connection.active();
        if (client == null) {
            sendError(exchange, 503, "尚未连接服务器");
            return false;
        }
        // 订阅等待不许拿锁:一个慢上游能把 connect/disconnect/close 全堵 10 秒
        String token = exchange.getRequestHeaders().getFirst("X-Token");
        PanelResponse response = client.subscribeEvents(token).get(10, TimeUnit.SECONDS);
        if (response.status() != 200) {
            HttpIo.send(exchange, response.status(), response.contentType(), response.body(), false);
            return false;
        }
        PanelEventStreams streams = this.connection.adoptEventToken(client, token);
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
        PanelTcpClient client = this.connection.active();
        if (client == null) {
            sendError(exchange, 503, "尚未连接服务器");
            return;
        }
        byte[] body = "GET".equalsIgnoreCase(exchange.getRequestMethod()) ? new byte[0] : HttpIo.readBody(exchange);
        PanelRequest request = new PanelRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                query, body, token, target == null ? "" : target);
        // request() 内部已按 REQUEST_TIMEOUT 跟踪超时,无参等即可,别再叠一层同值超时
        PanelResponse response = client.request(request).get();
        byte[] preGzip = null;
        if (request.path().equals("/api/bodies") && response.status() == 200
                && response.body().length > 1024 && HttpIo.acceptsGzip(exchange)) {
            String snapshot = response.headers().get(PanelResponse.BODIES_SNAPSHOT_HEADER);
            if (snapshot != null && !snapshot.isBlank()) {
                preGzip = this.bodiesGzip.get(snapshot, response.body());
            }
        }
        HttpIo.send(exchange, response.status(), response.contentType(), response.body(),
                response.compressible(), response.headers(), preGzip);
    }

    private void sendState(HttpExchange exchange) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty("mode", this.clientMode ? CLIENT_MODE : SERVER_MODE);
        state.addProperty("connected", this.connection.isConnected());
        // 地址只回给客户端模式的本机页面(预填上次集群地址);服务端模式这是公网口,不泄露内部上游端点
        if (this.clientMode) {
            state.addProperty("address", this.connection.displayAddress());
        }
        HttpIo.send(exchange, 200, "application/json", state.toString().getBytes(StandardCharsets.UTF_8), false);
    }

    private boolean yieldClientGateway(HttpExchange exchange) throws IOException {
        String requestedBy = exchange.getRequestHeaders().getFirst(YIELD_HEADER);
        boolean local = exchange.getRemoteAddress() != null
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
        if (!this.clientMode || !local || !YIELD_VALUE.equals(requestedBy)) {
            sendError(exchange, 403, "网关让渡请求无效");
            return false;
        }
        sendOk(exchange);
        return true;
    }

    private void connectClient(HttpExchange exchange) throws Exception {
        if (!this.clientMode) {
            sendError(exchange, 409, "服务端网关目标固定");
            return;
        }
        JsonObject body = HttpIo.readJsonBody(exchange);
        PanelResponse failure = this.connection.connectRequested(
                body.has("address") ? body.get("address").getAsString() : "",
                body.has("accept_fingerprint") ? body.get("accept_fingerprint").getAsString() : "");
        if (failure != null) {
            HttpIo.send(exchange, failure.status(), failure.contentType(), failure.body(), false);
            return;
        }
        sendOk(exchange);
    }

    private void disconnectClient(HttpExchange exchange) throws IOException {
        if (!this.clientMode) {
            sendError(exchange, 409, "服务端网关不能断开本机 API");
            return;
        }
        this.connection.disconnectRequested();
        sendOk(exchange);
    }

    @Override
    public void close() {
        HttpServer currentHttp;
        ExecutorService currentHttpPool;
        ExecutorService currentForwardPool;
        PanelEventStreams currentStreams;
        synchronized (this) {
            if (this.closed && this.http == null && this.httpPool == null && this.eventStreams == null) return;
            this.closed = true;
            currentHttp = this.http;
            currentHttpPool = this.httpPool;
            currentForwardPool = this.forwardPool;
            currentStreams = this.eventStreams;
            this.http = null;
            this.httpPool = null;
            this.forwardPool = null;
            this.eventStreams = null;
        }
        if (currentHttp != null) currentHttp.stop(0);
        if (currentStreams != null) currentStreams.close();
        this.bodiesGzip.clear();
        this.connection.close();
        PanelNet.shutdown(currentForwardPool);
        PanelNet.shutdown(currentHttpPool);
    }

    private void publishEvent(PanelEvent event) {
        PanelEventStreams streams = this.eventStreams;
        if (streams != null) streams.publish(event);
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

    private static String localApiHost(String bind) {
        if ("0.0.0.0".equals(bind)) return "127.0.0.1";
        if ("::".equals(bind) || "[::]".equals(bind)) return "::1";
        return bind;
    }

    private static String localGatewayMode(int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder(localGatewayUri(port, "/gateway/state"))
                    .timeout(LOCAL_REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> response = LOCAL_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return "";
            JsonObject state = JsonParser.parseString(response.body()).getAsJsonObject();
            return state.has("mode") ? state.get("mode").getAsString() : "";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception unavailable) {
            return "";
        }
    }

    private static boolean requestClientYield(int port) {
        if (!CLIENT_MODE.equals(localGatewayMode(port))) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(localGatewayUri(port, "/gateway/yield"))
                    .timeout(LOCAL_REQUEST_TIMEOUT).header(YIELD_HEADER, YIELD_VALUE)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            return LOCAL_HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static URI localGatewayUri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /** 错误响应统一走 PanelResponse.error 的 JSON 形状,和 TLS 侧同一张脸 */
    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        PanelResponse error = PanelResponse.error(status, message);
        HttpIo.send(exchange, error.status(), error.contentType(), error.body(), false);
    }

    private static void sendOk(HttpExchange exchange) throws IOException {
        PanelResponse ok = PanelResponse.ok();
        HttpIo.send(exchange, ok.status(), ok.contentType(), ok.body(), false);
    }

}
