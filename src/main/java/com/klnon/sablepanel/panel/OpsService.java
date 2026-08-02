package com.klnon.sablepanel.panel;

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
import net.neoforged.fml.loading.FMLPaths;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    public OpsService(MinecraftServer server, BodyIndex index, Runnable rescan) {
        this.server = server;
        this.index = index;
        this.rescan = rescan;
    }

    /** 收养链成员:条目位置+NBT+可选活指针 */
    private record MemberPlan(DiskScanner.EntryKey key, CompoundTag tag, DiskScanner.LiveLocation cold) {
    }

    public JsonObject teleport(UUID uuid, double x, double y, double z) throws Exception {
        Map<UUID, MemberPlan> chain = prepareChain(uuid);
        JsonObject result = onMain(() -> {
            ServerSubLevel sl = ensureLoaded(uuid, chain);
            ServerLevel level = sl.getLevel();
            SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
            sl.logicalPose().position().set(x, y, z);
            phys.getPipeline().teleport(sl, new Vector3d(x, y, z), sl.logicalPose().orientation());
            sl.updateLastPose();
            audit("teleport", uuid, sl.getName(), x + "," + y + "," + z);
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("dim", level.dimension().location().toString());
            return r;
        });
        this.rescan.run();
        return result;
    }

    public JsonObject delete(UUID uuid) throws Exception {
        Set<ServerLevel> touched = new LinkedHashSet<>();
        JsonObject result = deleteNoRescan(uuid, touched);
        flushDeletions(touched);
        this.rescan.run();
        return result;
    }

    /** 批量删除(整组等):逐体执行,末尾统一落盘 + 单次重扫。任何一体失败不阻断其余。 */
    public JsonObject deleteBatch(List<UUID> uuids) {
        JsonArray results = new JsonArray();
        Set<ServerLevel> touched = new LinkedHashSet<>();
        int ok = 0;
        for (UUID u : uuids) {
            JsonObject r = new JsonObject();
            r.addProperty("uuid", u.toString());
            try {
                JsonObject d = deleteNoRescan(u, touched);
                r.addProperty("ok", true);
                if (d.has("recycle")) r.add("recycle", d.get("recycle"));
                ok++;
            } catch (Exception e) {
                r.addProperty("ok", false);
                r.addProperty("error", String.valueOf(e.getMessage() != null ? e.getMessage() : e));
            }
            results.add(r);
        }
        try {
            flushDeletions(touched);
        } catch (Exception e) {
            SablePanel.LOGGER.warn("sablepanel: flush after batch delete failed", e);
        }
        this.rescan.run();
        JsonObject out = new JsonObject();
        out.addProperty("ok", ok);
        out.addProperty("total", uuids.size());
        out.add("results", results);
        return out;
    }

    private JsonObject deleteNoRescan(UUID uuid, Set<ServerLevel> touched) throws Exception {
        Map<UUID, MemberPlan> chain = prepareChain(uuid);
        return onMain(() -> {
            // 回收站:先导出磁盘条目原始数据
            String backup = exportToRecycle(uuid);
            ServerSubLevel sl = ensureLoaded(uuid, chain);
            ServerSubLevelContainer c = SubLevelContainer.getContainer(sl.getLevel());
            c.removeSubLevel(sl, SubLevelRemovalReason.REMOVED);
            touched.add(sl.getLevel());
            // 校验:确实从容器消失
            var after = c.getSubLevel(uuid);
            boolean gone = !(after instanceof ServerSubLevel ssl) || ssl.isRemoved();
            audit("delete", uuid, sl.getName(), backup);
            JsonObject r = new JsonObject();
            r.addProperty("ok", gone);
            if (backup != null) r.addProperty("recycle", backup);
            if (!gone) r.addProperty("warn", "removeSubLevel 后体仍在容器中");
            return r;
        }, 20);
    }

    /**
     * 让删除立刻落盘。
     *
     * <p>{@code removeSubLevel(REMOVED)} 只把体从内存容器摘掉,并把指针塞进 sable 的
     * {@code queuedDeletion};**真正清掉 .slvls 条目与 .slvlr 指针的是 saveAll()**,
     * 而 saveAll 平时只在 vanilla 自动保存(约 5 分钟)和关服时触发。不主动落盘的话,
     * 这段窗口里条目和指针都还在盘上——面板照旧列出该体,附近区块一加载 sable 就能把它
     * 按指针重新加载回来,表现就是"删了但物理结构还在"。
     *
     * <p>saveAll 会顺带重存该维度所有活体,开销与一次自动保存相当,所以整批删除只调一次。
     */
    private void flushDeletions(Set<ServerLevel> levels) throws Exception {
        if (levels.isEmpty()) return;
        onMain(() -> {
            int n = 0;
            for (ServerLevel level : levels) {
                try {
                    ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
                    if (c != null) {
                        c.getHoldingChunkMap().saveAll();
                        n++;
                    }
                } catch (Throwable t) {
                    SablePanel.LOGGER.warn("sablepanel: saveAll for {} failed", level.dimension().location(), t);
                }
            }
            JsonObject r = new JsonObject();
            r.addProperty("flushed", n);
            return r;
        }, 120);
    }

    /** 孤儿收养(依赖闭包一起):不动盘,全部经 sable 原生 loadHoldingSubLevel 入场 */
    public JsonObject adopt(UUID uuid) throws Exception {
        Map<UUID, MemberPlan> chain = prepareChain(uuid);
        if (chain.isEmpty()) throw new IllegalStateException("找不到该体的存档条目");
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
            r.add("chain", per);
            return r;
        });
        this.rescan.run();
        return result;
    }

    // ---------- 内部:加载路径 ----------

    /** HTTP 线程:为 uuid 及其依赖闭包准备条目数据(磁盘 IO 不占主线程) */
    private Map<UUID, MemberPlan> prepareChain(UUID root) {
        Map<UUID, MemberPlan> chain = new LinkedHashMap<>();
        Map<String, Path> dims = DiskScanner.sublevelDirs(this.server);
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && chain.size() < MAX_CHAIN) {
            UUID u = queue.poll();
            if (chain.containsKey(u)) continue;
            MemberPlan plan = locateMember(u, dims);
            if (plan == null) continue;
            chain.put(u, plan);
            try {
                if (plan.tag().contains("loading_dependencies")) {
                    var list = plan.tag().getList("loading_dependencies", net.minecraft.nbt.Tag.TAG_INT_ARRAY);
                    for (net.minecraft.nbt.Tag t : list) queue.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
                }
            } catch (Throwable ignored) {
            }
        }
        return chain;
    }

    /** 快路径:索引快照定位 + 重读校验;失败退化为全盘实时定位 */
    private MemberPlan locateMember(UUID u, Map<String, Path> dims) {
        DiskScanner.EntryKey key = null;
        CompoundTag tag = null;
        DiskScanner.DiskEntry cached = this.index.findEntry(u);
        if (cached != null) {
            Path dir = dims.get(cached.key().dim());
            if (dir != null) {
                CompoundTag t = DiskScanner.readEntryTag(dir, cached.key());
                try {
                    if (t != null && u.equals(t.getUUID("uuid"))) {
                        key = cached.key();
                        tag = t;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (tag == null) {
            for (var en : dims.entrySet()) {
                DiskScanner.LocatedEntry le = DiskScanner.locateEntry(en.getKey(), en.getValue(), u);
                if (le != null) {
                    key = le.key();
                    tag = le.tag();
                    break;
                }
            }
        }
        if (tag == null) return null;
        DiskScanner.LiveLocation cold = null;
        Path dir = dims.get(key.dim());
        if (dir != null) {
            cold = DiskScanner.locateLive(key.dim(), dir, u);
        }
        return new MemberPlan(key, tag, cold);
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
        // 1) sable 内存 holding 态:原生指针权威
        for (ServerLevel level : this.server.getAllLevels()) {
            try {
                ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
                if (c == null) continue;
                var holding = c.getHoldingChunkMap().getHoldingSubLevel(uuid);
                if (holding != null && holding.pointer() != null) {
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
                c.getHoldingChunkMap().snatchAndLoad(ptr, uuid);
                if (resolveLoaded(uuid) != null) return;
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: cold snatch {} failed", uuid, t);
            }
        }
        // 3) 真孤儿收养:构造 HoldingSubLevel 直接入 sable 加载管线。
        //    指针 chunk 取体位置 chunk,并夹取到条目所在 region 内(save 迁移时按该指针清理旧条目)。
        //    先重读条目确认 uuid 仍匹配——若 prepare 之后 sable 迁移/复用了该槽位,立即中止,
        //    否则收养体保存时会清掉别人的条目。
        try {
            Path dimDir = DiskScanner.sublevelDirs(this.server).get(plan.key().dim());
            CompoundTag fresh = dimDir != null ? DiskScanner.readEntryTag(dimDir, plan.key()) : null;
            if (fresh == null || !uuid.equals(fresh.getUUID("uuid"))) {
                SablePanel.LOGGER.warn("sablepanel: adopt {} aborted, entry slot changed since prepare", uuid);
                return;
            }
            SubLevelData data = SubLevelSerializer.fromData(fresh);
            if (data == null || !uuid.equals(data.uuid())) {
                SablePanel.LOGGER.warn("sablepanel: adopt {} aborted, entry data mismatch", uuid);
                return;
            }
            CompoundTag posTag = fresh.getCompound("pose").getCompound("position");
            int cx = clamp(((int) Math.floor(posTag.getDouble("x"))) >> 4, plan.key().rx() * 32, plan.key().rx() * 32 + 31);
            int cz = clamp(((int) Math.floor(posTag.getDouble("z"))) >> 4, plan.key().rz() * 32, plan.key().rz() * 32 + 31);
            GlobalSavedSubLevelPointer ptr = new GlobalSavedSubLevelPointer(
                    new ChunkPos(cx, cz), (short) plan.key().storage(), (short) plan.key().index());
            c.getHoldingChunkMap().loadHoldingSubLevel(new HoldingSubLevel(data, ptr));
            if (resolveLoaded(uuid) == null) {
                SablePanel.LOGGER.warn("sablepanel: adopt {} — sable fullyLoad 未产出体(条目在盘上原样保留)", uuid);
            }
        } catch (Throwable t) {
            SablePanel.LOGGER.warn("sablepanel: adopt {} failed", uuid, t);
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

    private ServerSubLevel resolveLoaded(UUID uuid) {
        for (ServerLevel level : this.server.getAllLevels()) {
            try {
                ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
                if (c == null) continue;
                var sl = c.getSubLevel(uuid);
                if (sl instanceof ServerSubLevel ssl && !ssl.isRemoved()) return ssl;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // ---------- 回收站 / 审计 ----------

    /** 面板手动触发磁盘重扫(异步) */
    public void rescanNow() {
        this.rescan.run();
    }

    /** 回收站文件列表 */
    public JsonArray recycleList() {
        JsonArray arr = new JsonArray();
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("sablepanel-recycle");
            if (!Files.isDirectory(dir)) return arr;
            List<Path> files = new ArrayList<>();
            try (var s = Files.list(dir)) {
                s.forEach(files::add);
            }
            files.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
            for (Path p : files) {
                JsonObject o = new JsonObject();
                o.addProperty("file", p.getFileName().toString());
                o.addProperty("size", Files.size(p));
                o.addProperty("mtime", Files.getLastModifiedTime(p).toMillis());
                arr.add(o);
            }
        } catch (Exception e) {
            SablePanel.LOGGER.warn("sablepanel: recycle list failed", e);
        }
        return arr;
    }

    private String exportToRecycle(UUID uuid) {
        try {
            DiskScanner.DiskEntry e = this.index.findEntry(uuid);
            if (e == null) return null;
            Path dir = FMLPaths.GAMEDIR.get().resolve("sablepanel-recycle");
            Files.createDirectories(dir);
            // 读原条目字节(gzip NBT)
            var dims = DiskScanner.sublevelDirs(this.server);
            Path sub = dims.get(e.key().dim());
            if (sub == null) return null;
            Path file = sub.resolve("r." + e.key().rx() + "." + e.key().rz() + "." + e.key().storage() + ".slvls");
            byte[] raw = Files.readAllBytes(file);
            int span = java.nio.ByteBuffer.wrap(raw, e.key().index() * 4, 4).getInt();
            int start = (span >> 8) & 0xFFFFFF;
            if (start <= 0) return null;
            int off = start * 4096;
            int size = java.nio.ByteBuffer.wrap(raw, off, 4).getInt();
            byte[] payload;
            if ((raw[off + 4] & 0x10) != 0) {
                payload = Files.readAllBytes(sub.resolve("r." + e.key().rx() + "." + e.key().rz() + ".r")
                        .resolve(e.key().index() + ".slvl"));
            } else {
                payload = new byte[size - 1];
                System.arraycopy(raw, off + 5, payload, 0, size - 1);
            }
            String fn = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
                    + "_" + uuid.toString().substring(0, 8) + ".nbt.gz";
            Files.write(dir.resolve(fn), payload);
            return fn;
        } catch (Exception ex) {
            SablePanel.LOGGER.warn("sablepanel: recycle export failed for {}", uuid, ex);
            return null;
        }
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

    private interface MainTask {
        JsonObject run() throws Exception;
    }

    private JsonObject onMain(MainTask task) throws Exception {
        return onMain(task, 20);
    }

    private JsonObject onMain(MainTask task, int timeoutSeconds) throws Exception {
        CompletableFuture<JsonObject> fut = new CompletableFuture<>();
        this.server.execute(() -> {
            try {
                fut.complete(task.run());
            } catch (Throwable t) {
                fut.completeExceptionally(t);
            }
        });
        return fut.get(timeoutSeconds, TimeUnit.SECONDS);
    }
}
