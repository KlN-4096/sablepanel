package com.klnon.sablepanel.panel.service;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;
import com.klnon.sablepanel.panel.data.CopyVersionScanner;
import com.klnon.sablepanel.panel.data.DiskScanner;
import com.klnon.sablepanel.panel.data.RecycleStore;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 删除事务引擎:组件化准备 → 主线程原子执行 → saveAll → 盘面/运行时双侧验收。
 * deleteBatch(DeleteOps)、恢复前清场(RestoreOps)与副本切换/隔离(CopyOps)共用同一份实现,
 * 快照失配即中止、部分删除标记、指针逐一入队的语义只实现一份。
 */
final class DeleteTx {
    private final OpKit kit;

    DeleteTx(OpKit kit) {
        this.kit = kit;
    }

    record DiskVerification(Map<UUID, Integer> entries,
                                    Map<DiskScanner.EntryKey, Integer> pointers) {
    }

    record DeleteCopy(DiskScanner.EntryKey key, CompoundTag tag, int blocks,
                              List<DiskScanner.LiveLocation> pointers) {
        OpKit.MemberPlan loadPlan() {
            return new OpKit.MemberPlan(this.key, this.tag, this.pointers.isEmpty() ? null : this.pointers.get(0));
        }
    }

    record CopySnapshot(Set<UUID> members,
                        Map<UUID, Map<DiskScanner.EntryKey, CompoundTag>> entries,
                        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers,
                        Map<UUID, String> active,
                        Map<UUID, RecycleStore.OperationalState> states) {
    }

    static final class DeleteComponent {
        final Set<UUID> targets = new LinkedHashSet<>();
        final Map<UUID, List<DeleteCopy>> copies = new LinkedHashMap<>();
        final Map<UUID, DeleteCopy> canonical = new LinkedHashMap<>();
        final Map<UUID, RecycleStore.OperationalState> states = new LinkedHashMap<>();
        Map<UUID, String> activeSnapshot;
        RecycleStore.Stage stage;
        boolean stateCleared;

        void addTarget(UUID uuid, List<DeleteCopy> prepared) {
            this.targets.add(uuid);
            this.copies.put(uuid, List.copyOf(prepared));
        }
    }

    static final class DeleteStatus {
        final UUID uuid;
        final Set<String> errors = new LinkedHashSet<>();
        final Set<DiskScanner.EntryKey> entryKeys = new LinkedHashSet<>();
        String recycleGroup;
        boolean removed;
        boolean alreadyAbsent;
        boolean ok;
        int remainingEntries;
        int remainingPointers;

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

    record DeleteFlush(Set<ServerLevel> touched,
                               Map<ServerLevel, Set<UUID>> targetsByLevel) {
    }

    static final class DeleteExecution {
        final DeleteComponent component;
        final Map<UUID, DeleteStatus> statuses;
        final DeleteFlush flush;
        final Map<UUID, ServerSubLevel> removedBodies = new LinkedHashMap<>();
        final Map<UUID, List<GlobalSavedSubLevelPointer>> handledPointers = new LinkedHashMap<>();

        DeleteExecution(DeleteComponent component, Map<UUID, DeleteStatus> statuses, DeleteFlush flush) {
            this.component = component;
            this.statuses = statuses;
            this.flush = flush;
        }
    }

    /** 只为指定成员重读完整 NBT；重读时验 UUID，避免准备期间槽位被 Sable 复用。 */
    Map<UUID, List<DeleteCopy>> readDeleteCopies(ScanSession scan, Set<UUID> targets,
                                                         List<String> warnings) throws Exception {
        Map<DiskScanner.EntryKey, CompoundTag> tags = new LinkedHashMap<>();
        for (UUID target : targets) {
            for (DiskScanner.EntryMeta copy : scan.entriesOf(target)) {
                tags.put(copy.key(), OpKit.readVerifiedTag(scan.dims(), target, copy.key()));
            }
        }
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers =
                DiskScanner.locatePointersStrict(scan.dims(), tags.keySet(), warnings);
        Map<UUID, List<DeleteCopy>> result = new LinkedHashMap<>();
        for (UUID target : targets) {
            List<DeleteCopy> copies = new ArrayList<>();
            for (DiskScanner.EntryMeta copy : scan.entriesOf(target)) {
                CompoundTag tag = tags.get(copy.key());
                copies.add(new DeleteCopy(copy.key(), tag,
                        DiskScanner.countBlocks(tag.getCompound("plot"), null),
                        List.copyOf(pointers.getOrDefault(copy.key(), List.of()))));
            }
            result.put(target, List.copyOf(copies));
        }
        return result;
    }

    void executeDeleteComponents(List<DeleteComponent> components,
                                         Map<UUID, DeleteStatus> statuses) throws Exception {
        this.kit.onMainUntilComplete(() -> {
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

    void processDeleteComponent(DeleteComponent component, Map<UUID, DeleteStatus> statuses,
                                        DeleteFlush flush) throws Exception {
        if (component.activeSnapshot != null) validatePreparedSnapshotOnMain(component);
        for (UUID uuid : component.targets) {
            for (DeleteCopy copy : component.copies.getOrDefault(uuid, List.of())) {
                statuses.get(uuid).entryKeys.add(copy.key());
            }
        }
        if (componentHasErrors(component, statuses)) return;
        boolean hasCopies = component.copies.values().stream().anyMatch(copies -> !copies.isEmpty());
        if (!hasCopies && componentIsAbsent(component)) {
            clearOperationalStateOnMain(component.targets);
            for (UUID uuid : component.targets) statuses.get(uuid).alreadyAbsent = true;
            return;
        }
        for (UUID uuid : component.targets) {
            if (component.copies.getOrDefault(uuid, List.of()).isEmpty()) {
                failComponent(component, statuses, "目标缺少可备份的磁盘条目,未执行在线删除");
                return;
            }
        }

        component.stateCleared = true;
        clearOperationalStateOnMain(component.targets);
        removeTargetCopies(new DeleteExecution(component, statuses, flush));
    }

    void validatePreparedSnapshotOnMain(DeleteComponent component) throws Exception {
        List<String> warnings = new ArrayList<>();
        ScanSession scan = ScanSession.fresh(this.kit.server, warnings);
        if (!warnings.isEmpty()) {
            throw new IOException("删除前存储校验失败: " + String.join("; ", warnings));
        }
        UUID seed = component.targets.iterator().next();
        Set<UUID> currentMembers = CopyVersionScanner.members(scan.meta(), seed);
        Map<UUID, Map<DiskScanner.EntryKey, CompoundTag>> expectedEntries = new LinkedHashMap<>();
        Map<UUID, Map<DiskScanner.EntryKey, CompoundTag>> currentEntries = new LinkedHashMap<>();
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> expectedPointers = new LinkedHashMap<>();
        Set<DiskScanner.EntryKey> keys = new LinkedHashSet<>();
        for (UUID uuid : component.targets) {
            Map<DiskScanner.EntryKey, CompoundTag> expected = new LinkedHashMap<>();
            for (DeleteCopy copy : component.copies.getOrDefault(uuid, List.of())) {
                expected.put(copy.key(), copy.tag());
                expectedPointers.put(copy.key(), copy.pointers());
                keys.add(copy.key());
            }
            expectedEntries.put(uuid, expected);
            Map<DiskScanner.EntryKey, CompoundTag> current = new LinkedHashMap<>();
            for (DiskScanner.EntryMeta entry : scan.entriesOf(uuid)) {
                current.put(entry.key(), OpKit.readVerifiedTag(scan.dims(), uuid, entry.key()));
                keys.add(entry.key());
            }
            currentEntries.put(uuid, current);
        }

        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> currentPointers =
                DiskScanner.locatePointersStrict(scan.dims(), keys, warnings);
        if (!warnings.isEmpty()) {
            throw new IOException("删除前指针校验失败: " + String.join("; ", warnings));
        }
        Map<UUID, RecycleStore.OperationalState> currentStates = new LinkedHashMap<>();
        for (UUID uuid : component.targets) {
            currentStates.put(uuid, new RecycleStore.OperationalState(
                    PauseService.isPaused(uuid), ForceLoadService.isForcedOnMain(this.kit.server, uuid)));
        }
        requireUnchangedCopySnapshot(
                new CopySnapshot(component.targets, expectedEntries, expectedPointers,
                        component.activeSnapshot, component.states),
                new CopySnapshot(currentMembers, currentEntries, currentPointers,
                        this.kit.activeEntriesOnMain(component.targets), currentStates));
    }

    static void requireUnchangedCopySnapshot(CopySnapshot expected, CopySnapshot current) {
        if (!expected.members().equals(current.members())) {
            throw new IllegalStateException("副本依赖组在确认期间发生变化，请重新扫描");
        }
        if (!expected.entries().keySet().equals(current.entries().keySet())) {
            throw new IllegalStateException("副本成员在确认期间发生变化，请重新扫描");
        }
        for (UUID uuid : expected.entries().keySet()) {
            Map<DiskScanner.EntryKey, CompoundTag> expectedEntries = expected.entries().get(uuid);
            Map<DiskScanner.EntryKey, CompoundTag> currentEntries = current.entries().get(uuid);
            if (!expectedEntries.keySet().equals(currentEntries.keySet())) {
                throw new IllegalStateException("物理结构 " + uuid + " 的副本槽位在确认期间发生变化，请重新扫描");
            }
            for (Map.Entry<DiskScanner.EntryKey, CompoundTag> entry : expectedEntries.entrySet()) {
                if (!entry.getValue().equals(currentEntries.get(entry.getKey()))) {
                    throw new IllegalStateException("条目 " + entry.getKey().id() + " 在确认期间发生变化，请重新扫描");
                }
                List<DiskScanner.LiveLocation> expectedValues =
                        expected.pointers().getOrDefault(entry.getKey(), List.of());
                List<DiskScanner.LiveLocation> currentValues =
                        current.pointers().getOrDefault(entry.getKey(), List.of());
                if (!orderedPointers(expectedValues).equals(orderedPointers(currentValues))) {
                    throw new IllegalStateException("条目 " + entry.getKey().id()
                            + " 的 holding 指针在确认期间发生变化，请重新扫描");
                }
            }
        }
        if (!expected.active().equals(current.active())) {
            throw new IllegalStateException("当前运行版本在确认期间发生变化，请重新扫描");
        }
        if (!expected.states().equals(current.states())) {
            throw new IllegalStateException("运行状态在确认期间发生变化，请重新扫描");
        }
    }

    static List<DiskScanner.LiveLocation> orderedPointers(
            Collection<DiskScanner.LiveLocation> pointers) {
        return pointers.stream().sorted(Comparator
                .comparing((DiskScanner.LiveLocation pointer) -> pointer.key().id())
                .thenComparingInt(DiskScanner.LiveLocation::chunkX)
                .thenComparingInt(DiskScanner.LiveLocation::chunkZ)).toList();
    }

    void clearOperationalStateOnMain(Collection<UUID> targets) {
        PauseService.applyOnMain(this.kit.server, targets, false);
        for (UUID uuid : targets) ForceLoadService.removeOnMain(this.kit.server, uuid);
        for (UUID uuid : targets) {
            if (PauseService.isPaused(uuid) || ForceLoadService.isForcedOnMain(this.kit.server, uuid)) {
                throw new IllegalStateException("删除前未能清理暂停/常驻状态: " + uuid);
            }
        }
    }

    boolean componentHasErrors(DeleteComponent component, Map<UUID, DeleteStatus> statuses) {
        for (UUID uuid : component.targets) {
            if (!statuses.get(uuid).errors.isEmpty()) return true;
        }
        return false;
    }

    boolean componentIsAbsent(DeleteComponent component) {
        for (UUID uuid : component.targets) {
            if (this.kit.resolveLoaded(uuid) != null || this.kit.isHolding(uuid)) return false;
        }
        return true;
    }

    void removeTargetCopies(DeleteExecution execution) {
        if (!preflightDeleteCopies(execution)) return;
        boolean failed = false;
        try {
            loadCanonicalTargets(execution);
            removeLoadedTargets(execution);
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

    /** 只把每个 UUID 的规范副本加载一次；依赖连带加载后，其余成员会直接命中运行时。 */
    void loadCanonicalTargets(DeleteExecution execution) {
        for (UUID uuid : execution.component.targets) {
            if (this.kit.resolveLoaded(uuid) != null) continue;
            DeleteCopy copy = execution.component.canonical.get(uuid);
            if (copy == null) throw new IllegalStateException("目标没有规范副本: " + uuid);
            this.kit.loadPreparedMember(uuid, copy.loadPlan());
        }
    }

    boolean preflightDeleteCopies(DeleteExecution execution) {
        for (List<DeleteCopy> copies : execution.component.copies.values()) {
            for (DeleteCopy copy : copies) {
                ServerLevel level = this.kit.levelOf(copy.key().dim());
                if (level == null || SubLevelContainer.getContainer(level) == null) {
                    failComponent(execution.component, execution.statuses,
                            "删除前检查失败: 存储副本所在维度不可用");
                    return false;
                }
            }
        }
        return true;
    }

    void removeLoadedTargets(DeleteExecution execution) {
        for (UUID uuid : execution.component.targets) {
            ServerSubLevel body = this.kit.resolveLoaded(uuid);
            if (body == null) continue;
            removeLoadedTarget(execution, uuid, body);
        }
    }

    void removeLoadedTarget(DeleteExecution execution, UUID uuid, ServerSubLevel body) {
        if (execution.removedBodies.containsKey(uuid)) return;
        ServerLevel level = body.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("物理体容器不存在");
        GlobalSavedSubLevelPointer pointer = body.getLastSerializationPointer();
        execution.flush.touched().add(level);
        execution.flush.targetsByLevel().computeIfAbsent(level, ignored -> new LinkedHashSet<>()).add(uuid);
        container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
        if (this.kit.resolveLoaded(uuid) != null) throw new IllegalStateException("removeSubLevel 后仍在容器中");
        execution.statuses.get(uuid).removed = true;
        execution.removedBodies.put(uuid, body);
        if (pointer != null) {
            execution.handledPointers.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(pointer);
        }
    }

    void queueRemainingCopies(DeleteExecution execution, UUID uuid, ServerSubLevel removedBody) {
        List<GlobalSavedSubLevelPointer> unconsumed = new ArrayList<>(
                execution.handledPointers.getOrDefault(uuid, List.of()));
        Map<String, Path> dims = DiskScanner.sublevelDirs(this.kit.server);
        for (DeleteCopy copy : execution.component.copies.getOrDefault(uuid, List.of())) {
            // sable 清槽(attemptSaveSubLevel(ptr,null))不验 uuid,入队前必须重读槽位确认还是目标,
            // 否则会静默清掉无辜体的条目(此处在主线程,sable 不会并发写盘)
            CompoundTag fresh = OpKit.readVerified(dims, uuid, copy.key());
            if (fresh == null) {
                throw new IllegalStateException("条目 " + copy.key().id() + " 在删除前被 sable 搬迁，已中止并回滚");
            }
            List<GlobalSavedSubLevelPointer> pointers = new ArrayList<>();
            if (copy.pointers().isEmpty()) {
                pointers.add(fallbackPointer(copy.key(), copy.tag()));
            } else {
                for (DiskScanner.LiveLocation location : copy.pointers()) pointers.add(toPointer(location));
            }
            for (GlobalSavedSubLevelPointer pointer : pointers) {
                int handledIndex = unconsumed.indexOf(pointer);
                if (handledIndex >= 0) {
                    unconsumed.remove(handledIndex);
                    continue;
                }
                ServerLevel level = this.kit.levelOf(copy.key().dim());
                ServerSubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
                if (container == null) throw new IllegalStateException("存储副本所在维度不可用: " + copy.key().dim());
                execution.flush.touched().add(level);
                execution.flush.targetsByLevel().computeIfAbsent(level, ignored -> new LinkedHashSet<>()).add(uuid);
                removedBody.setLastSerializationPointer(pointer);
                container.getHoldingChunkMap().queueDeletion(removedBody);
            }
        }
    }

    void markPartialDelete(DeleteExecution execution) {
        boolean partial = execution.component.targets.stream()
                .anyMatch(uuid -> execution.statuses.get(uuid).removed);
        String message = partial
                ? "同一依赖组发生不可自动回滚的部分删除,请从回收站恢复"
                : "同一依赖组未执行完整删除";
        failComponent(execution.component, execution.statuses, message);
        SablePanel.LOGGER.error("sablepanel: {}: {}", message, OpKit.shortUuids(execution.component.targets));
    }

    static GlobalSavedSubLevelPointer toPointer(DiskScanner.LiveLocation location) {
        return new GlobalSavedSubLevelPointer(new ChunkPos(location.chunkX(), location.chunkZ()),
                (short) location.key().storage(), (short) location.key().index());
    }

    static GlobalSavedSubLevelPointer fallbackPointer(DiskScanner.EntryKey key, CompoundTag tag) {
        CompoundTag posTag = tag.getCompound("pose").getCompound("position");
        int chunkX = OpKit.clamp(((int) Math.floor(posTag.getDouble("x"))) >> 4,
                key.rx() * 32, key.rx() * 32 + 31);
        int chunkZ = OpKit.clamp(((int) Math.floor(posTag.getDouble("z"))) >> 4,
                key.rz() * 32, key.rz() * 32 + 31);
        return new GlobalSavedSubLevelPointer(new ChunkPos(chunkX, chunkZ),
                (short) key.storage(), (short) key.index());
    }

    void flushDeleteLevels(DeleteFlush flush, Map<UUID, DeleteStatus> statuses) {
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

    void verifyDeletedTargets(Map<UUID, DeleteStatus> statuses, List<String> warnings) {
        verifyDeletedTargets(statuses, warnings, true);
    }

    void verifyDeletedTargets(Map<UUID, DeleteStatus> statuses, List<String> warnings,
                                      boolean triggerRescan) {
        DiskVerification disk;
        JsonObject runtime;
        try {
            disk = scanRemainingEntries(statuses, warnings);
            runtime = this.kit.readRuntimeStates(statuses.keySet());
        } catch (Exception error) {
            String message = "删除后验收失败: " + messageOf(error);
            for (DeleteStatus status : statuses.values()) status.fail(message);
            if (triggerRescan) this.kit.rescan.run();
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
            boolean paused = state != null && state.get("paused").getAsBoolean();
            boolean forced = state != null && state.get("forced").getAsBoolean();
            if (status.remainingEntries > 0) status.fail("仍有 " + status.remainingEntries + " 个磁盘条目");
            if (status.remainingPointers > 0) status.fail("仍有 " + status.remainingPointers + " 个 holding 指针");
            if (loaded) status.fail("运行时物理体仍存在");
            if (holding) status.fail("holding 中仍存在");
            if (paused) status.fail("暂停状态仍存在");
            if (forced) status.fail("常驻加载票仍存在");
            if (!status.removed && !status.alreadyAbsent) status.fail("未执行删除");
            status.ok = status.errors.isEmpty();
        }
        if (triggerRescan) this.kit.rescan.run();
    }

    DiskVerification scanRemainingEntries(Map<UUID, DeleteStatus> statuses, List<String> warnings)
            throws Exception {
        ScanSession scan = ScanSession.fresh(this.kit.server, warnings);
        Set<DiskScanner.EntryKey> keys = new LinkedHashSet<>();
        Map<UUID, Integer> entries = new HashMap<>();
        for (DeleteStatus status : statuses.values()) {
            keys.addAll(status.entryKeys);
            entries.put(status.uuid, scan.entriesOf(status.uuid).size());
        }
        Map<DiskScanner.EntryKey, Integer> pointerCounts = new HashMap<>();
        DiskScanner.locatePointersStrict(scan.dims(), keys, warnings)
                .forEach((key, locations) -> pointerCounts.put(key, locations.size()));
        return new DiskVerification(entries, pointerCounts);
    }

    DeleteComponent prepareExactDeleteComponent(Set<UUID> targets, List<String> warnings)
            throws Exception {
        ScanSession scan = ScanSession.strict(this.kit.server, warnings);
        if (this.kit.flushUnsavedTargets(new ArrayList<>(targets), scan.meta())) {
            scan = ScanSession.strict(this.kit.server, warnings);
        }
        Map<UUID, List<DeleteCopy>> prepared = readDeleteCopies(scan, targets, warnings);
        DeleteComponent component = new DeleteComponent();
        for (UUID target : targets) {
            List<DeleteCopy> copies = prepared.getOrDefault(target, List.of());
            if (!copies.isEmpty()) {
                component.addTarget(target, copies);
                component.canonical.put(target, copies.stream()
                        .min(OpKit.canonicalOrder(DeleteCopy::key, DeleteCopy::pointers, null)).orElseThrow());
            }
        }
        return component;
    }

    void requireTargetsAbsent(Set<UUID> targets, List<String> warnings) throws Exception {
        ScanSession scan = ScanSession.fresh(this.kit.server, warnings);
        JsonObject runtime = this.kit.readRuntimeStates(targets);
        for (UUID uuid : targets) {
            if (!scan.entriesOf(uuid).isEmpty()) {
                throw new IllegalStateException("回滚前仍有磁盘条目: " + uuid);
            }
            JsonObject state = runtime.getAsJsonObject(uuid.toString());
            if (state != null && (state.get("loaded").getAsBoolean() || state.get("holding").getAsBoolean()
                    || state.get("paused").getAsBoolean() || state.get("forced").getAsBoolean())) {
                throw new IllegalStateException("回滚前运行时或操作状态仍存在: " + uuid);
            }
        }
    }

    void failComponent(DeleteComponent component, Map<UUID, DeleteStatus> statuses, String message) {
        for (UUID uuid : component.targets) statuses.get(uuid).fail(message);
    }
}
