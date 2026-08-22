package com.klnon.sablepanel.panel.ops;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;
import com.klnon.sablepanel.panel.copies.CopyVersionScanner;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import com.klnon.sablepanel.panel.recycle.RecycleStore;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
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
import com.klnon.sablepanel.panel.storage.ScanSession;

/**
 * 删除事务引擎:组件化准备 → 作业线程磁盘侧确认校验 → 主线程原子执行 → saveAll → 盘面/运行时双侧验收。
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

    record DiskSnapshot(Set<UUID> members,
                        Map<UUID, Map<DiskScanner.EntryKey, CompoundTag>> entries,
                        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers) {
    }

    record OperationalSnapshot(Map<UUID, String> active,
                               Map<UUID, RecycleStore.OperationalState> states) {
    }

    record DependencyRewrite(UUID uuid, DiskScanner.EntryKey key, CompoundTag original,
                             CompoundTag updated, GlobalSavedSubLevelPointer pointer) {
    }

    @FunctionalInterface
    interface RewriteStep {
        void run() throws Exception;
    }

    record RewriteActions(RewriteStep verifyBefore, RewriteStep writeAfter,
                          RewriteStep verifyAfter, RewriteStep rollback) {
    }

    record DependencyTransition(List<DependencyRewrite> before,
                                List<DependencyRewrite> after) {
    }

    static final class DeleteComponent {
        final Set<UUID> targets = new LinkedHashSet<>();
        final Map<UUID, List<DeleteCopy>> copies = new LinkedHashMap<>();
        final Map<UUID, DeleteCopy> canonical = new LinkedHashMap<>();
        final Map<UUID, RecycleStore.OperationalState> states = new LinkedHashMap<>();
        Set<UUID> diskMembersSnapshot;
        Set<UUID> runtimeMembersSnapshot;
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
        final Map<DiskScanner.EntryKey, CompoundTag> entryTags = new LinkedHashMap<>();
        String recycleGroup;
        boolean removed;
        boolean alreadyAbsent;
        boolean restored;
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
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers = JobService.underLocate(
                () -> DiskScanner.locatePointersStrict(scan.dims(), tags.keySet(), warnings));
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

    List<DependencyRewrite> prepareDependencyRewrites(ScanSession scan, Set<UUID> targets,
                                                      List<String> warnings) throws Exception {
        Set<UUID> survivors = new LinkedHashSet<>();
        for (Set<UUID> component : DiskScanner.selectedDependencyComponents(scan.meta(), List.copyOf(targets))) {
            survivors.addAll(component);
        }
        survivors.removeAll(targets);
        Map<UUID, List<DeleteCopy>> copies = readDeleteCopies(scan, survivors, warnings);
        List<DependencyRewrite> rewrites = new ArrayList<>();
        for (Map.Entry<UUID, List<DeleteCopy>> entry : copies.entrySet()) {
            for (DeleteCopy copy : entry.getValue()) {
                CompoundTag updated = pruneDependencies(copy.tag(), targets);
                if (updated == null) continue;
                GlobalSavedSubLevelPointer pointer = copy.pointers().isEmpty()
                        ? fallbackPointer(copy.key(), copy.tag()) : toPointer(copy.pointers().get(0));
                rewrites.add(new DependencyRewrite(entry.getKey(), copy.key(), copy.tag(), updated, pointer));
            }
        }
        return List.copyOf(rewrites);
    }

    static CompoundTag pruneDependencies(CompoundTag source, Set<UUID> removed) {
        Set<UUID> retained = new LinkedHashSet<>(DiskScanner.dependencies(source));
        if (!retained.removeAll(removed)) return null;
        return CopyOps.retainDependencies(source, retained);
    }

    static List<DependencyRewrite> dependencyState(List<DependencyRewrite> base, Set<UUID> removed) {
        List<DependencyRewrite> state = new ArrayList<>();
        for (DependencyRewrite rewrite : base) {
            CompoundTag updated = pruneDependencies(rewrite.original(), removed);
            state.add(new DependencyRewrite(rewrite.uuid(), rewrite.key(), rewrite.original(),
                    updated == null ? rewrite.original() : updated, rewrite.pointer()));
        }
        return List.copyOf(state);
    }

    DependencyTransition prepareDependencyTransition(List<DependencyRewrite> base,
                                                     Set<UUID> beforeRemoved,
                                                     Set<UUID> afterRemoved,
                                                     List<String> warnings) throws Exception {
        Set<UUID> survivors = new LinkedHashSet<>();
        for (DependencyRewrite rewrite : base) survivors.add(rewrite.uuid());
        ScanSession scan = this.kit.freshScan(warnings);
        Map<UUID, List<DeleteCopy>> current = readDeleteCopies(scan, survivors, warnings);
        return rebaseDependencyTransition(base, current, beforeRemoved, afterRemoved);
    }

    void applyDependencyTransition(DependencyTransition transition) throws Exception {
        if (transition.before().isEmpty()) return;
        this.kit.onMainUntilComplete(() -> {
            applyRewriteTransaction(new RewriteActions(
                    () -> verifyDependencyTags(transition.before()),
                    () -> writeDependencyTags(transition.after()),
                    () -> verifyDependencyTags(transition.after()),
                    () -> {
                        writeDependencyTags(transition.before());
                        verifyDependencyTags(transition.before());
                    }));
            return new JsonObject();
        });
    }

    /**
     * 删除阶段的 saveAll 可能搬动仍在运行的幸存体。依赖改写必须重新绑定最新槽位，
     * 并以最新 NBT 为底稿，只替换依赖集合，不能把旧 pose/速度写回去。
     */
    static DependencyTransition rebaseDependencyTransition(
            List<DependencyRewrite> base, Map<UUID, List<DeleteCopy>> current,
            Set<UUID> beforeRemoved, Set<UUID> afterRemoved) {
        if (beforeRemoved.isEmpty()) return rebaseForwardDependencyTransition(base, current, afterRemoved);
        List<DependencyRewrite> before = new ArrayList<>();
        List<DependencyRewrite> after = new ArrayList<>();
        Map<UUID, List<DependencyRewrite>> preparedByUuid = new LinkedHashMap<>();
        for (DependencyRewrite prepared : base) {
            preparedByUuid.computeIfAbsent(prepared.uuid(), ignored -> new ArrayList<>()).add(prepared);
        }
        for (Map.Entry<UUID, List<DependencyRewrite>> entry : preparedByUuid.entrySet()) {
            List<DeleteCopy> copies = current.getOrDefault(entry.getKey(), List.of());
            if (copies.isEmpty()) {
                throw new IllegalStateException("依赖裁剪目标副本已变化: " + entry.getKey());
            }
            for (DeleteCopy copy : copies) {
                rebaseDependencyCopy(entry.getValue(), copy, beforeRemoved, afterRemoved, before, after);
            }
        }
        return new DependencyTransition(List.copyOf(before), List.copyOf(after));
    }

    private static DependencyTransition rebaseForwardDependencyTransition(
            List<DependencyRewrite> base, Map<UUID, List<DeleteCopy>> current, Set<UUID> removed) {
        List<DependencyRewrite> before = new ArrayList<>();
        List<DependencyRewrite> after = new ArrayList<>();
        Set<UUID> survivors = new LinkedHashSet<>();
        for (DependencyRewrite prepared : base) survivors.add(prepared.uuid());
        for (UUID uuid : survivors) {
            List<DeleteCopy> copies = current.getOrDefault(uuid, List.of());
            if (copies.isEmpty()) throw new IllegalStateException("依赖裁剪目标副本已变化: " + uuid);
            for (DeleteCopy copy : copies) {
                CompoundTag updated = pruneDependencies(copy.tag(), removed);
                if (updated != null) addDependencyRewrite(uuid, copy, updated, before, after);
            }
        }
        return new DependencyTransition(List.copyOf(before), List.copyOf(after));
    }

    private static void rebaseDependencyCopy(List<DependencyRewrite> preparedCopies, DeleteCopy copy,
                                             Set<UUID> beforeRemoved, Set<UUID> afterRemoved,
                                             List<DependencyRewrite> before, List<DependencyRewrite> after) {
        Set<UUID> currentDependencies = new LinkedHashSet<>(DiskScanner.dependencies(copy.tag()));
        DependencyRewrite prepared = preparedCopies.stream()
                .filter(candidate -> currentDependencies.equals(dependencyState(candidate.original(), beforeRemoved))
                        || currentDependencies.equals(dependencyState(candidate.original(), afterRemoved)))
                .sorted(Comparator.comparing((DependencyRewrite candidate) -> !candidate.key().equals(copy.key()))
                        .thenComparing(candidate -> candidate.key().id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "依赖裁剪目标副本已变化: " + preparedCopies.get(0).uuid()));
        Set<UUID> expectedDependencies = dependencyState(prepared.original(), beforeRemoved);
        Set<UUID> desiredDependencies = dependencyState(prepared.original(), afterRemoved);
        if (expectedDependencies.equals(desiredDependencies)) return;
        if (currentDependencies.equals(desiredDependencies)) return;
        CompoundTag updated = CopyOps.retainDependencies(copy.tag(), desiredDependencies);
        addDependencyRewrite(prepared.uuid(), copy, updated, before, after);
    }

    private static void addDependencyRewrite(UUID uuid, DeleteCopy copy, CompoundTag updated,
                                             List<DependencyRewrite> before, List<DependencyRewrite> after) {
        GlobalSavedSubLevelPointer pointer = copy.pointers().isEmpty()
                ? fallbackPointer(copy.key(), copy.tag()) : toPointer(copy.pointers().get(0));
        before.add(new DependencyRewrite(uuid, copy.key(), copy.tag(), copy.tag(), pointer));
        after.add(new DependencyRewrite(uuid, copy.key(), copy.tag(), updated, pointer));
    }

    private static Set<UUID> dependencyState(CompoundTag source, Set<UUID> removed) {
        Set<UUID> dependencies = new LinkedHashSet<>(DiskScanner.dependencies(source));
        dependencies.removeAll(removed);
        return Set.copyOf(dependencies);
    }

    static void applyRewriteTransaction(RewriteActions actions) throws Exception {
        actions.verifyBefore().run();
        try {
            actions.writeAfter().run();
            actions.verifyAfter().run();
        } catch (Throwable failure) {
            try {
                actions.rollback().run();
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            if (failure instanceof Exception exception) throw exception;
            throw new IllegalStateException("幸存体依赖裁剪失败", failure);
        }
    }

    private void writeDependencyTags(List<DependencyRewrite> rewrites) throws Exception {
        Set<SubLevelStorage> touched = new LinkedHashSet<>();
        for (DependencyRewrite rewrite : rewrites) {
            ServerLevel level = this.kit.levelOf(rewrite.key().dim());
            ServerSubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
            if (container == null) throw new IllegalStateException("依赖裁剪目标维度不可用: " + rewrite.key().dim());
            SubLevelData data = SubLevelSerializer.fromData(rewrite.updated());
            if (data == null || !rewrite.uuid().equals(data.uuid())) {
                throw new IllegalStateException("依赖裁剪 NBT 无法解析: " + rewrite.uuid());
            }
            SubLevelStorage storage = container.getHoldingChunkMap().getStorage();
            storage.attemptSaveSubLevel(rewrite.pointer(), data);
            touched.add(storage);
        }
        try {
            for (SubLevelStorage storage : touched) storage.flush();
        } finally {
            DiskScanner.invalidateCache();
        }
    }

    private void verifyDependencyTags(List<DependencyRewrite> rewrites) {
        Map<String, Path> dims = DiskScanner.sublevelDirs(this.kit.server);
        for (DependencyRewrite rewrite : rewrites) {
            CompoundTag current = OpKit.readVerified(dims, rewrite.uuid(), rewrite.key());
            if (!rewrite.updated().equals(current)) {
                throw new IllegalStateException("依赖裁剪槽位复核失败: " + rewrite.key().id());
            }
        }
    }

    void executeDeleteComponents(List<DeleteComponent> components,
                                         Map<UUID, DeleteStatus> statuses) throws Exception {
        // 磁盘侧确认校验留在作业线程:全量重扫+整组重读在大存档上是秒级 IO,从前整段占着主线程。
        // 到主线程块之间的毫秒空档不重开 TOCTOU 口子 —— 槽位搬迁/清空由入队前的逐槽位
        // readVerified 当场中止回滚,指针漂移由删除后验收如实报错;内容以用户确认时的快照为准。
        // 运行态(active 指针/暂停/常驻)只能在主线程读,校验留在块内。
        for (DeleteComponent component : components) {
            if (component.activeSnapshot != null) validatePreparedDiskSnapshot(component);
        }
        try {
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
            cleanupDanglingPointers(statuses);
        } finally {
            PauseService.persist();
            ForceLoadService.persist();
        }
    }

    void processDeleteComponent(DeleteComponent component, Map<UUID, DeleteStatus> statuses,
                                        DeleteFlush flush) throws Exception {
        if (component.activeSnapshot != null) validateOperationalSnapshotOnMain(component);
        for (UUID uuid : component.targets) {
            for (DeleteCopy copy : component.copies.getOrDefault(uuid, List.of())) {
                statuses.get(uuid).entryKeys.add(copy.key());
                statuses.get(uuid).entryTags.put(copy.key(), copy.tag());
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

    /** 作业线程:确认快照的磁盘侧校验 —— 全量重扫+整组重读+指针定位,这些 IO 不占主线程 */
    void validatePreparedDiskSnapshot(DeleteComponent component) throws Exception {
        List<String> warnings = new ArrayList<>();
        ScanSession scan = this.kit.freshScan(warnings);
        if (!warnings.isEmpty()) {
            throw new IOException("删除前存储校验失败: " + String.join("; ", warnings));
        }
        UUID seed = component.targets.iterator().next();
        Set<UUID> currentMembers = CopyVersionScanner.members(scan.meta(), seed);
        Set<UUID> expectedMembers = component.diskMembersSnapshot == null
                ? component.targets : component.diskMembersSnapshot;
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

        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> currentPointers = JobService.underLocate(
                () -> DiskScanner.locatePointersStrict(scan.dims(), keys, warnings));
        if (!warnings.isEmpty()) {
            throw new IOException("删除前指针校验失败: " + String.join("; ", warnings));
        }
        requireUnchangedDiskSnapshot(
                new DiskSnapshot(expectedMembers, expectedEntries, expectedPointers),
                new DiskSnapshot(currentMembers, currentEntries, currentPointers));
    }

    /** 主线程(执行块内):确认快照的运行态校验 —— active 指针/暂停/常驻只能在主线程读 */
    void validateOperationalSnapshotOnMain(DeleteComponent component) {
        if (component.runtimeMembersSnapshot != null) {
            if (component.runtimeMembersSnapshot.isEmpty()) {
                requireColdTargetsUnloaded(component.targets, uuid -> this.kit.resolveLoaded(uuid) != null);
            } else {
                this.kit.requireLoadedDependencyGroupOnMain(component.runtimeMembersSnapshot);
            }
        }
        Map<UUID, RecycleStore.OperationalState> currentStates = new LinkedHashMap<>();
        for (UUID uuid : component.targets) {
            currentStates.put(uuid, new RecycleStore.OperationalState(
                    PauseService.isPaused(uuid), ForceLoadService.isForcedOnMain(this.kit.server, uuid),
                    FreezeService.isFrozen(uuid)));
        }
        requireUnchangedOperationalSnapshot(
                new OperationalSnapshot(component.activeSnapshot, component.states),
                new OperationalSnapshot(this.kit.activeEntriesOnMain(component.targets), currentStates));
    }

    static void requireColdTargetsUnloaded(Collection<UUID> targets,
                                           java.util.function.Predicate<UUID> loaded) {
        if (targets.stream().anyMatch(loaded)) {
            throw new IllegalStateException("物理组在确认期间已经加载，请重新扫描");
        }
    }

    static void requireUnchangedDiskSnapshot(DiskSnapshot expected, DiskSnapshot current) {
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
    }

    static void requireUnchangedOperationalSnapshot(OperationalSnapshot expected, OperationalSnapshot current) {
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
        FreezeService.applyOnMain(targets, false);
        for (UUID uuid : targets) ForceLoadService.removeOnMain(this.kit.server, uuid);
        for (UUID uuid : targets) {
            if (PauseService.isPaused(uuid) || FreezeService.isFrozen(uuid)
                    || ForceLoadService.isForcedOnMain(this.kit.server, uuid)) {
                throw new IllegalStateException("删除前未能清理暂停/冻结/常驻状态: " + uuid);
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
        SablePanel.LOGGER.debug("sablepanel: delete {} removeSubLevel lastPointer={}", uuid, pointer);
        execution.flush.touched().add(level);
        execution.flush.targetsByLevel().computeIfAbsent(level, ignored -> new LinkedHashSet<>()).add(uuid);
        container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
        if (this.kit.resolveLoaded(uuid) != null) throw new IllegalStateException("removeSubLevel 后仍在容器中");
        execution.statuses.get(uuid).removed = true;
        execution.removedBodies.put(uuid, body);
    }

    /**
     * 把体从 holding 区块的"待加载表"里摘掉(按 UUID 全量清扫)。
     * <p>
     * 2026-08-08 实测:当时的 {@code queueDeletion} 只从 {@code getSubLevelPointers()} 摘指针,
     * 内存记录残留会被 {@code saveAll()} 的 holding 循环当搬家写回盘,删除变成搬家、体成孤儿。
     * sable 2.0.4 补丁版的 {@code queueDeletion} 已连内存两张表一起摘,但那是<b>按指针相等</b>的
     * 精确摘除;遗留多条目存档里,另一个已加载 holding 区块可能持有同 UUID、不同指针的兄弟记录,
     * 精确摘除罩不住它,saveAll 仍会把它复活。删除路径按 UUID 清扫的这一步因此保留,
     * 不属于可拆的冗余防线(2026-08-22 减脂审计裁决;同日拆除的是与 2.0.4 头部守卫重复的
     * HoldingChunkMapMixin)。
     * <p>
     * 每个维度在 saveAll 前只扫一次全部已加载 holding 区块,不按 pointer.chunkPos() 直接定位 ——
     * 记录所在区块和 pointer 说的区块本来就可能对不上(sable 那句 mis-match 日志就是为此),
     * 直接定位会漏。
     */
    private void dropHoldingRecords(ServerLevel level, Set<UUID> uuids) {
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) return;
            var map = (com.klnon.sablepanel.mixin.HoldingChunkMapAccessor)
                    (Object) container.getHoldingChunkMap();
            int dropped = 0;
            for (var chunk : map.sablepanel$loadedHoldingChunks().values()) {
                dropped += removeKeys(((com.klnon.sablepanel.mixin.HoldingChunkAccessor) (Object) chunk)
                        .sablepanel$loadedHoldingSubLevels(), uuids);
            }
            // 全局索引也要摘:区块表清了条目才真的删得掉,这张不清体依旧被判定为 holding
            dropped += removeKeys(map.sablepanel$allHoldingSubLevels(), uuids);
            if (dropped > 0) {
                SablePanel.LOGGER.debug("sablepanel: delete {} target(s) dropped {} holding record(s)",
                        uuids.size(), dropped);
            }
        } catch (Throwable error) {
            // 摘不掉不该让删除事务失败:后面的校验会发现条目还在并如实报错
            SablePanel.LOGGER.warn("sablepanel: dropping holding records for {} target(s) failed",
                    uuids.size(), error);
        }
    }

    private void dropInvalidHoldingRecords(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        var map = (com.klnon.sablepanel.mixin.HoldingChunkMapAccessor)
                (Object) container.getHoldingChunkMap();
        for (var chunk : map.sablepanel$loadedHoldingChunks().values()) {
            Map<UUID, dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel> records =
                    ((com.klnon.sablepanel.mixin.HoldingChunkAccessor) (Object) chunk)
                            .sablepanel$loadedHoldingSubLevels();
            Map<UUID, List<UUID>> dependencies = new LinkedHashMap<>();
            records.forEach((uuid, holding) -> dependencies.put(uuid, holding.data().dependencies()));
            for (UUID invalid : invalidHoldingRecords(dependencies)) {
                var removed = records.remove(invalid);
                if (map.sablepanel$allHoldingSubLevels().get(invalid) == removed) {
                    map.sablepanel$allHoldingSubLevels().remove(invalid);
                }
            }
        }
    }

    static Set<UUID> invalidHoldingRecords(Map<UUID, List<UUID>> dependencies) {
        Set<UUID> remaining = new LinkedHashSet<>(dependencies.keySet());
        boolean changed;
        do {
            changed = remaining.removeIf(uuid -> dependencies.getOrDefault(uuid, List.of()).stream()
                    .anyMatch(dependency -> !remaining.contains(dependency)));
        } while (changed);
        Set<UUID> invalid = new LinkedHashSet<>(dependencies.keySet());
        invalid.removeAll(remaining);
        return Set.copyOf(invalid);
    }

    static int removeKeys(Map<UUID, ?> values, Set<UUID> targets) {
        int before = values.size();
        values.keySet().removeAll(targets);
        return before - values.size();
    }

    void queueRemainingCopies(DeleteExecution execution, UUID uuid, ServerSubLevel removedBody) {
        Map<String, Path> dims = DiskScanner.sublevelDirs(this.kit.server);
        List<DeleteCopy> copies = execution.component.copies.getOrDefault(uuid, List.of());
        for (DeleteCopy copy : copies) {
            // sable 清槽(attemptSaveSubLevel(ptr,null))不验 uuid,入队前必须重读槽位确认还是目标,
            // 否则会静默清掉无辜体的条目(此处在主线程,sable 不会并发写盘)
            CompoundTag fresh = OpKit.readVerified(dims, uuid, copy.key());
            if (fresh == null) {
                throw new IllegalStateException("条目 " + copy.key().id() + " 在删除前被 sable 搬迁，已中止并回滚");
            }
            ServerLevel level = this.kit.levelOf(copy.key().dim());
            ServerSubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
            if (container == null) throw new IllegalStateException("存储副本所在维度不可用: " + copy.key().dim());
            execution.flush.touched().add(level);
            execution.flush.targetsByLevel().computeIfAbsent(level, ignored -> new LinkedHashSet<>()).add(uuid);
            for (GlobalSavedSubLevelPointer pointer : deletionPointers(List.of(copy))) {
                SablePanel.LOGGER.debug("sablepanel: delete {} queueDeletion ptr={}", uuid, pointer);
                removedBody.setLastSerializationPointer(pointer);
                container.getHoldingChunkMap().queueDeletion(removedBody);
            }
            removePointerReferences(container, copy);
        }
    }

    private static void removePointerReferences(ServerSubLevelContainer container, DeleteCopy copy) {
        if (copy.pointers().isEmpty()) return;
        var map = container.getHoldingChunkMap();
        var loaded = ((com.klnon.sablepanel.mixin.HoldingChunkMapAccessor) (Object) map)
                .sablepanel$loadedHoldingChunks();
        SavedSubLevelPointer local = new SavedSubLevelPointer(
                (short) copy.key().storage(), (short) copy.key().index());
        Set<Long> chunks = new LinkedHashSet<>();
        for (DiskScanner.LiveLocation location : copy.pointers()) {
            chunks.add(new ChunkPos(location.chunkX(), location.chunkZ()).toLong());
        }
        for (long packed : chunks) {
            ChunkPos position = new ChunkPos(packed);
            SubLevelHoldingChunk chunk = loaded.get(packed);
            if (chunk == null) chunk = map.getStorage().attemptLoadHoldingChunk(position);
            if (chunk == null) throw new IllegalStateException("holding 元数据已变化: " + position);
            chunk.getSubLevelPointers().removeIf(local::equals);
            map.getStorage().attemptSaveHoldingChunk(position, chunk);
        }
    }

    private void cleanupDanglingPointers(Map<UUID, DeleteStatus> statuses) throws Exception {
        Set<DiskScanner.EntryKey> candidates = new LinkedHashSet<>();
        Map<DiskScanner.EntryKey, DeleteCopy> copies = new LinkedHashMap<>();
        Map<DiskScanner.EntryKey, UUID> originalOwners = new LinkedHashMap<>();
        for (DeleteStatus status : statuses.values()) {
            if (!status.removed) continue;
            for (DiskScanner.EntryKey key : status.entryKeys) {
                CompoundTag tag = status.entryTags.get(key);
                if (tag == null) continue;
                candidates.add(key);
                copies.putIfAbsent(key, new DeleteCopy(key, tag, 0, List.of()));
                originalOwners.putIfAbsent(key, status.uuid);
            }
        }
        if (candidates.isEmpty()) return;
        Map<String, Path> dimensions = DiskScanner.sublevelDirs(this.kit.server);
        Set<DiskScanner.EntryKey> occupied = DiskScanner.occupiedEntrySlots(dimensions, candidates);
        Set<DiskScanner.EntryKey> empty = new LinkedHashSet<>(candidates);
        empty.removeAll(occupied);
        if (empty.isEmpty()) return;
        List<String> warnings = new ArrayList<>();
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> current = JobService.underLocate(
                () -> DiskScanner.locatePointersStrict(dimensions, empty, warnings));
        if (!warnings.isEmpty()) {
            throw new IOException("删除后指针定位失败: " + String.join("; ", warnings));
        }
        this.kit.onMainUntilComplete(() -> {
            List<String> recheckWarnings = new ArrayList<>();
            Set<DiskScanner.EntryKey> occupiedNow = new LinkedHashSet<>(
                    DiskScanner.occupiedEntrySlots(dimensions, empty));
            occupiedNow.addAll(occupiedRuntimeSlotsOnMain(empty, originalOwners));
            Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointersNow =
                    DiskScanner.locatePointersStrict(dimensions, empty, recheckWarnings);
            if (!recheckWarnings.isEmpty()) {
                throw new IOException("删除后指针复核失败: " + String.join("; ", recheckWarnings));
            }
            requirePointerCleanupSnapshot(empty, occupiedNow, current, pointersNow);
            Set<SubLevelStorage> touched = new LinkedHashSet<>();
            for (DiskScanner.EntryKey key : empty) {
                List<DiskScanner.LiveLocation> locations = current.getOrDefault(key, List.of());
                if (locations.isEmpty()) continue;
                DeleteCopy prepared = copies.get(key);
                DeleteCopy fresh = new DeleteCopy(key, prepared.tag(), prepared.blocks(), locations);
                ServerLevel level = this.kit.levelOf(key.dim());
                ServerSubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
                if (container == null) throw new IllegalStateException("删除后指针清理维度不可用: " + key.dim());
                removePointerReferences(container, fresh);
                touched.add(container.getHoldingChunkMap().getStorage());
            }
            for (SubLevelStorage storage : touched) storage.flush();
            return new JsonObject();
        });
        warnings.clear();
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> remaining = JobService.underLocate(
                () -> DiskScanner.locatePointersStrict(dimensions, empty, warnings));
        if (!warnings.isEmpty()) {
            throw new IOException("删除后指针复核失败: " + String.join("; ", warnings));
        }
        for (DiskScanner.EntryKey key : empty) {
            int count = remaining.getOrDefault(key, List.of()).size();
            if (count > 0) throw new IllegalStateException("删除后仍有 " + count + " 个 holding 指针: " + key.id());
        }
    }

    private Set<DiskScanner.EntryKey> occupiedRuntimeSlotsOnMain(
            Set<DiskScanner.EntryKey> candidates, Map<DiskScanner.EntryKey, UUID> originalOwners) {
        Set<DiskScanner.EntryKey> occupied = new LinkedHashSet<>();
        for (ServerLevel level : this.kit.server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            String dimension = level.dimension().location().toString();
            for (ServerSubLevel body : List.copyOf(container.getAllSubLevels())) {
                GlobalSavedSubLevelPointer pointer = body.getLastSerializationPointer();
                if (pointer == null) continue;
                DiskScanner.EntryKey key = OpKit.entryKey(dimension, pointer);
                if (candidates.contains(key)) occupied.add(key);
            }
            var allHolding = ((com.klnon.sablepanel.mixin.HoldingChunkMapAccessor) (Object)
                    container.getHoldingChunkMap()).sablepanel$allHoldingSubLevels();
            allHolding.forEach((uuid, holding) -> {
                DiskScanner.EntryKey key = OpKit.entryKey(dimension, holding.pointer());
                if (candidates.contains(key) && !uuid.equals(originalOwners.get(key))) occupied.add(key);
            });
        }
        return occupied;
    }

    static void requirePointerCleanupSnapshot(
            Set<DiskScanner.EntryKey> candidates, Set<DiskScanner.EntryKey> occupied,
            Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> expected,
            Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> actual) {
        Set<DiskScanner.EntryKey> reused = new LinkedHashSet<>(candidates);
        reused.retainAll(occupied);
        if (!reused.isEmpty()) throw new IllegalStateException("指针清理前存储槽已被复用: " + reused);
        for (DiskScanner.EntryKey key : candidates) {
            if (!pointerLocationCounts(expected.getOrDefault(key, List.of())).equals(
                    pointerLocationCounts(actual.getOrDefault(key, List.of())))) {
                throw new IllegalStateException("指针清理前 holding 元数据已变化: " + key.id());
            }
        }
    }

    private static Map<DiskScanner.LiveLocation, Integer> pointerLocationCounts(
            Collection<DiskScanner.LiveLocation> locations) {
        Map<DiskScanner.LiveLocation, Integer> counts = new LinkedHashMap<>();
        for (DiskScanner.LiveLocation location : locations) counts.merge(location, 1, Integer::sum);
        return counts;
    }

    static boolean hasDanglingDeletedPointers(DiskVerification disk,
                                              Map<UUID, DeleteStatus> statuses) {
        for (DeleteStatus status : statuses.values()) {
            if (!status.removed || disk.entries().getOrDefault(status.uuid, 0) > 0) continue;
            int pointers = 0;
            for (DiskScanner.EntryKey key : status.entryKeys) {
                pointers += disk.pointers().getOrDefault(key, 0);
            }
            if (pointers > 0) return true;
        }
        return false;
    }

    static List<GlobalSavedSubLevelPointer> deletionPointers(Collection<DeleteCopy> copies) {
        List<GlobalSavedSubLevelPointer> pointers = new ArrayList<>();
        for (DeleteCopy copy : copies) {
            if (copy.pointers().isEmpty()) pointers.add(fallbackPointer(copy.key(), copy.tag()));
            else for (DiskScanner.LiveLocation location : copy.pointers()) pointers.add(toPointer(location));
        }
        return List.copyOf(pointers);
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
        int chunkX = Math.clamp(((int) Math.floor(posTag.getDouble("x"))) >> 4,
                key.rx() * 32, key.rx() * 32 + 31);
        int chunkZ = Math.clamp(((int) Math.floor(posTag.getDouble("z"))) >> 4,
                key.rz() * 32, key.rz() * 32 + 31);
        return new GlobalSavedSubLevelPointer(new ChunkPos(chunkX, chunkZ),
                (short) key.storage(), (short) key.index());
    }

    void flushDeleteLevels(DeleteFlush flush, Map<UUID, DeleteStatus> statuses) {
        for (ServerLevel level : flush.touched()) {
            try {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container == null) throw new IllegalStateException("物理体容器不存在");
                container.getHoldingChunkMap().getStorage().flush();
                dropHoldingRecords(level, flush.targetsByLevel().getOrDefault(level, Set.of()));
                dropInvalidHoldingRecords(level);
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
        verifyDeletedTargets(List.of(), statuses, warnings, triggerRescan, false);
    }

    void verifyPermanentDeletion(List<DeleteComponent> components, Map<UUID, DeleteStatus> statuses,
                                 List<String> warnings) {
        verifyDeletedTargets(components, statuses, warnings, true, true);
    }

    private void verifyDeletedTargets(List<DeleteComponent> components,
                                      Map<UUID, DeleteStatus> statuses, List<String> warnings,
                                      boolean triggerRescan, boolean detachTracking) {
        DiskVerification disk;
        JsonObject runtime;
        try {
            disk = scanRemainingEntries(statuses, warnings);
            if (hasDanglingDeletedPointers(disk, statuses)) {
                cleanupDanglingPointers(statuses);
                disk = scanRemainingEntries(statuses, warnings);
            }
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
            boolean frozen = state != null && state.get("frozen").getAsBoolean();
            if (status.remainingEntries > 0) status.fail("仍有 " + status.remainingEntries + " 个磁盘条目");
            if (status.remainingPointers > 0) status.fail("仍有 " + status.remainingPointers + " 个 holding 指针");
            if (loaded) status.fail("运行时物理体仍存在");
            if (holding) status.fail("holding 中仍存在");
            if (paused) status.fail("暂停状态仍存在");
            if (frozen) status.fail("冻结状态仍存在");
            if (forced) status.fail("常驻加载票仍存在");
            if (!status.removed && !status.alreadyAbsent) status.fail("未执行删除");
        }

        Map<UUID, String> trackingErrors = Map.of();
        if (detachTracking) {
            try {
                trackingErrors = detachDeletedTrackingPoints(components, statuses);
            } catch (Exception error) {
                String message = "删除后追踪点验收失败: " + messageOf(error);
                for (DeleteStatus status : statuses.values()) status.fail(message);
            }
        }
        for (DeleteStatus status : statuses.values()) {
            if (trackingErrors.containsKey(status.uuid)) status.fail(trackingErrors.get(status.uuid));
            status.ok = status.errors.isEmpty();
        }
        if (triggerRescan) this.kit.rescan.run();
    }

    private Map<UUID, String> detachDeletedTrackingPoints(List<DeleteComponent> components,
                                                          Map<UUID, DeleteStatus> statuses) throws Exception {
        List<Map<UUID, Map<DiskScanner.EntryKey, CompoundTag>>> deletedGroups = new ArrayList<>();
        for (DeleteComponent component : components) {
            boolean eligible = component.targets.stream()
                    .map(statuses::get)
                    .allMatch(status -> status != null && status.errors.isEmpty()
                            && (status.removed || status.alreadyAbsent));
            if (!eligible) continue;
            Map<UUID, Map<DiskScanner.EntryKey, CompoundTag>> deleted = new LinkedHashMap<>();
            for (UUID uuid : component.targets) {
                DeleteStatus status = statuses.get(uuid);
                if (status.removed) deleted.put(uuid, Map.copyOf(status.entryTags));
            }
            if (!deleted.isEmpty()) deletedGroups.add(Map.copyOf(deleted));
        }
        if (deletedGroups.isEmpty()) return Map.of();
        Map<UUID, String> errors = new LinkedHashMap<>();
        this.kit.onMain(() -> {
            for (Map<UUID, Map<DiskScanner.EntryKey, CompoundTag>> deleted : deletedGroups) {
                errors.putAll(TrackingPointService.detachDeletedOnMain(this.kit.server, deleted));
            }
            return new JsonObject();
        });
        return Map.copyOf(errors);
    }

    DiskVerification scanRemainingEntries(Map<UUID, DeleteStatus> statuses, List<String> warnings)
            throws Exception {
        ScanSession scan = this.kit.freshScan(warnings);
        Set<DiskScanner.EntryKey> keys = new LinkedHashSet<>();
        Map<UUID, Integer> entries = new HashMap<>();
        Map<DiskScanner.EntryKey, UUID> originalOwners = new LinkedHashMap<>();
        for (DeleteStatus status : statuses.values()) {
            keys.addAll(status.entryKeys);
            for (DiskScanner.EntryKey key : status.entryKeys) originalOwners.put(key, status.uuid);
            entries.put(status.uuid, scan.entriesOf(status.uuid).size());
        }
        Map<DiskScanner.EntryKey, UUID> currentOwners = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<DiskScanner.EntryMeta>> entry : scan.meta().entrySet()) {
            for (DiskScanner.EntryMeta copy : entry.getValue()) currentOwners.put(copy.key(), entry.getKey());
        }
        Map<DiskScanner.EntryKey, Integer> rawPointerCounts = new HashMap<>();
        JobService.underLocate(() -> DiskScanner.locatePointersStrict(scan.dims(), keys, warnings))
                .forEach((key, locations) -> rawPointerCounts.put(key, locations.size()));
        Map<DiskScanner.EntryKey, Integer> pointerCounts = new HashMap<>(
                targetPointerCounts(rawPointerCounts, originalOwners, currentOwners));
        /* 验收失败时把「到底哪个槽位还剩着」打出来。「仍有 N 个磁盘条目」单看数字定位不了 ——
           排查这条错时最想知道的是残留的是规范副本(指望 removeSubLevel 清)还是排了队的那份。 */
        for (DeleteStatus status : statuses.values()) {
            List<String> remaining = scan.entriesOf(status.uuid).stream().map(e -> e.key().id()).toList();
            if (remaining.isEmpty()) continue;
            SablePanel.LOGGER.warn("sablepanel: delete verification {} remaining={} expected={} pointers={}",
                    status.uuid, remaining,
                    status.entryKeys.stream().map(DiskScanner.EntryKey::id).toList(), pointerCounts);
        }
        return new DiskVerification(entries, pointerCounts);
    }

    static Map<DiskScanner.EntryKey, Integer> targetPointerCounts(
            Map<DiskScanner.EntryKey, Integer> pointers,
            Map<DiskScanner.EntryKey, UUID> originalOwners,
            Map<DiskScanner.EntryKey, UUID> currentOwners) {
        Map<DiskScanner.EntryKey, Integer> remaining = new LinkedHashMap<>();
        for (Map.Entry<DiskScanner.EntryKey, Integer> entry : pointers.entrySet()) {
            UUID current = currentOwners.get(entry.getKey());
            UUID original = originalOwners.get(entry.getKey());
            if (current == null || current.equals(original)) remaining.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(remaining);
    }

    DeleteComponent prepareExactDeleteComponent(Set<UUID> targets, List<String> warnings)
            throws Exception {
        return prepareExactDeleteComponent(targets, warnings, true);
    }

    DeleteComponent prepareExactDeleteComponent(Set<UUID> targets, List<String> warnings,
                                                 boolean allowPreSave) throws Exception {
        ScanSession scan = this.kit.strictScan(warnings);
        if (allowPreSave && this.kit.flushUnsavedTargets(new ArrayList<>(targets), scan.meta())) {
            scan = this.kit.strictScan(warnings);
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
        ScanSession scan = this.kit.freshScan(warnings);
        JsonObject runtime = this.kit.readRuntimeStates(targets);
        for (UUID uuid : targets) {
            if (!scan.entriesOf(uuid).isEmpty()) {
                throw new IllegalStateException("回滚前仍有磁盘条目: " + uuid);
            }
            JsonObject state = runtime.getAsJsonObject(uuid.toString());
            if (state != null && (state.get("loaded").getAsBoolean() || state.get("holding").getAsBoolean()
                    || state.get("paused").getAsBoolean() || state.get("frozen").getAsBoolean()
                    || state.get("forced").getAsBoolean())) {
                throw new IllegalStateException("回滚前运行时或操作状态仍存在: " + uuid);
            }
        }
    }

    void failComponent(DeleteComponent component, Map<UUID, DeleteStatus> statuses, String message) {
        for (UUID uuid : component.targets) statuses.get(uuid).fail(message);
    }
}
