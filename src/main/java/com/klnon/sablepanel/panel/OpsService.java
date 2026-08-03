package com.klnon.sablepanel.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.EventLog;
import com.klnon.sablepanel.SablePanel;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector3d;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 面板操作(sable 交互全部主线程执行):传送/删除/收养。
 * 强制加载优先级:已加载 → holding 内存态 snatch → 盘上活指针 snatch → 孤儿收养
 * (读条目 → SubLevelSerializer.fromData → loadHoldingSubLevel,绕过 sable 的
 *  同 chunk 依赖门控;依赖闭包同 tick 一起收养)。
 * 删除前把磁盘条目导出到回收站;所有操作写审计 JSONL 并校验实际结果。
 */
public final class OpsService {
    private static final int MAX_CHAIN = 64;

    private final MinecraftServer server;
    private final BodyIndex index;
    private final Runnable rescan;
    private final RecycleStore recycle;

    public OpsService(MinecraftServer server, BodyIndex index, Runnable rescan, PanelConfig config) {
        this.server = server;
        this.index = index;
        this.rescan = rescan;
        this.recycle = new RecycleStore(config);
    }

    /** 收养链成员:条目位置+NBT+可选活指针 */
    private record MemberPlan(DiskScanner.EntryKey key, CompoundTag tag, DiskScanner.LiveLocation cold) {
    }

    private record DiskVerification(Map<UUID, Integer> entries,
                                    Map<DiskScanner.EntryKey, Integer> pointers) {
    }

    private record DeleteCopy(DiskScanner.EntryKey key, CompoundTag tag, int blocks,
                              List<DiskScanner.LiveLocation> pointers) {
        MemberPlan loadPlan() {
            return new MemberPlan(this.key, this.tag, this.pointers.isEmpty() ? null : this.pointers.get(0));
        }
    }

    private record SnatchKey(String dim, int chunkX, int chunkZ) {
    }

    private record SnatchRequest(UUID uuid, DeleteCopy copy, DiskScanner.LiveLocation location) {
    }

    private record CopyCandidate(DiskScanner.EntryKey key, CompoundTag tag, int blocks,
                                 List<DiskScanner.LiveLocation> pointers) {
    }

    private record CopyInspection(Map<String, Path> dimensions, List<CopyCandidate> copies,
                                  CopyCandidate keep, boolean identical) {
    }

    private static final class DeleteComponent {
        private final Set<UUID> targets = new LinkedHashSet<>();
        private final Map<UUID, List<DeleteCopy>> copies = new LinkedHashMap<>();
        private RecycleStore.Stage stage;

        void addTarget(UUID uuid, List<DeleteCopy> prepared) {
            this.targets.add(uuid);
            this.copies.put(uuid, List.copyOf(prepared));
        }
    }

    private static final class DeleteStatus {
        private final UUID uuid;
        private final Set<String> errors = new LinkedHashSet<>();
        private final Set<DiskScanner.EntryKey> entryKeys = new LinkedHashSet<>();
        private String recycleGroup;
        private boolean removed;
        private boolean alreadyAbsent;
        private boolean ok;
        private int remainingEntries;
        private int remainingPointers;

        DeleteStatus(UUID uuid) {
            this.uuid = uuid;
        }

        void fail(String message) {
            if (message != null && !message.isBlank()) this.errors.add(message);
            this.ok = false;
        }

        JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("uuid", this.uuid.toString());
            out.addProperty("ok", this.ok);
            if (this.recycleGroup != null) out.addProperty("recycle", this.recycleGroup);
            if (!this.ok) out.addProperty("state", "failed");
            else if (this.alreadyAbsent) out.addProperty("state", "already_absent");
            else out.addProperty("state", "deleted");
            if (this.remainingEntries > 0) out.addProperty("remaining_entries", this.remainingEntries);
            if (this.remainingPointers > 0) out.addProperty("remaining_pointers", this.remainingPointers);
            if (!this.errors.isEmpty()) out.addProperty("error", String.join("; ", this.errors));
            return out;
        }
    }

    private record DeleteFlush(Set<ServerLevel> touched,
                               Map<ServerLevel, Set<UUID>> targetsByLevel) {
    }

    private static final class DeleteExecution {
        private final DeleteComponent component;
        private final Map<UUID, DeleteStatus> statuses;
        private final DeleteFlush flush;
        private final Map<UUID, ServerSubLevel> removedBodies = new LinkedHashMap<>();
        private final Map<UUID, List<GlobalSavedSubLevelPointer>> handledPointers = new LinkedHashMap<>();

        DeleteExecution(DeleteComponent component, Map<UUID, DeleteStatus> statuses, DeleteFlush flush) {
            this.component = component;
            this.statuses = statuses;
            this.flush = flush;
        }
    }

    public JsonObject teleport(UUID uuid, double x, double y, double z) throws Exception {
        Map<UUID, MemberPlan> chain = prepareChain(uuid);
        JsonObject result = onMain(() -> {
            ServerSubLevel sl = ensureLoaded(uuid, chain);
            ServerLevel level = sl.getLevel();
            SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
            sl.logicalPose().position().set(x, y, z);
            phys.getPipeline().teleport(sl, new Vector3d(x, y, z), sl.logicalPose().orientation());
            sl.updateLastPose();
            audit("teleport", uuid, sl.getName(), x + "," + y + "," + z);
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("dim", level.dimension().location().toString());
            return r;
        });
        this.rescan.run();
        return result;
    }

    public JsonObject delete(UUID uuid) throws Exception {
        JsonObject batch = deleteBatch(List.of(uuid));
        JsonObject out = new JsonObject();
        int deleted = batch.get("ok").getAsInt();
        int total = batch.get("total").getAsInt();
        out.addProperty("ok", deleted == total);
        out.addProperty("deleted", deleted);
        out.addProperty("total", total);
        out.add("results", batch.getAsJsonArray("results"));
        if (batch.has("warnings")) out.add("warnings", batch.get("warnings"));
        List<String> errors = new ArrayList<>();
        for (var element : batch.getAsJsonArray("results")) {
            JsonObject result = element.getAsJsonObject();
            if (result.has("recycle") && !out.has("recycle")) out.add("recycle", result.get("recycle"));
            if (!result.get("ok").getAsBoolean() && result.has("error")) errors.add(result.get("error").getAsString());
        }
        if (!errors.isEmpty()) out.addProperty("error", String.join("; ", errors));
        return out;
    }

    /**
     * 批量删除按依赖组件执行。每个 holding chunk 只准备一次,随后在同一个主线程任务里
     * 连续 remove,随后统一 saveAll。这样后续目标不会再从尚未落盘的旧指针复活前面已删目标。
     *
     * <p>这里只走 sable 的 removeSubLevel(REMOVED) + saveAll,不直接清存储槽。
     */
    public synchronized JsonObject deleteBatch(List<UUID> uuids) {
        List<UUID> requested = new ArrayList<>(new LinkedHashSet<>(uuids));
        Map<UUID, DeleteStatus> statuses = new LinkedHashMap<>();
        for (UUID uuid : requested) statuses.put(uuid, new DeleteStatus(uuid));
        List<DeleteComponent> components = List.of();
        List<String> warnings = new ArrayList<>();

        try {
            components = prepareDeleteComponents(requested, warnings);
            for (DeleteComponent component : components) {
                for (UUID target : component.targets) statuses.computeIfAbsent(target, DeleteStatus::new);
            }
            if (statuses.size() > 500) throw new IllegalStateException("依赖组展开后超过 500 个物理体");
            stageDeleteBackups(components, statuses);
            executeDeleteComponents(components, statuses);
        } catch (Exception e) {
            String message = "删除事务失败: " + messageOf(e);
            for (DeleteStatus status : statuses.values()) status.fail(message);
            SablePanel.LOGGER.warn("sablepanel: batch delete transaction failed", e);
        }
        verifyDeletedTargets(statuses, warnings);
        finalizeDeleteBackups(components, statuses, warnings);
        for (DeleteStatus status : statuses.values()) {
            if (!status.ok) audit("delete_failed", status.uuid, null, String.join("; ", status.errors));
        }
        JsonObject response = deleteResponse(new ArrayList<>(statuses.keySet()), statuses);
        response.addProperty("requested", requested.size());
        attachWarnings(response, warnings);
        return response;
    }

    private List<DeleteComponent> prepareDeleteComponents(List<UUID> targets, List<String> warnings)
            throws Exception {
        Map<String, Path> dimensions = DiskScanner.sublevelDirsStrict(this.server);
        Map<UUID, List<DiskScanner.EntryMeta>> meta = DiskScanner.scanEntryMetaStrict(dimensions, warnings);
        // 纯运行时新体(刚生成、盘上还没有条目)先落一次盘再删:内存里的方块不落盘就无从备份
        if (flushUnsavedTargets(targets, meta)) {
            dimensions = DiskScanner.sublevelDirsStrict(this.server);
            meta = DiskScanner.scanEntryMetaStrict(dimensions, warnings);
        }
        List<Set<UUID>> selectedGroups = DiskScanner.selectedDependencyComponents(meta, targets);
        Set<UUID> selected = new LinkedHashSet<>();
        for (Set<UUID> group : selectedGroups) {
            selected.addAll(group);
        }
        Map<UUID, List<DeleteCopy>> prepared = readDeleteCopies(dimensions, meta, selected, warnings);

        List<DeleteComponent> components = new ArrayList<>();
        for (Set<UUID> group : selectedGroups) {
            DeleteComponent component = new DeleteComponent();
            for (UUID target : group) {
                component.addTarget(target, prepared.getOrDefault(target, List.of()));
            }
            components.add(component);
        }
        return components;
    }

    /** 只为指定成员重读完整 NBT；重读时验 UUID，避免准备期间槽位被 Sable 复用。 */
    private Map<UUID, List<DeleteCopy>> readDeleteCopies(
            Map<String, Path> dimensions, Map<UUID, List<DiskScanner.EntryMeta>> meta,
            Set<UUID> targets, List<String> warnings) throws Exception {
        Map<DiskScanner.EntryKey, CompoundTag> tags = new LinkedHashMap<>();
        for (UUID target : targets) {
            for (DiskScanner.EntryMeta copy : meta.getOrDefault(target, List.of())) {
                tags.put(copy.key(), readVerifiedTag(dimensions, target, copy.key()));
            }
        }
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers =
                DiskScanner.locatePointersStrict(dimensions, tags.keySet(), warnings);
        Map<UUID, List<DeleteCopy>> result = new LinkedHashMap<>();
        for (UUID target : targets) {
            List<DeleteCopy> copies = new ArrayList<>();
            for (DiskScanner.EntryMeta copy : meta.getOrDefault(target, List.of())) {
                CompoundTag tag = tags.get(copy.key());
                copies.add(new DeleteCopy(copy.key(), tag,
                        DiskScanner.countBlocks(tag.getCompound("plot"), null),
                        List.copyOf(pointers.getOrDefault(copy.key(), List.of()))));
            }
            result.put(target, List.copyOf(copies));
        }
        return result;
    }

    private static CompoundTag readVerifiedTag(Map<String, Path> dims, UUID uuid, DiskScanner.EntryKey key)
            throws IOException {
        Path dir = dims.get(key.dim());
        CompoundTag tag = dir != null ? DiskScanner.readEntryTag(dir, key) : null;
        if (tag == null || !uuid.equals(tagUuid(tag))) {
            throw new IOException("条目 " + key.id() + " 在准备阶段被 sable 搬迁，未执行删除，请重试");
        }
        return tag;
    }

    /** 目标已加载但盘上没有条目(刚生成的新体):先 saveAll 落盘,返回是否落过 */
    private boolean flushUnsavedTargets(List<UUID> targets, Map<UUID, List<DiskScanner.EntryMeta>> meta)
            throws Exception {
        List<UUID> unsaved = new ArrayList<>();
        for (UUID uuid : targets) {
            if (meta.getOrDefault(uuid, List.of()).isEmpty()) unsaved.add(uuid);
        }
        if (unsaved.isEmpty()) return false;
        JsonObject result = onMainUntilComplete(() -> {
            Set<ServerLevel> touched = new LinkedHashSet<>();
            for (UUID uuid : unsaved) {
                ServerSubLevel body = resolveLoaded(uuid);
                if (body != null) touched.add(body.getLevel());
            }
            for (ServerLevel level : touched) {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container == null) throw new IllegalStateException("物理体容器不存在");
                container.getHoldingChunkMap().saveAll();
            }
            JsonObject out = new JsonObject();
            out.addProperty("flushed", touched.size());
            return out;
        });
        return result.get("flushed").getAsInt() > 0;
    }

    private static UUID tagUuid(CompoundTag tag) {
        try {
            return tag.getUUID("uuid");
        } catch (Throwable error) {
            return null;
        }
    }

    private void stageDeleteBackups(List<DeleteComponent> components, Map<UUID, DeleteStatus> statuses) {
        for (DeleteComponent component : components) {
            List<RecycleStore.Source> sources = new ArrayList<>();
            for (UUID uuid : component.targets) {
                DeleteStatus status = statuses.get(uuid);
                List<DeleteCopy> ordered = new ArrayList<>(component.copies.getOrDefault(uuid, List.of()));
                ordered.sort((first, second) -> {
                    int reachable = Boolean.compare(!second.pointers().isEmpty(), !first.pointers().isEmpty());
                    if (reachable != 0) return reachable;
                    return Integer.compare(second.blocks(), first.blocks());
                });
                for (DeleteCopy copy : ordered) {
                    sources.add(new RecycleStore.Source(uuid, copy.key().dim(), copy.key(), copy.tag()));
                }
            }
            if (sources.isEmpty()) continue;
            try {
                component.stage = this.recycle.stage(sources);
            } catch (Exception error) {
                failComponent(component, statuses, "删除前临时备份失败: " + messageOf(error));
            }
        }
    }

    private void executeDeleteComponents(List<DeleteComponent> components,
                                         Map<UUID, DeleteStatus> statuses) throws Exception {
        onMainUntilComplete(() -> {
            DeleteFlush flush = new DeleteFlush(new LinkedHashSet<>(), new LinkedHashMap<>());
            try {
                for (DeleteComponent component : components) {
                    processDeleteComponent(component, statuses, flush);
                }
            } finally {
                flushDeleteLevels(flush, statuses);
            }
            return new JsonObject();
        });
    }

    private void processDeleteComponent(DeleteComponent component, Map<UUID, DeleteStatus> statuses,
                                        DeleteFlush flush) {
        for (UUID uuid : component.targets) {
            for (DeleteCopy copy : component.copies.getOrDefault(uuid, List.of())) {
                statuses.get(uuid).entryKeys.add(copy.key());
            }
        }
        if (componentHasErrors(component, statuses)) return;
        boolean hasCopies = component.copies.values().stream().anyMatch(copies -> !copies.isEmpty());
        if (!hasCopies && componentIsAbsent(component)) {
            for (UUID uuid : component.targets) statuses.get(uuid).alreadyAbsent = true;
            return;
        }
        for (UUID uuid : component.targets) {
            if (component.copies.getOrDefault(uuid, List.of()).isEmpty()) {
                failComponent(component, statuses, "目标缺少可备份的磁盘条目,未执行在线删除");
                return;
            }
        }

        removeTargetCopies(new DeleteExecution(component, statuses, flush));
    }

    private boolean componentHasErrors(DeleteComponent component, Map<UUID, DeleteStatus> statuses) {
        for (UUID uuid : component.targets) {
            if (!statuses.get(uuid).errors.isEmpty()) return true;
        }
        return false;
    }

    private boolean componentIsAbsent(DeleteComponent component) {
        for (UUID uuid : component.targets) {
            if (resolveLoaded(uuid) != null || isHolding(uuid)) return false;
        }
        return true;
    }

    private void removeTargetCopies(DeleteExecution execution) {
        if (!preflightDeleteCopies(execution)) return;
        boolean failed = false;
        try {
            removeLoadedTargets(execution);
            for (List<SnatchRequest> requests : snatchRequests(execution.component).values()) {
                prepareAndSnatchDeleteChunk(execution, requests);
                removeLoadedTargets(execution);
            }
            loadAndRemoveFallbackTargets(execution);
        } catch (Throwable error) {
            failed = true;
            failComponent(execution.component, execution.statuses, "Sable 删除阶段失败: " + messageOf(error));
            SablePanel.LOGGER.warn("sablepanel: component delete failed", error);
        }

        for (UUID uuid : execution.component.targets) {
            ServerSubLevel removedBody = execution.removedBodies.get(uuid);
            if (removedBody == null) {
                execution.statuses.get(uuid).fail("未能加载并删除目标");
                failed = true;
                continue;
            }
            try {
                queueRemainingCopies(execution, uuid, removedBody);
            } catch (Throwable error) {
                execution.statuses.get(uuid).fail("补充副本删除队列失败: " + messageOf(error));
                failed = true;
            }
        }

        if (failed) markPartialDelete(execution);
    }

    private boolean preflightDeleteCopies(DeleteExecution execution) {
        for (List<DeleteCopy> copies : execution.component.copies.values()) {
            for (DeleteCopy copy : copies) {
                ServerLevel level = levelOf(copy.key().dim());
                if (level == null || SubLevelContainer.getContainer(level) == null) {
                    failComponent(execution.component, execution.statuses,
                            "删除前检查失败: 存储副本所在维度不可用");
                    return false;
                }
            }
        }
        return true;
    }

    private Map<SnatchKey, List<SnatchRequest>> snatchRequests(DeleteComponent component) {
        Map<SnatchKey, List<SnatchRequest>> requests = new LinkedHashMap<>();
        for (UUID uuid : component.targets) {
            for (DeleteCopy copy : component.copies.getOrDefault(uuid, List.of())) {
                for (DiskScanner.LiveLocation location : copy.pointers()) {
                    SnatchKey key = new SnatchKey(copy.key().dim(), location.chunkX(), location.chunkZ());
                    requests.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(new SnatchRequest(uuid, copy, location));
                }
            }
        }
        return requests;
    }

    private void prepareAndSnatchDeleteChunk(DeleteExecution execution, List<SnatchRequest> requests) {
        Map<UUID, SnatchRequest> targets = new LinkedHashMap<>();
        for (SnatchRequest request : requests) {
            targets.putIfAbsent(request.uuid(), request);
        }
        if (targets.isEmpty()) return;
        SnatchRequest first = targets.values().iterator().next();
        ServerLevel level = levelOf(first.location().key().dim());
        ServerSubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("holding 指针所在维度不可用");
        var holdingMap = container.getHoldingChunkMap();
        loadPreparedMember(first.uuid(),
                new MemberPlan(first.copy().key(), first.copy().tag(), first.location()));
        removeLoadedTargets(execution);

        for (SnatchRequest request : targets.values()) {
            // 根体 snatch 会连带拖入同 chunk 的依赖目标(SubLevelHoldingChunk.snatch 遍历一层依赖),
            // 已经加载的不再 snatch。这里不能清空实时 dependencies；失败保存会把破坏持久化。
            if (resolveLoaded(request.uuid()) != null) continue;
            holdingMap.snatchAndLoad(toPointer(request.location()), request.uuid());
        }
    }

    private void removeLoadedTargets(DeleteExecution execution) {
        for (UUID uuid : execution.component.targets) {
            ServerSubLevel body = resolveLoaded(uuid);
            if (body == null) continue;
            removeLoadedTarget(execution, uuid, body);
        }
    }

    private void loadAndRemoveFallbackTargets(DeleteExecution execution) {
        for (UUID uuid : execution.component.targets) {
            if (execution.removedBodies.containsKey(uuid)) continue;
            for (DeleteCopy copy : execution.component.copies.getOrDefault(uuid, List.of())) {
                loadPreparedMember(uuid, copy.loadPlan());
                ServerSubLevel body = resolveLoaded(uuid);
                if (body == null) continue;
                removeLoadedTarget(execution, uuid, body);
                break;
            }
        }
    }

    private void removeLoadedTarget(DeleteExecution execution, UUID uuid, ServerSubLevel body) {
        ServerLevel level = body.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("物理体容器不存在");
        GlobalSavedSubLevelPointer pointer = body.getLastSerializationPointer();
        execution.flush.touched().add(level);
        execution.flush.targetsByLevel().computeIfAbsent(level, ignored -> new LinkedHashSet<>()).add(uuid);
        container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
        if (resolveLoaded(uuid) != null) throw new IllegalStateException("removeSubLevel 后仍在容器中");
        execution.statuses.get(uuid).removed = true;
        execution.removedBodies.put(uuid, body);
        if (pointer != null) {
            execution.handledPointers.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(pointer);
        }
    }

    private void queueRemainingCopies(DeleteExecution execution, UUID uuid, ServerSubLevel removedBody) {
        List<GlobalSavedSubLevelPointer> unconsumed = new ArrayList<>(
                execution.handledPointers.getOrDefault(uuid, List.of()));
        Map<String, Path> dims = DiskScanner.sublevelDirs(this.server);
        for (DeleteCopy copy : execution.component.copies.getOrDefault(uuid, List.of())) {
            // 指针来自 HTTP 线程的准备扫描;其间自动保存可能已把条目搬走、槽位复用给别的体。
            // sable 清槽(attemptSaveSubLevel(ptr,null))不验 uuid,入队前必须重读槽位确认还是目标,
            // 否则会静默清掉无辜体的条目(此处在主线程,sable 不会并发写盘)
            Path dimDir = dims.get(copy.key().dim());
            CompoundTag fresh = dimDir != null ? DiskScanner.readEntryTag(dimDir, copy.key()) : null;
            if (fresh == null || !uuid.equals(tagUuid(fresh))) {
                throw new IllegalStateException("条目 " + copy.key().id() + " 在删除前被 sable 搬迁，已中止并回滚");
            }
            List<GlobalSavedSubLevelPointer> pointers = new ArrayList<>();
            if (copy.pointers().isEmpty()) {
                pointers.add(fallbackPointer(copy));
            } else {
                for (DiskScanner.LiveLocation location : copy.pointers()) pointers.add(toPointer(location));
            }
            for (GlobalSavedSubLevelPointer pointer : pointers) {
                int handledIndex = unconsumed.indexOf(pointer);
                if (handledIndex >= 0) {
                    unconsumed.remove(handledIndex);
                    continue;
                }
                ServerLevel level = levelOf(copy.key().dim());
                ServerSubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
                if (container == null) throw new IllegalStateException("存储副本所在维度不可用: " + copy.key().dim());
                execution.flush.touched().add(level);
                execution.flush.targetsByLevel().computeIfAbsent(level, ignored -> new LinkedHashSet<>()).add(uuid);
                removedBody.setLastSerializationPointer(pointer);
                container.getHoldingChunkMap().queueDeletion(removedBody);
            }
        }
    }

    private void markPartialDelete(DeleteExecution execution) {
        boolean partial = execution.component.targets.stream()
                .anyMatch(uuid -> execution.statuses.get(uuid).removed);
        String message = partial
                ? "同一依赖组发生不可自动回滚的部分删除,请从回收站恢复"
                : "同一依赖组未执行完整删除";
        failComponent(execution.component, execution.statuses, message);
        SablePanel.LOGGER.error("sablepanel: {}: {}", message, shortUuids(execution.component.targets));
    }

    private static GlobalSavedSubLevelPointer toPointer(DiskScanner.LiveLocation location) {
        return new GlobalSavedSubLevelPointer(new ChunkPos(location.chunkX(), location.chunkZ()),
                (short) location.key().storage(), (short) location.key().index());
    }

    private static GlobalSavedSubLevelPointer fallbackPointer(DeleteCopy copy) {
        return fallbackPointer(copy.key(), copy.tag());
    }

    private static GlobalSavedSubLevelPointer fallbackPointer(DiskScanner.EntryKey key, CompoundTag tag) {
        CompoundTag posTag = tag.getCompound("pose").getCompound("position");
        int chunkX = clamp(((int) Math.floor(posTag.getDouble("x"))) >> 4,
                key.rx() * 32, key.rx() * 32 + 31);
        int chunkZ = clamp(((int) Math.floor(posTag.getDouble("z"))) >> 4,
                key.rz() * 32, key.rz() * 32 + 31);
        return new GlobalSavedSubLevelPointer(new ChunkPos(chunkX, chunkZ),
                (short) key.storage(), (short) key.index());
    }

    private void flushDeleteLevels(DeleteFlush flush, Map<UUID, DeleteStatus> statuses) {
        for (ServerLevel level : flush.touched()) {
            try {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container == null) throw new IllegalStateException("物理体容器不存在");
                container.getHoldingChunkMap().saveAll();
            } catch (Throwable error) {
                String message = "saveAll 失败: " + messageOf(error);
                for (UUID uuid : flush.targetsByLevel().getOrDefault(level, Set.of())) {
                    statuses.get(uuid).fail(message);
                }
                SablePanel.LOGGER.warn("sablepanel: saveAll for {} failed", level.dimension().location(), error);
            }
        }
    }

    private void verifyDeletedTargets(Map<UUID, DeleteStatus> statuses, List<String> warnings) {
        DiskVerification disk;
        JsonObject runtime;
        try {
            disk = scanRemainingEntries(statuses, warnings);
            runtime = readRuntimeStates(statuses.keySet());
        } catch (Exception error) {
            String message = "删除后验收失败: " + messageOf(error);
            for (DeleteStatus status : statuses.values()) status.fail(message);
            this.rescan.run();
            return;
        }

        for (DeleteStatus status : statuses.values()) {
            status.remainingEntries = disk.entries().getOrDefault(status.uuid, 0);
            status.remainingPointers = 0;
            for (DiskScanner.EntryKey key : status.entryKeys) {
                status.remainingPointers += disk.pointers().getOrDefault(key, 0);
            }
            JsonObject state = runtime.getAsJsonObject(status.uuid.toString());
            boolean loaded = state != null && state.get("loaded").getAsBoolean();
            boolean holding = state != null && state.get("holding").getAsBoolean();
            if (status.remainingEntries > 0) status.fail("仍有 " + status.remainingEntries + " 个磁盘条目");
            if (status.remainingPointers > 0) status.fail("仍有 " + status.remainingPointers + " 个 holding 指针");
            if (loaded) status.fail("运行时物理体仍存在");
            if (holding) status.fail("holding 中仍存在");
            if (!status.removed && !status.alreadyAbsent) status.fail("未执行删除");
            status.ok = status.errors.isEmpty();
        }
        this.rescan.run();
    }

    private DiskVerification scanRemainingEntries(Map<UUID, DeleteStatus> statuses, List<String> warnings)
            throws Exception {
        DiskScanner.invalidateCache();
        Map<String, Path> dimensions = DiskScanner.sublevelDirsStrict(this.server);
        Map<UUID, List<DiskScanner.EntryMeta>> meta = DiskScanner.scanEntryMetaStrict(dimensions, warnings);
        Set<DiskScanner.EntryKey> keys = new LinkedHashSet<>();
        Map<UUID, Integer> entries = new HashMap<>();
        for (DeleteStatus status : statuses.values()) {
            keys.addAll(status.entryKeys);
            entries.put(status.uuid, meta.getOrDefault(status.uuid, List.of()).size());
        }
        return new DiskVerification(entries, DiskScanner.countPointersStrict(dimensions, keys, warnings));
    }

    private JsonObject readRuntimeStates(Set<UUID> targets) throws Exception {
        return onMain(() -> {
            JsonObject out = new JsonObject();
            for (UUID uuid : targets) {
                JsonObject state = new JsonObject();
                state.addProperty("loaded", resolveLoaded(uuid) != null);
                state.addProperty("holding", isHolding(uuid));
                out.add(uuid.toString(), state);
            }
            return out;
        });
    }

    /** 删除失败回滚前先清掉所有残留，随后才能从快照完整重建同 UUID 依赖组。 */
    private void purgeRestoreTargets(Set<UUID> targets, List<String> warnings) throws Exception {
        DeleteComponent component = prepareExactDeleteComponent(targets, warnings);
        if (!component.targets.isEmpty()) {
            Map<UUID, DeleteStatus> statuses = new LinkedHashMap<>();
            for (UUID uuid : component.targets) statuses.put(uuid, new DeleteStatus(uuid));
            executeDeleteComponents(List.of(component), statuses);
            verifyDeletedTargets(statuses, warnings);
            List<String> errors = new ArrayList<>();
            for (DeleteStatus status : statuses.values()) {
                if (!status.ok) errors.add(status.uuid + ": " + String.join("; ", status.errors));
            }
            if (!errors.isEmpty()) throw new IllegalStateException("回滚前残留清理失败: " + String.join(" | ", errors));
        }
        requireTargetsAbsent(targets, warnings);
    }

    private DeleteComponent prepareExactDeleteComponent(Set<UUID> targets, List<String> warnings)
            throws Exception {
        Map<String, Path> dimensions = DiskScanner.sublevelDirsStrict(this.server);
        Map<UUID, List<DiskScanner.EntryMeta>> meta = DiskScanner.scanEntryMetaStrict(dimensions, warnings);
        if (flushUnsavedTargets(new ArrayList<>(targets), meta)) {
            dimensions = DiskScanner.sublevelDirsStrict(this.server);
            meta = DiskScanner.scanEntryMetaStrict(dimensions, warnings);
        }
        Map<UUID, List<DeleteCopy>> prepared = readDeleteCopies(dimensions, meta, targets, warnings);
        DeleteComponent component = new DeleteComponent();
        for (UUID target : targets) {
            List<DeleteCopy> copies = prepared.getOrDefault(target, List.of());
            if (!copies.isEmpty()) component.addTarget(target, copies);
        }
        return component;
    }

    private void requireTargetsAbsent(Set<UUID> targets, List<String> warnings) throws Exception {
        DiskScanner.invalidateCache();
        Map<String, Path> dimensions = DiskScanner.sublevelDirsStrict(this.server);
        Map<UUID, List<DiskScanner.EntryMeta>> meta = DiskScanner.scanEntryMetaStrict(dimensions, warnings);
        JsonObject runtime = readRuntimeStates(targets);
        for (UUID uuid : targets) {
            if (!meta.getOrDefault(uuid, List.of()).isEmpty()) {
                throw new IllegalStateException("回滚前仍有磁盘条目: " + uuid);
            }
            JsonObject state = runtime.getAsJsonObject(uuid.toString());
            if (state != null && (state.get("loaded").getAsBoolean() || state.get("holding").getAsBoolean())) {
                throw new IllegalStateException("回滚前运行时仍有物理体: " + uuid);
            }
        }
    }

    private void finalizeDeleteBackups(List<DeleteComponent> components, Map<UUID, DeleteStatus> statuses,
                                       List<String> warnings) {
        boolean restoredAny = false;
        for (DeleteComponent component : components) {
            if (component.stage == null) continue;
            boolean succeeded = component.targets.stream().allMatch(uuid -> statuses.get(uuid).ok);
            boolean changed = component.targets.stream().anyMatch(uuid -> statuses.get(uuid).removed);
            if (succeeded && changed) {
                try {
                    String groupId = this.recycle.commit(component.stage);
                    for (UUID uuid : component.targets) {
                        statuses.get(uuid).recycleGroup = groupId;
                        audit("delete", uuid, null, groupId);
                    }
                    continue;
                } catch (Exception error) {
                    failComponent(component, statuses, "回收站提交失败: " + messageOf(error));
                    SablePanel.LOGGER.warn("sablepanel: recycle commit failed after delete", error);
                    changed = true;
                }
            }
            if (!changed) {
                this.recycle.discard(component.stage);
                continue;
            }
            try {
                RecycleStore.RestoreGroup rollback = this.recycle.loadStage(component.stage);
                restoreGroupData(rollback, true, warnings);
                failComponent(component, statuses, "删除失败，已从临时事务自动恢复原依赖组");
                restoredAny = true;
                // 发生过 removeSubLevel 的组必须留下备份:sable 的 queuedDeletion 在 saveAll
                // 失败时不会清空,盘上"看似还在"的条目仍可能被下一次自动保存清掉。
                // 备份转正进回收站并标记已恢复;转正失败就留在 .pending,数据不丢。
                try {
                    String groupId = this.recycle.commit(component.stage);
                    this.recycle.markRestored(groupId);
                    for (UUID uuid : component.targets) statuses.get(uuid).recycleGroup = groupId;
                } catch (Exception keepError) {
                    SablePanel.LOGGER.warn("sablepanel: keeping rollback backup {} in the pending area",
                            component.stage.id(), keepError);
                }
            } catch (Exception error) {
                SablePanel.LOGGER.error("sablepanel: failed delete rollback {}", component.stage.id(), error);
                try {
                    String groupId = this.recycle.commitRecoveryRequired(component.stage);
                    for (UUID uuid : component.targets) statuses.get(uuid).recycleGroup = groupId;
                    failComponent(component, statuses,
                            "删除失败且自动恢复失败，完整备份已进入回收站: " + groupId);
                } catch (Exception keepError) {
                    failComponent(component, statuses,
                            "删除失败且自动恢复失败，内部事务已保留: " + component.stage.id());
                    error.addSuppressed(keepError);
                    SablePanel.LOGGER.error("sablepanel: failed to expose recovery transaction {}",
                            component.stage.id(), keepError);
                }
            }
        }
        if (restoredAny) this.rescan.run();
    }

    public JsonObject recycleView() {
        return this.recycle.view();
    }

    public JsonObject recycleMesh(String groupId, UUID uuid) throws Exception {
        return this.recycle.mesh(groupId, uuid);
    }

    public JsonObject setRecycleLimit(int limit) throws Exception {
        JsonObject out = new JsonObject();
        out.addProperty("limit", this.recycle.setLimit(limit));
        out.addProperty("ok", true);
        return out;
    }

    public synchronized JsonObject restoreRecycleGroups(List<String> groupIds) {
        JsonArray results = new JsonArray();
        List<String> warnings = new ArrayList<>();
        int restored = 0;
        for (String groupId : new LinkedHashSet<>(groupIds)) {
            JsonObject result = new JsonObject();
            result.addProperty("id", groupId);
            try {
                RecycleStore.RestoreGroup group = this.recycle.loadGroup(groupId);
                restoreGroupData(group, "recovery_required".equals(group.state()), warnings);
                try {
                    this.recycle.markRestored(groupId);
                } catch (Exception metadataError) {
                    result.addProperty("warn", "物理体已恢复，但回收站状态更新失败: " + messageOf(metadataError));
                }
                for (RecycleStore.RestoreBody body : group.bodies()) {
                    audit("restore", body.uuid(), null, groupId);
                }
                result.addProperty("ok", true);
                result.addProperty("members", group.bodies().size());
                restored++;
            } catch (Exception error) {
                result.addProperty("ok", false);
                result.addProperty("error", messageOf(error));
                SablePanel.LOGGER.warn("sablepanel: recycle restore {} failed", groupId, error);
            }
            results.add(result);
        }
        this.rescan.run();
        JsonObject out = new JsonObject();
        out.addProperty("ok", restored);
        out.addProperty("total", results.size());
        out.add("results", results);
        attachWarnings(out, warnings);
        return out;
    }

    /** 磁盘损坏跳过等非致命告警,随操作结果一并交给前端展示。 */
    private static void attachWarnings(JsonObject response, List<String> warnings) {
        if (warnings.isEmpty()) return;
        JsonArray array = new JsonArray();
        for (String warning : new LinkedHashSet<>(warnings)) array.add(warning);
        response.add("warnings", array);
    }

    private void restoreGroupData(RecycleStore.RestoreGroup group, boolean replaceExisting,
                                  List<String> warnings) throws Exception {
        Set<UUID> targets = new LinkedHashSet<>();
        for (RecycleStore.RestoreBody body : group.bodies()) targets.add(body.uuid());
        if (replaceExisting) purgeRestoreTargets(targets, warnings);
        Map<String, Path> dimensions = DiskScanner.sublevelDirsStrict(this.server);
        Map<UUID, List<DiskScanner.EntryMeta>> meta = DiskScanner.scanEntryMetaStrict(dimensions, warnings);
        Map<UUID, Integer> existingEntries = new HashMap<>();
        for (UUID uuid : targets) existingEntries.put(uuid, meta.getOrDefault(uuid, List.of()).size());
        // 同一趟扫描顺路建 plot 槽位占用表:删除释放的槽位会被 sable 按首位适配复用给新体,
        // 而恢复用的 allocateSubLevel 只查加载态 —— 不拦下来就会造出"同槽双体"(加载互斥)
        Map<DiskScanner.PlotKey, Set<UUID>> plotOwners = DiskScanner.plotOwners(meta);
        onMainUntilComplete(() -> restoreGroupOnMain(group, existingEntries, plotOwners));
        try {
            verifyRestoredGroup(group, warnings);
        } catch (Exception verificationError) {
            if (!replaceExisting) {
                try {
                    purgeRestoreTargets(targets, warnings);
                } catch (Exception cleanupError) {
                    verificationError.addSuppressed(cleanupError);
                }
            }
            throw verificationError;
        }
    }

    private JsonObject restoreGroupOnMain(RecycleStore.RestoreGroup group,
                                          Map<UUID, Integer> existingEntries,
                                          Map<DiskScanner.PlotKey, Set<UUID>> plotOwners) throws Exception {
        List<ServerSubLevel> created = new ArrayList<>();
        Set<ServerLevel> touched = new LinkedHashSet<>();
        try {
            for (RecycleStore.RestoreBody body : group.bodies()) {
                boolean exists = existingEntries.getOrDefault(body.uuid(), 0) > 0
                        || resolveLoaded(body.uuid()) != null || isHolding(body.uuid());
                if (exists) throw new IllegalStateException("UUID 已存在，未恢复该依赖组: " + body.uuid());
                ServerLevel level = restoreLevel(body.dimension());
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container == null) throw new IllegalStateException("恢复目标维度没有物理体容器");
                requireFreePlot(container, level, plotOwners, body);
                SubLevelData data = SubLevelSerializer.fromData(body.tag());
                if (data == null || !body.uuid().equals(data.uuid())) {
                    throw new IllegalStateException("回收站 NBT 无法解析: " + body.uuid());
                }
                ServerSubLevel restored = SubLevelSerializer.fullyLoad(level, data);
                if (restored == null) throw new IllegalStateException("Sable 拒绝恢复: " + body.uuid());
                created.add(restored);
                touched.add(level);
            }
            for (ServerLevel level : touched) {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container == null) throw new IllegalStateException("恢复目标维度没有物理体容器");
                container.getHoldingChunkMap().saveAll();
            }
        } catch (Throwable error) {
            cleanupFailedRestore(created, touched);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("恢复事务失败", error);
        }
        JsonObject out = new JsonObject();
        out.addProperty("restored", created.size());
        return out;
    }

    private void cleanupFailedRestore(List<ServerSubLevel> created, Set<ServerLevel> touched) {
        for (ServerSubLevel body : created) {
            try {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(body.getLevel());
                if (container != null && resolveLoaded(body.getUniqueId()) != null) {
                    container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
                }
            } catch (Throwable error) {
                SablePanel.LOGGER.warn("sablepanel: failed to clean partial restore {}", body.getUniqueId(), error);
            }
        }
        for (ServerLevel level : touched) {
            try {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container != null) container.getHoldingChunkMap().saveAll();
            } catch (Throwable error) {
                SablePanel.LOGGER.warn("sablepanel: failed to flush partial restore cleanup", error);
            }
        }
    }

    private void verifyRestoredGroup(RecycleStore.RestoreGroup group, List<String> warnings) throws Exception {
        Set<UUID> targets = new LinkedHashSet<>();
        Map<UUID, RecycleStore.RestoreBody> expected = new LinkedHashMap<>();
        for (RecycleStore.RestoreBody body : group.bodies()) {
            targets.add(body.uuid());
            expected.put(body.uuid(), body);
        }
        DiskScanner.invalidateCache();
        Map<String, Path> dimensions = DiskScanner.sublevelDirsStrict(this.server);
        Map<UUID, List<DiskScanner.EntryMeta>> meta = DiskScanner.scanEntryMetaStrict(dimensions, warnings);
        Set<DiskScanner.EntryKey> keys = new LinkedHashSet<>();
        for (UUID uuid : targets) {
            for (DiskScanner.EntryMeta copy : meta.getOrDefault(uuid, List.of())) keys.add(copy.key());
        }
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers =
                DiskScanner.locatePointersStrict(dimensions, keys, warnings);
        JsonObject runtime = readRuntimeStates(targets);
        for (UUID uuid : targets) {
            List<DiskScanner.EntryMeta> copies = meta.getOrDefault(uuid, List.of());
            if (copies.isEmpty()) throw new IllegalStateException("恢复后磁盘条目缺失: " + uuid);
            if (copies.size() != 1) throw new IllegalStateException("恢复后存在 " + copies.size() + " 个磁盘条目: " + uuid);
            RecycleStore.RestoreBody body = expected.get(uuid);
            DiskScanner.EntryMeta restored = copies.get(0);
            if (!restored.key().dim().equals(body.dimension())) {
                throw new IllegalStateException("恢复后维度不一致: " + uuid);
            }
            Set<UUID> expectedDependencies = tagDependencies(body.tag());
            if (!new LinkedHashSet<>(restored.deps()).equals(expectedDependencies)) {
                throw new IllegalStateException("恢复后依赖关系不一致: " + uuid);
            }
            int pointerCount = 0;
            for (DiskScanner.EntryMeta copy : copies) {
                pointerCount += pointers.getOrDefault(copy.key(), List.of()).size();
            }
            if (pointerCount < 1) throw new IllegalStateException("恢复后 holding 指针缺失: " + uuid);
            JsonObject state = runtime.getAsJsonObject(uuid.toString());
            boolean loaded = state != null && state.get("loaded").getAsBoolean();
            boolean holding = state != null && state.get("holding").getAsBoolean();
            // 刚恢复的体落在无人区可能几秒内就转入 holding —— 那是正常归宿,不算失败
            if (!loaded && !holding) {
                throw new IllegalStateException("恢复后物理体未加载: " + uuid);
            }
        }
    }

    private static Set<UUID> tagDependencies(CompoundTag tag) {
        Set<UUID> dependencies = new LinkedHashSet<>();
        if (!tag.contains("loading_dependencies")) return dependencies;
        var values = tag.getList("loading_dependencies", net.minecraft.nbt.Tag.TAG_INT_ARRAY);
        for (net.minecraft.nbt.Tag value : values) dependencies.add(net.minecraft.nbt.NbtUtils.loadUUID(value));
        return dependencies;
    }

    private ServerLevel restoreLevel(String dimension) {
        String target = dimension == null || dimension.isBlank() ? RecycleStore.DEFAULT_DIMENSION : dimension;
        ServerLevel level = levelOf(target);
        if (level == null && RecycleStore.DEFAULT_DIMENSION.equals(target)) level = this.server.overworld();
        if (level == null) throw new IllegalStateException("恢复目标维度不存在: " + target);
        return level;
    }

    /**
     * 槽位守卫:备份体的原 plot 槽位若已被其他 uuid 占用(盘上条目或加载态)则拒绝恢复整组。
     * sable 的 allocateSubLevel 只查加载态,不查 occupancy —— 放行会造出加载互斥的同槽双体。
     */
    private static void requireFreePlot(ServerSubLevelContainer container, ServerLevel level,
                                        Map<DiskScanner.PlotKey, Set<UUID>> plotOwners,
                                        RecycleStore.RestoreBody body) {
        CompoundTag plot = body.tag().getCompound("plot");
        int plotX = plot.getInt("plot_x");
        int plotZ = plot.getInt("plot_z");
        String dim = level.dimension().location().toString();
        for (UUID owner : plotOwners.getOrDefault(new DiskScanner.PlotKey(dim, plotX, plotZ), Set.of())) {
            if (!owner.equals(body.uuid())) {
                throw new IllegalStateException("plot 槽位 (" + plotX + "," + plotZ + ") 已被物理体 "
                        + owner + " 占用，未恢复该依赖组");
            }
        }
        var occupant = container.getSubLevel(plotX, plotZ);
        if (occupant != null && !body.uuid().equals(occupant.getUniqueId())) {
            throw new IllegalStateException("plot 槽位 (" + plotX + "," + plotZ + ") 已被加载中的物理体 "
                    + occupant.getUniqueId() + " 占用，未恢复该依赖组");
        }
    }

    private JsonObject deleteResponse(List<UUID> targets, Map<UUID, DeleteStatus> statuses) {
        JsonArray results = new JsonArray();
        int ok = 0;
        for (UUID uuid : targets) {
            DeleteStatus status = statuses.get(uuid);
            if (status.ok) ok++;
            results.add(status.toJson());
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", ok);
        out.addProperty("total", targets.size());
        out.add("results", results);
        return out;
    }

    private void failComponent(DeleteComponent component, Map<UUID, DeleteStatus> statuses, String message) {
        for (UUID uuid : component.targets) statuses.get(uuid).fail(message);
    }

    private static String shortUuids(Set<UUID> uuids) {
        List<String> values = new ArrayList<>();
        for (UUID uuid : uuids) {
            values.add(uuid.toString().substring(0, 8));
            if (values.size() == 6) break;
        }
        if (uuids.size() > values.size()) values.add("另 " + (uuids.size() - values.size()) + " 个");
        return String.join(", ", values);
    }

    private static String messageOf(Throwable error) {
        return String.valueOf(error.getMessage() != null ? error.getMessage() : error);
    }

    private static JsonArray numberArray(double[] values) {
        JsonArray out = new JsonArray();
        for (double value : values) out.add(value);
        return out;
    }

    /** 孤儿收养(依赖闭包一起):不动盘,全部经 sable 原生 loadHoldingSubLevel 入场 */
    public JsonObject adopt(UUID uuid) throws Exception {
        Map<UUID, MemberPlan> chain = prepareChain(uuid);
        if (chain.isEmpty()) throw new IllegalStateException("找不到该体的存档条目");
        // 链闭包触到 MAX_CHAIN 上限说明还有成员没进本次收养,要让用户知道是部分收养
        boolean truncated = chain.size() >= MAX_CHAIN;
        if (truncated) {
            SablePanel.LOGGER.warn("sablepanel: adopt {} dependency closure hit the {} member cap, adopting partially",
                    uuid, MAX_CHAIN);
        }
        JsonObject result = onMain(() -> {
            JsonObject per = new JsonObject();
            for (Map.Entry<UUID, MemberPlan> en : chain.entrySet()) {
                UUID u = en.getKey();
                try {
                    if (resolveLoaded(u) != null) {
                        per.addProperty(u.toString(), "already_loaded");
                        continue;
                    }
                    loadOne(u, en.getValue());
                    per.addProperty(u.toString(), resolveLoaded(u) != null ? "adopted" : "load_failed");
                } catch (Throwable t) {
                    per.addProperty(u.toString(), "error: " + t.getMessage());
                }
            }
            audit("adopt", uuid, null, per.toString());
            JsonObject r = new JsonObject();
            r.addProperty("ok", resolveLoaded(uuid) != null);
            if (truncated) r.addProperty("truncated", MAX_CHAIN);
            r.add("chain", per);
            return r;
        });
        this.rescan.run();
        return result;
    }

    /** 实时副本审查:列表快照只负责提示,真正操作前始终严格重扫并深比较完整 NBT。 */
    public JsonObject inspectCopies(UUID uuid) throws Exception {
        List<String> warnings = new ArrayList<>();
        JsonObject out = copiesJson(inspectCopyState(uuid, warnings));
        attachWarnings(out, warnings);
        return out;
    }

    /**
     * 只整理内容完全一致的同 UUID 磁盘条目。保留活动/可达副本,其余通过 sable 自身
     * queueDeletion + saveAll 清理；内容不同的一律拒绝,不猜哪份才是玩家资产。
     */
    public synchronized JsonObject deduplicate(UUID uuid) throws Exception {
        List<String> warnings = new ArrayList<>();
        CopyInspection inspection = inspectCopyState(uuid, warnings);
        if (inspection.copies().size() < 2) {
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("removed", 0);
            out.addProperty("kept_entry", inspection.keep().key().id());
            attachWarnings(out, warnings);
            return out;
        }
        if (!inspection.identical()) {
            throw new IllegalStateException("副本内容不一致，未执行去重");
        }

        // 执行端再做一轮完整扫描；GET 结果和本方法首轮都只用于展示/早拒绝。
        // 文件读取留在 HTTP 线程，主线程只碰 Sable 运行时。
        CopyInspection prepared = inspectCopyState(uuid, warnings);
        if (prepared.copies().size() < 2) {
            JsonObject out = copiesJson(prepared);
            out.addProperty("ok", true);
            out.addProperty("removed", 0);
            attachWarnings(out, warnings);
            return out;
        }
        if (!prepared.identical()) {
            throw new IllegalStateException("副本内容在确认后发生变化，未执行去重");
        }
        CopyCandidate keep = prepared.keep();
        Map<UUID, MemberPlan> chain = prepareChain(uuid);
        chain.put(uuid, new MemberPlan(keep.key(), keep.tag(),
                keep.pointers().isEmpty() ? null : keep.pointers().get(0)));
        int removed = prepared.copies().size() - 1;
        onMainUntilComplete(() -> {
            String active = activePointerEntryId(uuid);
            if (active != null && !active.equals(keep.key().id())) {
                throw new IllegalStateException("活动副本在准备后发生变化，请重试");
            }
            ServerSubLevel body = ensureLoaded(uuid, chain);
            GlobalSavedSubLevelPointer originalPointer = body.getLastSerializationPointer();
            Set<ServerLevel> touched = new LinkedHashSet<>();
            touched.add(body.getLevel());
            try {
                for (CopyCandidate copy : prepared.copies()) {
                    if (copy.key().equals(keep.key())) continue;
                    ServerLevel level = levelOf(copy.key().dim());
                    ServerSubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
                    if (container == null) throw new IllegalStateException("副本所在维度不可用: " + copy.key().dim());
                    touched.add(level);
                    List<GlobalSavedSubLevelPointer> targets = new ArrayList<>();
                    if (copy.pointers().isEmpty()) {
                        targets.add(fallbackPointer(copy.key(), copy.tag()));
                    } else {
                        for (DiskScanner.LiveLocation location : copy.pointers()) targets.add(toPointer(location));
                    }
                    // locatePointersStrict 会保留重复引用；每次 queueDeletion 只移除列表中的一个引用。
                    for (GlobalSavedSubLevelPointer pointer : targets) {
                        body.setLastSerializationPointer(pointer);
                        container.getHoldingChunkMap().queueDeletion(body);
                    }
                }
            } finally {
                body.setLastSerializationPointer(originalPointer);
            }
            for (ServerLevel level : touched) {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container == null) throw new IllegalStateException("物理体容器不存在");
                container.getHoldingChunkMap().saveAll();
            }
            JsonObject out = new JsonObject();
            out.addProperty("queued", removed);
            return out;
        });

        CopyInspection verified = inspectCopyState(uuid, warnings);
        if (verified.copies().size() != 1) {
            throw new IllegalStateException("去重后仍有 " + verified.copies().size() + " 个磁盘条目");
        }
        if (verified.keep().pointers().isEmpty()) {
            throw new IllegalStateException("去重后主副本没有有效 holding 指针");
        }
        Set<DiskScanner.EntryKey> removedKeys = new LinkedHashSet<>();
        for (CopyCandidate copy : prepared.copies()) {
            if (!copy.key().equals(keep.key())) removedKeys.add(copy.key());
        }
        int stalePointers = DiskScanner.locatePointersStrict(verified.dimensions(), removedKeys, warnings)
                .values().stream()
                .mapToInt(List::size).sum();
        if (stalePointers > 0) {
            throw new IllegalStateException("去重后仍有 " + stalePointers + " 个多余 holding 指针");
        }
        JsonObject detail = new JsonObject();
        detail.addProperty("removed", removed);
        detail.addProperty("kept_entry", verified.keep().key().id());
        audit("dedupe", uuid, null, detail.toString());
        this.rescan.run();
        JsonObject out = copiesJson(verified);
        out.addProperty("ok", true);
        out.addProperty("removed", removed);
        out.addProperty("kept_entry", verified.keep().key().id());
        attachWarnings(out, warnings);
        return out;
    }

    private CopyInspection inspectCopyState(UUID uuid, List<String> warnings) throws Exception {
        Map<String, Path> dimensions = DiskScanner.sublevelDirsStrict(this.server);
        Map<UUID, List<DiskScanner.EntryMeta>> meta = DiskScanner.scanEntryMetaStrict(dimensions, warnings);
        List<DiskScanner.EntryMeta> entries = meta.getOrDefault(uuid, List.of());
        if (entries.isEmpty()) throw new IllegalStateException("找不到该体的磁盘条目");
        Set<DiskScanner.EntryKey> keys = new LinkedHashSet<>();
        for (DiskScanner.EntryMeta entry : entries) keys.add(entry.key());
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers =
                DiskScanner.locatePointersStrict(dimensions, keys, warnings);
        JsonObject activeState = onMain(() -> {
            JsonObject out = new JsonObject();
            String id = activePointerEntryId(uuid);
            if (id != null) out.addProperty("entry", id);
            return out;
        });
        String active = activeState.has("entry") ? activeState.get("entry").getAsString() : null;

        List<CopyCandidate> copies = new ArrayList<>();
        for (DiskScanner.EntryMeta entry : entries) {
            CompoundTag tag = readCopy(dimensions, uuid, entry.key());
            copies.add(new CopyCandidate(entry.key(), tag,
                    DiskScanner.countBlocks(tag.getCompound("plot"), null),
                    List.copyOf(pointers.getOrDefault(entry.key(), List.of()))));
        }
        copies.sort(Comparator
                .comparing((CopyCandidate copy) -> !copy.key().id().equals(active))
                .thenComparing(copy -> copy.pointers().isEmpty())
                .thenComparing(copy -> copy.key().id()));
        CopyCandidate keep = copies.get(0);
        boolean identical = copies.stream().allMatch(copy -> copy.tag().equals(keep.tag()));
        return new CopyInspection(dimensions, List.copyOf(copies), keep, identical);
    }

    private static CompoundTag readCopy(Map<String, Path> dimensions, UUID uuid, DiskScanner.EntryKey key)
            throws IOException {
        Path directory = dimensions.get(key.dim());
        CompoundTag tag = directory == null ? null : DiskScanner.readEntryTag(directory, key);
        if (tag == null || !uuid.equals(tagUuid(tag))) {
            throw new IOException("副本槽位已经变化: " + key.id());
        }
        return tag;
    }

    private JsonObject copiesJson(CopyInspection inspection) {
        JsonObject out = new JsonObject();
        out.addProperty("uuid", tagUuid(inspection.keep().tag()).toString());
        out.addProperty("identical", inspection.identical());
        out.addProperty("keep_entry", inspection.keep().key().id());
        JsonArray copies = new JsonArray();
        for (CopyCandidate copy : inspection.copies()) {
            DiskScanner.DiskEntry summary = DiskScanner.summarize(copy.key(), copy.tag());
            JsonObject item = new JsonObject();
            item.addProperty("entry", copy.key().id());
            item.addProperty("keep", copy.key().equals(inspection.keep().key()));
            item.addProperty("identical", copy.tag().equals(inspection.keep().tag()));
            item.addProperty("reachable", !copy.pointers().isEmpty());
            item.addProperty("pointer_count", copy.pointers().size());
            item.addProperty("blocks", copy.blocks());
            item.addProperty("dim", copy.key().dim());
            item.add("pos", numberArray(summary.pos()));
            item.add("size", numberArray(summary.size()));
            copies.add(item);
        }
        out.add("copies", copies);
        return out;
    }

    private String activePointerEntryId(UUID uuid) {
        for (ServerLevel level : this.server.getAllLevels()) {
            try {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container == null) continue;
                ServerSubLevel loaded = container.getSubLevel(uuid) instanceof ServerSubLevel body ? body : null;
                GlobalSavedSubLevelPointer pointer = loaded != null ? loaded.getLastSerializationPointer() : null;
                if (pointer == null) {
                    HoldingSubLevel holding = container.getHoldingChunkMap().getHoldingSubLevel(uuid);
                    if (holding != null) pointer = holding.pointer();
                }
                if (pointer != null) {
                    return entryKey(level.dimension().location().toString(), pointer).id();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static DiskScanner.EntryKey entryKey(String dim, GlobalSavedSubLevelPointer pointer) {
        return new DiskScanner.EntryKey(dim,
                Math.floorDiv(pointer.chunkPos().x, 32), Math.floorDiv(pointer.chunkPos().z, 32),
                pointer.storageIndex(), pointer.subLevelIndex());
    }

    // ---------- 内部:加载路径 ----------

    /** HTTP 线程:为 uuid 及其依赖闭包准备条目数据(磁盘 IO 不占主线程) */
    private Map<UUID, MemberPlan> prepareChain(UUID root) {
        Map<UUID, MemberPlan> chain = new LinkedHashMap<>();
        Map<String, Path> dims = DiskScanner.sublevelDirs(this.server);
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && chain.size() < MAX_CHAIN) {
            UUID u = queue.poll();
            if (chain.containsKey(u)) continue;
            MemberPlan plan = locateMember(u, dims);
            if (plan == null) continue;
            chain.put(u, plan);
            try {
                if (plan.tag().contains("loading_dependencies")) {
                    var list = plan.tag().getList("loading_dependencies", net.minecraft.nbt.Tag.TAG_INT_ARRAY);
                    for (net.minecraft.nbt.Tag t : list) queue.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
                }
            } catch (Throwable ignored) {
            }
        }
        return chain;
    }

    /** 快路径:索引快照定位 + 重读校验;失败退化为全盘实时定位 */
    private MemberPlan locateMember(UUID u, Map<String, Path> dims) {
        DiskScanner.EntryKey key = null;
        CompoundTag tag = null;
        DiskScanner.DiskEntry cached = this.index.findEntry(u);
        if (cached != null) {
            Path dir = dims.get(cached.key().dim());
            if (dir != null) {
                CompoundTag t = DiskScanner.readEntryTag(dir, cached.key());
                try {
                    if (t != null && u.equals(t.getUUID("uuid"))) {
                        key = cached.key();
                        tag = t;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (tag == null) {
            for (var en : dims.entrySet()) {
                DiskScanner.LocatedEntry le = DiskScanner.locateEntry(en.getKey(), en.getValue(), u);
                if (le != null) {
                    key = le.key();
                    tag = le.tag();
                    break;
                }
            }
        }
        if (tag == null) return null;
        DiskScanner.LiveLocation cold = null;
        Path dir = dims.get(key.dim());
        if (dir != null) {
            cold = DiskScanner.locateLive(key.dim(), dir, u);
            if (cold != null && !cold.key().equals(key)) cold = null;
        }
        return new MemberPlan(key, tag, cold);
    }

    /** 主线程:确保 uuid 已加载(否则依链加载),返回加载后的体;失败抛异常 */
    private ServerSubLevel ensureLoaded(UUID uuid, Map<UUID, MemberPlan> chain) {
        ServerSubLevel sl = resolveLoaded(uuid);
        if (sl != null) return sl;
        for (Map.Entry<UUID, MemberPlan> en : chain.entrySet()) {
            if (resolveLoaded(en.getKey()) != null) continue;
            try {
                loadOne(en.getKey(), en.getValue());
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: chain load {} failed", en.getKey(), t);
            }
        }
        sl = resolveLoaded(uuid);
        if (sl == null) throw new IllegalStateException("无法加载该物理体(条目缺失或 sable 拒绝加载,详见服务器日志)");
        return sl;
    }

    /** 主线程:单体加载。holding snatch → 活指针 snatch → 孤儿收养(fromData+loadHoldingSubLevel) */
    private void loadOne(UUID uuid, MemberPlan plan) {
        Set<GlobalSavedSubLevelPointer> attemptedPointers = new LinkedHashSet<>();
        // 1) sable 内存 holding 态:原生指针权威
        for (ServerLevel level : this.server.getAllLevels()) {
            try {
                ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
                if (c == null) continue;
                var holding = c.getHoldingChunkMap().getHoldingSubLevel(uuid);
                if (holding != null && holding.pointer() != null && attemptedPointers.add(holding.pointer())) {
                    c.getHoldingChunkMap().snatchAndLoad(holding.pointer(), uuid);
                    if (resolveLoaded(uuid) != null) return;
                }
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: holding snatch {} failed", uuid, t);
            }
        }
        ServerLevel level = levelOf(plan.key().dim());
        if (level == null) return;
        ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
        if (c == null) return;
        // 2) 盘上活指针:走原生 snatch(保持 sable 自身指针记账精确)
        if (plan.cold() != null) {
            try {
                GlobalSavedSubLevelPointer ptr = new GlobalSavedSubLevelPointer(
                        new ChunkPos(plan.cold().chunkX(), plan.cold().chunkZ()),
                        (short) plan.cold().key().storage(), (short) plan.cold().key().index());
                if (attemptedPointers.add(ptr)) c.getHoldingChunkMap().snatchAndLoad(ptr, uuid);
                if (resolveLoaded(uuid) != null) return;
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: cold snatch {} failed", uuid, t);
            }
        }
        // 3) 真孤儿收养:构造 HoldingSubLevel 直接入 sable 加载管线。
        loadPreparedMember(uuid, plan);
    }

    /**
     * 从已准备且再次校验过的条目直接走 Sable fullyLoad。删除组件使用这条路径可避开
     * 已损坏 holding 指针上的 snatch；普通收养则把它作为原生 snatch 失败后的兜底。
     */
    private void loadPreparedMember(UUID uuid, MemberPlan plan) {
        try {
            ServerLevel level = levelOf(plan.key().dim());
            if (level == null) return;
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) return;
            Path dimDir = DiskScanner.sublevelDirs(this.server).get(plan.key().dim());
            CompoundTag fresh = dimDir != null ? DiskScanner.readEntryTag(dimDir, plan.key()) : null;
            if (fresh == null || !uuid.equals(fresh.getUUID("uuid"))) {
                SablePanel.LOGGER.warn("sablepanel: adopt {} aborted, entry slot changed since prepare", uuid);
                return;
            }
            SubLevelData data = SubLevelSerializer.fromData(fresh);
            if (data == null || !uuid.equals(data.uuid())) {
                SablePanel.LOGGER.warn("sablepanel: adopt {} aborted, entry data mismatch", uuid);
                return;
            }
            CompoundTag posTag = fresh.getCompound("pose").getCompound("position");
            int cx = plan.cold() != null ? plan.cold().chunkX()
                    : clamp(((int) Math.floor(posTag.getDouble("x"))) >> 4,
                    plan.key().rx() * 32, plan.key().rx() * 32 + 31);
            int cz = plan.cold() != null ? plan.cold().chunkZ()
                    : clamp(((int) Math.floor(posTag.getDouble("z"))) >> 4,
                    plan.key().rz() * 32, plan.key().rz() * 32 + 31);
            GlobalSavedSubLevelPointer ptr = new GlobalSavedSubLevelPointer(
                    new ChunkPos(cx, cz), (short) plan.key().storage(), (short) plan.key().index());
            container.getHoldingChunkMap().loadHoldingSubLevel(new HoldingSubLevel(data, ptr));
            if (resolveLoaded(uuid) == null) {
                SablePanel.LOGGER.warn("sablepanel: adopt {} — sable fullyLoad 未产出体(条目在盘上原样保留)", uuid);
            }
        } catch (Throwable t) {
            SablePanel.LOGGER.warn("sablepanel: prepared load {} failed", uuid, t);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private ServerLevel levelOf(String dim) {
        for (ServerLevel level : this.server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dim)) return level;
        }
        return null;
    }

    private ServerSubLevel resolveLoaded(UUID uuid) {
        for (ServerLevel level : this.server.getAllLevels()) {
            try {
                ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
                if (c == null) continue;
                var sl = c.getSubLevel(uuid);
                if (sl instanceof ServerSubLevel ssl && !ssl.isRemoved()) return ssl;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean isHolding(UUID uuid) {
        for (ServerLevel level : this.server.getAllLevels()) {
            try {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container != null && container.getHoldingChunkMap().getHoldingSubLevel(uuid) != null) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ---------- 审计 ----------

    /** 面板手动触发磁盘重扫(异步) */
    public void rescanNow() {
        this.rescan.run();
    }

    private void audit(String op, UUID uuid, String name, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("ev", "panel_op");
        o.addProperty("op", op);
        o.addProperty("uuid", uuid.toString());
        if (name != null) o.addProperty("name", name);
        if (detail != null) o.addProperty("detail", detail);
        EventLog.write(o);
        SablePanel.LOGGER.info("sablepanel: panel op {} {} ({})", op, uuid, name);
    }

    private interface MainTask {
        JsonObject run() throws Exception;
    }

    private JsonObject onMain(MainTask task) throws Exception {
        return submitMain(task).get(20, TimeUnit.SECONDS);
    }

    private JsonObject onMainUntilComplete(MainTask task) throws Exception {
        return submitMain(task).get();
    }

    private CompletableFuture<JsonObject> submitMain(MainTask task) {
        CompletableFuture<JsonObject> fut = new CompletableFuture<>();
        this.server.execute(() -> {
            try {
                fut.complete(task.run());
            } catch (Throwable t) {
                fut.completeExceptionally(t);
            }
        });
        return fut;
    }
}
