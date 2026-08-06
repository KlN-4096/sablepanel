package com.klnon.sablepanel.panel.service;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;
import com.klnon.sablepanel.panel.data.DiskScanner;
import com.klnon.sablepanel.panel.data.RecycleStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 批量删除:按依赖组件执行。删除前把磁盘条目备份进回收站临时事务,
 * 全部成功才转正;失败经 RestoreOps 自动回滚,回滚也失败则备份保留并标记待恢复。
 */
public final class DeleteOps {
    private final OpKit kit;
    private final DeleteTx tx;
    private final RestoreOps restore;
    private final RecycleStore recycle;

    DeleteOps(OpKit kit, DeleteTx tx, RestoreOps restore, RecycleStore recycle) {
        this.kit = kit;
        this.tx = tx;
        this.restore = restore;
        this.recycle = recycle;
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
    public JsonObject deleteBatch(List<UUID> uuids) {
        synchronized (this.kit.lock) { return deleteBatchExclusive(uuids); }
    }

    private JsonObject deleteBatchExclusive(List<UUID> uuids) {
        List<UUID> requested = new ArrayList<>(new LinkedHashSet<>(uuids));
        Map<UUID, DeleteTx.DeleteStatus> statuses = new LinkedHashMap<>();
        for (UUID uuid : requested) statuses.put(uuid, new DeleteTx.DeleteStatus(uuid));
        List<DeleteTx.DeleteComponent> components = List.of();
        List<String> warnings = new ArrayList<>();

        try {
            components = prepareDeleteComponents(requested, warnings);
            for (DeleteTx.DeleteComponent component : components) {
                for (UUID target : component.targets) statuses.computeIfAbsent(target, DeleteTx.DeleteStatus::new);
            }
            if (statuses.size() > 500) throw new IllegalStateException("依赖组展开后超过 500 个物理体");
            prepareDeleteSemantics(components, statuses);
            stageDeleteBackups(components, statuses);
            this.tx.executeDeleteComponents(components, statuses);
        } catch (Exception e) {
            String message = "删除事务失败: " + messageOf(e);
            for (DeleteTx.DeleteStatus status : statuses.values()) status.fail(message);
            SablePanel.LOGGER.warn("sablepanel: batch delete transaction failed", e);
        }
        this.tx.verifyDeletedTargets(statuses, warnings);
        finalizeDeleteBackups(components, statuses, warnings);
        for (DeleteTx.DeleteStatus status : statuses.values()) {
            if (!status.ok) this.kit.audit("delete_failed", status.uuid, null, String.join("; ", status.errors));
        }
        JsonObject response = deleteResponse(new ArrayList<>(statuses.keySet()), statuses);
        response.addProperty("requested", requested.size());
        OpKit.attachWarnings(response, warnings);
        return response;
    }

    private List<DeleteTx.DeleteComponent> prepareDeleteComponents(List<UUID> targets, List<String> warnings)
            throws Exception {
        ScanSession scan = ScanSession.strict(this.kit.server, warnings);
        // 纯运行时新体(刚生成、盘上还没有条目)先落一次盘再删:内存里的方块不落盘就无从备份
        if (this.kit.flushUnsavedTargets(targets, scan.meta())) {
            scan = ScanSession.strict(this.kit.server, warnings);
        }
        List<Set<UUID>> selectedGroups = DiskScanner.selectedDependencyComponents(scan.meta(), targets);
        Set<UUID> selected = new LinkedHashSet<>();
        for (Set<UUID> group : selectedGroups) {
            selected.addAll(group);
        }
        Map<UUID, List<DeleteTx.DeleteCopy>> prepared = this.tx.readDeleteCopies(scan, selected, warnings);

        List<DeleteTx.DeleteComponent> components = new ArrayList<>();
        for (Set<UUID> group : selectedGroups) {
            DeleteTx.DeleteComponent component = new DeleteTx.DeleteComponent();
            for (UUID target : group) {
                component.addTarget(target, prepared.getOrDefault(target, List.of()));
            }
            components.add(component);
        }
        return components;
    }

    /** 选择唯一规范副本并记录运行状态；内容不同的副本必须先由用户处理，删除不能猜。 */
    private void prepareDeleteSemantics(List<DeleteTx.DeleteComponent> components,
                                        Map<UUID, DeleteTx.DeleteStatus> statuses) throws Exception {
        Set<UUID> targets = new LinkedHashSet<>();
        for (DeleteTx.DeleteComponent component : components) targets.addAll(component.targets);
        JsonObject runtime = this.kit.readOperationalMetadata(targets);

        for (DeleteTx.DeleteComponent component : components) {
            boolean conflict = false;
            for (UUID uuid : component.targets) {
                List<DeleteTx.DeleteCopy> copies = component.copies.getOrDefault(uuid, List.of());
                if (!copies.isEmpty()) {
                    JsonObject state = runtime.getAsJsonObject(uuid.toString());
                    String active = state.has("active") ? state.get("active").getAsString() : null;
                    DeleteTx.DeleteCopy keep = copies.stream()
                            .min(OpKit.canonicalOrder(DeleteTx.DeleteCopy::key, DeleteTx.DeleteCopy::pointers, active)).orElseThrow();
                    component.canonical.put(uuid, keep);
                    if (copies.stream().anyMatch(copy -> !copy.tag().equals(keep.tag()))) conflict = true;
                }
                JsonObject state = runtime.getAsJsonObject(uuid.toString());
                component.states.put(uuid, new RecycleStore.OperationalState(
                        state.get("paused").getAsBoolean(), state.get("forced").getAsBoolean()));
            }
            if (conflict) {
                this.tx.failComponent(component, statuses, "依赖组存在内容不同的副本，请先处理副本后重试删除");
            }
        }
    }

    private void stageDeleteBackups(List<DeleteTx.DeleteComponent> components, Map<UUID, DeleteTx.DeleteStatus> statuses) {
        for (DeleteTx.DeleteComponent component : components) {
            if (this.tx.componentHasErrors(component, statuses)) continue;
            List<RecycleStore.Source> sources = new ArrayList<>();
            for (UUID uuid : component.targets) {
                DeleteTx.DeleteCopy copy = component.canonical.get(uuid);
                if (copy != null) sources.add(new RecycleStore.Source(
                        uuid, copy.key().dim(), copy.key(), copy.tag()));
            }
            if (sources.isEmpty()) continue;
            try {
                component.stage = this.recycle.stage(sources, component.states);
            } catch (Exception error) {
                this.tx.failComponent(component, statuses, "删除前临时备份失败: " + messageOf(error));
            }
        }
    }

    private void finalizeDeleteBackups(List<DeleteTx.DeleteComponent> components, Map<UUID, DeleteTx.DeleteStatus> statuses,
                                       List<String> warnings) {
        boolean restoredAny = false;
        for (DeleteTx.DeleteComponent component : components) {
            if (component.stage == null) continue;
            boolean succeeded = component.targets.stream().allMatch(uuid -> statuses.get(uuid).ok);
            boolean changed = component.targets.stream().anyMatch(uuid -> statuses.get(uuid).removed);
            if (succeeded && changed) {
                try {
                    String groupId = this.recycle.commit(component.stage);
                    for (UUID uuid : component.targets) {
                        statuses.get(uuid).recycleGroup = groupId;
                        this.kit.audit("delete", uuid, null, groupId);
                    }
                    continue;
                } catch (Exception error) {
                    this.tx.failComponent(component, statuses, "回收站提交失败: " + messageOf(error));
                    SablePanel.LOGGER.warn("sablepanel: recycle commit failed after delete", error);
                    changed = true;
                }
            }
            if (!changed) {
                if (component.stateCleared) {
                    try {
                        this.restore.restoreOperationalState(this.recycle.loadStage(component.stage));
                    } catch (Exception stateError) {
                        this.tx.failComponent(component, statuses,
                                "删除未发生，但暂停/常驻状态恢复失败: " + messageOf(stateError));
                        try {
                            String groupId = this.recycle.commitRecoveryRequired(component.stage);
                            for (UUID uuid : component.targets) statuses.get(uuid).recycleGroup = groupId;
                        } catch (Exception keepError) {
                            stateError.addSuppressed(keepError);
                        }
                        continue;
                    }
                }
                this.recycle.discard(component.stage);
                continue;
            }
            try {
                RecycleStore.RestoreGroup rollback = this.recycle.loadStage(component.stage);
                this.restore.restoreGroupData(rollback, true, warnings);
                this.tx.failComponent(component, statuses, "删除失败，已从临时事务自动恢复原依赖组");
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
                    this.tx.failComponent(component, statuses,
                            "删除失败且自动恢复失败，完整备份已进入回收站: " + groupId);
                } catch (Exception keepError) {
                    this.tx.failComponent(component, statuses,
                            "删除失败且自动恢复失败，内部事务已保留: " + component.stage.id());
                    error.addSuppressed(keepError);
                    SablePanel.LOGGER.error("sablepanel: failed to expose recovery transaction {}",
                            component.stage.id(), keepError);
                }
            }
        }
        if (restoredAny) this.kit.rescan.run();
    }

    private JsonObject deleteResponse(List<UUID> targets, Map<UUID, DeleteTx.DeleteStatus> statuses) {
        JsonArray results = new JsonArray();
        int ok = 0;
        for (UUID uuid : targets) {
            DeleteTx.DeleteStatus status = statuses.get(uuid);
            if (status.ok) ok++;
            results.add(status.toJson());
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", ok);
        out.addProperty("total", targets.size());
        out.add("results", results);
        if (ok == 0) {
            for (UUID uuid : targets) {
                DeleteTx.DeleteStatus status = statuses.get(uuid);
                if (!status.errors.isEmpty()) {
                    out.addProperty("error", String.join("; ", status.errors));
                    break;
                }
            }
        }
        return out;
    }
}
