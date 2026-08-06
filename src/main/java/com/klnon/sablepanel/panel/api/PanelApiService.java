package com.klnon.sablepanel.panel.api;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.data.BodyIndex;
import com.klnon.sablepanel.panel.data.DiskScanner;
import com.klnon.sablepanel.panel.data.MeshExtractor;
import com.klnon.sablepanel.panel.data.StatsCollector;
import com.klnon.sablepanel.panel.service.JobService;
import com.klnon.sablepanel.panel.service.OpsService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

public final class PanelApiService {
    private static final long MESH_CACHE_LIMIT = 24L * 1024 * 1024;
    private static final long IDLE_AFTER_MS = 300_000L;
    private static final Pattern RECYCLE_MESH = Pattern.compile(
            "/api/recycle/([0-9A-Za-z_-]{8,96})/body/([0-9a-fA-F-]{36})/mesh");
    private static final Pattern RECYCLE_ID = Pattern.compile("[0-9A-Za-z_-]{8,96}");
    private static final Pattern COPY_MESH = Pattern.compile(
            "/api/body/([0-9a-fA-F-]{36})/copy/([0-9a-f]{16})/mesh");
    private static final Pattern BODY_OP = Pattern.compile(
            "/api/body/([0-9a-fA-F-]{36})/(mesh|copies|teleport_player|teleport|delete|adopt|deduplicate|resolve_copies|quarantine_copies)");

    private final PanelConfig config;
    private final MinecraftServer server;
    private final BodyIndex index;
    private final OpsService ops;
    private final JobService jobs;
    private final String selfId;
    private final Object tokenLock = new Object();
    private final LinkedHashMap<String, byte[]> meshCache = new LinkedHashMap<>(16, 0.75f, true);
    private long meshCacheBytes;
    private volatile long lastActivityMs = System.currentTimeMillis();

    public PanelApiService(PanelConfig config, MinecraftServer server, BodyIndex index, OpsService ops,
                           JobService jobs) {
        this.config = config;
        this.server = server;
        this.index = index;
        this.ops = ops;
        this.jobs = jobs;
        this.selfId = config.serverId();
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
        switch (path) {
            case "/api/bodies" -> {
                JsonObject view = this.index.view();
                // 作业状态不在这儿:它每两秒变一次,而这份快照最大 12 MiB。前端改从
                // /api/jobs 的 running[] 取,那里字段是全的,顺带省掉一次日志请求
                // "虚空中/极高空"的高度阈值(服主可在配置里调),前端据此筛选
                JsonObject reach = new JsonObject();
                reach.addProperty("void_below", this.config.voidBelowY);
                reach.addProperty("sky_above", this.config.skyAboveY);
                view.add("reach", reach);
                return PanelResponse.json(200, view, true);
            }
            case "/api/jobs" -> {
                String file = request.query().get("file");
                if (file != null && !file.isBlank()) return PanelResponse.json(200, JobService.readLog(file), true);
                // poll=1 是面板每 2 秒那一次:只要 running 和精简过的历史,不列日志目录
                return PanelResponse.json(200, this.jobs.view(request.query().containsKey("poll")), true);
            }
            case "/api/players" -> {
                return PanelResponse.json(200, this.ops.listPlayers(), false);
            }
            case "/api/stats" -> {
                return PanelResponse.json(200, StatsCollector.INSTANCE.toJson(), true);
            }
            case "/api/recycle" -> {
                // 游标分页:cursor 是上一页最后一个组的 id,服务端只读这一页的 manifest
                String version = request.query().getOrDefault("version", "latest");
                if (!"latest".equals(version) && !"old".equals(version)) {
                    throw new IllegalArgumentException("version 必须是 latest 或 old");
                }
                String cursor = request.query().getOrDefault("cursor", "");
                int limit = request.query().containsKey("limit")
                        ? Integer.parseInt(request.query().get("limit")) : 0;
                return PanelResponse.json(200, this.ops.recycleView(version, cursor, limit), true);
            }
            case "/api/recycle/config" -> {
                requirePost(request);
                JsonObject body = request.jsonBody();
                if (!body.has("max_files")) throw new IllegalArgumentException("max_files 缺失");
                return PanelResponse.json(200, this.ops.setRecycleLimit(body.get("max_files").getAsInt()), false);
            }
            case "/api/recycle/restore" -> {
                requirePost(request);
                List<String> groupIds = readRecycleIds(request);
                return enqueue("回收站恢复", List.of(), groupIds.size() + " 个依赖组",
                        () -> this.ops.restoreRecycleGroups(groupIds));
            }
            case "/api/recycle/purge" -> {
                requirePost(request);
                List<String> groupIds = readRecycleIds(request);
                return enqueue("回收站彻底删除", List.of(), groupIds.size() + " 个依赖组",
                        () -> this.ops.purgeRecycleGroups(groupIds));
            }
            case "/api/rescan" -> {
                requirePost(request);
                return enqueue("重扫磁盘", List.of(), "", () -> {
                    this.ops.rescanNow();
                    JsonObject out = new JsonObject();
                    out.addProperty("ok", true);
                    return out;
                });
            }
            case "/api/consistency" -> {
                return PanelResponse.json(200, this.ops.consistencyView(), true);
            }
            case "/api/consistency/scan" -> {
                requirePost(request);
                return enqueue("一致性检查", List.of(), "", () -> this.ops.analyzeConsistency(false));
            }
            case "/api/consistency/repair" -> {
                requirePost(request);
                JsonObject body = request.jsonBody();
                String scanId = body.has("scan_id") ? body.get("scan_id").getAsString() : "";
                if (!scanId.matches("[0-9a-z]+-[0-9a-f]{8}")) throw new IllegalArgumentException("scan_id 无效");
                Set<String> pointers = readStrings(body, "pointers", "[0-9a-f]{16}");
                Set<UUID> forced = readUuidSet(body, "forced");
                Set<UUID> paused = readUuidSet(body, "paused");
                int total = pointers.size() + forced.size() + paused.size();
                if (total == 0 || total > 10_000) throw new IllegalArgumentException("修复项数量无效");
                return enqueue("一致性修复", List.of(), total + " 项",
                        () -> this.ops.repairConsistency(scanId, pointers, forced, paused));
            }
            case "/api/ops/batch_delete" -> {
                requirePost(request);
                List<UUID> uuids = readUuids(request);
                return enqueue("批量删除", uuids, targetLabel(uuids), () -> this.ops.deleteBatch(uuids));
            }
            case "/api/ops/batch_adopt" -> {
                requirePost(request);
                List<UUID> uuids = readUuids(request);
                return enqueue("批量收养", uuids, targetLabel(uuids), () -> this.ops.adoptBatch(uuids));
            }
            case "/api/ops/pause" -> {
                requirePost(request);
                JsonObject body = request.jsonBody();
                List<UUID> uuids = readUuids(body);
                boolean paused = body.has("paused") && body.get("paused").getAsBoolean();
                return enqueue(paused ? "暂停" : "恢复", uuids, targetLabel(uuids),
                        () -> this.ops.setPaused(uuids, paused));
            }
            case "/api/ops/force_load" -> {
                requirePost(request);
                JsonObject body = request.jsonBody();
                List<UUID> uuids = readUuids(body);
                boolean forced = body.has("forced") && body.get("forced").getAsBoolean();
                return enqueue(forced ? "常驻加载" : "取消常驻", uuids, targetLabel(uuids),
                        () -> this.ops.setForced(uuids, forced));
            }
        }

        var recycleMesh = RECYCLE_MESH.matcher(path);
        if (recycleMesh.matches()) {
            JsonObject mesh = this.ops.recycleMesh(recycleMesh.group(1), UUID.fromString(recycleMesh.group(2)));
            return PanelResponse.json(200, mesh, true);
        }

        var copyMesh = COPY_MESH.matcher(path);
        if (copyMesh.matches()) {
            JsonObject mesh = this.ops.copyVersionMesh(
                    UUID.fromString(copyMesh.group(1)), copyMesh.group(2));
            return PanelResponse.json(200, mesh, true);
        }

        var bodyOp = BODY_OP.matcher(path);
        if (!bodyOp.matches()) return PanelResponse.error(404, "not found");
        UUID uuid = UUID.fromString(bodyOp.group(1));
        return switch (bodyOp.group(2)) {
            case "mesh" -> mesh(uuid);
            case "copies" -> PanelResponse.json(200, this.ops.inspectCopies(uuid), true);
            case "teleport" -> {
                requirePost(request);
                // 参数在入队前解析:格式错误要当场 400,而不是过几秒变成一条失败作业
                double x = Double.parseDouble(request.query().get("x"));
                double y = Double.parseDouble(request.query().get("y"));
                double z = Double.parseDouble(request.query().get("z"));
                yield enqueue("传送", List.of(uuid), targetLabel(List.of(uuid)),
                        () -> this.ops.teleport(uuid, x, y, z));
            }
            case "teleport_player" -> {
                requirePost(request);
                String player = request.query().get("player");
                if (player == null || player.isBlank()) throw new IllegalArgumentException("player 缺失");
                UUID playerUuid = UUID.fromString(player);
                yield enqueue("传送玩家", List.of(uuid), targetLabel(List.of(uuid)),
                        () -> this.ops.teleportPlayer(uuid, playerUuid));
            }
            case "delete" -> {
                requirePost(request);
                yield enqueue("删除", List.of(uuid), targetLabel(List.of(uuid)), () -> this.ops.delete(uuid));
            }
            case "adopt" -> {
                requirePost(request);
                yield enqueue("收养", List.of(uuid), targetLabel(List.of(uuid)), () -> this.ops.adopt(uuid));
            }
            case "deduplicate" -> {
                requirePost(request);
                yield enqueue("去重", List.of(uuid), targetLabel(List.of(uuid)),
                        () -> this.ops.deduplicate(uuid));
            }
            case "resolve_copies" -> {
                requirePost(request);
                JsonObject body = request.jsonBody();
                String version = body.has("version") ? body.get("version").getAsString() : "";
                if (!version.matches("[0-9a-f]{16}")) throw new IllegalArgumentException("version 无效");
                yield enqueue("处理副本", List.of(uuid), targetLabel(List.of(uuid)),
                        () -> this.ops.resolveCopyVersion(uuid, version));
            }
            case "quarantine_copies" -> {
                requirePost(request);
                yield enqueue("隔离不完整副本", List.of(uuid), targetLabel(List.of(uuid)),
                        () -> this.ops.quarantineIncompleteCopies(uuid));
            }
            default -> PanelResponse.error(404, "not found");
        };
    }

    /**
     * 把一个操作交给 {@link JobService} 后台执行,请求线程立刻返回。
     * <p>
     * 目标体已有在跑的作业时返回 409 —— 这是重复点击的第一道闸:巨型体的操作可能跑
     * 几分钟,浏览器 30 秒就超时,用户看不到进展就会再点,从前正是这样把传输层
     * 4 个在飞槽位全占死、整个面板永久 503 的。
     */
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

    private PanelResponse mesh(UUID uuid) throws Exception {
        Map<String, Path> dims = DiskScanner.sublevelDirs(this.server);
        DiskScanner.DiskEntry entry = this.index.findEntry(uuid);
        Path dir = entry != null ? dims.get(entry.key().dim()) : null;
        long mtime = 0;
        if (dir != null) {
            try {
                mtime = Files.getLastModifiedTime(dir.resolve(
                        "r." + entry.key().rx() + "." + entry.key().rz() + "." + entry.key().storage() + ".slvls")).toMillis();
            } catch (Exception ignored) {
            }
        }
        String cacheKey = uuid + "@" + (entry != null ? entry.key().id() : "?") + "@" + mtime;
        byte[] cached;
        synchronized (this.meshCache) {
            cached = this.meshCache.get(cacheKey);
        }
        if (cached == null) {
            CompoundTag tag = dir != null ? DiskScanner.readEntryTag(dir, entry.key()) : null;
            if (tag == null || !uuid.equals(safeUuid(tag))) {
                tag = locateTag(dims, uuid);
            }
            if (tag == null) return PanelResponse.error(404, "条目读取失败(该体可能刚被移动或删除)");
            cached = MeshExtractor.extract(tag).toString().getBytes(StandardCharsets.UTF_8);
            cache(cacheKey, cached);
        }
        return new PanelResponse(200, "application/json", cached, true);
    }

    private static CompoundTag locateTag(Map<String, Path> dims, UUID uuid) throws Exception {
        for (var entry : dims.entrySet()) {
            DiskScanner.LocatedEntry located = DiskScanner.locateEntries(entry.getKey(), entry.getValue(), java.util.Set.of(uuid)).get(uuid);
            if (located != null) return located.tag();
        }
        return null;
    }

    private void cache(String key, byte[] value) {
        synchronized (this.meshCache) {
            // 单项都装不进总预算时只服务本次请求,不能让它绕过缓存硬上限。
            if (value.length > MESH_CACHE_LIMIT) return;
            byte[] previous = this.meshCache.put(key, value);
            this.meshCacheBytes += value.length - (previous != null ? previous.length : 0);
            var iterator = this.meshCache.entrySet().iterator();
            while (this.meshCacheBytes > MESH_CACHE_LIMIT && iterator.hasNext()) {
                var victim = iterator.next();
                this.meshCacheBytes -= victim.getValue().length;
                iterator.remove();
            }
        }
    }

    private void markActivity() {
        long now = System.currentTimeMillis();
        boolean wasIdle = now - this.lastActivityMs >= IDLE_AFTER_MS;
        this.lastActivityMs = now;
        if (wasIdle) {
            SablePanel.LOGGER.info("sablepanel: [{}] panel active again, rescanning", this.selfId);
            this.ops.rescanNow();
        }
    }

    private static List<UUID> readUuids(PanelRequest request) {
        return readUuids(request.jsonBody());
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

    private static Set<String> readStrings(JsonObject body, String name, String pattern) {
        JsonArray values = body.getAsJsonArray(name);
        Set<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (var value : values) {
            String text = value.getAsString();
            if (!text.matches(pattern)) throw new IllegalArgumentException(name + " 含无效值");
            result.add(text);
        }
        return result;
    }

    private static Set<UUID> readUuidSet(JsonObject body, String name) {
        JsonArray values = body.getAsJsonArray(name);
        Set<UUID> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (var value : values) result.add(UUID.fromString(value.getAsString()));
        return result;
    }

    private static List<String> readRecycleIds(PanelRequest request) {
        JsonArray values = request.jsonBody().getAsJsonArray("ids");
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("ids 为空");
        if (values.size() > 500) throw new IllegalArgumentException("单次最多处理 500 个依赖组");
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

    private static UUID safeUuid(CompoundTag tag) {
        try {
            return tag.getUUID("uuid");
        } catch (Exception ignored) {
            return null;
        }
    }

}
