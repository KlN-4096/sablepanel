package com.klnon.sablepanel.panel.service;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;
import com.klnon.sablepanel.panel.data.CopyVersionScanner;
import com.klnon.sablepanel.panel.data.DiskScanner;
import com.klnon.sablepanel.panel.data.RecycleStore;
import com.klnon.sablepanel.panel.data.MeshExtractor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
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

/**
 * 同 UUID 多磁盘条目的审查/去重/版本切换/隔离。列表快照只负责提示,
 * 真正操作前始终严格重扫并深比较完整 NBT;切换失败回滚到已知的当前版本。
 */
public final class CopyOps {
    private final OpKit kit;
    private final DeleteTx tx;
    private final RestoreOps restore;
    private final RecycleStore recycle;

    CopyOps(OpKit kit, DeleteTx tx, RestoreOps restore, RecycleStore recycle) {
        this.kit = kit;
        this.tx = tx;
        this.restore = restore;
        this.recycle = recycle;
    }

    private record CopyCandidate(DiskScanner.EntryKey key, CompoundTag tag, int blocks,
                                 List<DiskScanner.LiveLocation> pointers) {
    }

    private record CopyInspection(Map<String, Path> dimensions, List<CopyCandidate> copies,
                                  CopyCandidate keep, boolean identical) {
    }

    record CopyResolutionPlan(CopyVersionScanner.Version selected,
                              CopyVersionScanner.Version rollback) {
    }

    private record PreparedCopyResolution(DeleteTx.DeleteComponent component, CopyVersionScanner.Scan scan,
                                          Map<UUID, RecycleStore.OperationalState> states) {
    }

    /** 实时副本审查:列表快照只负责提示,真正操作前始终严格重扫并深比较完整 NBT。 */
    public JsonObject inspectCopies(UUID uuid) throws Exception {
        List<String> warnings = new ArrayList<>();
        JsonObject out = copyVersionsJson(inspectVersionState(uuid, warnings));
        OpKit.attachWarnings(out, warnings);
        return out;
    }

    public JsonObject copyVersionMesh(UUID uuid, String versionId) throws Exception {
        List<String> warnings = new ArrayList<>();
        CopyVersionScanner.Version version = requireVersion(inspectVersionState(uuid, warnings), versionId, false);
        CopyVersionScanner.Copy preview = version.copies().stream()
                .filter(copy -> copy.uuid().equals(uuid)).findFirst()
                .orElseGet(() -> version.copies().stream().max(
                        Comparator.comparingInt(CopyVersionScanner.Copy::blocks)).orElseThrow());
        return MeshExtractor.extract(preview.tag());
    }

    private CopyVersionScanner.Scan inspectVersionState(UUID uuid, List<String> warnings) throws Exception {
        ScanSession scan = ScanSession.strict(this.kit.server, warnings);
        Set<UUID> members = CopyVersionScanner.members(scan.meta(), uuid);
        JsonObject runtime = this.kit.readOperationalMetadata(members);
        return CopyVersionScanner.scan(scan.dims(), scan.meta(), uuid, activeEntries(runtime, members), warnings);
    }

    private static CopyVersionScanner.Version requireVersion(CopyVersionScanner.Scan scan, String versionId,
                                                              boolean complete) {
        CopyVersionScanner.Version version = scan.versions().stream()
                .filter(candidate -> candidate.id().equals(versionId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("副本版本已经变化，请重新扫描"));
        if (complete && !version.complete()) throw new IllegalStateException("不能选择依赖不完整的版本");
        return version;
    }

    static CopyResolutionPlan requireCopyResolution(CopyVersionScanner.Scan scan, String versionId) {
        if (scan.currentState() != CopyVersionScanner.CurrentState.KNOWN || scan.currentVersion() == null) {
            String reason = scan.currentState() == CopyVersionScanner.CurrentState.MIXED
                    ? "运行态证据横跨多个副本版本" : "没有足够运行态证据判定当前版本";
            throw new IllegalStateException(reason + "，未执行副本处理");
        }
        CopyVersionScanner.Version selected = requireVersion(scan, versionId, true);
        CopyVersionScanner.Version rollback = requireVersion(scan, scan.currentVersion(), true);
        return new CopyResolutionPlan(selected, rollback);
    }

    private static Map<UUID, String> activeEntries(JsonObject runtime, Collection<UUID> members) {
        Map<UUID, String> active = new LinkedHashMap<>();
        for (UUID member : members) {
            JsonObject state = runtime.getAsJsonObject(member.toString());
            if (state != null && state.has("active")) active.put(member, state.get("active").getAsString());
        }
        return active;
    }

    private JsonObject copyVersionsJson(CopyVersionScanner.Scan scan) {
        JsonObject out = new JsonObject();
        out.addProperty("uuid", scan.target().toString());
        if (scan.currentVersion() != null) out.addProperty("current_version", scan.currentVersion());
        out.addProperty("current_state", scan.currentState().name().toLowerCase(java.util.Locale.ROOT));
        out.addProperty("active_members", scan.activeMembers());
        out.addProperty("members", scan.members().size());
        JsonArray versions = new JsonArray();
        for (CopyVersionScanner.Version version : scan.versions()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", version.id());
            item.addProperty("complete", version.complete());
            item.addProperty("active", version.active());
            item.addProperty("active_members", version.activeMembers());
            item.addProperty("current", version.id().equals(scan.currentVersion()));
            item.addProperty("members", version.copies().size());
            item.addProperty("blocks", version.blocks());
            item.addProperty("redundant", version.redundant().size());
            JsonArray locations = new JsonArray();
            for (CopyVersionScanner.Location location : version.locations()) {
                JsonObject value = new JsonObject();
                value.addProperty("dim", location.dimension());
                value.addProperty("x", location.chunkX());
                value.addProperty("z", location.chunkZ());
                locations.add(value);
            }
            item.add("locations", locations);
            JsonArray copies = new JsonArray();
            for (CopyVersionScanner.Copy copy : version.copies()) copies.add(copyVersionItem(copy, false));
            for (CopyVersionScanner.Copy copy : version.redundant()) copies.add(copyVersionItem(copy, true));
            item.add("copies", copies);
            JsonArray missing = new JsonArray();
            for (UUID dependency : version.missingDependencies()) missing.add(dependency.toString());
            item.add("missing_dependencies", missing);
            versions.add(item);
        }
        out.add("versions", versions);
        JsonArray incomplete = new JsonArray();
        for (CopyVersionScanner.Copy copy : scan.incomplete()) incomplete.add(copyVersionItem(copy, false));
        out.add("incomplete", incomplete);
        return out;
    }

    private JsonObject copyVersionItem(CopyVersionScanner.Copy copy, boolean redundant) {
        JsonObject item = new JsonObject();
        item.addProperty("uuid", copy.uuid().toString());
        item.addProperty("entry", copy.key().id());
        item.addProperty("dim", copy.key().dim());
        item.addProperty("blocks", copy.blocks());
        item.addProperty("reachable", !copy.pointers().isEmpty());
        item.addProperty("pointer_count", copy.pointers().size());
        item.addProperty("redundant", redundant);
        DiskScanner.DiskEntry summary = DiskScanner.summarize(copy.key(), copy.tag());
        if (summary != null) {
            if (summary.name() != null && !summary.name().isBlank()) item.addProperty("name", summary.name());
            item.add("pos", OpKit.numberArray(summary.pos()));
            item.add("size", OpKit.numberArray(summary.size()));
        }
        return item;
    }

    private PreparedCopyResolution prepareCopyResolution(UUID uuid, List<String> warnings) throws Exception {
        ScanSession scan = ScanSession.strict(this.kit.server, warnings);
        Set<UUID> members = CopyVersionScanner.members(scan.meta(), uuid);
        this.kit.flushLoadedTargets(members);
        scan = ScanSession.strict(this.kit.server, warnings);
        members = CopyVersionScanner.members(scan.meta(), uuid);
        DeleteTx.DeleteComponent component = this.tx.prepareExactDeleteComponent(members, warnings);
        if (!component.targets.equals(members)) {
            throw new IllegalStateException("副本依赖组缺少可读取的磁盘条目，未执行副本处理");
        }

        JsonObject runtime = this.kit.readOperationalMetadata(members);
        Map<UUID, String> active = activeEntries(runtime, members);
        Map<UUID, RecycleStore.OperationalState> states = operationalStates(runtime, members);
        component.activeSnapshot = Map.copyOf(active);
        component.states.putAll(states);

        List<CopyVersionScanner.Copy> copies = new ArrayList<>();
        for (UUID member : members) {
            List<DeleteTx.DeleteCopy> prepared = component.copies.getOrDefault(member, List.of());
            String activeEntry = active.get(member);
            component.canonical.put(member, prepared.stream()
                    .min(OpKit.canonicalOrder(DeleteTx.DeleteCopy::key, DeleteTx.DeleteCopy::pointers, activeEntry)).orElseThrow());
            for (DeleteTx.DeleteCopy copy : prepared) {
                copies.add(new CopyVersionScanner.Copy(member, copy.key(), copy.tag(), copy.blocks(),
                        copy.pointers()));
            }
        }
        CopyVersionScanner.Scan versions = CopyVersionScanner.assemble(uuid, members, copies, active);
        return new PreparedCopyResolution(component, versions, states);
    }

    public JsonObject resolveCopyVersion(UUID uuid, String versionId) throws Exception {
        synchronized (this.kit.lock) { return resolveCopyVersionExclusive(uuid, versionId); }
    }

    private JsonObject resolveCopyVersionExclusive(UUID uuid, String versionId) throws Exception {
        List<String> warnings = new ArrayList<>();
        PreparedCopyResolution prepared = prepareCopyResolution(uuid, warnings);
        CopyVersionScanner.Scan scan = prepared.scan();
        CopyResolutionPlan plan = requireCopyResolution(scan, versionId);
        CopyVersionScanner.Version selected = plan.selected();
        CopyVersionScanner.Version rollbackVersion = plan.rollback();
        DeleteTx.DeleteComponent component = prepared.component();
        Map<UUID, RecycleStore.OperationalState> states = prepared.states();
        Map<String, RecycleStore.Stage> stages = new LinkedHashMap<>();
        Map<String, RecycleStore.Stage> incompleteStages = new LinkedHashMap<>();
        Map<UUID, DeleteTx.DeleteStatus> statuses = new LinkedHashMap<>();
        for (UUID target : component.targets) statuses.put(target, new DeleteTx.DeleteStatus(target));
        JsonObject out;
        try {
            for (CopyVersionScanner.Version version : scan.versions()) {
                if (!version.complete()) continue;
                stages.put(version.id(), this.recycle.stageArchived(
                        versionSources(version), states, "deleted"));
            }
            for (CopyVersionScanner.Copy copy : scan.incomplete()) {
                incompleteStages.put(copy.key().id(), this.recycle.stageArchived(
                        List.of(new RecycleStore.Source(copy.uuid(), copy.key().dim(), copy.key(), copy.tag())),
                        states, "incomplete"));
            }

            this.tx.executeDeleteComponents(List.of(component), statuses);
            this.tx.verifyDeletedTargets(statuses, warnings, false);
            List<String> failures = statuses.values().stream().filter(status -> !status.ok)
                    .map(status -> status.uuid + ": " + String.join("; ", status.errors)).toList();
            if (!failures.isEmpty()) throw new IllegalStateException("副本切换清理失败: " + String.join(" | ", failures));
            this.tx.requireTargetsAbsent(scan.members(), warnings);

            for (Map.Entry<String, RecycleStore.Stage> entry : stages.entrySet()) {
                if (entry.getKey().equals(selected.id())
                        || entry.getKey().equals(rollbackVersion.id())) continue;
                this.recycle.commitOld(entry.getValue());
            }
            for (RecycleStore.Stage stage : incompleteStages.values()) {
                this.recycle.commitIncomplete(stage);
            }
            this.restore.restoreGroupData(restoreGroup(selected, states, "copy-selection"), false, warnings);
            if (!rollbackVersion.id().equals(selected.id())) {
                this.recycle.commitOld(stages.get(rollbackVersion.id()));
            }
            this.recycle.discard(stages.get(selected.id()));
            out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("version", selected.id());
            out.addProperty("archived", Math.max(0, stages.size() - 1));
            out.addProperty("quarantined", incompleteStages.size());
            out.addProperty("removed_redundant", selected.redundant().size());
        } catch (Exception error) {
            boolean changed = component.stateCleared
                    || statuses.values().stream().anyMatch(status -> status.removed);
            if (!changed) {
                for (RecycleStore.Stage stage : stages.values()) this.recycle.discard(stage);
                for (RecycleStore.Stage stage : incompleteStages.values()) this.recycle.discard(stage);
                throw error;
            }
            RecycleStore.Stage rollback = stages.get(rollbackVersion.id());
            try {
                RecycleStore.RestoreGroup rollbackGroup = rollback.committed()
                        ? this.recycle.loadGroup(rollback.id()) : this.recycle.loadStage(rollback);
                this.restore.restoreGroupData(rollbackGroup, true, warnings);
                if (!rollback.committed()) this.recycle.discard(rollback);
                for (Map.Entry<String, RecycleStore.Stage> entry : stages.entrySet()) {
                    if (entry.getKey().equals(rollbackVersion.id()) || entry.getValue().committed()) continue;
                    try {
                        this.recycle.commitOld(entry.getValue());
                    } catch (Exception archiveError) {
                        error.addSuppressed(archiveError);
                    }
                }
                for (RecycleStore.Stage stage : incompleteStages.values()) {
                    if (stage.committed()) continue;
                    try {
                        this.recycle.commitIncomplete(stage);
                    } catch (Exception archiveError) {
                        error.addSuppressed(archiveError);
                    }
                }
            } catch (Exception rollbackError) {
                error.addSuppressed(rollbackError);
                try {
                    if (!rollback.committed()) this.recycle.commitOld(rollback);
                } catch (Exception keepError) {
                    error.addSuppressed(keepError);
                }
            }
            throw error;
        }

        JsonObject detail = new JsonObject();
        detail.addProperty("version", selected.id());
        detail.addProperty("archived", Math.max(0, stages.size() - 1));
        try {
            this.kit.audit("resolve_copies", uuid, null, detail.toString());
        } catch (Throwable error) {
            warnings.add("副本处理已完成，但审计日志写入失败: " + messageOf(error));
            SablePanel.LOGGER.warn("sablepanel: copy resolution audit failed after commit", error);
        }
        try {
            this.kit.rescan.run();
        } catch (Throwable error) {
            warnings.add("副本处理已完成，但磁盘索引重扫触发失败: " + messageOf(error));
            SablePanel.LOGGER.warn("sablepanel: copy resolution rescan failed after commit", error);
        }
        OpKit.attachWarnings(out, warnings);
        return out;
    }

    public JsonObject quarantineIncompleteCopies(UUID uuid) throws Exception {
        synchronized (this.kit.lock) { return quarantineIncompleteCopiesExclusive(uuid); }
    }

    private JsonObject quarantineIncompleteCopiesExclusive(UUID uuid) throws Exception {
        List<String> warnings = new ArrayList<>();
        CopyVersionScanner.Scan scan = inspectVersionState(uuid, warnings);
        if (scan.versions().stream().anyMatch(CopyVersionScanner.Version::complete)) {
            throw new IllegalStateException("存在完整候选版本，请选择主版本；未归属条目会随切换一起隔离");
        }
        if (scan.incomplete().isEmpty()) throw new IllegalStateException("没有可隔离的不完整副本");
        Map<UUID, RecycleStore.OperationalState> states = operationalStates(scan.members());
        List<RecycleStore.Stage> stages = new ArrayList<>();
        boolean changed = false;
        try {
            for (CopyVersionScanner.Copy copy : scan.incomplete()) {
                stages.add(this.recycle.stageArchived(List.of(new RecycleStore.Source(
                        copy.uuid(), copy.key().dim(), copy.key(), copy.tag())), states, "incomplete"));
            }
            DeleteTx.DeleteComponent component = this.tx.prepareExactDeleteComponent(scan.members(), warnings);
            Map<UUID, DeleteTx.DeleteStatus> statuses = new LinkedHashMap<>();
            for (UUID target : component.targets) statuses.put(target, new DeleteTx.DeleteStatus(target));
            changed = true;
            this.tx.executeDeleteComponents(List.of(component), statuses);
            this.tx.verifyDeletedTargets(statuses, warnings);
            List<String> failures = statuses.values().stream().filter(status -> !status.ok)
                    .map(status -> status.uuid + ": " + String.join("; ", status.errors)).toList();
            if (!failures.isEmpty()) throw new IllegalStateException("隔离清理失败: " + String.join(" | ", failures));
            this.tx.requireTargetsAbsent(scan.members(), warnings);
            for (RecycleStore.Stage stage : stages) {
                this.recycle.commitIncomplete(stage);
            }
            this.kit.audit("quarantine_copies", uuid, null, stages.size() + " entries");
            this.kit.rescan.run();
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("quarantined", stages.size());
            OpKit.attachWarnings(out, warnings);
            return out;
        } catch (Exception error) {
            if (!changed) {
                for (RecycleStore.Stage stage : stages) this.recycle.discard(stage);
            }
            throw error;
        }
    }

    private Map<UUID, RecycleStore.OperationalState> operationalStates(Set<UUID> targets) throws Exception {
        return operationalStates(this.kit.readOperationalMetadata(targets), targets);
    }

    private static Map<UUID, RecycleStore.OperationalState> operationalStates(
            JsonObject metadata, Collection<UUID> targets) {
        Map<UUID, RecycleStore.OperationalState> states = new LinkedHashMap<>();
        for (UUID uuid : targets) {
            JsonObject state = metadata.getAsJsonObject(uuid.toString());
            states.put(uuid, new RecycleStore.OperationalState(
                    state.get("paused").getAsBoolean(), state.get("forced").getAsBoolean()));
        }
        return states;
    }

    private static List<RecycleStore.Source> versionSources(CopyVersionScanner.Version version) {
        return version.copies().stream().map(copy -> new RecycleStore.Source(
                copy.uuid(), copy.key().dim(), copy.key(), copy.tag())).toList();
    }

    private static RecycleStore.RestoreGroup restoreGroup(CopyVersionScanner.Version version,
                                                           Map<UUID, RecycleStore.OperationalState> states,
                                                           String id) {
        List<RecycleStore.RestoreBody> bodies = version.copies().stream().map(copy -> {
            RecycleStore.OperationalState state = states.getOrDefault(copy.uuid(),
                    new RecycleStore.OperationalState(false, false));
            return new RecycleStore.RestoreBody(copy.uuid(), copy.key().dim(), copy.key().id(), copy.tag(),
                    state.paused(), state.forced());
        }).toList();
        return new RecycleStore.RestoreGroup(id, "pending", false, bodies);
    }

    /**
     * 只整理内容完全一致的同 UUID 磁盘条目。保留活动/可达副本,其余通过 sable 自身
     * queueDeletion + saveAll 清理；内容不同的一律拒绝,不猜哪份才是玩家资产。
     */
    public JsonObject deduplicate(UUID uuid) throws Exception {
        synchronized (this.kit.lock) { return deduplicateExclusive(uuid); }
    }

    private JsonObject deduplicateExclusive(UUID uuid) throws Exception {
        List<String> warnings = new ArrayList<>();
        CopyInspection inspection = inspectCopyState(uuid, warnings);
        if (inspection.copies().size() < 2) {
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("removed", 0);
            out.addProperty("kept_entry", inspection.keep().key().id());
            OpKit.attachWarnings(out, warnings);
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
            OpKit.attachWarnings(out, warnings);
            return out;
        }
        if (!prepared.identical()) {
            throw new IllegalStateException("副本内容在确认后发生变化，未执行去重");
        }
        CopyCandidate keep = prepared.keep();
        Map<UUID, OpKit.MemberPlan> chain = this.kit.prepareChain(uuid);
        chain.put(uuid, new OpKit.MemberPlan(keep.key(), keep.tag(),
                keep.pointers().isEmpty() ? null : keep.pointers().get(0)));
        int removed = prepared.copies().size() - 1;
        this.kit.onMainUntilComplete(() -> {
            String active = this.kit.activePointerEntryId(uuid);
            if (active != null && !active.equals(keep.key().id())) {
                throw new IllegalStateException("活动副本在准备后发生变化，请重试");
            }
            ServerSubLevel body = this.kit.ensureLoaded(uuid, chain);
            GlobalSavedSubLevelPointer originalPointer = body.getLastSerializationPointer();
            Set<ServerLevel> touched = new LinkedHashSet<>();
            touched.add(body.getLevel());
            try {
                for (CopyCandidate copy : prepared.copies()) {
                    if (copy.key().equals(keep.key())) continue;
                    ServerLevel level = this.kit.levelOf(copy.key().dim());
                    ServerSubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
                    if (container == null) throw new IllegalStateException("副本所在维度不可用: " + copy.key().dim());
                    touched.add(level);
                    List<GlobalSavedSubLevelPointer> targets = new ArrayList<>();
                    if (copy.pointers().isEmpty()) {
                        targets.add(DeleteTx.fallbackPointer(copy.key(), copy.tag()));
                    } else {
                        for (DiskScanner.LiveLocation location : copy.pointers()) targets.add(DeleteTx.toPointer(location));
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
            OpKit.saveAllLevels(touched);
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
        this.kit.audit("dedupe", uuid, null, detail.toString());
        this.kit.rescan.run();
        JsonObject out = copiesJson(verified);
        out.addProperty("ok", true);
        out.addProperty("removed", removed);
        out.addProperty("kept_entry", verified.keep().key().id());
        OpKit.attachWarnings(out, warnings);
        return out;
    }

    private CopyInspection inspectCopyState(UUID uuid, List<String> warnings) throws Exception {
        ScanSession scan = ScanSession.strict(this.kit.server, warnings);
        List<DiskScanner.EntryMeta> entries = scan.entriesOf(uuid);
        if (entries.isEmpty()) throw new IllegalStateException("找不到该体的磁盘条目");
        Set<DiskScanner.EntryKey> keys = new LinkedHashSet<>();
        for (DiskScanner.EntryMeta entry : entries) keys.add(entry.key());
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers =
                DiskScanner.locatePointersStrict(scan.dims(), keys, warnings);
        JsonObject activeState = this.kit.onMain(() -> {
            JsonObject out = new JsonObject();
            String id = this.kit.activePointerEntryId(uuid);
            if (id != null) out.addProperty("entry", id);
            return out;
        });
        String active = activeState.has("entry") ? activeState.get("entry").getAsString() : null;

        List<CopyCandidate> copies = new ArrayList<>();
        for (DiskScanner.EntryMeta entry : entries) {
            CompoundTag tag = OpKit.readVerified(scan.dims(), uuid, entry.key());
            if (tag == null) throw new IOException("副本槽位已经变化: " + entry.key().id());
            copies.add(new CopyCandidate(entry.key(), tag,
                    DiskScanner.countBlocks(tag.getCompound("plot"), null),
                    List.copyOf(pointers.getOrDefault(entry.key(), List.of()))));
        }
        copies.sort(OpKit.canonicalOrder(CopyCandidate::key, CopyCandidate::pointers, active));
        CopyCandidate keep = copies.get(0);
        boolean identical = copies.stream().allMatch(copy -> copy.tag().equals(keep.tag()));
        return new CopyInspection(scan.dims(), List.copyOf(copies), keep, identical);
    }

    private JsonObject copiesJson(CopyInspection inspection) {
        JsonObject out = new JsonObject();
        out.addProperty("uuid", OpKit.tagUuid(inspection.keep().tag()).toString());
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
            item.add("pos", OpKit.numberArray(summary.pos()));
            item.add("size", OpKit.numberArray(summary.size()));
            copies.add(item);
        }
        out.add("copies", copies);
        return out;
    }
}
