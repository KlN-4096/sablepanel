package com.klnon.sablepanel.panel.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.data.BodyIndex;
import com.klnon.sablepanel.panel.data.DiskScanner;
import com.klnon.sablepanel.panel.data.MeshExtractor;
import com.klnon.sablepanel.panel.data.StatsCollector;
import com.klnon.sablepanel.panel.service.OpsService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

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
    private final String selfId;
    private final Object tokenLock = new Object();
    private final LinkedHashMap<String, byte[]> meshCache = new LinkedHashMap<>(16, 0.75f, true);
    private long meshCacheBytes;
    private volatile long lastActivityMs = System.currentTimeMillis();

    public PanelApiService(PanelConfig config, MinecraftServer server, BodyIndex index, OpsService ops) {
        this.config = config;
        this.server = server;
        this.index = index;
        this.ops = ops;
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
                return PanelResponse.json(200, this.index.view(), true);
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
                return PanelResponse.json(200, this.ops.restoreRecycleGroups(groupIds), true);
            }
            case "/api/rescan" -> {
                requirePost(request);
                this.ops.rescanNow();
                return PanelResponse.json(200, "{\"ok\":true}", false);
            }
            case "/api/ops/batch_delete" -> {
                requirePost(request);
                return PanelResponse.json(200, this.ops.deleteBatch(readUuids(request)), true);
            }
            case "/api/ops/pause" -> {
                requirePost(request);
                JsonObject body = request.jsonBody();
                return PanelResponse.json(200,
                        this.ops.setPaused(readUuids(body), body.has("paused") && body.get("paused").getAsBoolean()), false);
            }
            case "/api/ops/force_load" -> {
                requirePost(request);
                JsonObject body = request.jsonBody();
                return PanelResponse.json(200,
                        this.ops.setForced(readUuids(body), body.has("forced") && body.get("forced").getAsBoolean()), false);
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
                yield PanelResponse.json(200, this.ops.teleport(uuid,
                        Double.parseDouble(request.query().get("x")),
                        Double.parseDouble(request.query().get("y")),
                        Double.parseDouble(request.query().get("z"))), false);
            }
            case "teleport_player" -> {
                requirePost(request);
                String player = request.query().get("player");
                if (player == null || player.isBlank()) throw new IllegalArgumentException("player 缺失");
                yield PanelResponse.json(200, this.ops.teleportPlayer(uuid, UUID.fromString(player)), false);
            }
            case "delete" -> {
                requirePost(request);
                yield PanelResponse.json(200, this.ops.delete(uuid), false);
            }
            case "adopt" -> {
                requirePost(request);
                yield PanelResponse.json(200, this.ops.adopt(uuid), false);
            }
            case "deduplicate" -> {
                requirePost(request);
                yield PanelResponse.json(200, this.ops.deduplicate(uuid), false);
            }
            default -> PanelResponse.error(404, "not found");
        };
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
