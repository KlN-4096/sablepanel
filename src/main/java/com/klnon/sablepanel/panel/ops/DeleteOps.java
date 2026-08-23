package com.klnon.sablepanel.panel.ops;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import com.klnon.sablepanel.panel.recycle.RecycleStore;
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
import com.klnon.sablepanel.panel.storage.ScanSession;

/**
 * 批量删除:按依赖组件执行。删除前把磁盘条目备份进回收站临时事务,
 * 全部成功才转正;失败经 RestoreOps 自动回滚,回滚也失败则备份保留并标记待恢复。
 */
public final class DeleteOps {
    private final OpKit kit;
    private final DeleteTx tx;
    private final RestoreOps restore;
    private final RecycleStore recycle;

    private record PreparedDelete(List<DeleteTx.DeleteComponent> components,
                                  List<DeleteTx.DependencyRewrite> dependencyRewrites) {
    }

    private static final class DeleteRun {
        final List<UUID> requested;
        final boolean expandGroups;
        final Map<UUID, DeleteTx.DeleteStatus> statuses = new LinkedHashMap<>();
        final List<String> warnings = new ArrayList<>();
        List<DeleteTx.DeleteComponent> components = List.of();
        List<DeleteTx.DependencyRewrite> dependencyRewrites = List.of();
        RecycleStore.Stage dependencyStage;
        Set<UUID> dependencyTargets = Set.of();
        Exception dependencyRollbackFailure;

        DeleteRun(List<UUID> uuids, boolean expandGroups) {
            this.requested = new ArrayList<>(new LinkedHashSet<>(uuids));
            this.expandGroups = expandGroups;
            for (UUID uuid : this.requested) this.statuses.put(uuid, new DeleteTx.DeleteStatus(uuid));
        }
    }

    DeleteOps(OpKit kit, DeleteTx tx, RestoreOps restore, RecycleStore recycle) {
        this.kit = kit;
        this.tx = tx;
        this.restore = restore;
        this.recycle = recycle;
    }

    /**
     * 批量删除按依赖组件执行。每个 holding chunk 只准备一次,随后在同一个主线程任务里
     * 连续 remove,随后统一 saveAll。这样后续目标不会再从尚未落盘的旧指针复活前面已删目标。
     *
     * <p>这里只走 sable 的 removeSubLevel(REMOVED) + saveAll,不直接清存储槽。
     *
     * @param expandGroups true(默认)= 把每个目标按依赖链展开成完整组一起删,依赖组一荣俱荣;
     *                     false = 只删点名的这些。
     *                     <p>
     *                     后者是给"清断链残骸"用的:残骸和主体在 sable 眼里是同一个依赖组
     *                     (轴承方块记着对方 UUID,甩开几百格也不撒手),展开就会把主体一起删掉。
     *                     删除事务会同时裁剪幸存者存档中的 {@code loading_dependencies},
     *                     并在写入前暂存幸存者原 NBT，失败时回滚。
     */
    public JsonObject deleteBatch(List<UUID> uuids, boolean expandGroups) {
        synchronized (this.kit.lock) { return deleteBatchExclusive(uuids, expandGroups); }
    }

    private JsonObject deleteBatchExclusive(List<UUID> uuids, boolean expandGroups) {
        DeleteRun run = new DeleteRun(uuids, expandGroups);
        try {
            executeDeleteTransaction(run);
        } catch (Exception e) {
            String message = "删除事务失败: " + messageOf(e);
            for (DeleteTx.DeleteStatus status : run.statuses.values()) status.fail(message);
            SablePanel.LOGGER.warn("sablepanel: batch delete transaction failed", e);
        }
        updateDependencyTargets(run, executedDeleteTargets(run.statuses));
        this.tx.verifyPermanentDeletion(run.components, run.statuses, run.warnings);
        updateDependencyTargets(run, successfulDeleteTargets(run));
        finalizeDeleteBackups(run.components, run.statuses, run.warnings);
        updateDependencyTargets(run, successfulDeleteTargets(run));
        finishDependencyBackup(run.dependencyStage, run.dependencyRollbackFailure, run.statuses);
        for (DeleteTx.DeleteStatus status : run.statuses.values()) {
            if (!status.ok) this.kit.audit("delete_failed", status.uuid, null, String.join("; ", status.errors));
        }
        JsonObject response = deleteResponse(new ArrayList<>(run.statuses.keySet()), run.statuses);
        response.addProperty("requested", run.requested.size());
        OpKit.attachWarnings(response, run.warnings);
        return response;
    }

    private void executeDeleteTransaction(DeleteRun run) throws Exception {
        PreparedDelete prepared = prepareDeleteComponents(run.requested, run.expandGroups, run.warnings);
        run.components = prepared.components();
        run.dependencyRewrites = prepared.dependencyRewrites();
        for (DeleteTx.DeleteComponent component : run.components) {
            for (UUID target : component.targets) {
                run.statuses.computeIfAbsent(target, DeleteTx.DeleteStatus::new);
            }
        }
        if (run.statuses.size() > 500) throw new IllegalStateException("物理组展开后超过 500 个物理体");
        prepareDeleteSemantics(run.components, run.statuses);
        stageDeleteBackups(run.components, run.statuses);
        if (!run.dependencyRewrites.isEmpty()) {
            run.dependencyStage = stageDependencyBackups(run.dependencyRewrites);
        }
        this.tx.executeDeleteComponents(run.components, run.statuses);
    }

    private void updateDependencyTargets(DeleteRun run, Set<UUID> desiredTargets) {
        if (run.dependencyRewrites.isEmpty() || run.dependencyTargets.equals(desiredTargets)) return;
        try {
            DeleteTx.DependencyTransition transition = this.tx.prepareDependencyTransition(
                    run.dependencyRewrites, run.dependencyTargets, desiredTargets, run.warnings);
            if (!transition.before().isEmpty()) {
                RecycleStore.Stage refreshed = stageDependencyBackups(transition.before());
                RecycleStore.Stage previous = run.dependencyStage;
                run.dependencyStage = refreshed;
                this.recycle.discard(previous);
            }
            this.tx.applyDependencyTransition(transition);
            run.dependencyTargets = Set.copyOf(desiredTargets);
            this.kit.rescan.run();
        } catch (Exception error) {
            if (dependencyRecoveryRequired(run.dependencyTargets, error)) {
                run.dependencyRollbackFailure = error;
            }
            Set<UUID> affected = new LinkedHashSet<>(run.dependencyTargets);
            affected.addAll(desiredTargets);
            for (UUID uuid : affected) {
                DeleteTx.DeleteStatus status = run.statuses.get(uuid);
                if (status != null) status.fail("幸存体依赖更新失败: " + messageOf(error));
            }
        }
    }

    static boolean dependencyRecoveryRequired(Set<UUID> previouslyApplied, Throwable error) {
        return !previouslyApplied.isEmpty() || error.getSuppressed().length > 0;
    }

    private static Set<UUID> successfulDeleteTargets(DeleteRun run) {
        return dependencyTargets(run.statuses, run.dependencyTargets);
    }

    static Set<UUID> executedDeleteTargets(Map<UUID, DeleteTx.DeleteStatus> statuses) {
        Set<UUID> removed = new LinkedHashSet<>();
        for (DeleteTx.DeleteStatus status : statuses.values()) {
            if (status.removed || status.alreadyAbsent) removed.add(status.uuid);
        }
        return Set.copyOf(removed);
    }

    static Set<UUID> dependencyTargets(Map<UUID, DeleteTx.DeleteStatus> statuses,
                                       Set<UUID> previouslyApplied) {
        Set<UUID> successful = new LinkedHashSet<>();
        for (DeleteTx.DeleteStatus status : statuses.values()) {
            boolean remainsDeleted = status.ok
                    || (previouslyApplied.contains(status.uuid) && !status.restored);
            if (remainsDeleted && (status.removed || status.alreadyAbsent)) successful.add(status.uuid);
        }
        return Set.copyOf(successful);
    }

    private PreparedDelete prepareDeleteComponents(List<UUID> targets, boolean expandGroups,
                                                   List<String> warnings)
            throws Exception {
        ScanSession scan = this.kit.strictScan(warnings);
        // 纯运行时新体(刚生成、盘上还没有条目)先落一次盘再删:内存里的方块不落盘就无从备份
        if (this.kit.flushUnsavedTargets(targets, scan.meta())) {
            scan = this.kit.strictScan(warnings);
        }
        List<Set<UUID>> selectedGroups = expandGroups
                ? DiskScanner.selectedDependencyComponents(scan.meta(), targets)
                // 不展开时每个目标自成一个组件。组件是失败的粒度 —— 副本冲突会否掉整个组件,
                // 全塞进一个组件就是一颗老鼠屎坏一锅汤:实测 173 个残骸里只有 7 个副本冲突,
                // 结果 0/173,一个都没删成。不展开的语义本来就是"这些体各自独立"。
                : targets.stream().<Set<UUID>>map(target -> new LinkedHashSet<>(List.of(target))).toList();
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
        List<DeleteTx.DependencyRewrite> rewrites = expandGroups ? List.of()
                : this.tx.prepareDependencyRewrites(scan, selected, warnings);
        return new PreparedDelete(List.copyOf(components), rewrites);
    }

    private RecycleStore.Stage stageDependencyBackups(List<DeleteTx.DependencyRewrite> rewrites) throws Exception {
        return this.recycle.stage(dependencyBackupSources(rewrites), Map.of());
    }

    static List<RecycleStore.Source> dependencyBackupSources(List<DeleteTx.DependencyRewrite> rewrites) {
        return rewrites.stream().map(rewrite -> new RecycleStore.Source(
                rewrite.uuid(), rewrite.key().dim(), rewrite.key(), rewrite.original())).toList();
    }

    private void finishDependencyBackup(RecycleStore.Stage stage, Exception rollbackFailure,
                                        Map<UUID, DeleteTx.DeleteStatus> statuses) {
        if (stage == null) return;
        if (rollbackFailure == null) {
            this.recycle.discard(stage);
            return;
        }
        try {
            String groupId = this.recycle.commitRecoveryRequired(stage);
            for (DeleteTx.DeleteStatus status : statuses.values()) {
                status.fail("幸存体原 NBT 已保留在回收站: " + groupId);
            }
        } catch (Exception backupFailure) {
            rollbackFailure.addSuppressed(backupFailure);
            SablePanel.LOGGER.error("sablepanel: keeping survivor dependency backup failed", backupFailure);
        }
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
                        state.get("paused").getAsBoolean(), state.get("forced").getAsBoolean(),
                        state.get("frozen").getAsBoolean()));
            }
            if (conflict) {
                this.tx.failComponent(component, statuses, "物理组存在内容不同的副本,请先处理副本后重试删除");
            }
        }
    }

    private void stageDeleteBackups(List<DeleteTx.DeleteComponent> components, Map<UUID, DeleteTx.DeleteStatus> statuses) {
        List<DeleteTx.DeleteComponent> candidates = new ArrayList<>();
        List<RecycleStore.StageRequest> requests = new ArrayList<>();
        for (DeleteTx.DeleteComponent component : components) {
            if (this.tx.componentHasErrors(component, statuses)) continue;
            List<RecycleStore.Source> sources = new ArrayList<>();
            for (UUID uuid : component.targets) {
                DeleteTx.DeleteCopy copy = component.canonical.get(uuid);
                if (copy != null) sources.add(new RecycleStore.Source(
                        uuid, copy.key().dim(), copy.key(), copy.tag()));
            }
            if (sources.isEmpty()) continue;
            candidates.add(component);
            requests.add(new RecycleStore.StageRequest(sources, component.states));
        }
        if (requests.isEmpty()) return;
        List<RecycleStore.StageAttempt> attempts;
        try {
            attempts = this.recycle.stageBatch(requests);
        } catch (Exception error) {
            for (DeleteTx.DeleteComponent component : candidates) {
                this.tx.failComponent(component, statuses, "删除前容量统计失败: " + messageOf(error));
            }
            return;
        }
        for (int index = 0; index < attempts.size(); index++) {
            RecycleStore.StageAttempt attempt = attempts.get(index);
            DeleteTx.DeleteComponent component = candidates.get(index);
            if (attempt.error() == null) {
                component.stage = attempt.stage();
            } else {
                this.tx.failComponent(component, statuses,
                        "删除前临时备份失败: " + messageOf(attempt.error()));
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
                                "删除未发生,但暂停/常驻状态恢复失败: " + messageOf(stateError));
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
                for (UUID uuid : component.targets) statuses.get(uuid).restored = true;
                this.tx.failComponent(component, statuses, "删除失败,已从临时事务自动恢复原物理组");
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
                            "删除失败且自动恢复失败,完整备份已进入回收站: " + groupId);
                } catch (Exception keepError) {
                    this.tx.failComponent(component, statuses,
                            "删除失败且自动恢复失败,内部事务已保留: " + component.stage.id());
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
