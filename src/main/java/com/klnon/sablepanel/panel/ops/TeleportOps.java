package com.klnon.sablepanel.panel.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** 运行态操作:传送/暂停/常驻加载/在线玩家交互。sable 交互全部主线程执行。 */
public final class TeleportOps {
    private static final double POSITION_EPSILON = 0.1;
    private final OpKit kit;

    TeleportOps(OpKit kit) {
        this.kit = kit;
    }

    public JsonObject teleport(UUID uuid, double x, double y, double z) throws Exception {
        Map<UUID, OpKit.MemberPlan> chain = this.kit.prepareChain(uuid);
        JsonObject result = this.kit.onMain(() -> {
            ServerSubLevel sl = this.kit.ensureLoaded(uuid, chain);
            ServerLevel level = sl.getLevel();
            SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
            // 面板坐标语义 = 包围盒底面中心。pose 原点与几何差一个 plot 偏移,
            // 直接设 pose 会让结构落点偏移十几格;按当前锚点差换算回 pose 再传送。
            requireFiniteTarget(x, y, z);
            Vector3d sourceAnchor = bottomCenter(sl.boundingBox());
            Vector3d target = new Vector3d(x, y, z);
            var position = sl.logicalPose().position();
            target.add(position.x() - sourceAnchor.x, position.y() - sourceAnchor.y,
                    position.z() - sourceAnchor.z);
            var pipeline = phys.getPipeline();
            Pose3d original = new Pose3d(sl.logicalPose());
            Quaterniond orientation = new Quaterniond(sl.logicalPose().orientation());
            PauseService.moveOnMain(sl,
                    () -> {
                        finishMove(() -> pipeline.teleport(sl, target, orientation),
                                () -> pipeline.resetVelocity(sl), () -> updatePoseAndBounds(sl));
                        requirePosition(sl, x, y, z);
                    },
                    () -> {
                        finishMove(() -> pipeline.teleport(sl, original.position(), original.orientation()),
                                () -> pipeline.resetVelocity(sl), () -> updatePoseAndBounds(sl));
                        requirePosition(sl, sourceAnchor.x, sourceAnchor.y, sourceAnchor.z);
                    });
            this.kit.audit("teleport", uuid, sl.getName(), x + "," + y + "," + z);
            String dim = level.dimension().location().toString();
            this.kit.index.updateRuntimePosition(uuid, dim, new double[]{x, y, z});
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("dim", dim);
            r.addProperty("x", x);
            r.addProperty("y", y);
            r.addProperty("z", z);
            return r;
        });
        this.kit.rescan.run();
        return result;
    }

    /** 整组物理暂停/恢复；暂停成功后清除组内全部线速度和角速度。 */
    public JsonObject setPaused(List<UUID> requested, boolean paused) throws Exception {
        OpKit.DependencySelection selection = this.kit.dependencyGroups(requested);
        List<UUID> uuids = selection.members();
        try {
            this.kit.onMain(() -> {
                this.kit.requirePreparedDependencyGroupsOnMain(selection.components());
                if (paused) {
                    List<ServerSubLevel> bodies = requireLoadedGroup(uuids);
                    pauseGroupAndStop(uuids, bodies);
                } else {
                    PauseService.applyOnMain(this.kit.server, uuids, false);
                }
                return null;
            });
        } finally {
            PauseService.persist();
        }
        for (UUID uuid : uuids) this.kit.audit(paused ? "pause" : "resume", uuid, null, null);
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("paused", paused);
        out.addProperty("count", uuids.size());
        return out;
    }

    /**
     * 常驻加载(sable force-load ticket)。开启前必须先把体加载出来 —— {@code addForceLoadTicket}
     * 只接受已加载的 {@link ServerSubLevel};关闭则对未加载体也能摘票。
     * 加载可能触发区块同步生成,故走不设超时的 {@link OpKit#onMainUntilComplete}。
     */
    public JsonObject setForced(List<UUID> requested, boolean forced) throws Exception {
        // 常驻加载必须整组。只钉一部分是无效操作:PhysicsChunkTicketManager 按整条依赖链判定卸载,
        // 2026-08-08 实测给 192 体组里的一个成员挂票,体加载出来 827 毫秒后照样 remove UNLOADED,
        // 而作业还报 ok。挂票和摘票必须保持相同的整组语义。
        OpKit.DependencySelection selection = this.kit.dependencyGroups(requested);
        List<UUID> uuids = selection.members();
        JsonObject out = forced ? enableForceLoad(selection) : disableForceLoad(selection);
        out.addProperty("requested", requested.size());
        for (UUID uuid : uuids) this.kit.audit(forced ? "force_load" : "force_unload", uuid, null, null);
        return out;
    }

    private JsonObject enableForceLoad(OpKit.DependencySelection selection) throws Exception {
        List<UUID> uuids = selection.members();
        // 整批一次建链:多选往往是同一个依赖组的成员,分层 BFS 会把它们一趟解完。
        // 逐个建链会把同一批 .slvls 解压 N 遍 —— 全选 178 体的绳链时就是 178 遍。
        Map<UUID, OpKit.MemberPlan> chain = Map.of();
        // 已加载的体不用进链:ensureLoaded 第一行 resolveLoaded 就会返回。
        // 生产上曾为一个已加载的 178 依赖体白扫 16 分钟磁盘。
        List<UUID> cold = uuids.stream().filter(u -> !this.kit.index.isLoaded(u)).toList();
        if (!cold.isEmpty()) chain = this.kit.prepareChain(cold); // 作业线程做磁盘定位,不占主线程
        Map<UUID, OpKit.MemberPlan> plans = chain;
        // ThreadLocal 到不了主线程,先在作业线程上取出来捕获进 lambda
        JobService.Job job = JobService.current();
        return this.kit.onMainUntilComplete(() -> {
            JsonArray failed = new JsonArray();
            List<ServerSubLevel> newlyTicketed = new ArrayList<>();
            int done = 0;
            for (UUID uuid : uuids) {
                if (job != null) job.phase("挂常驻票");
                try {
                    ServerSubLevel body = this.kit.ensureLoaded(uuid, plans);
                    boolean alreadyForced = ForceLoadService.isForcedOnMain(body);
                    ForceLoadService.addOnMain(body);
                    if (!alreadyForced) newlyTicketed.add(body);
                    done++;
                } catch (Throwable t) {
                    JsonObject f = new JsonObject();
                    f.addProperty("uuid", uuid.toString());
                    f.addProperty("error", String.valueOf(t.getMessage()));
                    failed.add(f);
                }
            }
            if (!failed.isEmpty()) {
                IllegalStateException operationFailure = new IllegalStateException("常驻加载失败: " + failed);
                for (ServerSubLevel body : newlyTicketed) {
                    try {
                        ForceLoadService.removeStrictOnMain(this.kit.server, body);
                    } catch (Throwable rollbackFailure) {
                        operationFailure.addSuppressed(rollbackFailure);
                    }
                }
                throw operationFailure;
            }
            try {
                for (Set<UUID> component : selection.components()) {
                    this.kit.requireLoadedDependencyGroupOnMain(component);
                }
            } catch (Throwable groupFailure) {
                IllegalStateException operationFailure = new IllegalStateException(
                        "常驻加载后的运行依赖组与准备结果不一致", groupFailure);
                for (ServerSubLevel body : newlyTicketed) {
                    try {
                        ForceLoadService.removeStrictOnMain(this.kit.server, body);
                    } catch (Throwable rollbackFailure) {
                        operationFailure.addSuppressed(rollbackFailure);
                    }
                }
                throw operationFailure;
            }
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("forced", true);
            o.addProperty("count", done);
            return o;
        });
    }

    private JsonObject disableForceLoad(OpKit.DependencySelection selection) throws Exception {
        List<UUID> uuids = selection.members();
        Set<UUID> originalPaused = new LinkedHashSet<>();
        Set<UUID> originalFrozen = new LinkedHashSet<>();
        Map<UUID, Set<String>> originalTickets = new LinkedHashMap<>();
        Map<UUID, StoredSnapshot> expectedStored = new LinkedHashMap<>();
        Map<UUID, DiskScanner.EntryKey> savedEntries = new LinkedHashMap<>();
        try {
            JsonObject out = this.kit.onMainUntilComplete(() -> {
                Set<ServerLevel> touched = new LinkedHashSet<>();
                Set<String> changedMetadata = new LinkedHashSet<>();
                JobService.Job job = JobService.current();
                for (UUID uuid : uuids) {
                    if (PauseService.isPaused(uuid)) originalPaused.add(uuid);
                    if (FreezeService.isFrozen(uuid)) originalFrozen.add(uuid);
                }
                originalTickets.putAll(ForceLoadService.panelTicketDimensionsOnMain(this.kit.server, uuids));
                finishUnforce(new UnforceActions(
                        () -> {
                            if (job != null) job.phase("清除运行状态");
                            this.kit.requirePreparedDependencyGroupsOnMain(selection.components());
                            Set<UUID> otherTickets = new LinkedHashSet<>();
                            for (UUID uuid : uuids) {
                                if (ForceLoadService.hasOtherTicketOnMain(this.kit.server, uuid)) {
                                    otherTickets.add(uuid);
                                }
                            }
                            if (!otherTickets.isEmpty()) {
                                throw new IllegalStateException("存在其他模组的常驻票，未取消常驻: " + otherTickets);
                            }
                            PauseService.applyOnMain(this.kit.server, uuids, false);
                            FreezeService.applyOnMain(uuids, false);
                        },
                        () -> {
                            if (job != null) job.phase("卸载到存档");
                            unloadGroupsOnMain(uuids, touched, expectedStored);
                        },
                        () -> {
                            if (job != null) job.phase("保存存档");
                            OpKit.saveAllLevels(touched);
                            captureSavedEntriesOnMain(expectedStored, savedEntries);
                        },
                        () -> {
                            if (job != null) job.phase("摘常驻票");
                            for (UUID uuid : uuids) {
                                changedMetadata.addAll(ForceLoadService.clearStrictOnMain(this.kit.server, uuid));
                            }
                            saveChangedMetadata(changedMetadata);
                        },
                        () -> verifyUnforcedOnMain(uuids)));
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("forced", false);
                result.addProperty("count", uuids.size());
                long stillLoaded = uuids.stream().filter(uuid -> this.kit.resolveLoaded(uuid) != null).count();
                result.addProperty("unloaded", uuids.size() - stillLoaded);
                result.addProperty("naturally_loaded", stillLoaded);
                return result;
            });
            verifyStoredAfterUnforce(uuids, expectedStored, savedEntries);
            this.kit.rescan.run();
            return out;
        } catch (Exception failure) {
            try {
                this.kit.onMainUntilComplete(() -> {
                    rollbackUnforceOnMain(uuids, originalPaused, originalFrozen,
                            originalTickets, expectedStored, savedEntries);
                    return new JsonObject();
                });
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            PauseService.persist();
        }
    }

    private void verifyStoredAfterUnforce(Collection<UUID> uuids,
                                          Map<UUID, StoredSnapshot> expectedStored,
                                          Map<UUID, DiskScanner.EntryKey> savedEntries) {
        DiskScanner.invalidateCache();
        Map<String, java.nio.file.Path> dimensions = DiskScanner.sublevelDirs(this.kit.server);
        Set<UUID> mismatched = new LinkedHashSet<>();
        for (var entry : expectedStored.entrySet()) {
            DiskScanner.EntryKey key = savedEntries.get(entry.getKey());
            CompoundTag actual = key == null ? null : OpKit.readVerified(dimensions, entry.getKey(), key);
            if (!entry.getValue().tag().equals(actual)) mismatched.add(entry.getKey());
        }
        Set<UUID> alreadyStored = new LinkedHashSet<>(uuids);
        alreadyStored.removeAll(expectedStored.keySet());
        if (!alreadyStored.isEmpty()) {
            Map<UUID, OpKit.MemberPlan> stored = this.kit.prepareChain(alreadyStored);
            alreadyStored.removeAll(stored.keySet());
            mismatched.addAll(alreadyStored);
        }
        if (!mismatched.isEmpty()) throw new IllegalStateException("取消常驻后磁盘复核失败: " + mismatched);
    }

    /**
     * 用户可解冻单个组恢复它的 tick。会崩是常态,所以调用方(前端)必须先弹警告 ——
     * 后端只按体量给出 {@code heavy} 标记,拦不拦由用户决定(不设闸门是既定约定)。
     */
    public JsonObject setFrozen(List<UUID> requested, boolean frozen) throws Exception {
        OpKit.DependencySelection selection = this.kit.dependencyGroups(requested);
        List<UUID> uuids = selection.members();
        this.kit.onMain(() -> {
            this.kit.requirePreparedDependencyGroupsOnMain(selection.components());
            if (frozen) {
                requireLoadedGroup(uuids);
            }
            FreezeService.applyOnMain(uuids, frozen);
            return null;
        });
        for (UUID uuid : requested) this.kit.audit(frozen ? "freeze" : "thaw", uuid, null, null);
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("frozen", frozen);
        out.addProperty("count", uuids.size());
        return out;
    }

    /** 后台一次性迁移旧 paused.json：任一成员曾暂停，则当前完整依赖组全部暂停。 */
    public int normalizePersistedPausedGroups() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            PauseService.IntentSnapshot current = PauseService.snapshotWithRevision();
            if (current.uuids().isEmpty()) return 0;
            OpKit.DependencySelection selection = this.kit.dependencyGroups(current.uuids());
            List<UUID> expanded = selection.members();
            Set<UUID> added = new LinkedHashSet<>(expanded);
            added.removeAll(current.uuids());
            if (added.isEmpty()) return 0;
            try {
                JsonObject migration = this.kit.onMain(() -> {
                    boolean applied = applyMigrationIfUnchanged(
                            current.revision(), PauseService::revision, () -> {
                            this.kit.requirePreparedDependencyGroupsOnMain(selection.components());
                            PauseService.applyOnMain(this.kit.server, expanded, true);
                            for (UUID uuid : expanded) {
                                ServerSubLevel body = this.kit.resolveLoaded(uuid);
                                if (body != null) SubLevelPhysicsSystem.get(body.getLevel())
                                        .getPipeline().resetVelocity(body);
                            }
                        });
                    JsonObject result = new JsonObject();
                    result.addProperty("applied", applied);
                    return result;
                });
                if (migration.get("applied").getAsBoolean()) return added.size();
            } finally {
                PauseService.persist();
            }
        }
        throw new IllegalStateException("旧暂停状态迁移期间持续发生变化");
    }

    private List<ServerSubLevel> requireLoadedGroup(Collection<UUID> uuids) {
        List<ServerSubLevel> bodies = new ArrayList<>(uuids.size());
        Set<UUID> missing = new LinkedHashSet<>();
        for (UUID uuid : uuids) {
            ServerSubLevel body = this.kit.resolveLoaded(uuid);
            if (body == null) missing.add(uuid);
            else bodies.add(body);
        }
        if (!missing.isEmpty()) throw new IllegalStateException("物理结构组未完整加载，请先常驻加载: " + missing);
        return bodies;
    }

    private void pauseGroupAndStop(Collection<UUID> uuids, List<ServerSubLevel> bodies) {
        Set<UUID> originallyPaused = new LinkedHashSet<>();
        for (UUID uuid : uuids) if (PauseService.isPaused(uuid)) originallyPaused.add(uuid);
        List<BodyVelocity> velocities = new ArrayList<>(bodies.size());
        for (ServerSubLevel body : bodies) {
            var pipeline = SubLevelPhysicsSystem.get(body.getLevel()).getPipeline();
            velocities.add(new BodyVelocity(body,
                    pipeline.getLinearVelocity(body, new Vector3d()),
                    pipeline.getAngularVelocity(body, new Vector3d())));
        }
        pauseAndStop(() -> PauseService.applyOnMain(this.kit.server, uuids, true),
                () -> bodies.forEach(body -> SubLevelPhysicsSystem.get(body.getLevel())
                        .getPipeline().resetVelocity(body)),
                () -> rollbackPauseOnMain(uuids, originallyPaused, velocities));
    }

    private void rollbackPauseOnMain(Collection<UUID> uuids, Set<UUID> originallyPaused,
                                     List<BodyVelocity> velocities) {
        List<Throwable> failures = new ArrayList<>();
        for (BodyVelocity velocity : velocities) {
            attemptRollback(failures, () -> {
                var pipeline = SubLevelPhysicsSystem.get(velocity.body().getLevel()).getPipeline();
                pipeline.resetVelocity(velocity.body());
                pipeline.addLinearAndAngularVelocity(
                        velocity.body(), velocity.linear(), velocity.angular());
            });
        }
        Set<UUID> newlyPaused = new LinkedHashSet<>(uuids);
        newlyPaused.removeAll(originallyPaused);
        if (!newlyPaused.isEmpty()) {
            attemptRollback(failures,
                    () -> PauseService.applyOnMain(this.kit.server, newlyPaused, false));
        }
        if (!failures.isEmpty()) {
            IllegalStateException failure = new IllegalStateException("暂停物理失败后的状态恢复失败");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private void unloadGroupsOnMain(Collection<UUID> uuids, Set<ServerLevel> touched,
                                    Map<UUID, StoredSnapshot> expectedStored) {
        Set<UUID> pending = new LinkedHashSet<>(uuids);
        while (true) {
            ServerSubLevel anchor = null;
            for (UUID uuid : pending) {
                anchor = this.kit.resolveLoaded(uuid);
                if (anchor != null) break;
            }
            if (anchor == null) return;
            Collection<ServerSubLevel> chain = SubLevelHelper.getLoadingDependencyChain(anchor);
            Set<UUID> otherTickets = new LinkedHashSet<>();
            for (ServerSubLevel body : chain) {
                if (ForceLoadService.hasOtherTicketOnMain(this.kit.server, body.getUniqueId())) {
                    otherTickets.add(body.getUniqueId());
                }
            }
            if (!otherTickets.isEmpty()) {
                throw new IllegalStateException("依赖组存在其他模组的常驻票，未卸载: " + otherTickets);
            }
            pending.removeAll(chain.stream().map(ServerSubLevel::getUniqueId).toList());
            ServerLevel level = anchor.getLevel();
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) throw new IllegalStateException("物理体容器不存在");
            Vector3d position = new Vector3d(anchor.logicalPose().position());
            ChunkPos holdingChunk = new ChunkPos(BlockPos.containing(position.x, position.y, position.z));
            List<UUID> chainUuids = chain.stream().map(ServerSubLevel::getUniqueId).toList();
            for (ServerSubLevel body : chain) {
                expectedStored.put(body.getUniqueId(), new StoredSnapshot(
                        level.dimension().location().toString(), holdingChunk,
                        body.getLastSerializationPointer(),
                        SubLevelSerializer.toData(body, chainUuids).fullTag().copy()));
            }
            container.getHoldingChunkMap().moveToUnloaded(anchor, holdingChunk);
            for (ServerSubLevel body : chain) {
                var holding = container.getHoldingChunkMap().getHoldingSubLevel(body.getUniqueId());
                if (holding == null) throw new IllegalStateException("物理体卸载后未进入存档队列: "
                        + body.getUniqueId());
                if (!expectedStored.get(body.getUniqueId()).tag().equals(holding.data().fullTag())) {
                    throw new IllegalStateException("物理体卸载快照不一致: " + body.getUniqueId());
                }
            }
            touched.add(level);
        }
    }

    private void saveChangedMetadata(Set<String> dimensions) {
        for (ServerLevel level : this.kit.server.getAllLevels()) {
            if (dimensions.contains(level.dimension().location().toString())) level.getDataStorage().save();
        }
    }

    private void captureSavedEntriesOnMain(Map<UUID, StoredSnapshot> expectedStored,
                                           Map<UUID, DiskScanner.EntryKey> savedEntries) {
        for (ServerLevel level : this.kit.server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            String dimension = level.dimension().location().toString();
            for (var entry : expectedStored.entrySet()) {
                UUID uuid = entry.getKey();
                if (!dimension.equals(entry.getValue().dimension())) continue;
                var holding = container.getHoldingChunkMap().getHoldingSubLevel(uuid);
                ServerSubLevel loaded = OpKit.loadedBody(container, uuid);
                GlobalSavedSubLevelPointer pointer = savedPointer(
                        holding == null ? null : holding.pointer(),
                        loaded == null ? null : loaded.getLastSerializationPointer());
                if (pointer != null) {
                    savedEntries.put(uuid, OpKit.entryKey(dimension, pointer));
                }
            }
        }
    }

    private void verifyUnforcedOnMain(Collection<UUID> uuids) {
        Set<UUID> failed = new LinkedHashSet<>();
        for (UUID uuid : uuids) {
            if (!unforceStateValid(this.kit.resolveLoaded(uuid) != null,
                    ForceLoadService.isForcedOnMain(this.kit.server, uuid),
                    PauseService.isPaused(uuid), FreezeService.isFrozen(uuid))) failed.add(uuid);
        }
        if (!failed.isEmpty()) throw new IllegalStateException("取消常驻状态复核失败: " + failed);
    }

    static boolean unforceStateValid(boolean loaded, boolean forced, boolean paused, boolean frozen) {
        return !forced && !paused && !frozen;
    }

    static <T> T savedPointer(T holdingPointer, T loadedPointer) {
        return holdingPointer != null ? holdingPointer : loadedPointer;
    }

    private void rollbackUnforceOnMain(Collection<UUID> uuids, Set<UUID> originalPaused,
                                       Set<UUID> originalFrozen,
                                       Map<UUID, Set<String>> originalTickets,
                                       Map<UUID, StoredSnapshot> stored,
                                       Map<UUID, DiskScanner.EntryKey> savedEntries) {
        List<Throwable> failures = new ArrayList<>();
        Set<String> restoredTicketDimensions = new LinkedHashSet<>();
        Set<ServerLevel> reloadedLevels = new LinkedHashSet<>();
        attemptRollback(failures, () -> restoredTicketDimensions.addAll(
                ForceLoadService.restorePanelTicketsOnMain(this.kit.server, originalTickets)));
        attemptRollback(failures, () -> reloadedLevels.addAll(reloadStoredOnMain(stored, savedEntries)));
        attemptRollback(failures, () -> FreezeService.applyOnMain(uuids, false));
        if (!originalFrozen.isEmpty()) {
            attemptRollback(failures, () -> FreezeService.applyOnMain(originalFrozen, true));
        }
        attemptRollback(failures, () -> PauseService.applyOnMain(this.kit.server, uuids, false));
        if (!originalPaused.isEmpty()) {
            attemptRollback(failures,
                    () -> PauseService.applyOnMain(this.kit.server, originalPaused, true));
        }
        attemptRollback(failures, () -> OpKit.saveAllLevels(reloadedLevels));
        attemptRollback(failures, () -> saveChangedMetadata(restoredTicketDimensions));
        attemptRollback(failures,
                () -> verifyRollbackState(uuids, originalPaused, originalFrozen,
                        originalTickets, stored));
        if (!failures.isEmpty()) {
            IllegalStateException rollbackFailure = new IllegalStateException("取消常驻失败后的状态恢复失败");
            failures.forEach(rollbackFailure::addSuppressed);
            throw rollbackFailure;
        }
    }

    private static void attemptRollback(List<Throwable> failures, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private Set<ServerLevel> reloadStoredOnMain(Map<UUID, StoredSnapshot> stored,
                                                Map<UUID, DiskScanner.EntryKey> savedEntries) {
        Set<ServerLevel> touched = new LinkedHashSet<>();
        for (var entry : stored.entrySet()) {
            StoredSnapshot snapshot = entry.getValue();
            ServerLevel level = this.kit.levelOf(snapshot.dimension());
            ServerSubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
            if (container == null) throw new IllegalStateException("无法恢复卸载体的容器: " + entry.getKey());
            ServerSubLevel existing = this.kit.resolveLoaded(entry.getKey());
            if (existing != null) container.removeSubLevel(existing, SubLevelRemovalReason.UNLOADED);
            DiskScanner.EntryKey saved = savedEntries.get(entry.getKey());
            GlobalSavedSubLevelPointer pointer = saved == null ? snapshot.pointer()
                    : new GlobalSavedSubLevelPointer(snapshot.holdingChunk(),
                    (short) saved.storage(), (short) saved.index());
            restoreExactSnapshot(snapshot.tag(), tag -> {
                SubLevelData data = SubLevelSerializer.fromData(tag);
                if (data == null || !entry.getKey().equals(data.uuid())) {
                    throw new IllegalStateException("取消常驻失败后快照无法解析: " + entry.getKey());
                }
                ServerSubLevel restored = SubLevelSerializer.fullyLoad(level, data);
                if (restored == null) {
                    throw new IllegalStateException("取消常驻失败后 Sable 拒绝恢复: " + entry.getKey());
                }
                restored.setLastSerializationPointer(pointer);
                restoreSnapshotVelocity(restored, tag);
                return restored;
            }, body -> serializeSnapshot(body, snapshot));
            touched.add(level);
        }
        Set<UUID> missing = new LinkedHashSet<>();
        for (UUID uuid : stored.keySet()) if (this.kit.resolveLoaded(uuid) == null) missing.add(uuid);
        if (!missing.isEmpty()) throw new IllegalStateException("取消常驻失败后无法重新加载原结构: " + missing);
        return touched;
    }

    private void verifyRollbackState(Collection<UUID> uuids, Set<UUID> originalPaused,
                                     Set<UUID> originalFrozen,
                                     Map<UUID, Set<String>> originalTickets,
                                     Map<UUID, StoredSnapshot> originallyLoaded) {
        Set<UUID> failed = new LinkedHashSet<>();
        Map<UUID, Set<String>> tickets = ForceLoadService.panelTicketDimensionsOnMain(this.kit.server, uuids);
        for (UUID uuid : uuids) {
            if (PauseService.isPaused(uuid) != originalPaused.contains(uuid)
                    || FreezeService.isFrozen(uuid) != originalFrozen.contains(uuid)
                    || !tickets.getOrDefault(uuid, Set.of()).equals(originalTickets.getOrDefault(uuid, Set.of()))
                    || originallyLoaded.containsKey(uuid) && this.kit.resolveLoaded(uuid) == null) {
                failed.add(uuid);
            }
        }
        for (var entry : originallyLoaded.entrySet()) {
            ServerSubLevel body = this.kit.resolveLoaded(entry.getKey());
            if (body != null && !entry.getValue().tag().equals(serializeSnapshot(body, entry.getValue()))) {
                failed.add(entry.getKey());
            }
        }
        if (!failed.isEmpty()) throw new IllegalStateException("取消常驻失败后原状态恢复不完整: " + failed);
    }

    private static CompoundTag serializeSnapshot(ServerSubLevel body, StoredSnapshot snapshot) {
        SubLevelData data = SubLevelSerializer.fromData(snapshot.tag());
        if (data == null) throw new IllegalStateException("无法解析操作前快照: " + body.getUniqueId());
        return SubLevelSerializer.toData(body, data.dependencies()).fullTag();
    }

    private static void restoreSnapshotVelocity(ServerSubLevel body, CompoundTag tag) {
        Vector3d linear = tag.contains("linear_velocity")
                ? SableNBTUtils.readVector3d(tag.getCompound("linear_velocity")) : new Vector3d();
        Vector3d angular = tag.contains("angular_velocity")
                ? SableNBTUtils.readVector3d(tag.getCompound("angular_velocity")) : new Vector3d();
        var pipeline = SubLevelPhysicsSystem.get(body.getLevel()).getPipeline();
        pipeline.resetVelocity(body);
        pipeline.addLinearAndAngularVelocity(body, linear, angular);
    }

    static <T> T restoreExactSnapshot(CompoundTag expected, Function<CompoundTag, T> loader,
                                      Function<T, CompoundTag> serializer) {
        T restored = loader.apply(expected.copy());
        CompoundTag actual = serializer.apply(restored);
        if (!expected.equals(actual)) throw new IllegalStateException("操作前快照恢复后内容不一致");
        return restored;
    }

    static boolean applyMigrationIfUnchanged(long expectedRevision, LongSupplier currentRevision,
                                             Runnable apply) {
        if (currentRevision.getAsLong() != expectedRevision) return false;
        apply.run();
        return true;
    }

    static void pauseAndStop(Runnable lock, Runnable resetVelocity, Runnable rollback) {
        try {
            lock.run();
            resetVelocity.run();
        } catch (Throwable failure) {
            try {
                rollback.run();
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("暂停物理失败", failure);
        }
    }

    record UnforceActions(Runnable clearPanelState, Runnable unload, Runnable save,
                          Runnable removeTickets, Runnable verify) {
    }

    private record StoredSnapshot(String dimension, ChunkPos holdingChunk,
                                  GlobalSavedSubLevelPointer pointer, CompoundTag tag) {
    }

    private record BodyVelocity(ServerSubLevel body, Vector3d linear, Vector3d angular) {
    }

    static void finishUnforce(UnforceActions actions) {
        actions.clearPanelState().run();
        actions.unload().run();
        actions.save().run();
        actions.removeTickets().run();
        actions.verify().run();
    }

    static void finishMove(Runnable teleport, Runnable resetVelocity, Runnable updatePoseAndBounds) {
        teleport.run();
        resetVelocity.run();
        updatePoseAndBounds.run();
    }

    private static void updatePoseAndBounds(ServerSubLevel sl) {
        updatePoseAndBounds(sl::updateLastPose, sl::updateBoundingBox, sl::forceUpdateGlobalBounds);
    }

    static void updatePoseAndBounds(Runnable updateLastPose, Runnable updateBoundingBox, Runnable syncLastBounds) {
        updateLastPose.run();
        updateBoundingBox.run();
        syncLastBounds.run();
    }

    private static void requirePosition(ServerSubLevel sl, double x, double y, double z) {
        Vector3d actual = bottomCenter(sl.boundingBox());
        if (Math.abs(actual.x - x) > POSITION_EPSILON || Math.abs(actual.y - y) > POSITION_EPSILON
                || Math.abs(actual.z - z) > POSITION_EPSILON) {
            throw new IllegalStateException("传送位置复核失败: " + actual.x + "," + actual.y + "," + actual.z);
        }
    }

    static Vector3d bottomCenter(BoundingBox3dc bounds) {
        double[] values = {bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ()};
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalStateException("物理体包围盒坐标无效");
        }
        if (bounds.maxX() < bounds.minX() || bounds.maxY() < bounds.minY()
                || bounds.maxZ() < bounds.minZ()) {
            throw new IllegalStateException("物理体包围盒范围无效");
        }
        return new Vector3d((bounds.minX() + bounds.maxX()) / 2, bounds.minY(),
                (bounds.minZ() + bounds.maxZ()) / 2);
    }

    private static void requireFiniteTarget(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("传送目标坐标无效");
        }
    }

    /**
     * 整维度停跑/恢复物理。审计记在维度上,没有 uuid —— 这一下影响的是所有人的船。
     */
    public JsonObject setDimensionPhysics(String dim, boolean paused) throws Exception {
        this.kit.onMain(() -> {
            PhysicsService.applyOnMain(this.kit.server, dim, paused);
            return null;
        });
        PhysicsService.persist();
        this.kit.audit(paused ? "dim_physics_pause" : "dim_physics_resume", null, dim, null);
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("dim", dim);
        out.addProperty("paused", paused);
        return out;
    }

    /** 在线玩家列表(主线程读取,给"传送玩家"下拉用) */
    public JsonObject listPlayers() throws Exception {
        return this.kit.onMain(() -> {
            JsonArray arr = new JsonArray();
            for (var player : this.kit.server.getPlayerList().getPlayers()) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", player.getUUID().toString());
                o.addProperty("name", player.getGameProfile().getName());
                o.addProperty("dim", player.serverLevel().dimension().location().toString());
                arr.add(o);
            }
            JsonObject out = new JsonObject();
            out.add("players", arr);
            return out;
        });
    }

    /**
     * 把在线玩家传到目标物理结构上(包围盒顶面中心,跨维度可用);体未加载先按链强制加载。
     * <p>
     * <b>落点必须在结构内</b>:sable 的 {@code PhysicsChunkTicketManager} 只在
     * "玩家碰撞箱中心落进包围盒扩 1.0 的保护盒"时才豁免卸载
     * ({@code sub_levels_with_players_cannot_unload})。玩家中心比脚高 0.9,
     * 落在顶面(y=maxY)时中心为 maxY+0.9,仍在 maxY+1.0 的保护盒内;
     * 旧实现落在 maxY+1 则中心 maxY+1.9 已出界 —— 人还没到,体就被卸载了。
     * 不取包围盒正中心是因为那里通常是结构实心处,会把玩家闷在方块里。
     */
    public JsonObject teleportPlayer(UUID uuid, UUID playerUuid) throws Exception {
        Map<UUID, OpKit.MemberPlan> chain = this.kit.prepareChain(uuid);
        return this.kit.onMain(() -> {
            var player = this.kit.server.getPlayerList().getPlayer(playerUuid);
            if (player == null) throw new IllegalStateException("玩家不在线");
            ServerSubLevel sl = this.kit.ensureLoaded(uuid, chain);
            ServerLevel level = sl.getLevel();
            double x, y, z;
            var bb = sl.boundingBox();
            if (bb.maxX() >= bb.minX() && Double.isFinite(bb.minX()) && Double.isFinite(bb.maxY())) {
                x = (bb.minX() + bb.maxX()) / 2;
                y = bb.maxY();
                z = (bb.minZ() + bb.maxZ()) / 2;
            } else {
                var p = sl.logicalPose().position();
                x = p.x();
                y = p.y() + 2;
                z = p.z();
            }
            player.teleportTo(level, x, y, z, player.getYRot(), player.getXRot());
            this.kit.audit("teleport_player", uuid, sl.getName(),
                    player.getGameProfile().getName() + " -> " + x + "," + y + "," + z);
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("player", player.getGameProfile().getName());
            r.addProperty("dim", level.dimension().location().toString());
            return r;
        });
    }
}
