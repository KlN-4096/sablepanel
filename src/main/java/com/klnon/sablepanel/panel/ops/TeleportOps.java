package com.klnon.sablepanel.panel.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import com.klnon.sablepanel.panel.storage.ScanSession;
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
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
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
import java.util.function.Supplier;

/** 运行态操作:传送/暂停/常驻加载/在线玩家交互。sable 交互全部主线程执行。 */
public final class TeleportOps {
    private static final double POSITION_EPSILON = 0.1;
    private static final int POSITION_CORRECTION_ATTEMPTS = 3;
    private static final int FORCE_LOAD_QUIET_TICKS = 5;
    private static final int FORCE_LOAD_MAX_OBSERVED_TICKS = 100;
    private static final long FORCE_LOAD_POLL_MILLIS = 50;
    private final OpKit kit;

    record ForceTicketPlan(Set<UUID> keep, Set<UUID> release) {
    }

    record TicketRef(UUID uuid, String dimension) {
    }

    record RuntimeObservation(int tick, Set<UUID> members) {
    }

    @FunctionalInterface
    interface RuntimeSampler {
        RuntimeObservation sample() throws Exception;
    }

    @FunctionalInterface
    interface WaitStep {
        void await() throws InterruptedException;
    }

    @FunctionalInterface
    interface TicketOperation {
        void apply(TicketRef ticket) throws Exception;
    }

    @FunctionalInterface
    interface TicketCheck {
        boolean test(TicketRef ticket) throws Exception;
    }

    @FunctionalInterface
    interface PositionMover {
        void move(Vector3d target);
    }

    TeleportOps(OpKit kit) {
        this.kit = kit;
    }

    public JsonObject teleport(UUID uuid, double x, double y, double z) throws Exception {
        Map<UUID, OpKit.MemberPlan> chain = this.kit.prepareChain(uuid);
        JsonObject result = this.kit.onMainUntilComplete(() -> {
            ServerSubLevel sl = this.kit.ensureLoaded(uuid, chain);
            ServerLevel level = sl.getLevel();
            SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
            // 面板坐标语义 = 包围盒底面中心。pose 原点与几何差一个 plot 偏移,
            // 直接设 pose 会让结构落点偏移十几格;按当前锚点差换算回 pose 再传送。
            requireFiniteTarget(x, y, z);
            Vector3d sourceAnchor = bottomCenter(sl.boundingBox());
            var pipeline = phys.getPipeline();
            Quaterniond orientation = new Quaterniond(sl.logicalPose().orientation());
            PauseService.moveOnMain(sl,
                    () -> {
                        alignBottomCenter(() -> new Vector3d(sl.logicalPose().position()),
                                () -> bottomCenter(sl.boundingBox()),
                                target -> finishMove(() -> pipeline.teleport(sl, target, orientation),
                                        () -> pipeline.resetVelocity(sl), () -> updatePoseAndBounds(sl)),
                                new Vector3d(x, y, z));
                        persistLoadedBody(sl, x, y, z);
                    },
                    () -> {
                        alignBottomCenter(() -> new Vector3d(sl.logicalPose().position()),
                                () -> bottomCenter(sl.boundingBox()),
                                target -> finishMove(() -> pipeline.teleport(sl, target, orientation),
                                        () -> pipeline.resetVelocity(sl), () -> updatePoseAndBounds(sl)),
                                sourceAnchor);
                        persistLoadedBody(sl, sourceAnchor.x, sourceAnchor.y, sourceAnchor.z);
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

    private static void persistLoadedBody(ServerSubLevel body, double x, double y, double z) {
        ServerLevel level = body.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("物理体容器不存在");
        GlobalSavedSubLevelPointer pointer = body.getLastSerializationPointer();
        if (pointer == null) throw new IllegalStateException("传送保存前缺少活动磁盘条目");
        List<UUID> dependencies = new ArrayList<>();
        for (ServerSubLevel member : SubLevelHelper.getLoadingDependencyChain(body)) {
            dependencies.add(member.getUniqueId());
        }
        SubLevelData data = SubLevelSerializer.toData(body, dependencies);
        SubLevelStorage storage = container.getHoldingChunkMap().getStorage();
        DiskScanner.EntryKey key = OpKit.entryKey(level.dimension().location().toString(), pointer);
        finishPersistence(() -> {
            storage.attemptSaveSubLevel(pointer, data);
            try {
                storage.flush();
            } catch (java.io.IOException error) {
                throw new IllegalStateException("传送磁盘写入失败", error);
            }
        }, () -> {
            CompoundTag stored = OpKit.readVerified(DiskScanner.sublevelDirs(level.getServer()),
                    body.getUniqueId(), key);
            DiskScanner.DiskEntry summary = stored == null ? null : DiskScanner.summarize(key, stored);
            if (summary == null || !positionMatches(summary.pos(), x, y, z)) {
                throw new IllegalStateException("传送磁盘复核失败: " + key.id());
            }
        });
    }

    static void finishPersistence(Runnable persist, Runnable verify) {
        persist.run();
        verify.run();
    }

    static boolean positionMatches(double[] position, double x, double y, double z) {
        return position != null && position.length == 3
                && Math.abs(position[0] - x) <= POSITION_EPSILON
                && Math.abs(position[1] - y) <= POSITION_EPSILON
                && Math.abs(position[2] - z) <= POSITION_EPSILON;
    }

    static boolean entryPositionMatches(String expectedEntry, DiskScanner.EntryKey entry,
                                        double[] position, double x, double y, double z) {
        return expectedEntry != null && expectedEntry.equals(entry.id()) && positionMatches(position, x, y, z);
    }

    /** 整组物理暂停/恢复；暂停成功后清除组内全部线速度和角速度。 */
    public JsonObject setPaused(List<UUID> requested, boolean paused) throws Exception {
        List<UUID> uuids;
        try {
            if (paused) {
                uuids = this.kit.onMain(() -> {
                    OpKit.DependencySelection selection = this.kit.loadedDependencyGroupsOnMain(requested, true);
                    List<UUID> members = selection.members();
                    List<ServerSubLevel> bodies = requireLoadedGroup(members);
                    pauseGroupAndStop(members, bodies);
                    return members;
                });
            } else {
                OpKit.DependencySelection selection = this.kit.dependencyGroups(requested);
                uuids = selection.members();
                this.kit.onMain(() -> {
                    this.kit.requirePreparedDependencyGroupsOnMain(selection);
                    PauseService.applyOnMain(this.kit.server, uuids, false);
                    return null;
                });
            }
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
        synchronized (this.kit.lock) {
            try {
                return setForcedExclusive(requested, forced);
            } finally {
                ForceLoadService.persist();
            }
        }
    }

    public void restoreForcedIntents(List<UUID> candidates) throws Exception {
        restoreForcedIntentGroups(this.kit.lock, this.kit.forceLoadIntentGroups(candidates),
                ForceLoadService::requestedSnapshot,
                current -> requireForcedGroupsSucceeded(setForcedExclusive(current, true)),
                ForceLoadService::persist);
    }

    static void restoreForcedIntents(Object lock, Collection<UUID> candidates,
                                     Supplier<Set<UUID>> requested, ForcedIntentRestorer restore,
                                     Runnable persist) throws Exception {
        restoreForcedIntentGroups(lock, List.of(List.copyOf(candidates)), requested, restore, persist);
    }

    static void restoreForcedIntentGroups(Object lock, Collection<List<UUID>> groups,
                                          Supplier<Set<UUID>> requested,
                                          ForcedIntentRestorer restore, Runnable persist) throws Exception {
        synchronized (lock) {
            try {
                List<Throwable> failures = new ArrayList<>();
                for (List<UUID> group : groups) {
                    List<UUID> current = requestedRestoreCandidates(group, requested.get());
                    if (current.isEmpty()) continue;
                    try {
                        restore.run(current);
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                }
                if (!failures.isEmpty()) {
                    IllegalStateException failure = new IllegalStateException(
                            failures.size() + " 个常驻意图组恢复失败");
                    failures.forEach(failure::addSuppressed);
                    throw failure;
                }
            } finally {
                persist.run();
            }
        }
    }

    static List<UUID> requestedRestoreCandidates(Collection<UUID> candidates, Set<UUID> requested) {
        return candidates.stream().filter(requested::contains).distinct().toList();
    }

    @FunctionalInterface
    interface ForcedIntentRestorer {
        void run(List<UUID> requested) throws Exception;
    }

    private JsonObject setForcedExclusive(List<UUID> requested, boolean forced) throws Exception {
        // 常驻加载必须整组。只钉一部分是无效操作:PhysicsChunkTicketManager 按整条依赖链判定卸载,
        // 2026-08-08 实测给 192 体组里的一个成员挂票,体加载出来 827 毫秒后照样 remove UNLOADED,
        // 而作业还报 ok。挂票和摘票必须保持相同的整组语义。
        Set<UUID> forcedSnapshot = forced ? Set.of()
                : this.kit.onMainUntilComplete(() -> ForceLoadService.forcedOnMain(this.kit.server));
        OpKit.DependencySelection selection = forced
                ? this.kit.forceLoadCandidates(requested)
                : this.kit.forcedDisableGroups(requested, forcedSnapshot);
        // 但整组语义只到组边界为止,依赖组之间是独立事务。2026-08-22 job#15:取消常驻 117 体一单,
        // 一个 4 体组在快照与复核的窗口里长出新成员,其余 113 个体全部连坐失败,重试才整单通过。
        // 每组各自成败、失败原因逐组带回;单组失败保持原始异常语义(单体按钮/自动修复靠它导流)。
        List<UUID> succeeded = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Exception firstFailure = null;
        for (Set<UUID> component : selection.components()) {
            OpKit.DependencySelection group = componentSelection(selection, component);
            try {
                List<UUID> members;
                if (forced) {
                    members = enableForceLoad(group);
                } else {
                    // 每组动手前取新的全局票快照:上一组刚摘掉的票不能被当成"操作期间被人动了票"
                    Set<UUID> snapshot = this.kit.onMainUntilComplete(
                            () -> ForceLoadService.forcedOnMain(this.kit.server));
                    disableForceLoad(group, snapshot);
                    members = group.members();
                }
                succeeded.addAll(members);
                for (UUID uuid : members) {
                    this.kit.audit(forced ? "force_load" : "force_unload", uuid, null, null);
                }
            } catch (Exception failure) {
                if (firstFailure == null) firstFailure = failure;
                failures.add("组[" + OpKit.shortUuids(component) + "]: "
                        + String.valueOf(failure.getMessage()));
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (selection.components().size() == 1 && firstFailure != null) throw firstFailure;
        return forcedBatchResponse(forced, selection.components().size(), requested.size(),
                succeeded, failures);
    }

    /** 单组事务的取材:该组的成员/根/(常驻加载时的)条目计划,共享同一次候选决议 */
    static OpKit.DependencySelection componentSelection(OpKit.DependencySelection all,
                                                        Set<UUID> component) {
        List<UUID> roots = all.roots().stream().filter(component::contains).toList();
        return new OpKit.DependencySelection(roots, List.copyOf(component),
                List.of(component), all.plans());
    }

    /** 终态契约(JobService.outcomeOf):ok 布尔 + failed 数组 → 全成/部分/全败;失败明细同发 warnings 供作业详情展示 */
    static JsonObject forcedBatchResponse(boolean forced, int groups, int requested,
                                          List<UUID> succeeded, List<String> failures) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", failures.isEmpty() || !succeeded.isEmpty());
        out.addProperty("forced", forced);
        out.addProperty("count", succeeded.size());
        out.addProperty("groups", groups);
        out.addProperty("requested", requested);
        JsonArray failed = new JsonArray();
        for (String failure : failures) failed.add(failure);
        out.add("failed", failed);
        OpKit.attachWarnings(out, failures);
        return out;
    }

    /** 周期恢复的失败闩靠异常:分组部分失败必须在这里升格抛出,否则失败组每 30 秒被无谓重扫重试 */
    static void requireForcedGroupsSucceeded(JsonObject response) {
        if (response.getAsJsonArray("failed").isEmpty()) return;
        throw new IllegalStateException("部分常驻组恢复失败: " + response.getAsJsonArray("failed"));
    }

    private List<UUID> enableForceLoad(OpKit.DependencySelection selection) throws Exception {
        List<UUID> uuids = selection.members();
        Map<UUID, OpKit.MemberPlan> plans = selection.plans();
        List<UUID> anchors = forceLoadAnchors(selection);
        // ThreadLocal 到不了主线程,先在作业线程上取出来捕获进 lambda
        JobService.Job job = JobService.current();
        List<TicketRef> newlyTicketed = new ArrayList<>();
        try {
            this.kit.onMainUntilComplete(() -> {
                JsonArray failed = new JsonArray();
                for (UUID uuid : uuids) {
                    if (job != null) job.phase("挂常驻票");
                    try {
                        ServerSubLevel body = this.kit.ensureLoaded(uuid, plans);
                        boolean alreadyForced = ForceLoadService.isForcedOnMain(body);
                        ForceLoadService.addOnMain(body);
                        if (!alreadyForced) newlyTicketed.add(ticketRef(body));
                    } catch (Throwable t) {
                        JsonObject f = new JsonObject();
                        f.addProperty("uuid", uuid.toString());
                        f.addProperty("error", String.valueOf(t.getMessage()));
                        failed.add(f);
                    }
                }
                if (!failed.isEmpty()) throw new IllegalStateException("常驻加载失败: " + failed);
                return null;
            });

            if (job != null) {
                job.phase("确认当前运行组");
                job.detail("等待依赖关系稳定");
            }
            Set<UUID> observedMembers = awaitSettledRuntimeMembers(
                    () -> this.kit.onMainUntilComplete(() -> observeAndTicketOnMain(anchors, newlyTicketed)),
                    () -> Thread.sleep(FORCE_LOAD_POLL_MILLIS),
                    FORCE_LOAD_QUIET_TICKS, FORCE_LOAD_MAX_OBSERVED_TICKS);

            Set<UUID> activeMembers = this.kit.onMainUntilComplete(() -> {
                Set<UUID> keep = settledForceMembers(
                        observedMembers, this.kit.loadedDependencyMembersOnMain(anchors));
                ForceTicketPlan plan = forceTicketPlan(
                        newlyTicketed.stream().map(TicketRef::uuid).toList(), keep);
                for (UUID uuid : plan.keep()) {
                    ServerSubLevel body = this.kit.resolveLoaded(uuid);
                    if (body == null) throw new IllegalStateException("当前运行组成员已卸载: " + uuid);
                    if (!ForceLoadService.isForcedOnMain(body)) {
                        ForceLoadService.addOnMain(body);
                        newlyTicketed.add(ticketRef(body));
                    }
                }
                for (var iterator = newlyTicketed.iterator(); iterator.hasNext();) {
                    TicketRef ticket = iterator.next();
                    if (!plan.release().contains(ticket.uuid())) continue;
                    ForceLoadService.removeStrictOnMain(
                            this.kit.server, ticket.dimension(), ticket.uuid());
                    iterator.remove();
                }
                Set<UUID> verified = this.kit.loadedDependencyMembersOnMain(anchors);
                if (!keep.containsAll(verified)) {
                    throw new IllegalStateException("常驻票收敛后出现未观察到的运行成员");
                }
                return Set.copyOf(keep);
            });
            return List.copyOf(activeMembers);
        } catch (Exception | Error failure) {
            try {
                this.kit.onMainUntilComplete(() -> {
                    rollbackNewTickets(List.copyOf(newlyTicketed),
                            ticket -> ForceLoadService.removeStrictOnMain(
                                    this.kit.server, ticket.dimension(), ticket.uuid()),
                            ticket -> ForceLoadService.isForcedOnMain(
                                    this.kit.server, ticket.dimension(), ticket.uuid()));
                    newlyTicketed.clear();
                    return null;
                });
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private RuntimeObservation observeAndTicketOnMain(Collection<UUID> anchors,
                                                       List<TicketRef> newlyTicketed) {
        Set<UUID> members = this.kit.loadedDependencyMembersOnMain(anchors);
        for (UUID uuid : members) {
            ServerSubLevel body = this.kit.resolveLoaded(uuid);
            if (body == null || ForceLoadService.isForcedOnMain(body)) continue;
            ForceLoadService.addOnMain(body);
            newlyTicketed.add(ticketRef(body));
        }
        return new RuntimeObservation(this.kit.server.getTickCount(), members);
    }

    static List<UUID> forceLoadAnchors(OpKit.DependencySelection selection) {
        List<UUID> anchors = new ArrayList<>();
        for (Set<UUID> component : selection.components()) {
            boolean found = false;
            for (UUID root : selection.roots()) {
                if (!component.contains(root)) continue;
                anchors.add(root);
                found = true;
            }
            if (!found) anchors.add(component.stream().min(UUID::compareTo).orElseThrow());
        }
        return List.copyOf(anchors);
    }

    static Set<UUID> settledForceMembers(Collection<UUID> observed, Collection<UUID> current) {
        Set<UUID> keep = new LinkedHashSet<>(observed);
        if (!keep.containsAll(current)) {
            throw new IllegalStateException("常驻票收敛后出现未观察到的运行成员");
        }
        return Set.copyOf(keep);
    }

    static ForceTicketPlan forceTicketPlan(Collection<UUID> provisional, Collection<UUID> runtime) {
        Set<UUID> keep = Set.copyOf(runtime);
        if (keep.isEmpty()) throw new IllegalStateException("当前运行依赖组为空");
        Set<UUID> release = new LinkedHashSet<>(provisional);
        release.removeAll(keep);
        return new ForceTicketPlan(keep, Set.copyOf(release));
    }

    static Set<UUID> awaitSettledRuntimeMembers(RuntimeSampler sampler, WaitStep waitStep,
                                                int quietTicks, int maxObservedTicks) throws Exception {
        Set<UUID> observed = new LinkedHashSet<>();
        int lastTick = Integer.MIN_VALUE;
        int ticksWithoutNewMembers = 0;
        int observedTicks = 0;
        while (true) {
            RuntimeObservation current = sampler.sample();
            if (current.tick() != lastTick) {
                lastTick = current.tick();
                observedTicks++;
                if (observed.addAll(current.members())) ticksWithoutNewMembers = 0;
                else ticksWithoutNewMembers++;
                if (ticksWithoutNewMembers >= quietTicks) return Set.copyOf(observed);
                if (observedTicks >= maxObservedTicks) {
                    throw new IllegalStateException("当前运行依赖组在观察上限内仍出现新成员，未挂常驻票");
                }
            }
            try {
                waitStep.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
    }

    static void rollbackNewTickets(Collection<TicketRef> tickets, TicketOperation remove,
                                   TicketCheck remains) throws Exception {
        List<Throwable> failures = new ArrayList<>();
        for (TicketRef ticket : tickets) {
            try {
                remove.apply(ticket);
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        Set<TicketRef> residual = new LinkedHashSet<>();
        for (TicketRef ticket : tickets) {
            try {
                if (remains.test(ticket)) residual.add(ticket);
            } catch (Throwable failure) {
                failures.add(failure);
                residual.add(ticket);
            }
        }
        if (failures.isEmpty() && residual.isEmpty()) return;
        IllegalStateException rollbackFailure = new IllegalStateException(
                "常驻加载失败后的新增票回滚不完整: " + residual);
        failures.forEach(rollbackFailure::addSuppressed);
        throw rollbackFailure;
    }

    private static TicketRef ticketRef(ServerSubLevel body) {
        return new TicketRef(body.getUniqueId(), body.getLevel().dimension().location().toString());
    }

    private JsonObject disableForceLoad(OpKit.DependencySelection selection,
                                        Set<UUID> forcedSnapshot) throws Exception {
        List<UUID> uuids = selection.members();
        Map<UUID, Set<String>> originalTickets = new LinkedHashMap<>();
        Map<UUID, StoredSnapshot> expectedStored = new LinkedHashMap<>();
        Map<UUID, DiskScanner.EntryKey> savedEntries = new LinkedHashMap<>();
        try {
            JsonObject out = this.kit.onMainUntilComplete(() -> {
                requireForcedTicketSnapshot(forcedSnapshot, ForceLoadService.forcedOnMain(this.kit.server));
                Set<ServerLevel> touched = new LinkedHashSet<>();
                Set<String> changedMetadata = new LinkedHashSet<>();
                JobService.Job job = JobService.current();
                originalTickets.putAll(ForceLoadService.panelTicketDimensionsOnMain(this.kit.server, uuids));
                finishUnforce(new UnforceActions(
                        // 取消常驻只退出常驻本身:暂停/冻结意图刻意保留,体重新加载时由观察器按意图重新生效。
                        () -> {
                            if (job != null) job.phase("复核前置状态");
                            this.kit.requirePreparedDependencyGroupsOnMain(selection);
                            Set<UUID> otherTickets = new LinkedHashSet<>();
                            for (UUID uuid : uuids) {
                                if (ForceLoadService.hasOtherTicketOnMain(this.kit.server, uuid)) {
                                    otherTickets.add(uuid);
                                }
                            }
                            if (!otherTickets.isEmpty()) {
                                throw new IllegalStateException("存在其他模组的常驻票，未取消常驻: " + otherTickets);
                            }
                        },
                        () -> {
                            if (job != null) job.phase("卸载到存档");
                            unloadGroupsOnMain(selection, touched, expectedStored);
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
                        () -> {
                            verifyUnforcedOnMain(uuids);
                            Set<UUID> expectedRemaining = new LinkedHashSet<>(forcedSnapshot);
                            expectedRemaining.removeAll(uuids);
                            requireForcedTicketSnapshot(
                                    expectedRemaining, ForceLoadService.forcedOnMain(this.kit.server));
                        }));
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
                    rollbackUnforceOnMain(uuids, originalTickets, expectedStored, savedEntries);
                    return new JsonObject();
                });
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private void verifyStoredAfterUnforce(Collection<UUID> uuids,
                                          Map<UUID, StoredSnapshot> expectedStored,
                                          Map<UUID, DiskScanner.EntryKey> savedEntries) throws Exception {
        ScanSession scan = this.kit.freshScan(new ArrayList<>());
        Set<UUID> mismatched = new LinkedHashSet<>();
        for (var entry : expectedStored.entrySet()) {
            DiskScanner.EntryKey key = matchingStoredEntry(
                    scan, entry.getKey(), entry.getValue().storedTag(), savedEntries.get(entry.getKey()));
            if (key == null) mismatched.add(entry.getKey());
            else savedEntries.put(entry.getKey(), key);
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

    private static DiskScanner.EntryKey matchingStoredEntry(
            ScanSession scan, UUID uuid, CompoundTag expected, DiskScanner.EntryKey preferred) {
        Map<DiskScanner.EntryKey, CompoundTag> entries = new LinkedHashMap<>();
        for (DiskScanner.EntryMeta entry : scan.entriesOf(uuid)) {
            entries.put(entry.key(), OpKit.readVerified(scan.dims(), uuid, entry.key()));
        }
        return selectStoredEntry(expected, entries, preferred);
    }

    static DiskScanner.EntryKey selectStoredEntry(CompoundTag expected,
                                                   Map<DiskScanner.EntryKey, CompoundTag> entries,
                                                   DiskScanner.EntryKey preferred) {
        if (entries.isEmpty()) return null;
        List<DiskScanner.EntryKey> conflicts = entries.entrySet().stream()
                .filter(entry -> !storedEntryMatches(expected, entry.getValue()))
                .map(Map.Entry::getKey).toList();
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("取消常驻后存在内容冲突副本: "
                    + conflicts.stream().map(DiskScanner.EntryKey::id).toList());
        }
        if (preferred != null && entries.containsKey(preferred)) return preferred;
        return entries.keySet().stream()
                .min(java.util.Comparator.comparing(DiskScanner.EntryKey::id)).orElse(null);
    }

    static boolean storedEntryMatches(CompoundTag expected, CompoundTag actual) {
        if (expected == null || actual == null
                || !java.util.Objects.equals(OpKit.tagUuid(expected), OpKit.tagUuid(actual))) return false;
        if (!new LinkedHashSet<>(DiskScanner.dependencies(expected))
                .equals(new LinkedHashSet<>(DiskScanner.dependencies(actual)))) return false;
        CompoundTag expectedPlot = expected.getCompound("plot");
        CompoundTag actualPlot = actual.getCompound("plot");
        if (expectedPlot.getInt("plot_x") != actualPlot.getInt("plot_x")
                || expectedPlot.getInt("plot_z") != actualPlot.getInt("plot_z")) return false;
        return DiskScanner.countBlocks(expectedPlot, null)
                == DiskScanner.countBlocks(actualPlot, null);
    }

    /**
     * 用户可解冻单个组恢复它的 tick。会崩是常态,所以调用方(前端)必须先弹警告 ——
     * 后端只按体量给出 {@code heavy} 标记,拦不拦由用户决定(不设闸门是既定约定)。
     */
    public JsonObject setFrozen(List<UUID> requested, boolean frozen) throws Exception {
        List<UUID> uuids;
        if (frozen) {
            uuids = this.kit.onMain(() -> {
                List<UUID> members = this.kit.loadedDependencyGroupsOnMain(requested, true).members();
                requireLoadedGroup(members);
                FreezeService.applyOnMain(members, true);
                return members;
            });
        } else {
            OpKit.DependencySelection selection = this.kit.dependencyGroups(requested);
            uuids = selection.members();
            this.kit.onMain(() -> {
                this.kit.requirePreparedDependencyGroupsOnMain(selection);
                FreezeService.applyOnMain(uuids, false);
                return null;
            });
        }
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
                            this.kit.requirePreparedDependencyGroupsOnMain(selection);
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

    private void unloadGroupsOnMain(OpKit.DependencySelection selection, Set<ServerLevel> touched,
                                    Map<UUID, StoredSnapshot> expectedStored) {
        for (Set<UUID> component : selection.components()) {
            Map<UUID, Set<UUID>> runtimeGroups = new LinkedHashMap<>();
            for (UUID uuid : component) {
                if (this.kit.resolveLoaded(uuid) == null) continue;
                runtimeGroups.put(uuid, this.kit.loadedDependencyMembersOnMain(List.of(uuid)));
            }
            if (runtimeGroups.isEmpty()) continue;
            UUID anchorUuid = exactGroupAnchor(component, selection.roots(), runtimeGroups);
            ServerSubLevel anchor = this.kit.resolveLoaded(anchorUuid);
            if (anchor == null) throw new IllegalStateException("完整卸载锚点已卸载: " + anchorUuid);
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
            ServerLevel level = anchor.getLevel();
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) throw new IllegalStateException("物理体容器不存在");
            Vector3d position = new Vector3d(anchor.logicalPose().position());
            ChunkPos holdingChunk = new ChunkPos(BlockPos.containing(position.x, position.y, position.z));
            List<UUID> chainUuids = chain.stream().map(ServerSubLevel::getUniqueId).toList();
            for (ServerSubLevel body : chain) {
                CompoundTag captured = SubLevelSerializer.toData(body, chainUuids).fullTag().copy();
                expectedStored.put(body.getUniqueId(), new StoredSnapshot(
                        level.dimension().location().toString(), holdingChunk,
                        body.getLastSerializationPointer(), captured, captured));
            }
            container.getHoldingChunkMap().moveToUnloaded(anchor, holdingChunk);
            for (ServerSubLevel body : chain) {
                var holding = container.getHoldingChunkMap().getHoldingSubLevel(body.getUniqueId());
                if (holding == null) throw new IllegalStateException("物理体卸载后未进入存档队列: "
                        + body.getUniqueId());
                CompoundTag storedTag = holding.data().fullTag().copy();
                if (!unloadedSnapshotMatches(body.getUniqueId(), chainUuids, storedTag)) {
                    throw new IllegalStateException("物理体卸载快照不一致: " + body.getUniqueId());
                }
                expectedStored.computeIfPresent(body.getUniqueId(),
                        (ignored, snapshot) -> snapshot.withStoredTag(storedTag));
            }
            touched.add(level);
        }
    }

    static UUID exactGroupAnchor(Set<UUID> expected, Collection<UUID> preferred,
                                 Map<UUID, Set<UUID>> runtimeGroups) {
        LinkedHashSet<UUID> candidates = new LinkedHashSet<>();
        for (UUID uuid : preferred) if (expected.contains(uuid)) candidates.add(uuid);
        expected.stream().sorted().forEach(candidates::add);
        for (UUID uuid : candidates) {
            if (expected.equals(runtimeGroups.get(uuid))) return uuid;
        }
        throw new IllegalStateException("当前运行依赖组没有完整卸载锚点: " + expected);
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
                if (loaded != null) {
                    StoredSnapshot snapshot = entry.getValue();
                    SubLevelData data = SubLevelSerializer.fromData(snapshot.storedTag());
                    if (data == null) throw new IllegalStateException("无法解析卸载存档: " + uuid);
                    CompoundTag refreshed = SubLevelSerializer.toData(
                            loaded, data.dependencies()).fullTag().copy();
                    expectedStored.put(uuid, snapshot.withStoredTag(refreshed));
                }
                if (pointer != null) {
                    savedEntries.put(uuid, OpKit.entryKey(dimension, pointer));
                }
            }
        }
    }

    private void verifyUnforcedOnMain(Collection<UUID> uuids) {
        // 只复核常驻票本身;暂停/冻结意图属于独立功能,取消常驻不动它们(自然加载也不算失败)。
        Set<UUID> failed = new LinkedHashSet<>();
        for (UUID uuid : uuids) {
            if (ForceLoadService.isForcedOnMain(this.kit.server, uuid)) failed.add(uuid);
        }
        if (!failed.isEmpty()) throw new IllegalStateException("取消常驻状态复核失败: " + failed);
    }

    static void requireForcedTicketSnapshot(Set<UUID> expected, Set<UUID> actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("当前面板常驻票在操作期间发生变化: expected="
                    + expected + ", actual=" + actual);
        }
    }

    static <T> T savedPointer(T holdingPointer, T loadedPointer) {
        return holdingPointer != null ? holdingPointer : loadedPointer;
    }

    private void rollbackUnforceOnMain(Collection<UUID> uuids,
                                       Map<UUID, Set<String>> originalTickets,
                                       Map<UUID, StoredSnapshot> stored,
                                       Map<UUID, DiskScanner.EntryKey> savedEntries) {
        List<Throwable> failures = new ArrayList<>();
        Set<String> restoredTicketDimensions = new LinkedHashSet<>();
        Set<ServerLevel> reloadedLevels = new LinkedHashSet<>();
        attemptRollback(failures, () -> restoredTicketDimensions.addAll(
                ForceLoadService.restorePanelTicketsOnMain(this.kit.server, originalTickets)));
        attemptRollback(failures, () -> reloadedLevels.addAll(reloadStoredOnMain(stored, savedEntries)));
        attemptRollback(failures, () -> OpKit.saveAllLevels(reloadedLevels));
        attemptRollback(failures, () -> saveChangedMetadata(restoredTicketDimensions));
        attemptRollback(failures,
                () -> verifyRollbackState(uuids, originalTickets, stored));
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
            restoreExactSnapshot(snapshot.rollbackTag(), tag -> {
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

    private void verifyRollbackState(Collection<UUID> uuids,
                                     Map<UUID, Set<String>> originalTickets,
                                     Map<UUID, StoredSnapshot> originallyLoaded) {
        Set<UUID> failed = new LinkedHashSet<>();
        Map<UUID, Set<String>> tickets = ForceLoadService.panelTicketDimensionsOnMain(this.kit.server, uuids);
        for (UUID uuid : uuids) {
            if (!tickets.getOrDefault(uuid, Set.of()).equals(originalTickets.getOrDefault(uuid, Set.of()))
                    || originallyLoaded.containsKey(uuid) && this.kit.resolveLoaded(uuid) == null) {
                failed.add(uuid);
            }
        }
        for (var entry : originallyLoaded.entrySet()) {
            ServerSubLevel body = this.kit.resolveLoaded(entry.getKey());
            if (body != null && !storedSnapshotMatches(
                    entry.getValue().rollbackTag(), serializeSnapshot(body, entry.getValue()))) {
                failed.add(entry.getKey());
            }
        }
        if (!failed.isEmpty()) throw new IllegalStateException("取消常驻失败后原状态恢复不完整: " + failed);
    }

    private static CompoundTag serializeSnapshot(ServerSubLevel body, StoredSnapshot snapshot) {
        SubLevelData data = SubLevelSerializer.fromData(snapshot.rollbackTag());
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
        if (!storedSnapshotMatches(expected, actual)) {
            throw new IllegalStateException("操作前快照恢复后内容不一致");
        }
        return restored;
    }

    /** Sable 把依赖当集合使用，但每次 BFS 产出的列表顺序不稳定；其余 NBT 仍逐字段严格比较。 */
    static boolean storedSnapshotMatches(CompoundTag expected, CompoundTag actual) {
        if (expected == null || actual == null) return expected == actual;
        Set<String> keys = new LinkedHashSet<>(expected.getAllKeys());
        keys.addAll(actual.getAllKeys());
        keys.remove("loading_dependencies");
        for (String key : keys) {
            if (!java.util.Objects.equals(expected.get(key), actual.get(key))) return false;
        }
        return new LinkedHashSet<>(DiskScanner.dependencies(expected))
                .equals(new LinkedHashSet<>(DiskScanner.dependencies(actual)));
    }

    static boolean unloadedSnapshotMatches(UUID uuid, Collection<UUID> chain, CompoundTag actual) {
        if (actual == null || !uuid.equals(OpKit.tagUuid(actual))) return false;
        Set<UUID> expectedDependencies = new LinkedHashSet<>(chain);
        expectedDependencies.remove(uuid);
        return expectedDependencies.equals(new LinkedHashSet<>(DiskScanner.dependencies(actual)));
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
                                  GlobalSavedSubLevelPointer pointer,
                                  CompoundTag rollbackTag, CompoundTag storedTag) {
        StoredSnapshot withStoredTag(CompoundTag next) {
            return new StoredSnapshot(this.dimension, this.holdingChunk, this.pointer,
                    this.rollbackTag, next);
        }
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

    static void alignBottomCenter(Supplier<Vector3d> pose, Supplier<Vector3d> anchor,
                                  PositionMover move, Vector3d desired) {
        Vector3d actual = anchor.get();
        for (int attempt = 0; attempt < POSITION_CORRECTION_ATTEMPTS; attempt++) {
            Vector3d targetPose = pose.get().add(desired).sub(actual);
            move.move(targetPose);
            actual = anchor.get();
            if (positionMatches(new double[]{actual.x, actual.y, actual.z},
                    desired.x, desired.y, desired.z)) return;
        }
        throw new IllegalStateException("传送位置复核失败: " + actual.x + "," + actual.y + "," + actual.z);
    }

    private static void updatePoseAndBounds(ServerSubLevel sl) {
        updatePoseAndBounds(sl::updateLastPose, sl::updateBoundingBox, sl::forceUpdateGlobalBounds);
    }

    static void updatePoseAndBounds(Runnable updateLastPose, Runnable updateBoundingBox, Runnable syncLastBounds) {
        updateLastPose.run();
        updateBoundingBox.run();
        syncLastBounds.run();
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
