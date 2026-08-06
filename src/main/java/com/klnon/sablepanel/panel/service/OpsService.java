package com.klnon.sablepanel.panel.service;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;

import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.data.BodyIndex;
import com.klnon.sablepanel.panel.data.CopyVersionScanner;
import com.klnon.sablepanel.panel.data.DiskScanner;
import com.klnon.sablepanel.panel.data.RecycleStore;
import com.klnon.sablepanel.panel.data.MeshExtractor;
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
import java.util.concurrent.Callable;

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
    private final ConsistencyService consistency;

    public OpsService(MinecraftServer server, BodyIndex index, Runnable rescan, PanelConfig config) {
        this.server = server;
        this.index = index;
        this.rescan = rescan;
        this.recycle = new RecycleStore(config);
        this.consistency = new ConsistencyService(server);
    }

    public synchronized JsonObject analyzeConsistency(boolean startup) {
        return this.consistency.scan(startup);
    }

    public JsonObject consistencyView() {
        return this.consistency.view();
    }

    public synchronized JsonObject repairConsistency(String scanId, Set<String> pointers,
                                                     Set<UUID> forced, Set<UUID> paused) throws Exception {
        JsonObject out = this.consistency.repair(scanId, pointers, forced, paused);
        this.rescan.run();
        return out;
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

    private record CopyCandidate(DiskScanner.EntryKey key, CompoundTag tag, int blocks,
                                 List<DiskScanner.LiveLocation> pointers) {
    }

    private record CopyInspection(Map<String, Path> dimensions, List<CopyCandidate> copies,
                                  CopyCandidate keep, boolean identical) {
    }

    record CopyResolutionPlan(CopyVersionScanner.Version selected,
                              CopyVersionScanner.Version rollback) {
    }

    private record PreparedCopyResolution(DeleteComponent component, CopyVersionScanner.Scan scan,
                                          Map<UUID, RecycleStore.OperationalState> states) {
    }

    record CopySnapshot(Set<UUID> members,
                        Map<UUID, Map<DiskScanner.EntryKey, CompoundTag>> entries,
                        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers,
                        Map<UUID, String> active,
                        Map<UUID, RecycleStore.OperationalState> states) {
    }

    private static final class DeleteComponent {
        private final Set<UUID> targets = new LinkedHashSet<>();
        private final Map<UUID, List<DeleteCopy>> copies = new LinkedHashMap<>();
        private final Map<UUID, DeleteCopy> canonical = new LinkedHashMap<>();
        private final Map<UUID, RecycleStore.OperationalState> states = new LinkedHashMap<>();
        private Map<UUID, String> activeSnapshot;
        private RecycleStore.Stage stage;
        private boolean stateCleared;

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
            // 面板坐标语义 = 包围盒底面中心。pose 原点与几何差一个 plot 偏移,
            // 直接设 pose 会让结构落点偏移十几格;按当前锚点差换算回 pose 再传送。
            Vector3d target = new Vector3d(x, y, z);
            try {
                var bb = sl.boundingBox();
                double ax = (bb.minX() + bb.maxX()) / 2, ay = bb.minY(), az = (bb.minZ() + bb.maxZ()) / 2;
                var p = sl.logicalPose().position();
                if (Double.isFinite(ax) && Double.isFinite(ay) && Double.isFinite(az) && bb.maxX() >= bb.minX()) {
                    target.set(x + (p.x() - ax), y + (p.y() - ay), z + (p.z() - az));
                }
            } catch (Throwable ignored) {
            }
            sl.logicalPose().position().set(target.x, target.y, target.z);
            phys.getPipeline().teleport(sl, target, sl.logicalPose().orientation());
            sl.updateLastPose();
            // 暂停(约束锁定)中的体传送后在新位置重挂约束
            com.klnon.sablepanel.panel.service.PauseService.reanchor(sl);
            audit("teleport", uuid, sl.getName(), x + "," + y + "," + z);
            String dim = level.dimension().location().toString();
            this.index.updateRuntimePosition(uuid, dim, new double[]{x, y, z});
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("dim", dim);
            r.addProperty("x", x);
            r.addProperty("y", y);
            r.addProperty("z", z);
            return r;
        });
        this.rescan.run();
        return result;
    }

    /** 单体物理暂停/恢复 = 挂/拆引擎固定约束(同物理手杖锁定),持久化,重启后保持 */
    public JsonObject setPaused(List<UUID> uuids, boolean paused) throws Exception {
        onMain(() -> {
            com.klnon.sablepanel.panel.service.PauseService.applyOnMain(this.server, uuids, paused);
            return null;
        });
        for (UUID uuid : uuids) audit(paused ? "pause" : "resume", uuid, null, null);
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("paused", paused);
        out.addProperty("count", uuids.size());
        return out;
    }

    /**
     * 常驻加载(sable force-load ticket)。开启前必须先把体加载出来 —— {@code addForceLoadTicket}
     * 只接受已加载的 {@link ServerSubLevel};关闭则对未加载体也能摘票。
     * 加载可能触发区块同步生成,故走不设超时的 {@link #onMainUntilComplete}。
     */
    public JsonObject setForced(List<UUID> uuids, boolean forced) throws Exception {
        // 整批一次建链:多选往往是同一个依赖组的成员,分层 BFS 会把它们一趟解完。
        // 逐个建链会把同一批 .slvls 解压 N 遍 —— 全选 178 体的绳链时就是 178 遍。
        Map<UUID, MemberPlan> chain = Map.of();
        if (forced) {
            // 已加载的体不用进链:ensureLoaded 第一行 resolveLoaded 就会返回。
            // 生产上曾为一个已加载的 178 依赖体白扫 16 分钟磁盘。
            List<UUID> cold = uuids.stream().filter(u -> !this.index.isLoaded(u)).toList();
            if (!cold.isEmpty()) chain = prepareChain(cold); // 作业线程做磁盘定位,不占主线程
        }
        Map<UUID, MemberPlan> plans = chain;
        // ThreadLocal 到不了主线程,先在作业线程上取出来捕获进 lambda
        JobService.Job job = JobService.current();
        JsonObject out = onMainUntilComplete(() -> {
            JsonArray failed = new JsonArray();
            int done = 0;
            for (UUID uuid : uuids) {
                if (job != null) job.phase(forced ? "挂常驻票" : "摘常驻票");
                if (!forced) {
                    ForceLoadService.removeOnMain(this.server, uuid);
                    done++;
                    continue;
                }
                try {
                    ForceLoadService.addOnMain(ensureLoaded(uuid, plans));
                    done++;
                } catch (Throwable t) {
                    JsonObject f = new JsonObject();
                    f.addProperty("uuid", uuid.toString());
                    f.addProperty("error", String.valueOf(t.getMessage()));
                    failed.add(f);
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("forced", forced);
            o.addProperty("count", done);
            if (!failed.isEmpty()) o.add("failed", failed);
            return o;
        });
        for (UUID uuid : uuids) audit(forced ? "force_load" : "force_unload", uuid, null, null);
        return out;
    }

    /** 在线玩家列表(主线程读取,给"传送玩家"下拉用) */
    public JsonObject listPlayers() throws Exception {
        return onMain(() -> {
            JsonArray arr = new JsonArray();
            for (var player : this.server.getPlayerList().getPlayers()) {
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
        Map<UUID, MemberPlan> chain = prepareChain(uuid);
        return onMain(() -> {
            var player = this.server.getPlayerList().getPlayer(playerUuid);
            if (player == null) throw new IllegalStateException("玩家不在线");
            ServerSubLevel sl = ensureLoaded(uuid, chain);
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
            audit("teleport_player", uuid, sl.getName(),
                    player.getGameProfile().getName() + " -> " + x + "," + y + "," + z);
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("player", player.getGameProfile().getName());
            r.addProperty("dim", level.dimension().location().toString());
            return r;
        });
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
            prepareDeleteSemantics(components, statuses);
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
        ScanSession scan = ScanSession.strict(this.server, warnings);
        // 纯运行时新体(刚生成、盘上还没有条目)先落一次盘再删:内存里的方块不落盘就无从备份
        if (flushUnsavedTargets(targets, scan.meta())) {
            scan = ScanSession.strict(this.server, warnings);
        }
        List<Set<UUID>> selectedGroups = DiskScanner.selectedDependencyComponents(scan.meta(), targets);
        Set<UUID> selected = new LinkedHashSet<>();
        for (Set<UUID> group : selectedGroups) {
            selected.addAll(group);
        }
        Map<UUID, List<DeleteCopy>> prepared = readDeleteCopies(scan, selected, warnings);

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

    /**
     * 规范副本优先级的唯一定义:活动条目 > 有 holding 指针 > 条目 id 字典序。
     * active 传 null = 该路径语义上不看活动项(回滚清理只关心可达性)。
     */
    private static <T> Comparator<T> canonicalOrder(
            java.util.function.Function<T, DiskScanner.EntryKey> key,
            java.util.function.Function<T, List<DiskScanner.LiveLocation>> pointers, String active) {
        return Comparator.comparing((T copy) -> !key.apply(copy).id().equals(active))
                .thenComparing(copy -> pointers.apply(copy).isEmpty())
                .thenComparing(copy -> key.apply(copy).id());
    }

    /** 选择唯一规范副本并记录运行状态；内容不同的副本必须先由用户处理，删除不能猜。 */
    private void prepareDeleteSemantics(List<DeleteComponent> components,
                                        Map<UUID, DeleteStatus> statuses) throws Exception {
        Set<UUID> targets = new LinkedHashSet<>();
        for (DeleteComponent component : components) targets.addAll(component.targets);
        JsonObject runtime = readOperationalMetadata(targets);

        for (DeleteComponent component : components) {
            boolean conflict = false;
            for (UUID uuid : component.targets) {
                List<DeleteCopy> copies = component.copies.getOrDefault(uuid, List.of());
                if (!copies.isEmpty()) {
                    JsonObject state = runtime.getAsJsonObject(uuid.toString());
                    String active = state.has("active") ? state.get("active").getAsString() : null;
                    DeleteCopy keep = copies.stream()
                            .min(canonicalOrder(DeleteCopy::key, DeleteCopy::pointers, active)).orElseThrow();
                    component.canonical.put(uuid, keep);
                    if (copies.stream().anyMatch(copy -> !copy.tag().equals(keep.tag()))) conflict = true;
                }
                JsonObject state = runtime.getAsJsonObject(uuid.toString());
                component.states.put(uuid, new RecycleStore.OperationalState(
                        state.get("paused").getAsBoolean(), state.get("forced").getAsBoolean()));
            }
            if (conflict) {
                failComponent(component, statuses, "依赖组存在内容不同的副本，请先处理副本后重试删除");
            }
        }
    }

    /** 删除语义准备用:paused/forced + 活动条目 */
    private JsonObject readOperationalMetadata(Set<UUID> targets) throws Exception {
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
                String active = activeEntries.get(uuid);
                if (active != null) state.addProperty("active", active);
                out.add(uuid.toString(), state);
            }
            return out;
        });
    }

    private Map<UUID, String> activeEntriesOnMain(Collection<UUID> targets) {
        Map<UUID, String> active = new LinkedHashMap<>();
        for (UUID uuid : targets) {
            String entry = activePointerEntryId(uuid);
            if (entry != null) active.put(uuid, entry);
        }
        return active;
    }

    /** 只为指定成员重读完整 NBT；重读时验 UUID，避免准备期间槽位被 Sable 复用。 */
    private Map<UUID, List<DeleteCopy>> readDeleteCopies(ScanSession scan, Set<UUID> targets,
                                                         List<String> warnings) throws Exception {
        Map<DiskScanner.EntryKey, CompoundTag> tags = new LinkedHashMap<>();
        for (UUID target : targets) {
            for (DiskScanner.EntryMeta copy : scan.entriesOf(target)) {
                tags.put(copy.key(), readVerifiedTag(scan.dims(), target, copy.key()));
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

    /**
     * 按 key 重读条目并验 uuid 的唯一句式。快照可能陈旧(autosave 会搬迁条目、槽位会被
     * sable 复用给别的体),读回不验 uuid 是 0.6.0 定下的铁律。返回 null = 条目缺失或
     * 槽位已易主,失败语义(抛错/警告/跳过)由调用方决定。
     */
    private static CompoundTag readVerified(Map<String, Path> dims, UUID uuid, DiskScanner.EntryKey key) {
        Path dir = dims.get(key.dim());
        CompoundTag tag = dir != null ? DiskScanner.readEntryTag(dir, key) : null;
        return tag != null && uuid.equals(tagUuid(tag)) ? tag : null;
    }

    private static CompoundTag readVerifiedTag(Map<String, Path> dims, UUID uuid, DiskScanner.EntryKey key)
            throws IOException {
        CompoundTag tag = readVerified(dims, uuid, key);
        if (tag == null) {
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
        return !unsaved.isEmpty() && flushTargetLevels(unsaved) > 0;
    }

    private void flushLoadedTargets(Collection<UUID> targets) throws Exception {
        flushTargetLevels(targets);
        DiskScanner.invalidateCache();
    }

    /** 把目标中已加载的体所在维度逐个 saveAll,返回落盘的维度数 */
    private int flushTargetLevels(Collection<UUID> targets) throws Exception {
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
    private static void saveAllLevels(Collection<ServerLevel> levels) {
        for (ServerLevel level : levels) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) throw new IllegalStateException("物理体容器不存在");
            container.getHoldingChunkMap().saveAll();
        }
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
            if (componentHasErrors(component, statuses)) continue;
            List<RecycleStore.Source> sources = new ArrayList<>();
            for (UUID uuid : component.targets) {
                DeleteCopy copy = component.canonical.get(uuid);
                if (copy != null) sources.add(new RecycleStore.Source(
                        uuid, copy.key().dim(), copy.key(), copy.tag()));
            }
            if (sources.isEmpty()) continue;
            try {
                component.stage = this.recycle.stage(sources, component.states);
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

    private void validatePreparedSnapshotOnMain(DeleteComponent component) throws Exception {
        List<String> warnings = new ArrayList<>();
        ScanSession scan = ScanSession.fresh(this.server, warnings);
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
                current.put(entry.key(), readVerifiedTag(scan.dims(), uuid, entry.key()));
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
                    PauseService.isPaused(uuid), ForceLoadService.isForcedOnMain(this.server, uuid)));
        }
        requireUnchangedCopySnapshot(
                new CopySnapshot(component.targets, expectedEntries, expectedPointers,
                        component.activeSnapshot, component.states),
                new CopySnapshot(currentMembers, currentEntries, currentPointers,
                        activeEntriesOnMain(component.targets), currentStates));
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

    private static List<DiskScanner.LiveLocation> orderedPointers(
            Collection<DiskScanner.LiveLocation> pointers) {
        return pointers.stream().sorted(Comparator
                .comparing((DiskScanner.LiveLocation pointer) -> pointer.key().id())
                .thenComparingInt(DiskScanner.LiveLocation::chunkX)
                .thenComparingInt(DiskScanner.LiveLocation::chunkZ)).toList();
    }

    private void clearOperationalStateOnMain(Collection<UUID> targets) {
        PauseService.applyOnMain(this.server, targets, false);
        for (UUID uuid : targets) ForceLoadService.removeOnMain(this.server, uuid);
        for (UUID uuid : targets) {
            if (PauseService.isPaused(uuid) || ForceLoadService.isForcedOnMain(this.server, uuid)) {
                throw new IllegalStateException("删除前未能清理暂停/常驻状态: " + uuid);
            }
        }
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
    private void loadCanonicalTargets(DeleteExecution execution) {
        for (UUID uuid : execution.component.targets) {
            if (resolveLoaded(uuid) != null) continue;
            DeleteCopy copy = execution.component.canonical.get(uuid);
            if (copy == null) throw new IllegalStateException("目标没有规范副本: " + uuid);
            loadPreparedMember(uuid, copy.loadPlan());
        }
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

    private void removeLoadedTargets(DeleteExecution execution) {
        for (UUID uuid : execution.component.targets) {
            ServerSubLevel body = resolveLoaded(uuid);
            if (body == null) continue;
            removeLoadedTarget(execution, uuid, body);
        }
    }

    private void removeLoadedTarget(DeleteExecution execution, UUID uuid, ServerSubLevel body) {
        if (execution.removedBodies.containsKey(uuid)) return;
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
            // sable 清槽(attemptSaveSubLevel(ptr,null))不验 uuid,入队前必须重读槽位确认还是目标,
            // 否则会静默清掉无辜体的条目(此处在主线程,sable 不会并发写盘)
            CompoundTag fresh = readVerified(dims, uuid, copy.key());
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
        verifyDeletedTargets(statuses, warnings, true);
    }

    private void verifyDeletedTargets(Map<UUID, DeleteStatus> statuses, List<String> warnings,
                                      boolean triggerRescan) {
        DiskVerification disk;
        JsonObject runtime;
        try {
            disk = scanRemainingEntries(statuses, warnings);
            runtime = readRuntimeStates(statuses.keySet());
        } catch (Exception error) {
            String message = "删除后验收失败: " + messageOf(error);
            for (DeleteStatus status : statuses.values()) status.fail(message);
            if (triggerRescan) this.rescan.run();
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
        if (triggerRescan) this.rescan.run();
    }

    private DiskVerification scanRemainingEntries(Map<UUID, DeleteStatus> statuses, List<String> warnings)
            throws Exception {
        ScanSession scan = ScanSession.fresh(this.server, warnings);
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

    /** 删除后验收用:loaded/holding/paused/forced */
    private JsonObject readRuntimeStates(Set<UUID> targets) throws Exception {
        return readStates(targets, false, true);
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
        onMainUntilComplete(() -> {
            clearOperationalStateOnMain(targets);
            return new JsonObject();
        });
        requireTargetsAbsent(targets, warnings);
    }

    private DeleteComponent prepareExactDeleteComponent(Set<UUID> targets, List<String> warnings)
            throws Exception {
        ScanSession scan = ScanSession.strict(this.server, warnings);
        if (flushUnsavedTargets(new ArrayList<>(targets), scan.meta())) {
            scan = ScanSession.strict(this.server, warnings);
        }
        Map<UUID, List<DeleteCopy>> prepared = readDeleteCopies(scan, targets, warnings);
        DeleteComponent component = new DeleteComponent();
        for (UUID target : targets) {
            List<DeleteCopy> copies = prepared.getOrDefault(target, List.of());
            if (!copies.isEmpty()) {
                component.addTarget(target, copies);
                component.canonical.put(target, copies.stream()
                        .min(canonicalOrder(DeleteCopy::key, DeleteCopy::pointers, null)).orElseThrow());
            }
        }
        return component;
    }

    private void requireTargetsAbsent(Set<UUID> targets, List<String> warnings) throws Exception {
        ScanSession scan = ScanSession.fresh(this.server, warnings);
        JsonObject runtime = readRuntimeStates(targets);
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
                if (component.stateCleared) {
                    try {
                        restoreOperationalState(this.recycle.loadStage(component.stage));
                    } catch (Exception stateError) {
                        failComponent(component, statuses,
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

    public JsonObject recycleView(String version, String cursor, int limit) {
        return this.recycle.view(version, cursor, limit);
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

    public synchronized JsonObject purgeRecycleGroups(List<String> groupIds) {
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
        ScanSession scan = ScanSession.strict(this.server, warnings);
        Map<UUID, Integer> existingEntries = new HashMap<>();
        for (UUID uuid : targets) existingEntries.put(uuid, scan.entriesOf(uuid).size());
        if (!replaceExisting) requireRestoreTargetsFree(targets, existingEntries);
        onMainUntilComplete(() -> {
            clearOperationalStateOnMain(targets);
            return new JsonObject();
        });
        // 同一趟扫描顺路建 plot 槽位占用表:删除释放的槽位会被 sable 按首位适配复用给新体,
        // 而恢复用的 allocateSubLevel 只查加载态 —— 不拦下来就会造出"同槽双体"(加载互斥)
        Map<DiskScanner.PlotKey, Set<UUID>> plotOwners = DiskScanner.plotOwners(scan.meta());
        List<ServerSubLevel> created = new ArrayList<>();
        Set<ServerLevel> touched = new LinkedHashSet<>();
        try {
            onMainUntilComplete(() -> restoreGroupOnMain(group, existingEntries, plotOwners, created, touched));
            verifyRestoredGroup(group, warnings);
            restoreOperationalState(group);
        } catch (Exception verificationError) {
            try {
                purgeRestoreTargets(targets, warnings);
                requireTargetsAbsent(targets, warnings);
            } catch (Exception cleanupError) {
                verificationError.addSuppressed(cleanupError);
            }
            throw verificationError;
        }
    }

    private void requireRestoreTargetsFree(Set<UUID> targets, Map<UUID, Integer> existingEntries) throws Exception {
        JsonObject runtime = readRuntimeStates(targets);
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

    private void restoreOperationalState(RecycleStore.RestoreGroup group) throws Exception {
        List<UUID> forced = group.bodies().stream()
                .filter(RecycleStore.RestoreBody::forced).map(RecycleStore.RestoreBody::uuid).toList();
        Map<UUID, MemberPlan> plans = forced.isEmpty() ? Map.of() : prepareChain(forced);
        onMainUntilComplete(() -> {
            for (UUID uuid : forced) ForceLoadService.addOnMain(ensureLoaded(uuid, plans));
            List<UUID> paused = group.bodies().stream()
                    .filter(RecycleStore.RestoreBody::paused).map(RecycleStore.RestoreBody::uuid).toList();
            if (!paused.isEmpty()) PauseService.applyOnMain(this.server, paused, true);
            for (RecycleStore.RestoreBody body : group.bodies()) {
                boolean pausedState = PauseService.isPaused(body.uuid());
                boolean forcedState = ForceLoadService.isForcedOnMain(this.server, body.uuid());
                if (pausedState != body.paused() || forcedState != body.forced()) {
                    throw new IllegalStateException("恢复后暂停/常驻状态不一致: " + body.uuid());
                }
            }
            return new JsonObject();
        });
    }

    private JsonObject restoreGroupOnMain(RecycleStore.RestoreGroup group,
                                          Map<UUID, Integer> existingEntries,
                                          Map<DiskScanner.PlotKey, Set<UUID>> plotOwners,
                                          List<ServerSubLevel> created,
                                          Set<ServerLevel> touched) throws Exception {
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
            saveAllLevels(touched);
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
                if (container != null && resolveLoaded(body.getUniqueId()) == body) {
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
        ScanSession scan = ScanSession.fresh(this.server, warnings);
        Set<DiskScanner.EntryKey> keys = new LinkedHashSet<>();
        for (UUID uuid : targets) {
            for (DiskScanner.EntryMeta copy : scan.entriesOf(uuid)) keys.add(copy.key());
        }
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers =
                DiskScanner.locatePointersStrict(scan.dims(), keys, warnings);
        JsonObject runtime = readRuntimeStates(targets);
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
            if (!new LinkedHashSet<>(restored.deps()).equals(expectedDependencies)) {
                throw new IllegalStateException("恢复后依赖关系不一致: " + uuid);
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
        if (ok == 0) {
            for (UUID uuid : targets) {
                DeleteStatus status = statuses.get(uuid);
                if (!status.errors.isEmpty()) {
                    out.addProperty("error", String.join("; ", status.errors));
                    break;
                }
            }
        }
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

    private static JsonArray numberArray(double[] values) {
        JsonArray out = new JsonArray();
        for (double value : values) out.add(value);
        return out;
    }

    /** 孤儿收养(依赖闭包一起):不动盘,全部经 sable 原生 loadHoldingSubLevel 入场 */
    public JsonObject adopt(UUID uuid) throws Exception {
        JsonObject result = adoptOne(uuid);
        this.rescan.run();
        return result;
    }

    /** 收养单体但不触发重扫。批量路径用它,整批结束后只扫一次。 */
    private JsonObject adoptOne(UUID uuid) throws Exception {
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
        return result;
    }

    /**
     * 批量收养。前端从前是对每个孤儿体单独 POST 一次,N 个体就是 N 次作业提交 + N 次全量
     * bodies 刷新,选区一大就线性放大;现在整批一个作业,失败项结构化留在 results/failed 里。
     * <p>
     * 逐项走 {@link #adoptOne} 而不是 {@code adopt}:后者每次都要重扫一遍磁盘,合并门闩只在
     * 扫描仍排队时能合并,前一次扫完了下一项照样会再排一次,N 个体最坏就是 N 次全量扫描。
     */
    public JsonObject adoptBatch(List<UUID> uuids) {
        JsonArray results = new JsonArray();
        JsonArray failed = new JsonArray();
        int ok = 0;
        int index = 0;
        for (UUID uuid : uuids) {
            JobService.phase("收养");
            JobService.detail(++index + "/" + uuids.size());
            JsonObject item = new JsonObject();
            item.addProperty("uuid", uuid.toString());
            try {
                JsonObject one = adoptOne(uuid);
                boolean adopted = one.has("ok") && one.get("ok").getAsBoolean();
                item.addProperty("ok", adopted);
                if (one.has("truncated")) item.add("truncated", one.get("truncated"));
                if (adopted) ok++;
                else failed.add(uuid.toString());
            } catch (Exception error) {
                item.addProperty("ok", false);
                item.addProperty("error", messageOf(error));
                failed.add(uuid.toString());
                SablePanel.LOGGER.warn("sablepanel: batch adopt {} failed", uuid, error);
            }
            results.add(item);
        }
        this.rescan.run();   // 整批一次
        JsonObject out = new JsonObject();
        out.addProperty("ok", ok);
        out.addProperty("total", uuids.size());
        out.add("results", results);
        if (!failed.isEmpty()) out.add("failed", failed);
        return out;
    }

    /** 实时副本审查:列表快照只负责提示,真正操作前始终严格重扫并深比较完整 NBT。 */
    public JsonObject inspectCopies(UUID uuid) throws Exception {
        List<String> warnings = new ArrayList<>();
        JsonObject out = copyVersionsJson(inspectVersionState(uuid, warnings));
        attachWarnings(out, warnings);
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
        ScanSession scan = ScanSession.strict(this.server, warnings);
        Set<UUID> members = CopyVersionScanner.members(scan.meta(), uuid);
        JsonObject runtime = readOperationalMetadata(members);
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
            item.add("pos", numberArray(summary.pos()));
            item.add("size", numberArray(summary.size()));
        }
        return item;
    }

    private PreparedCopyResolution prepareCopyResolution(UUID uuid, List<String> warnings) throws Exception {
        ScanSession scan = ScanSession.strict(this.server, warnings);
        Set<UUID> members = CopyVersionScanner.members(scan.meta(), uuid);
        flushLoadedTargets(members);
        scan = ScanSession.strict(this.server, warnings);
        members = CopyVersionScanner.members(scan.meta(), uuid);
        DeleteComponent component = prepareExactDeleteComponent(members, warnings);
        if (!component.targets.equals(members)) {
            throw new IllegalStateException("副本依赖组缺少可读取的磁盘条目，未执行副本处理");
        }

        JsonObject runtime = readOperationalMetadata(members);
        Map<UUID, String> active = activeEntries(runtime, members);
        Map<UUID, RecycleStore.OperationalState> states = operationalStates(runtime, members);
        component.activeSnapshot = Map.copyOf(active);
        component.states.putAll(states);

        List<CopyVersionScanner.Copy> copies = new ArrayList<>();
        for (UUID member : members) {
            List<DeleteCopy> prepared = component.copies.getOrDefault(member, List.of());
            String activeEntry = active.get(member);
            component.canonical.put(member, prepared.stream()
                    .min(canonicalOrder(DeleteCopy::key, DeleteCopy::pointers, activeEntry)).orElseThrow());
            for (DeleteCopy copy : prepared) {
                copies.add(new CopyVersionScanner.Copy(member, copy.key(), copy.tag(), copy.blocks(),
                        copy.pointers()));
            }
        }
        CopyVersionScanner.Scan versions = CopyVersionScanner.assemble(uuid, members, copies, active);
        return new PreparedCopyResolution(component, versions, states);
    }

    public synchronized JsonObject resolveCopyVersion(UUID uuid, String versionId) throws Exception {
        List<String> warnings = new ArrayList<>();
        PreparedCopyResolution prepared = prepareCopyResolution(uuid, warnings);
        CopyVersionScanner.Scan scan = prepared.scan();
        CopyResolutionPlan plan = requireCopyResolution(scan, versionId);
        CopyVersionScanner.Version selected = plan.selected();
        CopyVersionScanner.Version rollbackVersion = plan.rollback();
        DeleteComponent component = prepared.component();
        Map<UUID, RecycleStore.OperationalState> states = prepared.states();
        Map<String, RecycleStore.Stage> stages = new LinkedHashMap<>();
        Map<String, RecycleStore.Stage> incompleteStages = new LinkedHashMap<>();
        Map<UUID, DeleteStatus> statuses = new LinkedHashMap<>();
        for (UUID target : component.targets) statuses.put(target, new DeleteStatus(target));
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

            executeDeleteComponents(List.of(component), statuses);
            verifyDeletedTargets(statuses, warnings, false);
            List<String> failures = statuses.values().stream().filter(status -> !status.ok)
                    .map(status -> status.uuid + ": " + String.join("; ", status.errors)).toList();
            if (!failures.isEmpty()) throw new IllegalStateException("副本切换清理失败: " + String.join(" | ", failures));
            requireTargetsAbsent(scan.members(), warnings);

            for (Map.Entry<String, RecycleStore.Stage> entry : stages.entrySet()) {
                if (entry.getKey().equals(selected.id())
                        || entry.getKey().equals(rollbackVersion.id())) continue;
                this.recycle.commitOld(entry.getValue());
            }
            for (RecycleStore.Stage stage : incompleteStages.values()) {
                this.recycle.commitIncomplete(stage);
            }
            restoreGroupData(restoreGroup(selected, states, "copy-selection"), false, warnings);
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
                restoreGroupData(rollbackGroup, true, warnings);
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
            audit("resolve_copies", uuid, null, detail.toString());
        } catch (Throwable error) {
            warnings.add("副本处理已完成，但审计日志写入失败: " + messageOf(error));
            SablePanel.LOGGER.warn("sablepanel: copy resolution audit failed after commit", error);
        }
        try {
            this.rescan.run();
        } catch (Throwable error) {
            warnings.add("副本处理已完成，但磁盘索引重扫触发失败: " + messageOf(error));
            SablePanel.LOGGER.warn("sablepanel: copy resolution rescan failed after commit", error);
        }
        attachWarnings(out, warnings);
        return out;
    }

    public synchronized JsonObject quarantineIncompleteCopies(UUID uuid) throws Exception {
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
            DeleteComponent component = prepareExactDeleteComponent(scan.members(), warnings);
            Map<UUID, DeleteStatus> statuses = new LinkedHashMap<>();
            for (UUID target : component.targets) statuses.put(target, new DeleteStatus(target));
            changed = true;
            executeDeleteComponents(List.of(component), statuses);
            verifyDeletedTargets(statuses, warnings);
            List<String> failures = statuses.values().stream().filter(status -> !status.ok)
                    .map(status -> status.uuid + ": " + String.join("; ", status.errors)).toList();
            if (!failures.isEmpty()) throw new IllegalStateException("隔离清理失败: " + String.join(" | ", failures));
            requireTargetsAbsent(scan.members(), warnings);
            for (RecycleStore.Stage stage : stages) {
                this.recycle.commitIncomplete(stage);
            }
            audit("quarantine_copies", uuid, null, stages.size() + " entries");
            this.rescan.run();
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("quarantined", stages.size());
            attachWarnings(out, warnings);
            return out;
        } catch (Exception error) {
            if (!changed) {
                for (RecycleStore.Stage stage : stages) this.recycle.discard(stage);
            }
            throw error;
        }
    }

    private Map<UUID, RecycleStore.OperationalState> operationalStates(Set<UUID> targets) throws Exception {
        return operationalStates(readOperationalMetadata(targets), targets);
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
            saveAllLevels(touched);
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
        ScanSession scan = ScanSession.strict(this.server, warnings);
        List<DiskScanner.EntryMeta> entries = scan.entriesOf(uuid);
        if (entries.isEmpty()) throw new IllegalStateException("找不到该体的磁盘条目");
        Set<DiskScanner.EntryKey> keys = new LinkedHashSet<>();
        for (DiskScanner.EntryMeta entry : entries) keys.add(entry.key());
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers =
                DiskScanner.locatePointersStrict(scan.dims(), keys, warnings);
        JsonObject activeState = onMain(() -> {
            JsonObject out = new JsonObject();
            String id = activePointerEntryId(uuid);
            if (id != null) out.addProperty("entry", id);
            return out;
        });
        String active = activeState.has("entry") ? activeState.get("entry").getAsString() : null;

        List<CopyCandidate> copies = new ArrayList<>();
        for (DiskScanner.EntryMeta entry : entries) {
            CompoundTag tag = readVerified(scan.dims(), uuid, entry.key());
            if (tag == null) throw new IOException("副本槽位已经变化: " + entry.key().id());
            copies.add(new CopyCandidate(entry.key(), tag,
                    DiskScanner.countBlocks(tag.getCompound("plot"), null),
                    List.copyOf(pointers.getOrDefault(entry.key(), List.of()))));
        }
        copies.sort(canonicalOrder(CopyCandidate::key, CopyCandidate::pointers, active));
        CopyCandidate keep = copies.get(0);
        boolean identical = copies.stream().allMatch(copy -> copy.tag().equals(keep.tag()));
        return new CopyInspection(scan.dims(), List.copyOf(copies), keep, identical);
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

    private static DiskScanner.EntryKey entryKey(String dim, GlobalSavedSubLevelPointer pointer) {
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
    private Map<UUID, MemberPlan> prepareChain(UUID root) {
        return prepareChain(List.of(root));
    }

    private Map<UUID, MemberPlan> prepareChain(Collection<UUID> roots) {
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
            CompoundTag fresh = readVerified(DiskScanner.sublevelDirs(this.server), uuid, plan.key());
            if (fresh == null) {
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

    private ServerSubLevel resolveLoaded(UUID uuid) {
        return findInContainers((level, container) -> loadedBody(container, uuid));
    }

    private boolean isHolding(UUID uuid) {
        return findInContainers((level, container) ->
                container.getHoldingChunkMap().getHoldingSubLevel(uuid) != null ? Boolean.TRUE : null) != null;
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

    private JsonObject onMain(Callable<JsonObject> task) throws Exception {
        return MainThread.on(this.server, 20, task);
    }

    private JsonObject onMainUntilComplete(Callable<JsonObject> task) throws Exception {
        return MainThread.onUntilComplete(this.server, task);
    }
}
