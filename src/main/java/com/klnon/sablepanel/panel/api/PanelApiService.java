package com.klnon.sablepanel.panel.api;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.bodies.BodyIndex;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import com.klnon.sablepanel.panel.metrics.StatsCollector;
import com.klnon.sablepanel.panel.ops.ForceLoadService;
import com.klnon.sablepanel.panel.ops.FreezeService;
import com.klnon.sablepanel.panel.ops.JobService;
import com.klnon.sablepanel.panel.ops.PanelOps;
import com.klnon.sablepanel.panel.ops.PauseService;
import com.klnon.sablepanel.panel.preview.PreviewSubsystem;
import net.minecraft.nbt.CompoundTag;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.Objects;

public final class PanelApiService {
    private static final long IDLE_AFTER_MS = 300_000L;
    private static final Pattern RECYCLE_MESH = Pattern.compile(
            "/api/recycle/([0-9A-Za-z_-]{8,96})/body/([0-9a-fA-F-]{36})/mesh");
    private static final Pattern RECYCLE_ID = Pattern.compile("[0-9A-Za-z_-]{8,96}");
    private static final Pattern COPY_MESH = Pattern.compile(
            "/api/body/([0-9a-fA-F-]{36})/copy/([0-9a-f]{16})/mesh");
    private static final Pattern PREVIEW_MANIFEST = Pattern.compile(
            "/api/preview/resources/([0-9a-f]{64})/manifest");
    private static final Pattern PREVIEW_SHARD = Pattern.compile(
            "/api/preview/resources/([0-9a-f]{64})/shard/([0-9a-f]{64})");
    private static final Pattern BODY_OP = Pattern.compile(
            "/api/body/([0-9a-fA-F-]{36})/([a-z_]{1,32})");
    private static final Pattern THUMB = Pattern.compile("/api/thumb/([0-9a-fA-F-]{36})");

    private final PanelConfig config;
    private final BodyIndex index;
    private final PanelOps ops;
    private final JobService jobs;
    private final PreviewSubsystem preview;
    private final com.klnon.sablepanel.panel.preview.thumb.ThumbService thumbs;
    private final String selfId;
    private final String bodiesEpoch = UUID.randomUUID().toString();
    private final Object tokenLock = new Object();
    /** 精确路径与单体操作的路由表;构造时按所属服务分组注册 */
    private final Map<String, Route> routes = new LinkedHashMap<>();
    private final Map<String, BodyRoute> bodyRoutes = new LinkedHashMap<>();
    private volatile long lastActivityMs = System.currentTimeMillis();
    private volatile CachedBodies cachedBodies = new CachedBodies(Long.MIN_VALUE, new byte[0]);

    private record CachedBodies(long version, byte[] body) {
    }

    public PanelApiService(PanelConfig config, BodyIndex index, PanelOps ops,
                           JobService jobs, PreviewSubsystem preview,
                           com.klnon.sablepanel.panel.preview.thumb.ThumbService thumbs) {
        this.config = config;
        this.index = index;
        this.ops = ops;
        this.jobs = jobs;
        this.preview = Objects.requireNonNull(preview, "preview");
        this.thumbs = thumbs; // 可为 null:缓存目录建不起来时面板照常跑,缩略图一律 404 生成中
        this.selfId = config.serverId();
        registerListRoutes();
        registerRecycleRoutes();
        registerConsistencyRoutes();
        registerBatchRoutes();
        registerBodyRoutes();
    }

    /**
     * 本节点的 API 入口:网关直连本机时走这里,HOST 转发过来由本节点应答时也走这里
     * ({@code PanelTcpClient.connectPeer} 拿的就是这个方法的引用)。
     * <p>
     * 这里不判字节上限。集群节点自己应答的 {@code /api/servers}、转发、以及各处 catch 出来的
     * 500 都不经过本方法,只有 {@code PanelWire.response} 是全部响应的必经之路,上限判在那儿。
     */
    public PanelResponse dispatch(PanelRequest request) {
        if (!authorized(request.token())) return PanelResponse.error(401, "token 无效");
        markActivity();
        try {
            return dispatchAuthorized(request);
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: api error {}", request.path(), error);
            return PanelResponse.error(500, messageOf(error));
        }
    }

    private PanelResponse dispatchAuthorized(PanelRequest request) throws Exception {
        String path = request.path();
        Route route = this.routes.get(path);
        if (route != null) return route.handle(request);

        var recycleMesh = RECYCLE_MESH.matcher(path);
        if (recycleMesh.matches()) {
            String groupId = recycleMesh.group(1);
            UUID uuid = UUID.fromString(recycleMesh.group(2));
            return previewResponse(this.preview.renderSpm2Async("recycle:" + groupId + ":" + uuid,
                    () -> this.ops.recycle().previewTag(groupId, uuid)));
        }
        var copyMesh = COPY_MESH.matcher(path);
        if (copyMesh.matches()) {
            UUID uuid = UUID.fromString(copyMesh.group(1));
            String version = copyMesh.group(2);
            return previewResponse(this.preview.renderSpm2Async("copy:" + uuid + ":" + version,
                    () -> this.ops.copies().copyVersionTag(uuid, version)));
        }
        var manifest = PREVIEW_MANIFEST.matcher(path);
        if (manifest.matches()) return resourceResponse(this.preview.resource(manifest.group(1), null), true);
        var shard = PREVIEW_SHARD.matcher(path);
        if (shard.matches()) return resourceResponse(
                this.preview.resource(shard.group(1), shard.group(2)), false);
        var thumb = THUMB.matcher(path);
        if (thumb.matches()) return thumbResponse(request, UUID.fromString(thumb.group(1)));
        var bodyOp = BODY_OP.matcher(path);
        if (!bodyOp.matches()) return PanelResponse.error(404, "not found");
        BodyRoute op = this.bodyRoutes.get(bodyOp.group(2));
        if (op == null) return PanelResponse.error(404, "not found");
        return op.handle(request, UUID.fromString(bodyOp.group(1)));
    }

    @FunctionalInterface
    private interface Route {
        PanelResponse handle(PanelRequest request) throws Exception;
    }

    @FunctionalInterface
    private interface BodyRoute {
        PanelResponse handle(PanelRequest request, UUID uuid) throws Exception;
    }

    /** 只读视图与重扫:列表/作业/玩家/统计 */
    private void registerListRoutes() {
        this.routes.put("/api/preview/resources/retry", request -> {
            requirePost(request);
            this.preview.retryResources();
            return PanelResponse.json(202, new JsonObject(), false);
        });
        this.routes.put("/api/bodies", request -> {
            CachedBodies bodies = bodiesResponse();
            return new PanelResponse(200, "application/json", bodies.body(), true,
                    Map.of(PanelResponse.BODIES_SNAPSHOT_HEADER,
                            this.bodiesEpoch + "-" + bodies.version()));
        });
        this.routes.put("/api/jobs", request -> {
            String file = request.query().get("file");
            if (file != null && !file.isBlank()) return PanelResponse.json(200, JobService.readLog(file), true);
            // poll=1 是面板每 2 秒那一次:只要 running 和精简过的历史,不列日志目录。
            // 顺带附上四个状态集合(HTTP 线程镜像,几 KB):作业期间列表不重拉(12 MiB),
            // 徽章靠这份真值逐组跟随实际进度,不再等到作业结束才一次性变
            boolean poll = request.query().containsKey("poll");
            JsonObject view = this.jobs.view(poll);
            if (poll) attachStateSets(view);
            return PanelResponse.json(200, view, true);
        });
        this.routes.put("/api/players", request ->
                PanelResponse.json(200, this.ops.teleport().listPlayers(), false));
        this.routes.put("/api/stats", request ->
                PanelResponse.json(200, StatsCollector.INSTANCE.toJson(), true));
        this.routes.put("/api/rescan", request -> {
            requirePost(request);
            return enqueue("重扫磁盘", List.of(), "", () -> {
                this.ops.kit().rescanNow();
                JsonObject out = new JsonObject();
                out.addProperty("ok", true);
                return out;
            });
        });
    }

    /** 作业轮询顺带下发的状态真值:全部来自并发镜像集合,不碰主线程 */
    private static void attachStateSets(JsonObject view) {
        view.add("paused", uuidArray(PauseService.snapshot()));
        view.add("forced", uuidArray(ForceLoadService.snapshot()));
        view.add("forced_requested", uuidArray(ForceLoadService.requestedSnapshot()));
        view.add("frozen", uuidArray(FreezeService.snapshot()));
    }

    private static JsonArray uuidArray(Set<UUID> uuids) {
        JsonArray arr = new JsonArray();
        for (UUID uuid : uuids) arr.add(uuid.toString());
        return arr;
    }

    private synchronized CachedBodies bodiesResponse() {
        long version = this.index.version();
        CachedBodies cached = this.cachedBodies;
        if (cached.version == version) return cached;
        JsonObject view = this.index.view();
        // 作业状态不在这儿:它每两秒变一次,而这份快照最大 12 MiB。前端改从
        // /api/jobs 的 running[] 取,那里字段是全的,顺带省掉一次日志请求
        JsonObject reach = new JsonObject();
        reach.addProperty("void_below", this.config.voidBelowY);
        reach.addProperty("sky_above", this.config.skyAboveY);
        view.add("reach", reach);
        byte[] body = view.toString().getBytes(StandardCharsets.UTF_8);
        CachedBodies fresh = new CachedBodies(version, body);
        this.cachedBodies = fresh;
        return fresh;
    }

    /** 回收站:分页视图/上限配置/恢复/彻底删除 */
    private void registerRecycleRoutes() {
        this.routes.put("/api/recycle", request -> {
            // 游标分页:cursor 是上一页最后一个组的 id,服务端只读这一页的 manifest
            String version = request.query().getOrDefault("version", "latest");
            if (!"latest".equals(version) && !"old".equals(version)) {
                throw new IllegalArgumentException("version 必须是 latest 或 old");
            }
            String cursor = request.query().getOrDefault("cursor", "");
            int limit = request.query().containsKey("limit")
                    ? Integer.parseInt(request.query().get("limit")) : 0;
            return PanelResponse.json(200, this.ops.recycle().view(version, cursor, limit), true);
        });
        this.routes.put("/api/recycle/config", request -> {
            requirePost(request);
            JsonObject body = request.jsonBody();
            if (!body.has("max_files")) throw new IllegalArgumentException("max_files 缺失");
            JsonObject out = new JsonObject();
            out.addProperty("limit", this.ops.recycle().setLimit(body.get("max_files").getAsInt()));
            out.addProperty("ok", true);
            return PanelResponse.json(200, out, false);
        });
        this.routes.put("/api/recycle/restore", request -> {
            requirePost(request);
            List<String> groupIds = readRecycleIds(request);
            return enqueue("回收站恢复", List.of(), groupIds.size() + " 个物理组",
                    () -> this.ops.restore().restoreRecycleGroups(groupIds));
        });
        this.routes.put("/api/recycle/purge", request -> {
            requirePost(request);
            List<String> groupIds = readRecycleIds(request);
            return enqueue("回收站彻底删除", List.of(), groupIds.size() + " 个物理组",
                    () -> this.ops.restore().purgeRecycleGroups(groupIds));
        });
    }

    /** 一致性:结果视图/手动扫描/显式修复 */
    private void registerConsistencyRoutes() {
        this.routes.put("/api/consistency", request ->
                PanelResponse.json(200, this.ops.consistency().view(), true));
        this.routes.put("/api/consistency/scan", request -> {
            requirePost(request);
            return enqueue("一致性检查", List.of(), "", () -> this.ops.consistency().scan());
        });
        this.routes.put("/api/consistency/repair", request -> {
            requirePost(request);
            JsonObject body = request.jsonBody();
            String scanId = body.has("scan_id") ? body.get("scan_id").getAsString() : "";
            if (!scanId.matches("[0-9a-z]+-[0-9a-f]{8}")) throw new IllegalArgumentException("scan_id 无效");
            Set<String> pointers = readHexSet(body, "pointers");
            Set<String> tracking = readHexSet(body, "tracking");
            Set<UUID> forced = readUuidSet(body, "forced");
            Set<UUID> paused = readUuidSet(body, "paused");
            int total = pointers.size() + tracking.size() + forced.size() + paused.size();
            if (total == 0 || total > 10_000) throw new IllegalArgumentException("修复项数量无效");
            return enqueue("一致性修复", List.of(), total + " 项",
                    () -> this.ops.consistency().repair(scanId, pointers, tracking, forced, paused));
        });
    }

    /** 多选批量操作 */
    private void registerBatchRoutes() {
        this.routes.put("/api/ops/batch_delete", request -> {
            requirePost(request);
            JsonObject body = request.jsonBody();
            List<UUID> uuids = readUuids(body);
            // expand=false 只删点名的这些,不按依赖链展开 —— 清断链残骸专用,见 DeleteOps.deleteBatch
            boolean expand = !body.has("expand") || body.get("expand").getAsBoolean();
            return enqueue(expand ? "批量删除" : "清理断链残骸", uuids,
                    () -> this.ops.delete().deleteBatch(uuids, expand));
        });
        this.routes.put("/api/ops/batch_adopt", request -> {
            requirePost(request);
            List<UUID> uuids = readUuids(request.jsonBody());
            return enqueue("批量收养", uuids, () -> this.ops.adopt().adoptBatch(uuids));
        });
        this.routes.put("/api/ops/pause", request -> {
            requirePost(request);
            JsonObject body = request.jsonBody();
            List<UUID> uuids = readUuids(body);
            boolean paused = body.has("paused") && body.get("paused").getAsBoolean();
            return enqueue(paused ? "暂停" : "恢复", uuids,
                    () -> this.ops.teleport().setPaused(uuids, paused));
        });
        // 清除速度:整组线/角速度清零止停,只作用于已加载成员;冷体导流传送
        this.routes.put("/api/ops/clear_velocity", request -> {
            requirePost(request);
            List<UUID> uuids = readUuids(request.jsonBody());
            return enqueue("清除速度", uuids, () -> this.ops.teleport().clearVelocity(uuids));
        });
        this.routes.put("/api/ops/force_load", request -> {
            requirePost(request);
            JsonObject body = request.jsonBody();
            List<UUID> uuids = readUuids(body);
            boolean forced = body.has("forced") && body.get("forced").getAsBoolean();
            return enqueue(forced ? "常驻加载" : "取消常驻", uuids,
                    () -> this.ops.teleport().setForced(uuids, forced));
        });
        // 解冻 = 让这一组重新 tick。会不会压垮主线程由用户判断,后端不设闸门(既定约定),
        // 但前端必须先弹警告 —— 实测 192 体 / 25009 方块的组解冻后约 5 分钟被看门狗杀。
        this.routes.put("/api/ops/freeze", request -> {
            requirePost(request);
            JsonObject body = request.jsonBody();
            List<UUID> uuids = readUuids(body);
            boolean frozen = body.has("frozen") && body.get("frozen").getAsBoolean();
            return enqueue(frozen ? "冻结" : "恢复 tick", uuids,
                    () -> this.ops.teleport().setFrozen(uuids, frozen));
        });
        // 整维度停跑物理:急救阀,不针对某个体,所以不进作业队列(一次字段写,没有等待)
        this.routes.put("/api/ops/dim_physics", request -> {
            requirePost(request);
            JsonObject body = request.jsonBody();
            String dim = body.has("dim") ? body.get("dim").getAsString() : "";
            if (dim.isBlank()) throw new IllegalArgumentException("dim 不能为空");
            boolean paused = body.has("paused") && body.get("paused").getAsBoolean();
            return PanelResponse.json(200, this.ops.teleport().setDimensionPhysics(dim, paused), false);
        });
    }

    /** 单体操作(/api/body/{uuid}/{op});op 未注册即 404,不再另维护一份正则白名单 */
    private void registerBodyRoutes() {
        this.bodyRoutes.put("mesh", (request, uuid) -> previewResponse(this.preview.onlineSpm2(uuid)));
        this.bodyRoutes.put("copies", (request, uuid) ->
                PanelResponse.json(200, this.ops.copies().inspectCopies(uuid), true));
        this.bodyRoutes.put("teleport", (request, uuid) -> {
            requirePost(request);
            // 参数在入队前解析:格式错误要当场 400,而不是过几秒变成一条失败作业
            double x = Double.parseDouble(request.query().get("x"));
            double y = Double.parseDouble(request.query().get("y"));
            double z = Double.parseDouble(request.query().get("z"));
            return enqueue("传送", uuid,
                    () -> this.ops.teleport().teleport(uuid, x, y, z));
        });
        this.bodyRoutes.put("teleport_player", (request, uuid) -> {
            requirePost(request);
            String player = request.query().get("player");
            if (player == null || player.isBlank()) throw new IllegalArgumentException("player 缺失");
            UUID playerUuid = UUID.fromString(player);
            return enqueue("传送玩家", uuid,
                    () -> this.ops.teleport().teleportPlayer(uuid, playerUuid));
        });
        this.bodyRoutes.put("adopt", (request, uuid) -> {
            requirePost(request);
            return enqueue("收养", uuid,
                    () -> this.ops.adopt().adopt(uuid));
        });
        this.bodyRoutes.put("resolve_copies", (request, uuid) -> {
            requirePost(request);
            JsonObject body = request.jsonBody();
            String version = body.has("version") ? body.get("version").getAsString() : "";
            if (!version.matches("[0-9a-f]{16}")) throw new IllegalArgumentException("version 无效");
            return enqueue("处理副本", uuid,
                    () -> this.ops.copies().resolveCopyVersion(uuid, version));
        });
        this.bodyRoutes.put("quarantine_copies", (request, uuid) -> {
            requirePost(request);
            return enqueue("隔离不完整副本", uuid,
                    () -> this.ops.copies().quarantineIncompleteCopies(uuid));
        });
    }

    /**
     * 把一个操作交给 {@link JobService} 后台执行,请求线程立刻返回。
     * <p>
     * 目标体已有在跑的作业时返回 409 —— 这是重复点击的第一道闸:巨型体的操作可能跑
     * 几分钟,浏览器 30 秒就超时,用户看不到进展就会再点,从前正是这样把传输层
     * 4 个在飞槽位全占死、整个面板永久 503 的。
     */
    /** 绝大多数入口的显示名就是 targetLabel(targets),在这儿算,调用点别再抄 */
    private PanelResponse enqueue(String op, List<UUID> targets, Callable<JsonObject> work) {
        return enqueue(op, targets, targetLabel(targets), work);
    }

    private PanelResponse enqueue(String op, UUID target, Callable<JsonObject> work) {
        return enqueue(op, List.of(target), work);
    }

    private PanelResponse enqueue(String op, List<UUID> targets, String name, Callable<JsonObject> work) {
        try {
            JobService.Job job = this.jobs.submit(op, targets, name, work);
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("accepted", true);
            out.addProperty("job", job.seq);
            out.addProperty("op", op);
            return PanelResponse.json(200, out, false);
        } catch (IllegalStateException conflict) {
            return PanelResponse.error(409, conflict.getMessage());
        } catch (java.util.concurrent.RejectedExecutionException overload) {
            // worker 和队列都满了。必须当场回绝而不是继续排队 —— 排队只会推迟失败,还看不出容量已经不够
            return PanelResponse.error(503, "面板作业队列已满,请等当前操作结束后重试");
        }
    }

    /** 作业在日志里的显示名:优先用体名,退化为 uuid 前 8 位 */
    private String targetLabel(List<UUID> uuids) {
        if (uuids.isEmpty()) return "";
        UUID first = uuids.get(0);
        String label = null;
        DiskScanner.DiskEntry entry = this.index.findEntry(first);
        if (entry != null && entry.name() != null && !entry.name().isBlank()) label = entry.name();
        if (label == null) label = first.toString().substring(0, 8);
        return uuids.size() > 1 ? label + " 等 " + uuids.size() + " 个" : label;
    }


    public boolean authorized(String candidate) {
        String current = token();
        return MessageDigest.isEqual(current.getBytes(StandardCharsets.UTF_8),
                (candidate == null ? "" : candidate).getBytes(StandardCharsets.UTF_8));
    }

    public JsonObject setToken(String next) throws java.io.IOException {
        next = next == null ? "" : next.trim();
        if (next.isEmpty() || next.length() > 64 || !next.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("token 只能用字母、数字和 . - _ ~,长度 1~64");
        }
        synchronized (this.tokenLock) {
            String previous = this.config.token;
            this.config.token = next;
            try {
                this.config.save();
            } catch (java.io.IOException error) {
                this.config.token = previous;
                throw error;
            }
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("token", next);
            return out;
        }
    }

    /**
     * volatile 快照读,不排 {@link #setToken} 的队:事件路径在 Netty 事件循环上取 token,
     * 换 token 事务(写盘+失败回滚)期间读到瞬时旧值无害——changeToken 随后总会吊销全部订阅。
     */
    public String token() {
        return this.config.token;
    }

    public String selfId() {
        return this.selfId;
    }

    public boolean usingDefaultToken() {
        return PanelConfig.DEFAULT_TOKEN.equals(token());
    }

    public boolean isActive() {
        return System.currentTimeMillis() - this.lastActivityMs < IDLE_AFTER_MS;
    }

    /**
     * 缩略图:渲染在浏览器,这里只管缓存与签名握手。
     * <ul>
     *   <li>GET 命中给 PNG;内容已过期时附 {@code X-Thumb-Stale: <新签名>},前端照常显示旧图
     *       并就地重渲替换 —— 不 404,飞船天天动,失配即闪占位太难看;</li>
     *   <li>GET 未命中 404 thumb_pending,体在盘上就附当前签名作为「请你渲」的邀请函;</li>
     *   <li>POST 收前端渲好的图,签名对得上才入库(渲染期间体已变化的陈旧图直接拒)。</li>
     * </ul>
     * 旧版本集群节点没有这条路由,同样落在 404,前端占位兜底,协议不破。PNG 自带压缩,不再走 gzip。
     */
    private PanelResponse thumbResponse(PanelRequest request, UUID uuid) throws Exception {
        if (this.thumbs == null) return PanelResponse.error(404, "thumb_pending");
        if ("POST".equals(request.method())) {
            String error = this.thumbs.accept(uuid, request.query().get("sig"), request.body());
            if (error != null) return PanelResponse.error(409, error);
            return PanelResponse.ok();
        }
        byte[] png = this.thumbs.read(uuid);
        String current = this.thumbs.currentSig(uuid);
        if (png != null) {
            Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Cache-Control", "private, no-store");
            if (current != null && !current.equals(this.thumbs.cachedSig(uuid))) {
                headers.put("X-Thumb-Stale", current);
            }
            return new PanelResponse(200, "image/png", png, false, headers);
        }
        JsonObject body = new JsonObject();
        body.addProperty("error", "thumb_pending");
        if (current != null) body.addProperty("sig", current);
        return PanelResponse.json(404, body, false);
    }

    private static PanelResponse previewResponse(PreviewSubsystem.Result result) {
        return switch (result.status()) {
            case READY -> new PanelResponse(200, "application/vnd.sablepanel.mesh-v2", result.payload(), true);
            case ACCEPTED -> {
                JsonObject body = new JsonObject();
                body.addProperty("status", "accepted");
                body.addProperty("retry_after", 1);
                yield new PanelResponse(202, "application/json",
                        body.toString().getBytes(StandardCharsets.UTF_8), false,
                        PanelResponse.NO_STORE);
            }
            case TOO_LARGE -> PanelResponse.error(413, "preview_too_large");
            case BUSY -> PanelResponse.error(503, "preview_busy");
            case RETRYABLE -> new PanelResponse(503, "application/json",
                    "{\"error\":\"preview_retryable\",\"retry_after\":1}".getBytes(StandardCharsets.UTF_8),
                    false, PanelResponse.NO_STORE);
            case CONFLICT -> PanelResponse.error(409, "preview_version_ambiguous");
            case NOT_FOUND -> PanelResponse.error(404, "条目读取失败(该体可能刚被移动或删除)");
            case FAILED -> PanelResponse.error(500, "preview_failed");
        };
    }

    private static PanelResponse resourceResponse(PreviewSubsystem.ResourceResult result, boolean manifest) {
        return switch (result.status()) {
            case READY -> new PanelResponse(200, manifest ? "application/json"
                    : "application/vnd.sablepanel.resource-shard", result.payload(), true,
                    Map.of("Cache-Control", manifest ? "private, no-cache"
                                    : "private, max-age=31536000, immutable",
                            "Vary", "Accept-Encoding"));
            case ACCEPTED -> {
                JsonObject body = new JsonObject();
                body.addProperty("status", "accepted");
                body.addProperty("retry_after", 1);
                if (result.progress() != null) {
                    body.addProperty("phase", result.progress().phase().name().toLowerCase(java.util.Locale.ROOT));
                    body.addProperty("source", result.progress().source());
                    body.addProperty("downloaded", result.progress().downloaded());
                    body.addProperty("total", result.progress().total());
                    body.addProperty("detail", result.progress().message());
                }
                yield new PanelResponse(202, "application/json",
                        body.toString().getBytes(StandardCharsets.UTF_8), false,
                        PanelResponse.NO_STORE);
            }
            case NOT_FOUND -> PanelResponse.error(404, "preview_resource_not_found");
            case BUSY -> new PanelResponse(503, "application/json",
                    "{\"error\":\"preview_resource_busy\",\"retry_after\":1}".getBytes(StandardCharsets.UTF_8),
                    false, PanelResponse.NO_STORE);
            case FAILED -> PanelResponse.error(503, "preview_resource_failed");
        };
    }

    private void markActivity() {
        long now = System.currentTimeMillis();
        boolean wasIdle = now - this.lastActivityMs >= IDLE_AFTER_MS;
        this.lastActivityMs = now;
        if (wasIdle) {
            SablePanel.LOGGER.info("sablepanel: [{}] panel active again, rescanning", this.selfId);
            this.ops.kit().rescanNow();
        }
    }

    private static List<UUID> readUuids(JsonObject body) {
        JsonArray values = body.getAsJsonArray("uuids");
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("uuids 为空");
        if (values.size() > 500) throw new IllegalArgumentException("单次最多 500 个");
        // 去重放在这里,所有批量入口共用:重复 uuid 只会让同一个体被处理两遍
        Set<UUID> result = new LinkedHashSet<>();
        for (var value : values) result.add(UUID.fromString(value.getAsString()));
        return List.copyOf(result);
    }

    private static Set<UUID> readUuidSet(JsonObject body, String name) {
        JsonArray values = body.getAsJsonArray(name);
        Set<UUID> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (var value : values) result.add(UUID.fromString(value.getAsString()));
        return result;
    }

    /** 一致性修复的 16 位十六进制 id 集合(悬空指针/失效追踪点共用同一编码) */
    private static Set<String> readHexSet(JsonObject body, String name) {
        Set<String> result = new LinkedHashSet<>();
        JsonArray values = body.getAsJsonArray(name);
        if (values == null) return result;
        for (var value : values) {
            String text = value.getAsString();
            if (!text.matches("[0-9a-f]{16}")) throw new IllegalArgumentException(name + " 含无效值");
            result.add(text);
        }
        return result;
    }

    private static List<String> readRecycleIds(PanelRequest request) {
        JsonArray values = request.jsonBody().getAsJsonArray("ids");
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("ids 为空");
        if (values.size() > 500) throw new IllegalArgumentException("单次最多处理 500 个物理组");
        Set<String> result = new LinkedHashSet<>();
        for (var value : values) {
            String id = value.getAsString();
            if (!RECYCLE_ID.matcher(id).matches()) throw new IllegalArgumentException("回收组 ID 无效");
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static void requirePost(PanelRequest request) {
        if (!"POST".equals(request.method())) throw new IllegalArgumentException("需要 POST");
    }

}
