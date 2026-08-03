package com.klnon.sablepanel.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klnon.sablepanel.SablePanel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * 面板 HTTP 服务(JDK 内置 HttpServer,独立线程池,不触碰主线程):
 *   GET  /                        面板页面
 *   GET  /vendor/three.min.js     内置 three.js(无需外网)
 *   GET  /api/servers             集群成员列表
 *   GET  /api/bodies              全量索引(组聚合,纯内存缓存)
 *   GET  /api/stats?from=&to=&max_points= TPS/MSPT/物理耗时与持久化历史
 *   GET  /api/recycle             回收站依赖组、属性与上限
 *   GET  /api/recycle/{id}/body/{uuid}/mesh 回收站体素预览
 *   POST /api/recycle/restore     {"ids":[...]}
 *   POST /api/recycle/config      {"max_files":500}
 *   GET  /api/body/{uuid}/mesh    体素预览(带 LRU 缓存)
 *   GET  /api/body/{uuid}/copies 实时副本审查
 *   POST /api/rescan              立即触发磁盘重扫
 *   POST /api/body/{uuid}/teleport?x=&y=&z=
 *   POST /api/body/{uuid}/delete
 *   POST /api/body/{uuid}/adopt   孤儿收养(含依赖闭包)
 *   POST /api/body/{uuid}/deduplicate 仅清理内容完全一致的副本
 *   POST /api/ops/batch_delete    {"uuids":[...]}
 *   POST /api/cluster/register    集群成员注册/心跳(仅本机回环)
 *   POST /api/cluster/token       修改访问口令(HOST 收到会推给全体成员)
 * 鉴权:?token= 或 X-Token 头。
 *
 * <p><b>多服共用一个端口</b>:同机多个服务端装本 mod 时,启动时抢主端口——抢到的当 HOST
 * 开网页并维护成员表,没抢到的退到 127.0.0.1 随机端口当 PEER,每 15s 向 HOST 注册心跳。
 * 所有 /api/* 带 {@code ?server=<id>};不是自己就反代到对应 PEER。HOST 停服后 PEER 心跳失败
 * 会尝试抢端口接管,故网页地址始终有效。端口 + token 相同视为同一集群。
 */
public final class PanelHttpServer {
    private static final long MESH_CACHE_LIMIT = 24L * 1024 * 1024;
    /** 成员心跳周期;超过 PEER_TTL 没心跳视为掉线 */
    public static final int HEARTBEAT_SECONDS = 15;
    private static final long PEER_TTL_MS = 45_000L;
    /** 这么久没有任何面板请求 → 空闲态:磁盘扫描暂停、运行时索引降频,常态开销归零 */
    private static final long IDLE_AFTER_MS = 300_000L;

    private final PanelConfig config;
    private final BodyIndex index;
    private final OpsService ops;
    private final MinecraftServer server;
    private final String selfId;
    private volatile HttpServer http;
    private volatile boolean isHost;
    private java.util.concurrent.ExecutorService httpPool;
    /** 最近一次通过鉴权的面板请求时刻;启动时算作活跃,给首轮扫描留窗口 */
    private volatile long lastActivityMs = System.currentTimeMillis();

    /** HOST 视角的成员表:id -> 该 PEER 的回环端口 + 它自己当前的 token(反代鉴权用) */
    private record Peer(int port, long lastSeen, String token) {
    }

    private final Map<String, Peer> peers = new java.util.concurrent.ConcurrentHashMap<>();

    /** entryId+mtime -> mesh JSON bytes,LRU */
    private final LinkedHashMap<String, byte[]> meshCache = new LinkedHashMap<>(16, 0.75f, true);
    private long meshCacheBytes;

    public PanelHttpServer(PanelConfig config, MinecraftServer server, BodyIndex index, OpsService ops) {
        this.config = config;
        this.server = server;
        this.index = index;
        this.ops = ops;
        this.selfId = config.serverId();
    }

    public void start() throws IOException {
        this.httpPool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "sablepanel-http");
            t.setDaemon(true);
            return t;
        });
        try {
            this.http = listen(this.config.bind, this.config.port);
            this.isHost = true;
            SablePanel.LOGGER.info("sablepanel: [{}] panel HOST at http://{}:{}/",
                    this.selfId, this.config.bind, this.config.port);
        } catch (IOException bindFailed) {
            // 端口被同机另一个服务端占着 —— 退为 PEER,挂到那个面板里
            this.http = listen("127.0.0.1", 0);
            this.isHost = false;
            SablePanel.LOGGER.info("sablepanel: [{}] port {} taken, joining as PEER on 127.0.0.1:{}",
                    this.selfId, this.config.port, this.http.getAddress().getPort());
        }
    }

    private HttpServer listen(String host, int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(host, port), 0);
        s.setExecutor(this.httpPool);
        s.createContext("/", this::handle);
        s.start();
        return s;
    }

    /** 每 {@link #HEARTBEAT_SECONDS} 秒在扫描线程调用:PEER 注册心跳 / HOST 清理掉线成员 */
    public void clusterTick() {
        if (this.isHost) {
            long deadline = System.currentTimeMillis() - PEER_TTL_MS;
            this.peers.entrySet().removeIf(e -> {
                boolean gone = e.getValue().lastSeen() < deadline;
                if (gone) SablePanel.LOGGER.info("sablepanel: peer {} left the panel", e.getKey());
                return gone;
            });
            return;
        }
        if (!heartbeat()) {
            tryPromote();
        }
    }

    /**
     * 向 HOST 注册/续期,返回是否成功。
     * 注册**不校验 token** —— 同机只该有一个面板,谁占着端口谁说了算,
     * 后启动的一律挂上去。HOST 回包里带的是集群当前口令,和自己不一样就直接采纳。
     */
    private boolean heartbeat() {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("id", this.selfId);
            body.addProperty("port", this.http.getAddress().getPort());
            body.addProperty("token", this.config.token);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            // 新版 HOST 不校验注册的 token,但仍带上:滚动升级时对面可能是旧版本,
            // 旧版本的 /api/cluster/register 是要过鉴权的,不带就 401 进不去集群
            var conn = (java.net.HttpURLConnection) java.net.URI.create(
                    "http://127.0.0.1:" + this.config.port + "/api/cluster/register?token="
                            + java.net.URLEncoder.encode(this.config.token, StandardCharsets.UTF_8))
                    .toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.getOutputStream().write(payload);
            int code = conn.getResponseCode();
            // 注意:4xx 时 getInputStream() 会抛异常,必须走 errorStream(排查过一次,别改回去)
            String reply = "";
            try (InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (in != null) reply = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            if (code == 200) adoptClusterToken(reply);
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 采纳 HOST 的口令,让整个集群始终只有一个 token(否则一旦发生接管,书签就失效) */
    private void adoptClusterToken(String reply) {
        try {
            JsonObject o = JsonParser.parseString(reply).getAsJsonObject();
            if (!o.has("token")) return;
            String authoritative = o.get("token").getAsString();
            if (authoritative.isEmpty() || authoritative.equals(this.config.token)) return;
            SablePanel.LOGGER.info("sablepanel: [{}] adopting the panel token from the host", this.selfId);
            this.config.token = authoritative;
            this.config.save();
        } catch (Exception e) {
            SablePanel.LOGGER.warn("sablepanel: adopting the host token failed", e);
        }
    }

    /** HOST 没了:试着抢下主端口自己当 HOST(抢不到说明别人先接管了,下一轮继续心跳) */
    private void tryPromote() {
        HttpServer next;
        try {
            next = listen(this.config.bind, this.config.port);
        } catch (IOException e) {
            return;
        }
        HttpServer old = this.http;
        this.http = next;
        this.isHost = true;
        this.peers.clear();
        try {
            old.stop(1);
        } catch (Exception ignored) {
        }
        SablePanel.LOGGER.info("sablepanel: [{}] took over the panel on port {}", this.selfId, this.config.port);
    }

    public void stop() {
        if (this.http != null) {
            this.http.stop(0);
            this.http = null;
        }
        if (this.httpPool != null) {
            this.httpPool.shutdownNow();
            this.httpPool = null;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            // 静态资源不鉴权(不含数据)
            if (path.equals("/vendor/three.min.js")) {
                sendResource(ex, "/web/vendor/three.min.js", "text/javascript; charset=utf-8", true);
                return;
            }
            // 登录外壳不含面板数据,必须先加载它才能让用户输入访问口令。
            if (path.equals("/") || path.equals("/index.html")) {
                sendResource(ex, "/web/index.html", "text/html; charset=utf-8", false);
                return;
            }
            // 集群注册不看 token:同机只该有一个面板,回环来源即可信,
            // 后启动的服务端无论 token 一不一样都要能挂上来(口令由 HOST 统一下发)
            if (path.equals("/api/cluster/register")) {
                requirePost(ex);
                handleRegister(ex);
                return;
            }
            if (!authed(ex)) {
                send(ex, 401, "application/json", "{\"error\":\"token 无效\"}".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            markActivity();
            if (path.equals("/api/cluster/token")) {
                requirePost(ex);
                handleSetToken(ex);
                return;
            }
            if (path.equals("/api/servers")) {
                send(ex, 200, "application/json", serversJson().toString().getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            // 请求的是集群里别的服务端 -> 反代到它的回环端口
            String target = query(ex.getRequestURI()).get("server");
            if (target != null && !target.isEmpty() && !target.equals(this.selfId)) {
                proxy(ex, target);
                return;
            }
            switch (path) {
                case "/api/bodies" -> {
                    send(ex, 200, "application/json", this.index.view().toString().getBytes(StandardCharsets.UTF_8), true);
                    return;
                }
                case "/api/stats" -> {
                    Map<String, String> values = query(ex.getRequestURI());
                    JsonObject stats;
                    if (values.containsKey("from") || values.containsKey("to")) {
                        if (!values.containsKey("from") || !values.containsKey("to")) {
                            throw new IllegalArgumentException("from 和 to 必须同时提供");
                        }
                        long from = Long.parseLong(values.get("from"));
                        long to = Long.parseLong(values.get("to"));
                        int maxPoints = values.containsKey("max_points")
                                ? Integer.parseInt(values.get("max_points")) : 2000;
                        stats = StatsCollector.INSTANCE.toJson(from, to, maxPoints);
                    } else {
                        stats = StatsCollector.INSTANCE.toJson(300);
                    }
                    send(ex, 200, "application/json",
                            stats.toString().getBytes(StandardCharsets.UTF_8), true);
                    return;
                }
                case "/api/recycle" -> {
                    JsonObject view = this.ops.recycleView();
                    send(ex, 200, "application/json", view.toString().getBytes(StandardCharsets.UTF_8), true);
                    return;
                }
                case "/api/recycle/config" -> {
                    requirePost(ex);
                    JsonObject body = readJsonBody(ex);
                    if (!body.has("max_files")) throw new IllegalArgumentException("max_files 缺失");
                    JsonObject result = this.ops.setRecycleLimit(body.get("max_files").getAsInt());
                    send(ex, 200, "application/json", result.toString().getBytes(StandardCharsets.UTF_8), false);
                    return;
                }
                case "/api/recycle/restore" -> {
                    requirePost(ex);
                    JsonArray ids = readJsonBody(ex).getAsJsonArray("ids");
                    if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("ids 为空");
                    if (ids.size() > 500) throw new IllegalArgumentException("单次最多恢复 500 个依赖组");
                    List<String> groupIds = new ArrayList<>();
                    for (var id : ids) groupIds.add(id.getAsString());
                    JsonObject result = this.ops.restoreRecycleGroups(groupIds);
                    send(ex, 200, "application/json", result.toString().getBytes(StandardCharsets.UTF_8), true);
                    return;
                }
                case "/api/rescan" -> {
                    requirePost(ex);
                    this.ops.rescanNow();
                    send(ex, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), false);
                    return;
                }
                case "/api/ops/batch_delete" -> {
                    requirePost(ex);
                    JsonArray arr = readJsonBody(ex).getAsJsonArray("uuids");
                    if (arr == null || arr.isEmpty()) throw new IllegalArgumentException("uuids 为空");
                    if (arr.size() > 500) throw new IllegalArgumentException("单次最多 500 个");
                    List<UUID> uuids = new ArrayList<>();
                    for (var e : arr) uuids.add(UUID.fromString(e.getAsString()));
                    JsonObject r = this.ops.deleteBatch(uuids);
                    send(ex, 200, "application/json", r.toString().getBytes(StandardCharsets.UTF_8), true);
                    return;
                }
            }
            var recycleMesh = java.util.regex.Pattern.compile(
                    "/api/recycle/([0-9A-Za-z_-]{8,96})/body/([0-9a-fA-F-]{36})/mesh").matcher(path);
            if (recycleMesh.matches()) {
                JsonObject mesh = this.ops.recycleMesh(recycleMesh.group(1), UUID.fromString(recycleMesh.group(2)));
                send(ex, 200, "application/json", mesh.toString().getBytes(StandardCharsets.UTF_8), true);
                return;
            }
            var m = java.util.regex.Pattern.compile(
                    "/api/body/([0-9a-fA-F-]{36})/(mesh|copies|teleport|delete|adopt|deduplicate)").matcher(path);
            if (m.matches()) {
                UUID uuid = UUID.fromString(m.group(1));
                switch (m.group(2)) {
                    case "mesh" -> {
                        handleMesh(ex, uuid);
                        return;
                    }
                    case "copies" -> {
                        JsonObject r = this.ops.inspectCopies(uuid);
                        send(ex, 200, "application/json", r.toString().getBytes(StandardCharsets.UTF_8), true);
                        return;
                    }
                    case "teleport" -> {
                        requirePost(ex);
                        Map<String, String> q = query(ex.getRequestURI());
                        JsonObject r = this.ops.teleport(uuid,
                                Double.parseDouble(q.get("x")), Double.parseDouble(q.get("y")), Double.parseDouble(q.get("z")));
                        send(ex, 200, "application/json", r.toString().getBytes(StandardCharsets.UTF_8), false);
                        return;
                    }
                    case "delete" -> {
                        requirePost(ex);
                        JsonObject r = this.ops.delete(uuid);
                        send(ex, 200, "application/json", r.toString().getBytes(StandardCharsets.UTF_8), false);
                        return;
                    }
                    case "adopt" -> {
                        requirePost(ex);
                        JsonObject r = this.ops.adopt(uuid);
                        send(ex, 200, "application/json", r.toString().getBytes(StandardCharsets.UTF_8), false);
                        return;
                    }
                    case "deduplicate" -> {
                        requirePost(ex);
                        JsonObject r = this.ops.deduplicate(uuid);
                        send(ex, 200, "application/json", r.toString().getBytes(StandardCharsets.UTF_8), false);
                        return;
                    }
                }
            }
            send(ex, 404, "application/json", "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8), false);
        } catch (Exception e) {
            SablePanel.LOGGER.warn("sablepanel: http error {}", ex.getRequestURI(), e);
            JsonObject err = new JsonObject();
            err.addProperty("error", String.valueOf(e.getMessage() != null ? e.getMessage() : e));
            send(ex, 500, "application/json", err.toString().getBytes(StandardCharsets.UTF_8), false);
        } finally {
            ex.close();
        }
    }

    /**
     * PEER 注册/心跳。**不校验 token**:同机端口只有一个,谁占着谁就是这台机器唯一的面板,
     * 后启动的服务端必须能挂上来,否则它既进不了面板又开不了自己的面板,等于凭空消失。
     * 信任边界是"来源必须是本机回环"——能撞上这个端口的本来就只有同机进程。
     * 回包带上集群当前口令,PEER 采纳后全集群 token 保持一致(接管时书签才不会失效)。
     */
    private void handleRegister(HttpExchange ex) throws IOException {
        if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
            send(ex, 403, "application/json",
                    "{\"error\":\"仅接受本机注册\"}".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        if (!this.isHost) {
            // 自己也是 PEER,没资格收编;对方会继续尝试抢端口
            send(ex, 409, "application/json",
                    "{\"error\":\"本节点不是 HOST\"}".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        JsonObject body = readJsonBody(ex);
        String id = body.get("id").getAsString();
        int port = body.get("port").getAsInt();
        String peerToken = body.has("token") ? body.get("token").getAsString() : this.config.token;
        if (id.equals(this.selfId)) {
            send(ex, 409, "application/json",
                    "{\"error\":\"serverName 与 HOST 重名,请改一个\"}".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        // 记住对方当前的 token:它采纳新口令前的这一小段时间,反代还得用旧的才能过它的鉴权
        if (this.peers.put(id, new Peer(port, System.currentTimeMillis(), peerToken)) == null) {
            SablePanel.LOGGER.info("sablepanel: peer {} joined the panel (127.0.0.1:{})", id, port);
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("token", this.config.token);
        send(ex, 200, "application/json", out.toString().getBytes(StandardCharsets.UTF_8), false);
    }

    /**
     * 改访问 token。**必须整个集群一起改**——只改 HOST 的话,PEER 下一轮心跳就被 401 拒了,
     * 集群当场散架。所以 HOST 先拿旧 token 逐个通知 PEER,全部就位后再改自己;
     * PEER 收到(来自 HOST 的转发)只改自己。各自立即回写自己的 config 文件。
     */
    private void handleSetToken(HttpExchange ex) throws IOException {
        JsonObject body = readJsonBody(ex);
        String next = body.has("token") ? body.get("token").getAsString().trim() : "";
        if (next.isEmpty() || next.length() > 64 || !next.matches("[A-Za-z0-9._~-]+")) {
            send(ex, 400, "application/json",
                    "{\"error\":\"token 只能用字母、数字和 . - _ ~,长度 1~64\"}".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        JsonObject out = new JsonObject();
        JsonArray failed = new JsonArray();
        if (this.isHost && !this.peers.isEmpty()) {
            String current = this.config.token;
            for (Map.Entry<String, Peer> en : this.peers.entrySet()) {
                Peer p = en.getValue();
                if (pushToken(p.port(), current, next)) {
                    // 立刻更新成员表里记的 token,否则到下次心跳(最多 15s)之前
                    // 反代还在用旧口令,页面上会看到一串 401
                    this.peers.put(en.getKey(), new Peer(p.port(), p.lastSeen(), next));
                } else {
                    failed.add(en.getKey());
                }
            }
        }
        this.config.token = next;
        try {
            this.config.save();
        } catch (Exception e) {
            SablePanel.LOGGER.error("sablepanel: token changed in memory but writing config failed", e);
            out.addProperty("warn", "已生效,但写入配置文件失败(重启后会回退):" + e.getMessage());
        }
        SablePanel.LOGGER.info("sablepanel: [{}] panel token changed from the web UI", this.selfId);
        out.addProperty("ok", true);
        out.addProperty("token", next);
        if (!failed.isEmpty()) out.add("failed", failed);
        send(ex, 200, "application/json", out.toString().getBytes(StandardCharsets.UTF_8), false);
    }

    /** HOST → PEER 转发新 token,用改之前的旧 token 鉴权 */
    private boolean pushToken(int peerPort, String currentToken, String next) {
        try {
            JsonObject b = new JsonObject();
            b.addProperty("token", next);
            var conn = (java.net.HttpURLConnection) java.net.URI.create(
                    "http://127.0.0.1:" + peerPort + "/api/cluster/token?token="
                            + java.net.URLEncoder.encode(currentToken, StandardCharsets.UTF_8)).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(8000);
            conn.getOutputStream().write(b.toString().getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            try (InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (in != null) in.readAllBytes();
            } catch (Exception ignored) {
            }
            return code == 200;
        } catch (Exception e) {
            SablePanel.LOGGER.warn("sablepanel: pushing the new token to peer on port {} failed", peerPort, e);
            return false;
        }
    }

    /** 把查询串里的 token 换成指定值,其余参数原样保留 */
    private static String rewriteToken(String rawQuery, String token) {
        StringBuilder sb = new StringBuilder("token=")
                .append(java.net.URLEncoder.encode(token, StandardCharsets.UTF_8));
        if (rawQuery != null) {
            for (String kv : rawQuery.split("&")) {
                if (kv.isEmpty() || kv.equals("token") || kv.startsWith("token=")) continue;
                sb.append('&').append(kv);
            }
        }
        return sb.toString();
    }

    private JsonObject serversJson() {
        JsonArray arr = new JsonArray();
        JsonObject me = new JsonObject();
        me.addProperty("id", this.selfId);
        me.addProperty("self", true);
        me.addProperty("host", this.isHost);
        arr.add(me);
        for (String id : new java.util.TreeSet<>(this.peers.keySet())) {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("self", false);
            o.addProperty("host", false);
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("self", this.selfId);
        out.addProperty("using_default_token", PanelConfig.DEFAULT_TOKEN.equals(this.config.token));
        out.add("servers", arr);
        return out;
    }

    /**
     * 把请求原样转给集群里的另一个服务端(同机回环)。
     * 不向 PEER 索要 gzip —— 拿到原始字节后由本节点按浏览器的 Accept-Encoding 再压。
     */
    private void proxy(HttpExchange ex, String target) throws IOException {
        Peer peer = this.peers.get(target);
        if (peer == null) {
            send(ex, 404, "application/json",
                    "{\"error\":\"服务器不在线\"}".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        URI uri = ex.getRequestURI();
        // 浏览器带的是 HOST 的 token,PEER 未必已经采纳同一个 —— 换成它自己的再转发
        String url = "http://127.0.0.1:" + peer.port() + uri.getRawPath()
                + "?" + rewriteToken(uri.getRawQuery(), peer.token());
        try {
            var conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setRequestMethod(ex.getRequestMethod());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    ex.getRequestBody().transferTo(os);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] body = in != null ? in.readAllBytes() : new byte[0];
            String type = conn.getContentType();
            send(ex, code, type != null ? type : "application/json", body, true);
        } catch (Exception e) {
            SablePanel.LOGGER.warn("sablepanel: proxy to {} failed", target, e);
            JsonObject err = new JsonObject();
            err.addProperty("error", "转发到 " + target + " 失败:" + e.getMessage());
            send(ex, 502, "application/json", err.toString().getBytes(StandardCharsets.UTF_8), false);
        }
    }

    private void handleMesh(HttpExchange ex, UUID uuid) throws IOException {
        Map<String, Path> dims = DiskScanner.sublevelDirs(this.server);
        DiskScanner.DiskEntry e = this.index.findEntry(uuid);
        Path dir = e != null ? dims.get(e.key().dim()) : null;
        long mtime = 0;
        if (dir != null) {
            try {
                mtime = Files.getLastModifiedTime(dir.resolve(
                        "r." + e.key().rx() + "." + e.key().rz() + "." + e.key().storage() + ".slvls")).toMillis();
            } catch (Exception ignored) {
            }
        }
        // 缓存键带 uuid:快照过期时条目槽位可能已被 sable 挪给别的体
        String cacheKey = uuid + "@" + (e != null ? e.key().id() : "?") + "@" + mtime;
        byte[] cached;
        synchronized (this.meshCache) {
            cached = this.meshCache.get(cacheKey);
        }
        if (cached == null) {
            CompoundTag tag = dir != null ? DiskScanner.readEntryTag(dir, e.key()) : null;
            // 必须校验读到的确实是这个体:快照 120s 刷新一次,期间 sable 的 save 会搬迁条目
            if (tag == null || !uuid.equals(safeUuid(tag))) {
                tag = null;
                for (var en : dims.entrySet()) {
                    DiskScanner.LocatedEntry le = DiskScanner.locateEntry(en.getKey(), en.getValue(), uuid);
                    if (le != null) {
                        tag = le.tag();
                        break;
                    }
                }
            }
            if (tag == null) {
                send(ex, 404, "application/json", "{\"error\":\"条目读取失败(该体可能刚被移动或删除)\"}"
                        .getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            cached = MeshExtractor.extract(tag).toString().getBytes(StandardCharsets.UTF_8);
            synchronized (this.meshCache) {
                this.meshCache.put(cacheKey, cached);
                this.meshCacheBytes += cached.length;
                var it = this.meshCache.entrySet().iterator();
                while (this.meshCacheBytes > MESH_CACHE_LIMIT && it.hasNext()) {
                    var victim = it.next();
                    if (victim.getKey().equals(cacheKey)) continue;
                    this.meshCacheBytes -= victim.getValue().length;
                    it.remove();
                }
            }
        }
        send(ex, 200, "application/json", cached, true);
    }

    private static UUID safeUuid(CompoundTag tag) {
        try {
            return tag.getUUID("uuid");
        } catch (Exception e) {
            return null;
        }
    }

    /** 有面板请求视为活跃;空闲期间磁盘扫描是暂停的,唤醒时立刻补一轮,数据几秒内追平 */
    private void markActivity() {
        long now = System.currentTimeMillis();
        boolean wasIdle = now - this.lastActivityMs >= IDLE_AFTER_MS;
        this.lastActivityMs = now;
        if (wasIdle) {
            SablePanel.LOGGER.info("sablepanel: [{}] panel active again, rescanning", this.selfId);
            try {
                this.ops.rescanNow();
            } catch (Exception ignored) {
            }
        }
    }

    /** 近 {@link #IDLE_AFTER_MS} 内有无面板请求(PEER 收到的反代请求同样算) */
    public boolean isActive() {
        return System.currentTimeMillis() - this.lastActivityMs < IDLE_AFTER_MS;
    }

    private boolean authed(HttpExchange ex) {
        String t = query(ex.getRequestURI()).get("token");
        if (t == null) t = ex.getRequestHeaders().getFirst("X-Token");
        return this.config.token.equals(t);
    }

    private static void requirePost(HttpExchange ex) {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            throw new IllegalArgumentException("需要 POST");
        }
    }

    private static JsonObject readJsonBody(HttpExchange ex) throws IOException {
        return JsonParser.parseString(
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> map = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null) return map;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                map.put(java.net.URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private void sendResource(HttpExchange ex, String res, String type, boolean cacheable) throws IOException {
        try (InputStream in = PanelHttpServer.class.getResourceAsStream(res)) {
            if (in == null) {
                send(ex, 404, "text/plain", "resource missing".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            if (cacheable) {
                ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            }
            send(ex, 200, type, in.readAllBytes(), true);
        }
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body, boolean tryGzip) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (tryGzip && ae != null && ae.contains("gzip") && body.length > 1024) {
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            var buf = new java.io.ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(buf)) {
                gz.write(body);
            }
            body = buf.toByteArray();
        }
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
