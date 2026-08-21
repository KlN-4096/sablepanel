package com.klnon.sablepanel.panel.ops;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import com.klnon.sablepanel.panel.recycle.RecycleStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.audit.EventLog;
import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.mixin.HoldingChunkAccessor;
import com.klnon.sablepanel.mixin.HoldingChunkMapAccessor;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.klnon.sablepanel.panel.storage.ScanSession;

/**
 * 回收站依赖组的恢复/彻底删除,以及删除回滚与副本切换共用的
 * "清场 → 重建 → 验收 → 状态复原"恢复事务。
 */
public final class RestoreOps {
    private final OpKit kit;
    private final DeleteTx tx;
    private final RecycleStore recycle;

    RestoreOps(OpKit kit, DeleteTx tx, RecycleStore recycle) {
        this.kit = kit;
        this.tx = tx;
        this.recycle = recycle;
    }

    /** 删除失败回滚前先清掉所有残留，随后才能从快照完整重建同 UUID 依赖组。 */
    private void purgeRestoreTargets(Set<UUID> targets, List<String> warnings) throws Exception {
        DeleteTx.DeleteComponent component = this.tx.prepareExactDeleteComponent(targets, warnings);
        if (!component.targets.isEmpty()) {
            Map<UUID, DeleteTx.DeleteStatus> statuses = new LinkedHashMap<>();
            for (UUID uuid : component.targets) statuses.put(uuid, new DeleteTx.DeleteStatus(uuid));
            this.tx.executeDeleteComponents(List.of(component), statuses);
            this.tx.verifyDeletedTargets(statuses, warnings);
            List<String> errors = new ArrayList<>();
            for (DeleteTx.DeleteStatus status : statuses.values()) {
                if (!status.ok) errors.add(status.uuid + ": " + String.join("; ", status.errors));
            }
            if (!errors.isEmpty()) throw new IllegalStateException("回滚前残留清理失败: " + String.join(" | ", errors));
        }
        try {
            this.kit.onMainUntilComplete(() -> {
                this.tx.clearOperationalStateOnMain(targets);
                return new JsonObject();
            });
        } finally {
            PauseService.persist();
            ForceLoadService.persist();
        }
        this.tx.requireTargetsAbsent(targets, warnings);
    }

    public JsonObject restoreRecycleGroups(List<String> groupIds) {
        synchronized (this.kit.lock) { return restoreRecycleGroupsExclusive(groupIds); }
    }

    private JsonObject restoreRecycleGroupsExclusive(List<String> groupIds) {
        JsonArray results = new JsonArray();
        List<String> warnings = new ArrayList<>();
        int restored = 0;
        Set<String> ids = new LinkedHashSet<>(groupIds);
        int index = 0;
        for (String groupId : ids) {
            // 恢复的体已经从列表里消失,行徽章挂不上,进度只能靠顶栏指示器 —— 阶段文本是它唯一的内容
            JobService.phase("恢复依赖组");
            JobService.detail(++index + "/" + ids.size());
            JsonObject result = new JsonObject();
            result.addProperty("id", groupId);
            try {
                RecycleStore.RestoreGroup group = this.recycle.loadGroup(groupId);
                if ("incomplete".equals(group.state())) {
                    throw new IllegalStateException("依赖不完整的隔离副本不能直接恢复");
                }
                restoreGroupData(group, !group.oldVersion() && "recovery_required".equals(group.state()), warnings);
                try {
                    this.recycle.markRestored(groupId);
                } catch (Exception metadataError) {
                    result.addProperty("warn", "物理体已恢复，但回收站状态更新失败: " + messageOf(metadataError));
                }
                for (RecycleStore.RestoreBody body : group.bodies()) {
                    this.kit.audit("restore", body.uuid(), null, groupId);
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
        this.kit.rescan.run();
        JsonObject out = new JsonObject();
        out.addProperty("ok", restored);
        out.addProperty("total", results.size());
        out.add("results", results);
        OpKit.attachWarnings(out, warnings);
        return out;
    }

    public JsonObject purgeRecycleGroups(List<String> groupIds) {
        synchronized (this.kit.lock) { return purgeRecycleGroupsExclusive(groupIds); }
    }

    private JsonObject purgeRecycleGroupsExclusive(List<String> groupIds) {
        JobService.phase("彻底删除回收组");
        JobService.detail(groupIds.size() + " 个依赖组");
        JsonObject out = this.recycle.purgeGroups(groupIds);
        JsonArray warnings = new JsonArray();
        for (var element : out.getAsJsonArray("results")) {
            JsonObject result = element.getAsJsonObject();
            if (!result.get("ok").getAsBoolean()) {
                warnings.add(result.get("id").getAsString() + ": " + result.get("error").getAsString());
                continue;
            }
            JsonObject event = new JsonObject();
            event.addProperty("ev", "panel_op");
            event.addProperty("op", "recycle_purge");
            event.addProperty("group", result.get("id").getAsString());
            event.addProperty("members", result.get("members").getAsInt());
            event.addProperty("files", result.get("files").getAsInt());
            event.addProperty("bytes", result.get("bytes").getAsLong());
            EventLog.write(event);
        }
        if (!warnings.isEmpty()) out.add("warnings", warnings);
        return out;
    }

    void restoreGroupData(RecycleStore.RestoreGroup group, boolean replaceExisting,
                          List<String> warnings) throws Exception {
        Set<UUID> targets = new LinkedHashSet<>();
        for (RecycleStore.RestoreBody body : group.bodies()) targets.add(body.uuid());
        ScanSession scan = this.kit.strictScan(warnings);
        requireExternalDependenciesPresent(group, scan.meta());
        if (replaceExisting) {
            purgeRestoreTargets(targets, warnings);
            scan = this.kit.strictScan(warnings);
            requireExternalDependenciesPresent(group, scan.meta());
        }
        Map<UUID, Integer> existingEntries = new HashMap<>();
        for (UUID uuid : targets) existingEntries.put(uuid, scan.entriesOf(uuid).size());
        if (!replaceExisting) requireRestoreTargetsFree(targets, existingEntries);
        try {
            this.kit.onMainUntilComplete(() -> {
                this.tx.clearOperationalStateOnMain(targets);
                return new JsonObject();
            });
        } finally {
            PauseService.persist();
            ForceLoadService.persist();
        }
        // 同一趟扫描顺路建 plot 槽位占用表:删除释放的槽位会被 sable 按首位适配复用给新体,
        // 而恢复用的 allocateSubLevel 只查加载态 —— 不拦下来就会造出"同槽双体"(加载互斥)
        Map<DiskScanner.PlotKey, Set<UUID>> plotOwners = DiskScanner.plotOwners(scan.meta());
        List<ServerSubLevel> created = new ArrayList<>();
        Set<ServerLevel> touched = new LinkedHashSet<>();
        try {
            this.kit.onMainUntilComplete(() -> restoreGroupOnMain(group, existingEntries, plotOwners, created, touched));
            verifyRestoredGroup(group, warnings);
            restoreOperationalState(group);
        } catch (Exception verificationError) {
            try {
                purgeRestoreTargets(targets, warnings);
                this.tx.requireTargetsAbsent(targets, warnings);
            } catch (Exception cleanupError) {
                verificationError.addSuppressed(cleanupError);
            }
            throw verificationError;
        }
    }

    static void requireExternalDependenciesPresent(
            RecycleStore.RestoreGroup group, Map<UUID, List<DiskScanner.EntryMeta>> entries) {
        Set<UUID> members = new LinkedHashSet<>();
        for (RecycleStore.RestoreBody body : group.bodies()) members.add(body.uuid());
        Map<UUID, Integer> unavailable = new LinkedHashMap<>();
        for (RecycleStore.RestoreBody body : group.bodies()) {
            for (UUID dependency : DiskScanner.dependencies(body.tag())) {
                if (members.contains(dependency)) continue;
                int copies = entries.getOrDefault(dependency, List.of()).size();
                if (copies != 1) unavailable.put(dependency, copies);
            }
        }
        if (!unavailable.isEmpty()) {
            throw new IllegalStateException("回收组的外部依赖缺失或存在副本: " + unavailable);
        }
    }

    private void requireRestoreTargetsFree(Set<UUID> targets, Map<UUID, Integer> existingEntries) throws Exception {
        JsonObject runtime = this.kit.readRuntimeStates(targets);
        for (UUID uuid : targets) {
            if (existingEntries.getOrDefault(uuid, 0) > 0) {
                throw new IllegalStateException("UUID 已存在，未恢复该依赖组: " + uuid);
            }
            JsonObject state = runtime.getAsJsonObject(uuid.toString());
            if (state.get("loaded").getAsBoolean() || state.get("holding").getAsBoolean()) {
                throw new IllegalStateException("UUID 已存在，未恢复该依赖组: " + uuid);
            }
        }
    }

    void restoreOperationalState(RecycleStore.RestoreGroup group) throws Exception {
        List<UUID> groupUuids = restoreOrder(restoreDependencies(group));
        boolean restoreForced = group.bodies().stream().anyMatch(RecycleStore.RestoreBody::forced);
        boolean restorePaused = group.bodies().stream().anyMatch(RecycleStore.RestoreBody::paused);
        boolean restoreFrozen = group.bodies().stream().anyMatch(RecycleStore.RestoreBody::frozen);
        List<UUID> forced = restoreForced ? groupUuids : List.of();
        List<UUID> paused = restorePaused ? groupUuids : List.of();
        Map<UUID, OpKit.MemberPlan> plans = forced.isEmpty() ? Map.of() : this.kit.prepareChain(forced);
        try {
            this.kit.onMainUntilComplete(() -> {
                for (UUID uuid : forced) {
                    ForceLoadService.addOnMain(this.kit.ensureLoaded(uuid, plans));
                }
                if (!paused.isEmpty()) {
                    PauseService.applyOnMain(this.kit.server, paused, true);
                    for (UUID uuid : paused) {
                        var body = this.kit.resolveLoaded(uuid);
                        if (body != null) SubLevelPhysicsSystem.get(body.getLevel())
                                .getPipeline().resetVelocity(body);
                    }
                }
                FreezeService.applyOnMain(groupUuids, restoreFrozen);
                for (RecycleStore.RestoreBody body : group.bodies()) {
                    boolean pausedState = PauseService.isPaused(body.uuid());
                    boolean forcedState = ForceLoadService.isForcedOnMain(this.kit.server, body.uuid());
                    boolean frozenState = FreezeService.isFrozen(body.uuid());
                    if (pausedState != restorePaused || forcedState != restoreForced
                            || frozenState != restoreFrozen) {
                        throw new IllegalStateException("恢复后暂停/冻结/常驻状态不一致: " + body.uuid());
                    }
                }
                return new JsonObject();
            });
        } finally {
            if (!paused.isEmpty()) PauseService.persist();
            ForceLoadService.persist();
        }
    }

    private JsonObject restoreGroupOnMain(RecycleStore.RestoreGroup group,
                                          Map<UUID, Integer> existingEntries,
                                          Map<DiskScanner.PlotKey, Set<UUID>> plotOwners,
                                          List<ServerSubLevel> created,
                                          Set<ServerLevel> touched) throws Exception {
        try {
            Map<UUID, RecycleStore.RestoreBody> bodies = new LinkedHashMap<>();
            for (RecycleStore.RestoreBody body : group.bodies()) bodies.put(body.uuid(), body);
            for (UUID uuid : restoreOrder(restoreDependencies(group))) {
                RecycleStore.RestoreBody body = bodies.get(uuid);
                boolean exists = existingEntries.getOrDefault(body.uuid(), 0) > 0
                        || this.kit.resolveLoaded(body.uuid()) != null || this.kit.isHolding(body.uuid());
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
            OpKit.saveAllLevels(touched);
            replaceHoldingSnapshots(group);
        } catch (Throwable error) {
            cleanupFailedRestore(created, touched);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("恢复事务失败", error);
        }
        JsonObject out = new JsonObject();
        out.addProperty("restored", created.size());
        return out;
    }

    private static Map<UUID, Collection<UUID>> restoreDependencies(RecycleStore.RestoreGroup group) {
        Set<UUID> members = new LinkedHashSet<>();
        for (RecycleStore.RestoreBody body : group.bodies()) members.add(body.uuid());
        Map<UUID, Collection<UUID>> dependencies = new LinkedHashMap<>();
        for (RecycleStore.RestoreBody body : group.bodies()) {
            dependencies.put(body.uuid(), DiskScanner.dependencies(body.tag()).stream()
                    .filter(members::contains).toList());
        }
        return dependencies;
    }

    private void replaceHoldingSnapshots(RecycleStore.RestoreGroup group) throws Exception {
        Set<SubLevelStorage> touched = new LinkedHashSet<>();
        try {
            for (RecycleStore.RestoreBody body : group.bodies()) {
                ServerLevel level = restoreLevel(body.dimension());
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container == null) throw new IllegalStateException("恢复目标维度没有物理体容器");
                var holdingMap = container.getHoldingChunkMap();
                HoldingSubLevel previous = holdingMap.getHoldingSubLevel(body.uuid());
                ServerSubLevel loaded = this.kit.resolveLoaded(body.uuid());
                var pointer = previous != null ? previous.pointer()
                        : loaded != null ? loaded.getLastSerializationPointer() : null;
                if (pointer == null) throw new IllegalStateException("恢复后存储指针缺失: " + body.uuid());
                SubLevelData exactData = SubLevelSerializer.fromData(body.tag().copy());
                if (exactData == null || !body.uuid().equals(exactData.uuid())) {
                    throw new IllegalStateException("回收站 NBT 无法解析: " + body.uuid());
                }
                HoldingSubLevel exact = new HoldingSubLevel(exactData, pointer);
                HoldingChunkMapAccessor map = (HoldingChunkMapAccessor) (Object) holdingMap;
                var chunk = map.sablepanel$loadedHoldingChunks().get(pointer.chunkPos().toLong());
                if (chunk != null) {
                    if (loaded != null) container.removeSubLevel(loaded, SubLevelRemovalReason.UNLOADED);
                    for (var loadedChunk : map.sablepanel$loadedHoldingChunks().values()) {
                        var records = ((HoldingChunkAccessor) (Object) loadedChunk)
                                .sablepanel$loadedHoldingSubLevels();
                        records.remove(body.uuid());
                    }
                    ((HoldingChunkAccessor) (Object) chunk).sablepanel$loadedHoldingSubLevels()
                            .put(body.uuid(), exact);
                    map.sablepanel$allHoldingSubLevels().put(body.uuid(), exact);
                } else if (loaded == null) {
                    throw new IllegalStateException("恢复后运行体与 holding 区块均缺失: " + body.uuid());
                }
                SubLevelStorage storage = holdingMap.getStorage();
                storage.attemptSaveSubLevel(pointer, exactData);
                touched.add(storage);
            }
            for (SubLevelStorage storage : touched) storage.flush();
        } finally {
            DiskScanner.invalidateCache();
        }
    }

    /**
     * Sable 会在保存时按已经加载的运行链重建依赖。先创建依赖者，再创建它依赖的体，
     * 才能保留非对称依赖图；环内顺序只需稳定，不影响成员集合。
     */
    static List<UUID> restoreOrder(Map<UUID, ? extends Collection<UUID>> dependencies) {
        LinkedHashSet<UUID> dependencyFirst = new LinkedHashSet<>();
        Set<UUID> visiting = new LinkedHashSet<>();
        Set<UUID> visited = new LinkedHashSet<>();
        for (UUID uuid : dependencies.keySet()) {
            visitDependencies(uuid, dependencies, visiting, visited, dependencyFirst);
        }
        List<UUID> order = new ArrayList<>(dependencyFirst);
        Collections.reverse(order);
        return List.copyOf(order);
    }

    private static void visitDependencies(UUID uuid,
                                          Map<UUID, ? extends Collection<UUID>> dependencies,
                                          Set<UUID> visiting, Set<UUID> visited,
        LinkedHashSet<UUID> dependencyFirst) {
        if (visited.contains(uuid) || !visiting.add(uuid)) return;
        Collection<UUID> direct = dependencies.get(uuid);
        if (direct == null) direct = List.of();
        for (UUID dependency : direct) {
            if (dependencies.containsKey(dependency)) {
                visitDependencies(dependency, dependencies, visiting, visited, dependencyFirst);
            }
        }
        visiting.remove(uuid);
        visited.add(uuid);
        dependencyFirst.add(uuid);
    }

    private void cleanupFailedRestore(List<ServerSubLevel> created, Set<ServerLevel> touched) {
        for (ServerSubLevel body : created) {
            try {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(body.getLevel());
                if (container != null && this.kit.resolveLoaded(body.getUniqueId()) == body) {
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
        ScanSession scan = this.kit.freshScan(warnings);
        Set<DiskScanner.EntryKey> keys = new LinkedHashSet<>();
        for (UUID uuid : targets) {
            for (DiskScanner.EntryMeta copy : scan.entriesOf(uuid)) keys.add(copy.key());
        }
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers = JobService.underLocate(
                () -> DiskScanner.locatePointersStrict(scan.dims(), keys, warnings));
        JsonObject runtime = this.kit.readRuntimeStates(targets);
        for (UUID uuid : targets) {
            List<DiskScanner.EntryMeta> copies = scan.entriesOf(uuid);
            if (copies.isEmpty()) throw new IllegalStateException("恢复后磁盘条目缺失: " + uuid);
            if (copies.size() != 1) throw new IllegalStateException("恢复后存在 " + copies.size() + " 个磁盘条目: " + uuid);
            RecycleStore.RestoreBody body = expected.get(uuid);
            DiskScanner.EntryMeta restored = copies.get(0);
            if (!restored.key().dim().equals(body.dimension())) {
                throw new IllegalStateException("恢复后维度不一致: " + uuid);
            }
            Set<UUID> expectedDependencies = new LinkedHashSet<>(DiskScanner.dependencies(body.tag()));
            Set<UUID> actualDependencies = new LinkedHashSet<>(restored.deps());
            if (!actualDependencies.equals(expectedDependencies)) {
                throw new IllegalStateException("恢复后依赖关系不一致: " + uuid
                        + ", 期望 " + expectedDependencies + ", 实际 " + actualDependencies);
            }
            int pointerCount = 0;
            for (DiskScanner.EntryMeta copy : copies) {
                pointerCount += pointers.getOrDefault(copy.key(), List.of()).size();
            }
            if (pointerCount != 1) {
                throw new IllegalStateException("恢复后存在 " + pointerCount + " 个 holding 指针: " + uuid);
            }
            JsonObject state = runtime.getAsJsonObject(uuid.toString());
            boolean loaded = state != null && state.get("loaded").getAsBoolean();
            boolean holding = state != null && state.get("holding").getAsBoolean();
            // 刚恢复的体落在无人区可能几秒内就转入 holding —— 那是正常归宿,不算失败
            if (!loaded && !holding) {
                throw new IllegalStateException("恢复后物理体未加载: " + uuid);
            }
        }
    }

    private ServerLevel restoreLevel(String dimension) {
        String target = dimension == null || dimension.isBlank() ? RecycleStore.DEFAULT_DIMENSION : dimension;
        ServerLevel level = this.kit.levelOf(target);
        if (level == null && RecycleStore.DEFAULT_DIMENSION.equals(target)) level = this.kit.server.overworld();
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
}
