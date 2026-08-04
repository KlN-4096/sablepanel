package com.klnon.sablepanel.panel.api;

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
import net.minecraft.server.level.ServerLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class PanelApiService {
    private static final long MESH_CACHE_LIMIT = 24L * 1024 * 1024;
    private static final long IDLE_AFTER_MS = 300_000L;
    private static final Pattern RECYCLE_MESH = Pattern.compile(
            "/api/recycle/([0-9A-Za-z_-]{8,96})/body/([0-9a-fA-F-]{36})/mesh");
    private static final Pattern BODY_OP = Pattern.compile(
            "/api/body/([0-9a-fA-F-]{36})/(mesh|copies|teleport_player|teleport|delete|adopt|deduplicate)");

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
                // 正在排队/执行的作业:前端据此显示转圈并禁用按钮
                view.add("busy", this.jobs.busyView());
                // 各维度真实建筑高度,给"虚空中/极高空"筛选用。
                // 不能在前端写死 -64/320:FTB 那边有 16 个子维度,高度限制各不相同
                view.add("dims", buildHeights());
                return PanelResponse.json(200, view, true);
            }
            case "/api/jobs" -> {
                String file = request.query().get("file");
                return PanelResponse.json(200,
                        file == null || file.isBlank() ? this.jobs.view() : JobService.readLog(file), true);
            }
            case "/api/players" -> {
                return PanelResponse.json(200, this.ops.listPlayers(), false);
            }
            case "/api/stats" -> {
                Map<String, String> values = request.query();
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
                return PanelResponse.json(200, stats, true);
            }
            case "/api/recycle" -> {
                return PanelResponse.json(200, this.ops.recycleView(), true);
            }
            case "/api/recycle/config" -> {
                requirePost(request);
                JsonObject body = request.jsonBody();
                if (!body.has("max_files")) throw new IllegalArgumentException("max_files 缺失");
                return PanelResponse.json(200, this.ops.setRecycleLimit(body.get("max_files").getAsInt()), false);
            }
            case "/api/recycle/restore" -> {
                requirePost(request);
                JsonArray ids = request.jsonBody().getAsJsonArray("ids");
                if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("ids 为空");
                if (ids.size() > 500) throw new IllegalArgumentException("单次最多恢复 500 个依赖组");
                List<String> groupIds = new ArrayList<>();
                for (var id : ids) groupIds.add(id.getAsString());
                return enqueue("回收站恢复", List.of(), groupIds.size() + " 个依赖组",
                        () -> this.ops.restoreRecycleGroups(groupIds));
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
            case "/api/ops/batch_delete" -> {
                requirePost(request);
                List<UUID> uuids = readUuids(request);
                return enqueue("批量删除", uuids, targetLabel(uuids), () -> this.ops.deleteBatch(uuids));
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
    private PanelResponse enqueue(String op, List<UUID> targets, String name, JobService.Work work) {
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

    /** 各维度建筑高度上下限,给"虚空中/极高空"筛选做判据 */
    private JsonObject buildHeights() {
        JsonObject dims = new JsonObject();
        for (ServerLevel level : this.server.getAllLevels()) {
            JsonObject range = new JsonObject();
            range.addProperty("min", level.getMinBuildHeight());
            range.addProperty("max", level.getMaxBuildHeight());
            dims.add(level.dimension().location().toString(), range);
        }
        return dims;
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

    public String token() {
        synchronized (this.tokenLock) {
            return this.config.token;
        }
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
            DiskScanner.LocatedEntry located = DiskScanner.locateEntry(entry.getKey(), entry.getValue(), uuid);
            if (located != null) return located.tag();
        }
        return null;
    }

    private void cache(String key, byte[] value) {
        synchronized (this.meshCache) {
            this.meshCache.put(key, value);
            this.meshCacheBytes += value.length;
            var iterator = this.meshCache.entrySet().iterator();
            while (this.meshCacheBytes > MESH_CACHE_LIMIT && iterator.hasNext()) {
                var victim = iterator.next();
                if (victim.getKey().equals(key)) continue;
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
        List<UUID> result = new ArrayList<>();
        for (var value : values) result.add(UUID.fromString(value.getAsString()));
        return result;
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

    private static String messageOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }
}
