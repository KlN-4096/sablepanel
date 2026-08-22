package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.panel.bodies.BodyIndex;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import com.klnon.sablepanel.panel.storage.ScanSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.audit.EventLog;
import com.klnon.sablepanel.SablePanel;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import com.klnon.sablepanel.panel.MainThread;

/**
 * 面板操作的共享底盘:服务器/索引/重扫入口、单把变更锁,以及各操作服务共用的
 * 加载链路(链 BFS/ensureLoaded/snatch/收养)、主线程状态读取、盘面重读验 UUID
 * 与落盘收尾原语。原 OpsService 拆分后留下的公共部分,各操作服务持有它协作。
 */
public final class OpKit {
    final MinecraftServer server;
    final BodyIndex index;
    final Runnable rescan;
    /** 变更型操作(删除/恢复/副本处理/一致性修复)共用的互斥锁 —— 原 OpsService 单实例监视器的继任 */
    final Object lock = new Object();

    OpKit(MinecraftServer server, BodyIndex index, Runnable rescan) {
        this.server = server;
        this.index = index;
        this.rescan = rescan;
    }

    static final int MAX_CHAIN = 64;

    /** 收养链成员:条目位置+NBT+可选活指针 */
    record MemberPlan(DiskScanner.EntryKey key, CompoundTag tag, DiskScanner.LiveLocation cold) {
    }

    record RuntimeSnapshot(String dimension, CompoundTag tag) {
    }

    record DependencySelection(List<UUID> roots, List<UUID> members, List<Set<UUID>> components,
                               Map<UUID, MemberPlan> plans) {
        DependencySelection(List<UUID> roots, List<UUID> members, List<Set<UUID>> components) {
            this(roots, members, components, Map.of());
        }
    }

    record LoadAttempts(BooleanSupplier selectedCold, BooleanSupplier selectedPrepared,
                        BooleanSupplier matchingHolding) {
    }

    /** 加载中的体以 Sable 当前运行链为准；未加载体才使用严格磁盘依赖闭包。 */
    List<UUID> expandToDependencyGroups(Collection<UUID> roots) throws Exception {
        return dependencyGroups(roots).members();
    }

    DependencySelection dependencyGroups(Collection<UUID> roots) throws Exception {
        return dependencyGroups(roots, false);
    }

    DependencySelection forcedDisableGroups(Collection<UUID> requested,
                                             Collection<UUID> activeForced) throws Exception {
        List<UUID> selected = List.copyOf(new LinkedHashSet<>(requested));
        LinkedHashSet<UUID> roots = new LinkedHashSet<>(selected);
        roots.addAll(activeForced);
        DependencySelection all = dependencyGroups(roots, true);
        List<Set<UUID>> components = intersectingComponents(all.components(), selected);
        LinkedHashSet<UUID> members = new LinkedHashSet<>();
        components.forEach(members::addAll);
        List<UUID> componentRoots = all.roots().stream().filter(members::contains).toList();
        return new DependencySelection(componentRoots, List.copyOf(members), components);
    }

    static List<Set<UUID>> intersectingComponents(Collection<Set<UUID>> components,
                                                   Collection<UUID> selected) {
        Set<UUID> requested = Set.copyOf(selected);
        return components.stream()
                .filter(component -> component.stream().anyMatch(requested::contains)).toList();
    }

    DependencySelection forceLoadCandidates(Collection<UUID> roots) throws Exception {
        List<UUID> requested = List.copyOf(new LinkedHashSet<>(roots));
        Map<UUID, Set<UUID>> runtime = runtimeDependencyGroups(requested);
        List<String> warnings = new ArrayList<>();
        ScanSession scan = strictScan(warnings);
        if (!warnings.isEmpty()) {
            throw new IllegalStateException("常驻候选磁盘扫描存在损坏: " + String.join("; ", warnings));
        }
        Map<UUID, List<DiskScanner.EntryMeta>> disk = scan.meta();
        Set<UUID> unknown = new LinkedHashSet<>();
        for (UUID root : requested) {
            if (!runtime.containsKey(root) && !disk.containsKey(root)) unknown.add(root);
        }
        if (!unknown.isEmpty()) throw new IllegalStateException("依赖组根成员不存在: " + unknown);

        Set<UUID> loadedMembers = new LinkedHashSet<>();
        runtime.values().forEach(loadedMembers::addAll);
        Map<UUID, String> selectedEntries = onMain(() -> activeEntriesOnMain(loadedMembers));
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers =
                forceLoadPointers(scan);
        Map<UUID, String> resolvedEntries = pointedForceLoadEntries(disk, selectedEntries, pointers);
        List<Set<UUID>> components = selectForceLoadCandidateGroupSets(
                requested, disk, runtime, resolvedEntries);
        LinkedHashSet<UUID> members = new LinkedHashSet<>();
        components.forEach(members::addAll);
        Map<UUID, MemberPlan> plans = JobService.underLocate(
                () -> prepareForceLoadPlans(scan, members, resolvedEntries, pointers));
        return new DependencySelection(requested, List.copyOf(members), components, plans);
    }

    List<List<UUID>> forceLoadIntentGroups(Collection<UUID> candidates) throws Exception {
        List<String> warnings = new ArrayList<>();
        ScanSession scan = strictScan(warnings);
        if (!warnings.isEmpty()) {
            throw new IllegalStateException("常驻意图磁盘扫描存在损坏: " + String.join("; ", warnings));
        }
        return forceLoadIntentGroups(scan.meta(), candidates);
    }

    static List<List<UUID>> forceLoadIntentGroups(
            Map<UUID, List<DiskScanner.EntryMeta>> disk, Collection<UUID> candidates) {
        List<UUID> requested = List.copyOf(new LinkedHashSet<>(candidates));
        List<List<UUID>> groups = new ArrayList<>();
        for (Set<UUID> component : DiskScanner.selectedDependencyComponents(disk, requested)) {
            List<UUID> roots = requested.stream()
                    .filter(component::contains).toList();
            if (!roots.isEmpty()) groups.add(roots);
        }
        return List.copyOf(groups);
    }

    private DependencySelection dependencyGroups(Collection<UUID> roots, boolean mergeOverlaps) throws Exception {
        List<UUID> requested = List.copyOf(new LinkedHashSet<>(roots));
        Map<UUID, Set<UUID>> runtime = runtimeDependencyGroups(requested);
        Set<UUID> coldRoots = new LinkedHashSet<>();
        for (UUID root : requested) if (!runtime.containsKey(root)) coldRoots.add(root);
        Map<UUID, List<DiskScanner.EntryMeta>> disk = Map.of();
        if (!coldRoots.isEmpty()) disk = strictScan(new ArrayList<>()).meta();
        Set<UUID> unknown = new LinkedHashSet<>();
        for (UUID root : coldRoots) if (!disk.containsKey(root)) unknown.add(root);
        if (!unknown.isEmpty()) throw new IllegalStateException("依赖组根成员不存在: " + unknown);
        List<Set<UUID>> components = mergeOverlaps
                ? selectForceLoadGroupSets(requested, disk, runtime)
                : selectDependencyGroupSets(requested, disk, runtime);
        LinkedHashSet<UUID> members = new LinkedHashSet<>();
        components.forEach(members::addAll);
        return new DependencySelection(requested, List.copyOf(members), components);
    }

    static List<UUID> selectDependencyGroups(Collection<UUID> roots,
                                             Map<UUID, List<DiskScanner.EntryMeta>> disk,
                                             Map<UUID, Set<UUID>> runtime) {
        LinkedHashSet<UUID> all = new LinkedHashSet<>();
        selectDependencyGroupSets(roots, disk, runtime).forEach(all::addAll);
        return List.copyOf(all);
    }

    static List<Set<UUID>> selectDependencyGroupSets(Collection<UUID> roots,
                                                     Map<UUID, List<DiskScanner.EntryMeta>> disk,
                                                     Map<UUID, Set<UUID>> runtime) {
        List<Set<UUID>> groups = new ArrayList<>();
        for (UUID root : roots) {
            Set<UUID> loaded = runtime.get(root);
            List<Set<UUID>> diskComponents = loaded == null
                    ? DiskScanner.selectedDependencyComponents(disk, List.of(root)) : List.of();
            Set<UUID> selected = loaded != null ? loaded
                    : diskComponents.isEmpty() ? Set.of(root) : Set.copyOf(diskComponents.get(0));
            boolean duplicate = false;
            for (Set<UUID> existing : groups) {
                if (existing.equals(selected)) {
                    duplicate = true;
                    break;
                }
                if (!java.util.Collections.disjoint(existing, selected)) {
                    throw new IllegalStateException("准备阶段依赖组相互重叠但不一致");
                }
            }
            if (!duplicate) groups.add(Set.copyOf(selected));
        }
        return List.copyOf(groups);
    }

    static List<Set<UUID>> selectForceLoadGroupSets(Collection<UUID> roots,
                                                    Map<UUID, List<DiskScanner.EntryMeta>> disk,
                                                    Map<UUID, Set<UUID>> runtime) {
        List<Set<UUID>> selected = new ArrayList<>();
        for (UUID root : roots) {
            selected.addAll(selectDependencyGroupSets(List.of(root), disk, runtime));
        }
        return mergeOverlappingGroups(selected);
    }

    static List<Set<UUID>> selectForceLoadCandidateGroupSets(
            Collection<UUID> roots, Map<UUID, List<DiskScanner.EntryMeta>> disk,
            Map<UUID, Set<UUID>> runtime, Map<UUID, String> selectedEntries) {
        List<Set<UUID>> selected = new ArrayList<>();
        for (UUID root : roots) {
            Set<UUID> loaded = runtime.get(root);
            selected.add(directedDependencyClosure(loaded == null ? Set.of(root) : loaded,
                    disk, selectedEntries));
        }
        return mergeOverlappingGroups(selected);
    }

    static Set<UUID> directedDependencyClosure(Set<UUID> seeds,
                                               Map<UUID, List<DiskScanner.EntryMeta>> disk,
                                               Map<UUID, String> selectedEntries) {
        LinkedHashSet<UUID> members = new LinkedHashSet<>(seeds);
        List<UUID> pending = new ArrayList<>(seeds);
        for (int index = 0; index < pending.size(); index++) {
            UUID uuid = pending.get(index);
            List<DiskScanner.EntryMeta> copies = disk.getOrDefault(uuid, List.of());
            String selected = selectedEntries.get(uuid);
            DiskScanner.EntryMeta current = selectedForceLoadEntry(uuid, copies, selected);
            if (current == null) continue;
            for (UUID dependency : current.deps()) {
                // 缺失依赖保持既有断链语义：不凭空加入候选，也不在常驻流程内删除。
                if (disk.containsKey(dependency) && members.add(dependency)) pending.add(dependency);
            }
        }
        return Set.copyOf(members);
    }

    static DiskScanner.EntryMeta selectedForceLoadEntry(
            UUID uuid, List<DiskScanner.EntryMeta> copies, String selected) {
        if (copies.isEmpty()) {
            if (selected != null) {
                throw new IllegalStateException("当前活动条目不在同次磁盘扫描中: " + uuid);
            }
            return null;
        }
        if (selected != null) {
            return copies.stream().filter(copy -> copy.key().id().equals(selected)).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "当前活动条目不在同次磁盘扫描中: " + uuid));
        }
        if (copies.size() == 1) return copies.get(0);
        throw new IllegalStateException("依赖成员存在多份副本，无法选择常驻版本: " + uuid);
    }

    static Map<UUID, String> pointedForceLoadEntries(
            Map<UUID, List<DiskScanner.EntryMeta>> disk, Map<UUID, String> activeEntries,
            Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers) {
        Map<UUID, String> selected = new LinkedHashMap<>(activeEntries);
        for (Map.Entry<UUID, List<DiskScanner.EntryMeta>> item : disk.entrySet()) {
            if (selected.containsKey(item.getKey()) || item.getValue().size() < 2) continue;
            List<DiskScanner.EntryMeta> pointed = item.getValue().stream()
                    .filter(copy -> !pointers.getOrDefault(copy.key(), List.of()).isEmpty()).toList();
            if (pointed.size() == 1) selected.put(item.getKey(), pointed.get(0).key().id());
        }
        return selected;
    }

    private Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> forceLoadPointers(
            ScanSession scan) throws Exception {
        Set<DiskScanner.EntryKey> keys = new LinkedHashSet<>();
        scan.meta().values().forEach(copies -> copies.forEach(copy -> keys.add(copy.key())));
        List<String> warnings = new ArrayList<>();
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers = JobService.underLocate(
                () -> DiskScanner.locatePointersStrict(scan.dims(), keys, warnings));
        if (!warnings.isEmpty()) {
            throw new IllegalStateException("常驻候选指针扫描存在损坏: " + String.join("; ", warnings));
        }
        return pointers;
    }

    private Map<UUID, MemberPlan> prepareForceLoadPlans(
            ScanSession scan, Collection<UUID> members, Map<UUID, String> selectedEntries,
            Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers) throws Exception {
        Map<UUID, DiskScanner.EntryMeta> selected = new LinkedHashMap<>();
        Map<UUID, CompoundTag> tags = new LinkedHashMap<>();
        for (UUID uuid : members) {
            DiskScanner.EntryMeta entry = selectedForceLoadEntry(
                    uuid, scan.entriesOf(uuid), selectedEntries.get(uuid));
            if (entry == null) continue;
            CompoundTag tag = readVerifiedTag(scan.dims(), uuid, entry.key());
            DiskScanner.DiskEntry summary = DiskScanner.summarize(entry.key(), tag);
            if (summary == null || summary.plotX() != entry.plotX() || summary.plotZ() != entry.plotZ()
                    || !Set.copyOf(summary.deps()).equals(Set.copyOf(entry.deps()))) {
                throw new IllegalStateException("常驻候选条目在准备期间发生变化: " + uuid);
            }
            selected.put(uuid, entry);
            tags.put(uuid, tag);
        }
        Map<UUID, MemberPlan> plans = new LinkedHashMap<>();
        for (Map.Entry<UUID, DiskScanner.EntryMeta> item : selected.entrySet()) {
            List<DiskScanner.LiveLocation> locations = pointers.getOrDefault(item.getValue().key(), List.of());
            if (locations.size() > 1) {
                throw new IllegalStateException("常驻候选存在多个 holding 指针: " + item.getKey());
            }
            plans.put(item.getKey(), new MemberPlan(item.getValue().key(), tags.get(item.getKey()),
                    locations.isEmpty() ? null : locations.get(0)));
        }
        return Map.copyOf(plans);
    }

    static List<Set<UUID>> mergeOverlappingGroups(Collection<Set<UUID>> selected) {
        List<Set<UUID>> groups = new ArrayList<>();
        for (Set<UUID> group : selected) {
            Set<UUID> merged = new LinkedHashSet<>(group);
            for (var iterator = groups.iterator(); iterator.hasNext();) {
                Set<UUID> existing = iterator.next();
                if (java.util.Collections.disjoint(existing, merged)) continue;
                merged.addAll(existing);
                iterator.remove();
            }
            groups.add(Set.copyOf(merged));
        }
        return List.copyOf(groups);
    }

    Set<UUID> runtimeDependencyGroup(UUID root) throws Exception {
        return runtimeDependencyGroups(List.of(root)).getOrDefault(root, Set.of());
    }

    Set<UUID> loadedDependencyMembersOnMain(Collection<UUID> roots) {
        Set<UUID> members = new LinkedHashSet<>();
        Set<UUID> missing = new LinkedHashSet<>();
        for (UUID root : new LinkedHashSet<>(roots)) {
            ServerSubLevel body = resolveLoaded(root);
            if (body == null) missing.add(root);
            else members.addAll(loadingDependencyUuids(body));
        }
        if (!missing.isEmpty()) throw new IllegalStateException("运行依赖组根成员未加载: " + missing);
        return Set.copyOf(members);
    }

    void requireLoadedDependencyGroupOnMain(Collection<UUID> expectedMembers) {
        Set<UUID> expected = Set.copyOf(expectedMembers);
        Set<UUID> missing = new LinkedHashSet<>();
        ServerSubLevel anchor = null;
        for (UUID uuid : expected) {
            ServerSubLevel body = resolveLoaded(uuid);
            if (body == null) missing.add(uuid);
            else if (anchor == null) anchor = body;
        }
        Set<UUID> actual = new LinkedHashSet<>();
        if (anchor != null) for (ServerSubLevel member : SubLevelHelper.getLoadingDependencyChain(anchor)) {
            actual.add(member.getUniqueId());
        }
        requireExactRuntimeGroup(expected, actual, missing);
    }

    void requirePreparedDependencyGroupsOnMain(DependencySelection selection) {
        for (Set<UUID> component : selection.components()) {
            boolean anyLoaded = component.stream().anyMatch(uuid -> resolveLoaded(uuid) != null);
            if (!anyLoaded) continue;
            Set<UUID> actual = new LinkedHashSet<>();
            Set<UUID> missing = new LinkedHashSet<>();
            for (UUID root : selection.roots()) {
                if (!component.contains(root)) continue;
                ServerSubLevel body = resolveLoaded(root);
                if (body == null) missing.add(root);
                else actual.addAll(loadingDependencyUuids(body));
            }
            requireExactRuntimeGroup(component, actual, missing);
        }
    }

    static void requireExactRuntimeGroup(Set<UUID> expected, Set<UUID> actual, Set<UUID> missing) {
        if (!missing.isEmpty() || !actual.equals(expected)) {
            throw new IllegalStateException("当前运行依赖组在操作期间发生变化: expected="
                    + expected + ", actual=" + actual + ", missing=" + missing);
        }
    }

    Map<UUID, RuntimeSnapshot> snapshotRuntimeGroup(Set<UUID> expected) throws Exception {
        return MainThread.onUntilComplete(this.server, () -> {
            if (expected.isEmpty()) return Map.of();
            List<UUID> ordered = expected.stream().sorted().toList();
            Map<UUID, ServerSubLevel> bodies = new LinkedHashMap<>();
            for (UUID uuid : ordered) {
                ServerSubLevel body = resolveLoaded(uuid);
                if (body == null) throw new IllegalStateException("当前运行组成员已卸载: " + uuid);
                bodies.put(uuid, body);
            }
            Set<UUID> actual = new LinkedHashSet<>();
            for (ServerSubLevel member : SubLevelHelper.getLoadingDependencyChain(bodies.get(ordered.get(0)))) {
                actual.add(member.getUniqueId());
            }
            if (!actual.equals(expected)) {
                throw new IllegalStateException("当前运行依赖组在操作期间发生变化");
            }
            Map<UUID, RuntimeSnapshot> snapshots = new LinkedHashMap<>();
            for (Map.Entry<UUID, ServerSubLevel> entry : bodies.entrySet()) {
                ServerSubLevel body = entry.getValue();
                snapshots.put(entry.getKey(), new RuntimeSnapshot(
                        body.getLevel().dimension().location().toString(),
                        SubLevelSerializer.toData(body, ordered).fullTag().copy()));
            }
            return Map.copyOf(snapshots);
        });
    }

    private Map<UUID, Set<UUID>> runtimeDependencyGroups(Collection<UUID> roots) throws Exception {
        return MainThread.on(this.server, 20, () -> runtimeDependencyGroupsOnMain(roots));
    }

    private Map<UUID, Set<UUID>> runtimeDependencyGroupsOnMain(Collection<UUID> roots) {
        Map<UUID, ServerSubLevel> loaded = new LinkedHashMap<>();
        for (ServerLevel level : this.server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (ServerSubLevel body : List.copyOf(container.getAllSubLevels())) {
                ServerSubLevel previous = loaded.putIfAbsent(body.getUniqueId(), body);
                if (previous != null) throw new IllegalStateException("运行态存在重复 UUID: " + body.getUniqueId());
            }
        }
        Map<UUID, Set<UUID>> groups = new LinkedHashMap<>();
        for (UUID root : new LinkedHashSet<>(roots)) {
            ServerSubLevel body = loaded.get(root);
            if (body != null) groups.put(root, loadingDependencyUuids(body));
        }
        return Map.copyOf(groups);
    }

    DependencySelection loadedDependencyGroupsOnMain(Collection<UUID> roots, boolean mergeOverlaps) {
        List<UUID> requested = List.copyOf(new LinkedHashSet<>(roots));
        Map<UUID, Set<UUID>> runtime = runtimeDependencyGroupsOnMain(requested);
        Set<UUID> missing = new LinkedHashSet<>(requested);
        missing.removeAll(runtime.keySet());
        if (!missing.isEmpty()) throw new IllegalStateException("运行依赖组根成员未加载: " + missing);
        List<Set<UUID>> components = mergeOverlaps
                ? selectForceLoadGroupSets(requested, Map.of(), runtime)
                : selectDependencyGroupSets(requested, Map.of(), runtime);
        LinkedHashSet<UUID> members = new LinkedHashSet<>();
        components.forEach(members::addAll);
        return new DependencySelection(requested, List.copyOf(members), components);
    }

    private static Set<UUID> loadingDependencyUuids(ServerSubLevel anchor) {
        if (anchor == null) return Set.of();
        Set<UUID> members = new LinkedHashSet<>();
        for (ServerSubLevel member : SubLevelHelper.getLoadingDependencyChain(anchor)) {
            members.add(member.getUniqueId());
        }
        return Set.copyOf(members);
    }

    ScanSession freshScan(List<String> warnings) throws Exception {
        return JobService.underLocate(() -> ScanSession.fresh(this.server, warnings));
    }

    ScanSession strictScan(List<String> warnings) throws Exception {
        return JobService.underLocate(() -> ScanSession.strict(this.server, warnings));
    }

    /**
     * 规范副本优先级的唯一定义:活动条目 > 有 holding 指针 > 条目 id 字典序。
     * active 传 null = 该路径语义上不看活动项(回滚清理只关心可达性)。
     */
    static <T> Comparator<T> canonicalOrder(
            java.util.function.Function<T, DiskScanner.EntryKey> key,
            java.util.function.Function<T, List<DiskScanner.LiveLocation>> pointers, String active) {
        return Comparator.comparing((T copy) -> !key.apply(copy).id().equals(active))
                .thenComparing(copy -> pointers.apply(copy).isEmpty())
                .thenComparing(copy -> key.apply(copy).id());
    }

    /** 删除语义准备用:paused/forced + 活动条目 */
    JsonObject readOperationalMetadata(Set<UUID> targets) throws Exception {
        return readStates(targets, true, false);
    }

    /**
     * 主线程状态读取的唯一入口。两组调用方要的字段集不同:
     * 删除/副本准备要活动条目(withActive),删除后验收要加载/holding 存在性(withRuntime)。
     */
    private JsonObject readStates(Collection<UUID> targets, boolean withActive, boolean withRuntime)
            throws Exception {
        return onMain(() -> {
            JsonObject out = new JsonObject();
            Map<UUID, String> activeEntries = withActive ? activeEntriesOnMain(targets) : Map.of();
            for (UUID uuid : targets) {
                JsonObject state = new JsonObject();
                if (withRuntime) {
                    state.addProperty("loaded", resolveLoaded(uuid) != null);
                    state.addProperty("holding", isHolding(uuid));
                }
                state.addProperty("paused", PauseService.isPaused(uuid));
                state.addProperty("forced", ForceLoadService.isForcedOnMain(this.server, uuid));
                state.addProperty("frozen", FreezeService.isFrozen(uuid));
                String active = activeEntries.get(uuid);
                if (active != null) state.addProperty("active", active);
                out.add(uuid.toString(), state);
            }
            return out;
        });
    }

    Map<UUID, String> activeEntriesOnMain(Collection<UUID> targets) {
        Map<UUID, String> active = new LinkedHashMap<>();
        for (UUID uuid : targets) {
            String entry = activePointerEntryId(uuid);
            if (entry != null) active.put(uuid, entry);
        }
        return active;
    }

    /**
     * 按 key 重读条目并验 uuid 的唯一句式。快照可能陈旧(autosave 会搬迁条目、槽位会被
     * sable 复用给别的体),读回不验 uuid 是 0.6.0 定下的铁律。返回 null = 条目缺失或
     * 槽位已易主,失败语义(抛错/警告/跳过)由调用方决定。
     */
    static CompoundTag readVerified(Map<String, Path> dims, UUID uuid, DiskScanner.EntryKey key) {
        Path dir = dims.get(key.dim());
        CompoundTag tag = dir != null ? DiskScanner.readEntryTag(dir, key) : null;
        return tag != null && uuid.equals(tagUuid(tag)) ? tag : null;
    }

    static CompoundTag readVerifiedTag(Map<String, Path> dims, UUID uuid, DiskScanner.EntryKey key)
            throws IOException {
        CompoundTag tag = readVerified(dims, uuid, key);
        if (tag == null) {
            throw new IOException("条目 " + key.id() + " 在准备阶段被 sable 搬迁，未执行删除，请重试");
        }
        return tag;
    }

    /** 目标已加载但盘上没有条目(刚生成的新体):先 saveAll 落盘,返回是否落过 */
    boolean flushUnsavedTargets(List<UUID> targets, Map<UUID, List<DiskScanner.EntryMeta>> meta)
            throws Exception {
        List<UUID> unsaved = new ArrayList<>();
        for (UUID uuid : targets) {
            if (meta.getOrDefault(uuid, List.of()).isEmpty()) unsaved.add(uuid);
        }
        return !unsaved.isEmpty() && flushTargetLevels(unsaved) > 0;
    }

    void flushLoadedTargets(Collection<UUID> targets) throws Exception {
        flushTargetLevels(targets);
        DiskScanner.invalidateCache();
    }

    /** 把目标中已加载的体所在维度逐个 saveAll,返回落盘的维度数 */
    int flushTargetLevels(Collection<UUID> targets) throws Exception {
        JsonObject result = onMainUntilComplete(() -> {
            Set<ServerLevel> touched = new LinkedHashSet<>();
            for (UUID uuid : targets) {
                ServerSubLevel body = resolveLoaded(uuid);
                if (body != null) touched.add(body.getLevel());
            }
            saveAllLevels(touched);
            JsonObject out = new JsonObject();
            out.addProperty("flushed", touched.size());
            return out;
        });
        return result.get("flushed").getAsInt();
    }

    /** 主线程:对一批维度依次 saveAll;容器缺失视为致命(维度在操作中途消失了) */
    static void saveAllLevels(Collection<ServerLevel> levels) {
        for (ServerLevel level : levels) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) throw new IllegalStateException("物理体容器不存在");
            container.getHoldingChunkMap().saveAll();
        }
    }

    static UUID tagUuid(CompoundTag tag) {
        try {
            return tag.getUUID("uuid");
        } catch (Throwable error) {
            return null;
        }
    }

    /** 删除后验收用:loaded/holding/paused/forced */
    JsonObject readRuntimeStates(Set<UUID> targets) throws Exception {
        return readStates(targets, false, true);
    }

    /** 磁盘损坏跳过等非致命告警,随操作结果一并交给前端展示。 */
    static void attachWarnings(JsonObject response, List<String> warnings) {
        if (warnings.isEmpty()) return;
        JsonArray array = new JsonArray();
        for (String warning : new LinkedHashSet<>(warnings)) array.add(warning);
        response.add("warnings", array);
    }

    static String shortUuids(Set<UUID> uuids) {
        List<String> values = new ArrayList<>();
        for (UUID uuid : uuids) {
            values.add(uuid.toString().substring(0, 8));
            if (values.size() == 6) break;
        }
        if (uuids.size() > values.size()) values.add("另 " + (uuids.size() - values.size()) + " 个");
        return String.join(", ", values);
    }

    static JsonArray numberArray(double[] values) {
        JsonArray out = new JsonArray();
        for (double value : values) out.add(value);
        return out;
    }

    String activePointerEntryId(UUID uuid) {
        return findInContainers((level, container) -> {
            ServerSubLevel loaded = container.getSubLevel(uuid) instanceof ServerSubLevel body ? body : null;
            GlobalSavedSubLevelPointer pointer = loaded != null ? loaded.getLastSerializationPointer() : null;
            if (pointer == null) {
                HoldingSubLevel holding = container.getHoldingChunkMap().getHoldingSubLevel(uuid);
                if (holding != null) pointer = holding.pointer();
            }
            return pointer != null ? entryKey(level.dimension().location().toString(), pointer).id() : null;
        });
    }

    static DiskScanner.EntryKey entryKey(String dim, GlobalSavedSubLevelPointer pointer) {
        return new DiskScanner.EntryKey(dim,
                Math.floorDiv(pointer.chunkPos().x, 32), Math.floorDiv(pointer.chunkPos().z, 32),
                pointer.storageIndex(), pointer.subLevelIndex());
    }

    // ---------- 内部:加载路径 ----------

    /**
     * 作业线程:为 uuid 及其依赖闭包准备条目数据(磁盘 IO 不占主线程)。
     * <p>
     * 全局串行执行({@link JobService#underLocate}):链上每个成员在快照失配时都要全盘
     * gunzip 一遍,多个作业并行读的是同一批文件、做的是同一份解压——并行毫无收益,
     * 只会互相抢 IO 和 CPU 并把主线程饿着。生产上 4 个并行作业曾让服务端持续落后 10 秒。
     */
    Map<UUID, MemberPlan> prepareChain(UUID root) {
        return prepareChain(List.of(root));
    }

    Map<UUID, MemberPlan> prepareChain(Collection<UUID> roots) {
        try {
            return JobService.underLocate(() -> prepareChainSerial(roots));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("磁盘定位被中断");
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * 逐层 BFS:每层的成员一趟磁盘解完,而不是一个一个来。
     * <p>
     * 绳链依赖很密(一个体动辄依赖上百个),按层走通常两三趟就到底,而旧的逐个定位
     * 要对每个成员各做一遍全盘扫描 —— 64 个成员就是 64 遍同样的解压。
     */
    private Map<UUID, MemberPlan> prepareChainSerial(Collection<UUID> roots) {
        Map<UUID, MemberPlan> chain = new LinkedHashMap<>();
        Map<String, Path> dims = DiskScanner.sublevelDirs(this.server);
        // 显式点名的体一律要进链;MAX_CHAIN 只约束依赖闭包的外延
        int budget = Math.max(MAX_CHAIN, roots.size());
        Set<UUID> frontier = new LinkedHashSet<>(roots);
        JobService.phase("定位磁盘条目");
        while (!frontier.isEmpty() && chain.size() < budget) {
            frontier.removeAll(chain.keySet());
            if (frontier.isEmpty()) break;
            if (chain.size() + frontier.size() > budget) {
                Set<UUID> capped = new LinkedHashSet<>();
                for (UUID u : frontier) {
                    if (chain.size() + capped.size() >= budget) break;
                    capped.add(u);
                }
                frontier = capped;
            }
            JobService.detail(chain.size() + "/" + budget);
            Map<UUID, MemberPlan> layer = locateMembers(frontier, dims);
            if (layer.isEmpty()) break;
            chain.putAll(layer);
            Set<UUID> next = new LinkedHashSet<>();
            for (MemberPlan plan : layer.values()) {
                try {
                    for (UUID dep : DiskScanner.dependencies(plan.tag())) {
                        if (!chain.containsKey(dep)) next.add(dep);
                    }
                } catch (Throwable ignored) {
                }
            }
            frontier = next;
        }
        return chain;
    }

    /**
     * 批量定位一层成员。快路径按快照指针直读(读单条,不再解压整个存储文件),
     * 失配的走按维度批量全盘定位;引用 chunk 也按维度一趟解出。
     */
    private Map<UUID, MemberPlan> locateMembers(Set<UUID> uuids, Map<String, Path> dims) {
        Map<UUID, DiskScanner.EntryKey> keys = new LinkedHashMap<>();
        Map<UUID, CompoundTag> tags = new LinkedHashMap<>();
        Set<UUID> missing = new LinkedHashSet<>();
        for (UUID u : uuids) {
            DiskScanner.DiskEntry cached = this.index.findEntry(u);
            CompoundTag t = cached != null ? readVerified(dims, u, cached.key()) : null;
            if (t != null) {
                keys.put(u, cached.key());
                tags.put(u, t);
                continue;
            }
            missing.add(u);
        }
        for (var en : dims.entrySet()) {
            if (missing.isEmpty()) break;
            for (var hit : DiskScanner.locateEntries(en.getKey(), en.getValue(), missing).entrySet()) {
                keys.put(hit.getKey(), hit.getValue().key());
                tags.put(hit.getKey(), hit.getValue().tag());
            }
            missing.removeAll(keys.keySet());
        }
        // 引用 chunk 按维度分组批量解,一个维度只扫一趟
        Map<String, Set<UUID>> byDim = new LinkedHashMap<>();
        for (var en : keys.entrySet()) {
            byDim.computeIfAbsent(en.getValue().dim(), d -> new LinkedHashSet<>()).add(en.getKey());
        }
        Map<UUID, DiskScanner.LiveLocation> cold = new LinkedHashMap<>();
        for (var en : byDim.entrySet()) {
            Path dir = dims.get(en.getKey());
            if (dir == null) continue;
            cold.putAll(DiskScanner.locateLiveAll(en.getKey(), dir, en.getValue()));
        }
        Map<UUID, MemberPlan> out = new LinkedHashMap<>();
        for (var en : keys.entrySet()) {
            UUID u = en.getKey();
            DiskScanner.LiveLocation live = cold.get(u);
            // 指向别的条目说明盘上已经搬过位置,这份 cold 不能用于 snatch
            if (live != null && !live.key().equals(en.getValue())) live = null;
            out.put(u, new MemberPlan(en.getValue(), tags.get(u), live));
        }
        return out;
    }

    /** 主线程:确保 uuid 已加载(否则依链加载),返回加载后的体;失败抛异常 */
    ServerSubLevel ensureLoaded(UUID uuid, Map<UUID, MemberPlan> chain) {
        ServerSubLevel sl = resolveLoaded(uuid);
        if (sl != null) return sl;
        for (UUID candidate : loadOrder(uuid, chain.keySet())) {
            if (resolveLoaded(candidate) != null) continue;
            try {
                loadOne(candidate, chain.get(candidate));
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: chain load {} failed", candidate, t);
            }
            sl = resolveLoaded(uuid);
            if (sl != null) return sl;
        }
        if (sl == null) throw new IllegalStateException("无法加载该物理体(条目缺失或 sable 拒绝加载,详见服务器日志)");
        return sl;
    }

    static List<UUID> loadOrder(UUID root, Collection<UUID> candidates) {
        LinkedHashSet<UUID> ordered = new LinkedHashSet<>();
        if (candidates.contains(root)) ordered.add(root);
        ordered.addAll(candidates);
        return List.copyOf(ordered);
    }

    /** 主线程:单体加载。选定活指针 → 选定条目收养 → 同内容旧 holding 兜底。 */
    void loadOne(UUID uuid, MemberPlan plan) {
        Set<GlobalSavedSubLevelPointer> attemptedPointers = new LinkedHashSet<>();
        ServerLevel selectedLevel = levelOf(plan.key().dim());
        ServerSubLevelContainer selected = selectedLevel == null ? null : SubLevelContainer.getContainer(selectedLevel);
        if (selected == null) return;
        boolean occupied = plotOccupiedByOther(selected, plan.tag(), uuid);
        // 面板已经按当前副本证据选过条目，先用与该条目匹配的活指针。
        BooleanSupplier selectedCold = plan.cold() == null ? null : () -> {
            try {
                GlobalSavedSubLevelPointer pointer = new GlobalSavedSubLevelPointer(
                        new ChunkPos(plan.cold().chunkX(), plan.cold().chunkZ()),
                        (short) plan.cold().key().storage(), (short) plan.cold().key().index());
                HoldingSubLevel holding = selected.getHoldingChunkMap().getHoldingSubLevel(uuid);
                if (!samePointer(pointer, holding == null ? null : holding.pointer())) return false;
                return loadNow(() -> {
                    if (attemptedPointers.add(pointer)) {
                        selected.getHoldingChunkMap().snatchAndLoad(pointer, uuid);
                    }
                }, selected.getHoldingChunkMap()::processChanges, () -> resolveLoaded(uuid) != null);
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: selected snatch {} failed", uuid, t);
                return false;
            }
        };
        boolean loaded = loadSelectedFirst(occupied, new LoadAttempts(selectedCold,
                () -> {
                    loadPreparedMember(uuid, plan);
                    return resolveLoaded(uuid) != null;
                }, () -> loadMatchingHolding(uuid, plan, attemptedPointers)));
        if (!loaded && occupied) {
            SablePanel.LOGGER.warn("sablepanel: selected copy {} uses an occupied plot; resolve copies first", uuid);
        }
    }

    private boolean loadMatchingHolding(UUID uuid, MemberPlan plan,
                                        Set<GlobalSavedSubLevelPointer> attemptedPointers) {
        for (ServerLevel level : this.server.getAllLevels()) {
            try {
                ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
                if (c == null) continue;
                var holding = c.getHoldingChunkMap().getHoldingSubLevel(uuid);
                if (holding != null && holding.pointer() != null
                        && matchingHolding(plan.tag(), holding.data().fullTag(),
                        plotOccupiedByOther(c, holding.data().fullTag(), uuid))
                        && attemptedPointers.add(holding.pointer())) {
                    c.getHoldingChunkMap().snatchAndLoad(holding.pointer(), uuid);
                    if (resolveLoaded(uuid) != null) return true;
                }
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: holding snatch {} failed", uuid, t);
            }
        }
        return false;
    }

    static boolean loadNow(Runnable enqueue, Runnable drain, BooleanSupplier loaded) {
        enqueue.run();
        if (!loaded.getAsBoolean()) drain.run();
        return loaded.getAsBoolean();
    }

    static boolean loadSelectedFirst(boolean occupied, LoadAttempts attempts) {
        if (occupied) return false;
        if (attempts.selectedCold() != null && attempts.selectedCold().getAsBoolean()) return true;
        if (attempts.selectedPrepared().getAsBoolean()) return true;
        return attempts.matchingHolding().getAsBoolean();
    }

    static boolean matchingHolding(CompoundTag selected, CompoundTag holding, boolean occupied) {
        return !occupied && selected.equals(holding);
    }

    static boolean samePointer(GlobalSavedSubLevelPointer selected, GlobalSavedSubLevelPointer holding) {
        return selected != null && selected.equals(holding);
    }

    static boolean plotOccupiedByOther(ServerSubLevelContainer container, CompoundTag tag, UUID uuid) {
        CompoundTag plot = tag.getCompound("plot");
        var occupant = container.getSubLevel(plot.getInt("plot_x"), plot.getInt("plot_z"));
        return occupant != null && !uuid.equals(occupant.getUniqueId());
    }

    /**
     * 从已准备且再次校验过的条目直接走 Sable fullyLoad。删除组件使用这条路径可避开
     * 已损坏 holding 指针上的 snatch；普通收养则把它作为原生 snatch 失败后的兜底。
     */
    void loadPreparedMember(UUID uuid, MemberPlan plan) {
        try {
            ServerLevel level = levelOf(plan.key().dim());
            if (level == null) return;
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) return;
            CompoundTag fresh = readVerified(DiskScanner.sublevelDirs(this.server), uuid, plan.key());
            if (!preparedTagMatches(plan.tag(), fresh)) {
                SablePanel.LOGGER.warn("sablepanel: adopt {} aborted, entry changed since prepare", uuid);
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

    static boolean preparedTagMatches(CompoundTag expected, CompoundTag actual) {
        return actual != null && expected.equals(actual);
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    ServerLevel levelOf(String dim) {
        for (ServerLevel level : this.server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dim)) return level;
        }
        return null;
    }

    /**
     * 全维度容器探测的唯一循环:逐容器执行 probe,拿到第一个非 null 就返回。
     * 单个维度出错静默跳过 —— 需要逐维度记日志的循环(loadOne 的 snatch)不适用。
     */
    private <T> T findInContainers(java.util.function.BiFunction<ServerLevel, ServerSubLevelContainer, T> probe) {
        for (ServerLevel level : this.server.getAllLevels()) {
            try {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container == null) continue;
                T hit = probe.apply(level, container);
                if (hit != null) return hit;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** "已加载且未移除"的唯一判定句(ForceLoadService 摘票/守护同用) */
    static ServerSubLevel loadedBody(ServerSubLevelContainer container, UUID uuid) {
        return container.getSubLevel(uuid) instanceof ServerSubLevel body && !body.isRemoved() ? body : null;
    }

    ServerSubLevel resolveLoaded(UUID uuid) {
        return findInContainers((level, container) -> loadedBody(container, uuid));
    }

    boolean isHolding(UUID uuid) {
        return findInContainers((level, container) ->
                container.getHoldingChunkMap().getHoldingSubLevel(uuid) != null ? Boolean.TRUE : null) != null;
    }

    // ---------- 审计 ----------

    /** 面板手动触发磁盘重扫(异步) */
    public void rescanNow() {
        this.rescan.run();
    }

    void audit(String op, UUID uuid, String name, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("ev", "panel_op");
        o.addProperty("op", op);
        // 整维度操作没有 uuid;审计条目照发,name 里放维度 id
        if (uuid != null) o.addProperty("uuid", uuid.toString());
        if (name != null) o.addProperty("name", name);
        if (detail != null) o.addProperty("detail", detail);
        EventLog.write(o);
        SablePanel.LOGGER.info("sablepanel: panel op {} {} ({})", op, uuid, name);
    }

    <T> T onMain(Callable<T> task) throws Exception {
        return MainThread.on(this.server, 20, task);
    }

    <T> T onMainUntilComplete(Callable<T> task) throws Exception {
        return MainThread.onUntilComplete(this.server, task);
    }
}
