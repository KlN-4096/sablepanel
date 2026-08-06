package com.klnon.sablepanel.panel.service;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.panel.data.DiskScanner;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

/** Standalone, removable consistency audit and explicit repair for Sable 2.0.3 metadata. */
public final class ConsistencyService {
    private static final int MAX_ISSUES = 10_000;
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /**
     * 反射句柄按需初始化(holder 惯用法):不点一致性扫描就不付 setAccessible 的代价,
     * 目标字段改名时也只砸扫描调用,不砸 ConsistencyService 类加载连带整个面板启动。
     */
    private static final class Reflect {
        static final Field LOADED_CHUNKS = field(SubLevelHoldingChunkMap.class, "loadedHoldingChunks");
        static final Field DATA_FOLDER = field(DimensionDataStorage.class, "dataFolder");

        private static Field field(Class<?> type, String name) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Exception error) {
                throw new ExceptionInInitializerError(error);
            }
        }
    }

    private final MinecraftServer server;
    private volatile Report report = Report.pending();

    public ConsistencyService(MinecraftServer server) {
        this.server = server;
    }

    public synchronized JsonObject scan(boolean startup) {
        try {
            Report fresh = JobService.underLocate(() -> collect(startup));
            this.report = fresh;
            return fresh.toJson();
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: consistency scan failed", error);
            Report failed = Report.failed(startup, error.getMessage());
            this.report = failed;
            return failed.toJson();
        }
    }

    public JsonObject view() {
        return this.report.toJson();
    }

    public synchronized JsonObject repair(String scanId, Set<String> pointerIds,
                                          Set<UUID> forced, Set<UUID> paused) throws Exception {
        Report shown = this.report;
        if (!shown.ready || !shown.id.equals(scanId)) throw new IllegalStateException("一致性结果已变化，请重新扫描");
        Report current = JobService.underLocate(() -> collect(false));
        Map<String, PointerIssue> pointerById = new LinkedHashMap<>();
        for (PointerIssue issue : current.pointers) pointerById.put(issue.id, issue);
        List<PointerIssue> selectedPointers = pointerIds.stream().map(pointerById::get).toList();
        if (selectedPointers.stream().anyMatch(java.util.Objects::isNull)
                || !current.forced.containsAll(forced) || !current.paused.containsAll(paused)) {
            this.report = current;
            throw new IllegalStateException("一致性结果已变化，请查看最新扫描");
        }
        if (selectedPointers.isEmpty() && forced.isEmpty() && paused.isEmpty()) {
            throw new IllegalArgumentException("没有选择修复项");
        }

        Map<String, Path> dimensions = DiskScanner.sublevelDirsStrict(this.server);
        RepairAttempt attempt = null;
        Path backup = null;
        String operationError = null;
        try {
            attempt = onMain(() -> repairOnMain(dimensions, selectedPointers, forced, paused));
            operationError = attempt.error;
            backup = attempt.backup;
        } catch (Exception error) {
            operationError = messageOf(error);
        }

        Report verified;
        try {
            verified = JobService.underLocate(() -> collect(false));
        } catch (Exception error) {
            String suffix = backup == null ? "" : "，元数据备份位于 " + backup;
            throw new IllegalStateException("修复可能部分完成" + suffix, error);
        }
        Set<String> remainingPointers = new LinkedHashSet<>();
        for (PointerIssue issue : verified.pointers) remainingPointers.add(issue.id);
        Set<String> failedItems = new LinkedHashSet<>();
        if (attempt != null) failedItems.addAll(attempt.skipped);
        if (attempt == null && operationError != null) {
            pointerIds.forEach(id -> failedItems.add("pointer:" + id));
            forced.forEach(uuid -> failedItems.add("forced:" + uuid));
            paused.forEach(uuid -> failedItems.add("paused:" + uuid));
        }
        for (String id : pointerIds) if (remainingPointers.contains(id)) failedItems.add("pointer:" + id);
        for (UUID uuid : forced) if (verified.forced.contains(uuid)) failedItems.add("forced:" + uuid);
        for (UUID uuid : paused) if (verified.paused.contains(uuid)) failedItems.add("paused:" + uuid);
        JsonArray failed = new JsonArray();
        failedItems.forEach(failed::add);
        int total = pointerIds.size() + forced.size() + paused.size();
        int succeeded = total - failed.size();
        JsonObject out = new JsonObject();
        out.addProperty("ok", succeeded);
        out.addProperty("total", total);
        out.addProperty("pointers", selectedPointers.stream()
                .filter(issue -> !failedItems.contains("pointer:" + issue.id)).mapToInt(PointerIssue::count).sum());
        out.addProperty("forced", forced.stream()
                .filter(uuid -> !failedItems.contains("forced:" + uuid)).count());
        out.addProperty("paused", paused.stream()
                .filter(uuid -> !failedItems.contains("paused:" + uuid)).count());
        if (!failed.isEmpty()) out.add("failed", failed);
        if (operationError != null) out.addProperty("warning", operationError);
        if (backup != null) out.addProperty("backup", backup.toString());
        JsonObject summary = new JsonObject();
        summary.addProperty("ok", succeeded);
        summary.addProperty("total", total);
        if (!failed.isEmpty()) summary.add("failed", failed.deepCopy());
        if (operationError != null) summary.addProperty("warning", operationError);
        if (backup != null) summary.addProperty("backup", backup.toString());
        this.report = verified.withRepair(summary);
        out.add("report", this.report.toJson());
        return out;
    }

    private RepairAttempt repairOnMain(Map<String, Path> dimensions, List<PointerIssue> requestedPointers,
                                       Set<UUID> requestedForced, Set<UUID> requestedPaused) throws Exception {
        List<String> warnings = new ArrayList<>();
        Map<UUID, List<DiskScanner.EntryMeta>> metadata =
                DiskScanner.scanEntryMetaStrict(dimensions, warnings);
        Set<DiskScanner.EntryKey> payloads = payloadKeys(metadata);
        Map<String, PointerIssue> dangling = new LinkedHashMap<>();
        for (PointerIssue issue : danglingPointers(dimensions, payloads, warnings)) dangling.put(issue.id, issue);

        Set<String> skipped = new LinkedHashSet<>();
        List<PointerIssue> pointers = new ArrayList<>();
        for (PointerIssue requested : requestedPointers) {
            if (payloads.contains(requested.key)) {
                skipped.add("pointer:" + requested.id);
                continue;
            }
            PointerIssue current = dangling.get(requested.id);
            if (current != null) pointers.add(current);
        }

        Set<UUID> forced = eligibleStates(requestedForced, metadata.keySet(), true, skipped);
        Set<UUID> paused = eligibleStates(requestedPaused, metadata.keySet(), false, skipped);
        Path backup = backupMetadata(dimensions, pointers, forced, paused);
        String error = null;
        try {
            repairPointersOnMain(pointers);
            for (UUID uuid : forced) ForceLoadService.removeOnMain(this.server, uuid);
            if (!paused.isEmpty()) PauseService.applyOnMain(this.server, paused, false);
        } catch (Exception failure) {
            error = messageOf(failure);
        }
        return new RepairAttempt(Set.copyOf(skipped), error, backup);
    }

    private Set<UUID> eligibleStates(Set<UUID> requested, Set<UUID> diskUuids, boolean forced,
                                     Set<String> skipped) {
        Set<UUID> eligible = new LinkedHashSet<>();
        for (UUID uuid : requested) {
            boolean present = forced ? ForceLoadService.isForcedOnMain(this.server, uuid)
                    : PauseService.isPaused(uuid);
            if (!present) continue;
            if (diskUuids.contains(uuid) || runtimeExistsOnMain(uuid)) {
                skipped.add((forced ? "forced:" : "paused:") + uuid);
            } else {
                eligible.add(uuid);
            }
        }
        return eligible;
    }

    private boolean runtimeExistsOnMain(UUID uuid) {
        for (ServerLevel level : this.server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            if (container.getSubLevel(uuid) instanceof ServerSubLevel body && !body.isRemoved()) return true;
            if (container.getHoldingChunkMap().getHoldingSubLevel(uuid) != null) return true;
        }
        return false;
    }

    private Report collect(boolean startup) throws Exception {
        List<String> warnings = new ArrayList<>();
        ScanSession scan = ScanSession.strict(this.server, warnings);
        Set<DiskScanner.EntryKey> payloads = payloadKeys(scan.meta());
        List<PointerIssue> allPointers = danglingPointers(scan.dims(), payloads, warnings);
        List<PointerIssue> pointers = allPointers.stream().limit(MAX_ISSUES).toList();

        RuntimeState runtime = onMain(() -> {
            Set<UUID> loaded = new LinkedHashSet<>();
            for (ServerLevel level : this.server.getAllLevels()) {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container == null) continue;
                for (ServerSubLevel body : container.getAllSubLevels()) loaded.add(body.getUniqueId());
            }
            Set<UUID> forced = ForceLoadService.forcedOnMain(this.server);
            Set<UUID> operational = new LinkedHashSet<>(forced);
            operational.addAll(PauseService.snapshot());
            for (UUID uuid : operational) if (runtimeExistsOnMain(uuid)) loaded.add(uuid);
            return new RuntimeState(loaded, forced);
        });
        Set<UUID> existing = new LinkedHashSet<>(scan.meta().keySet());
        existing.addAll(runtime.loaded);
        List<UUID> staleForced = runtime.forced.stream().filter(uuid -> !existing.contains(uuid)).sorted().toList();
        List<UUID> stalePaused = PauseService.snapshot().stream().filter(uuid -> !existing.contains(uuid)).sorted().toList();
        boolean truncated = allPointers.size() > pointers.size();
        return new Report(scanId(), true, startup, System.currentTimeMillis(), pointers,
                staleForced, stalePaused, List.copyOf(new LinkedHashSet<>(warnings)), truncated, null, null);
    }

    private static Set<DiskScanner.EntryKey> payloadKeys(
            Map<UUID, List<DiskScanner.EntryMeta>> metadata) {
        Set<DiskScanner.EntryKey> payloads = new LinkedHashSet<>();
        for (List<DiskScanner.EntryMeta> entries : metadata.values()) {
            for (DiskScanner.EntryMeta entry : entries) payloads.add(entry.key());
        }
        return payloads;
    }

    private static List<PointerIssue> danglingPointers(Map<String, Path> dimensions,
                                                       Set<DiskScanner.EntryKey> payloads,
                                                       List<String> warnings) throws IOException {
        Map<String, MutablePointerIssue> grouped = new LinkedHashMap<>();
        DiskScanner.forEachPointerStrict(dimensions, warnings, reference -> {
            if (payloads.contains(reference.key())) return;
            String key = reference.key().id() + "@" + reference.chunkX() + "," + reference.chunkZ();
            MutablePointerIssue issue = grouped.get(key);
            if (issue != null) {
                issue.count++;
            } else if (grouped.size() <= MAX_ISSUES) {
                issue = new MutablePointerIssue(reference);
                issue.count = 1;
                grouped.put(key, issue);
            }
        });
        return grouped.values().stream().map(MutablePointerIssue::freeze)
                .sorted(Comparator.comparing(PointerIssue::id)).toList();
    }

    private Path backupMetadata(Map<String, Path> dimensions, List<PointerIssue> pointers,
                                Set<UUID> forced, Set<UUID> paused) throws IOException {
        Path root = FMLPaths.GAMEDIR.get().resolve("sablepanel-repair")
                .resolve(BACKUP_TIME.format(LocalDateTime.now()) + "-" + this.report.id);
        Files.createDirectories(root);
        Set<Path> files = new LinkedHashSet<>();
        for (PointerIssue issue : pointers) {
            Path directory = dimensions.get(issue.key.dim());
            if (directory != null) files.add(directory.resolve("r." + Math.floorDiv(issue.chunkX, 32)
                    + "." + Math.floorDiv(issue.chunkZ, 32) + ".slvlr"));
        }
        if (!paused.isEmpty()) files.add(FMLPaths.CONFIGDIR.get().resolve("sablepanel").resolve("paused.json"));
        if (!forced.isEmpty()) {
            for (ServerLevel level : this.server.getAllLevels()) {
                files.add(ticketFile(level));
            }
        }
        int index = 0;
        for (Path source : files) {
            if (!Files.isRegularFile(source)) continue;
            String safe = source.getFileName().toString().replaceAll("[^0-9A-Za-z._-]", "_");
            Files.copy(source, root.resolve(index++ + "-" + safe), StandardCopyOption.COPY_ATTRIBUTES);
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private void repairPointersOnMain(List<PointerIssue> issues) throws Exception {
        record ChunkKey(String dimension, int x, int z) {
        }
        record Target(SubLevelHoldingChunkMap map, SubLevelHoldingChunk chunk,
                      ChunkPos position, List<PointerIssue> issues) {
        }
        Map<ChunkKey, List<PointerIssue>> byChunk = new LinkedHashMap<>();
        for (PointerIssue issue : issues) {
            ChunkKey key = new ChunkKey(issue.key.dim(), issue.chunkX, issue.chunkZ);
            byChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(issue);
        }

        List<Target> targets = new ArrayList<>();
        for (Map.Entry<ChunkKey, List<PointerIssue>> entry : byChunk.entrySet()) {
            ChunkKey key = entry.getKey();
            ServerLevel level = levelOf(key.dimension);
            ServerSubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
            if (container == null) throw new IllegalStateException("修复维度不可用: " + key.dimension);
            SubLevelHoldingChunkMap map = container.getHoldingChunkMap();
            ChunkPos pos = new ChunkPos(key.x, key.z);
            Map<Long, SubLevelHoldingChunk> loaded = (Map<Long, SubLevelHoldingChunk>) Reflect.LOADED_CHUNKS.get(map);
            SubLevelHoldingChunk chunk = loaded.get(pos.toLong());
            if (chunk == null) chunk = map.getStorage().attemptLoadHoldingChunk(pos);
            if (chunk == null) throw new IllegalStateException("holding 元数据已变化: " + pos);
            for (PointerIssue issue : entry.getValue()) {
                SavedSubLevelPointer pointer = new SavedSubLevelPointer(
                        (short) issue.key.storage(), (short) issue.key.index());
                long count = chunk.getSubLevelPointers().stream().filter(pointer::equals).count();
                if (count < issue.count) throw new IllegalStateException("holding 指针数量已变化: " + issue.id);
            }
            targets.add(new Target(map, chunk, pos, List.copyOf(entry.getValue())));
        }
        Set<SubLevelHoldingChunkMap> touched = new LinkedHashSet<>();
        for (Target target : targets) {
            for (PointerIssue issue : target.issues) {
                SavedSubLevelPointer pointer = new SavedSubLevelPointer(
                        (short) issue.key.storage(), (short) issue.key.index());
                for (int index = 0; index < issue.count; index++) {
                    if (!target.chunk.getSubLevelPointers().remove(pointer)) {
                        throw new IllegalStateException("holding 指针在修复时发生变化: " + issue.id);
                    }
                }
            }
            target.map.getStorage().attemptSaveHoldingChunk(target.position, target.chunk);
            touched.add(target.map);
        }
        for (SubLevelHoldingChunkMap map : touched) map.getStorage().flush();
    }

    private ServerLevel levelOf(String dimension) {
        try {
            ResourceLocation id = ResourceLocation.parse(dimension);
            return this.server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        } catch (Exception ignored) {
            return null;
        }
    }

    private <T> T onMain(Callable<T> task) throws Exception {
        return MainThread.on(this.server, 60, task);
    }

    private static Path ticketFile(ServerLevel level) throws IOException {
        try {
            java.io.File folder = (java.io.File) Reflect.DATA_FOLDER.get(level.getChunkSource().getDataStorage());
            return folder.toPath().resolve("sable_sub_level_force_load_tickets.dat");
        } catch (ReflectiveOperationException error) {
            throw new IOException("无法定位 Sable 常驻票文件", error);
        }
    }

    private static String scanId() {
        return Long.toUnsignedString(System.currentTimeMillis(), 36) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String issueId(DiskScanner.PointerReference reference) {
        try {
            String value = reference.key().id() + "@" + reference.chunkX() + "," + reference.chunkZ();
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private record RuntimeState(Set<UUID> loaded, Set<UUID> forced) {
    }

    private record RepairAttempt(Set<String> skipped, String error, Path backup) {
    }

    private record PointerIssue(String id, DiskScanner.EntryKey key, int chunkX, int chunkZ, int count) {
        JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("id", this.id);
            out.addProperty("target", this.key.id());
            out.addProperty("dim", this.key.dim());
            out.addProperty("chunk_x", this.chunkX);
            out.addProperty("chunk_z", this.chunkZ);
            out.addProperty("count", this.count);
            return out;
        }
    }

    private static final class MutablePointerIssue {
        private final DiskScanner.PointerReference reference;
        private int count;

        private MutablePointerIssue(DiskScanner.PointerReference reference) {
            this.reference = reference;
        }

        private PointerIssue freeze() {
            return new PointerIssue(issueId(this.reference), this.reference.key(),
                    this.reference.chunkX(), this.reference.chunkZ(), this.count);
        }
    }

    private record Report(String id, boolean ready, boolean startup, long scannedAt,
                          List<PointerIssue> pointers, List<UUID> forced, List<UUID> paused,
                          List<String> warnings, boolean truncated, String error, JsonObject repair) {
        static Report pending() {
            return new Report("", false, true, 0, List.of(), List.of(), List.of(), List.of(), false, null, null);
        }

        static Report failed(boolean startup, String error) {
            return new Report(scanId(), true, startup, System.currentTimeMillis(), List.of(), List.of(),
                    List.of(), List.of(), false, error == null ? "一致性扫描失败" : error, null);
        }

        Report withRepair(JsonObject value) {
            return new Report(this.id, this.ready, this.startup, this.scannedAt, this.pointers, this.forced,
                    this.paused, this.warnings, this.truncated, this.error, value.deepCopy());
        }

        JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("ready", this.ready);
            out.addProperty("scan_id", this.id);
            out.addProperty("startup", this.startup);
            out.addProperty("scanned_at", this.scannedAt);
            JsonArray pointerArray = new JsonArray();
            this.pointers.forEach(issue -> pointerArray.add(issue.toJson()));
            out.add("dangling_pointers", pointerArray);
            JsonArray forcedArray = new JsonArray();
            this.forced.forEach(uuid -> forcedArray.add(uuid.toString()));
            out.add("stale_forced", forcedArray);
            JsonArray pausedArray = new JsonArray();
            this.paused.forEach(uuid -> pausedArray.add(uuid.toString()));
            out.add("stale_paused", pausedArray);
            out.addProperty("issue_count", this.pointers.stream().mapToInt(PointerIssue::count).sum()
                    + this.forced.size() + this.paused.size());
            out.addProperty("truncated", this.truncated);
            JsonArray warningArray = new JsonArray();
            this.warnings.forEach(warningArray::add);
            if (!warningArray.isEmpty()) out.add("warnings", warningArray);
            if (this.error != null) out.addProperty("error", this.error);
            if (this.repair != null) out.add("last_repair", this.repair.deepCopy());
            return out;
        }
    }
}
