package com.klnon.sablepanel.panel.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 运行态操作:传送/暂停/常驻加载/在线玩家交互。sable 交互全部主线程执行。 */
public final class TeleportOps {
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
            var pipeline = phys.getPipeline();
            finishTeleport(() -> pipeline.teleport(sl, target, sl.logicalPose().orientation()),
                    () -> pipeline.resetVelocity(sl), sl::updateLastPose, () -> PauseService.reanchor(sl));
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

    /** 单体物理暂停/恢复 = 挂/拆引擎固定约束(同物理手杖锁定),持久化,重启后保持 */
    public JsonObject setPaused(List<UUID> uuids, boolean paused) throws Exception {
        this.kit.onMain(() -> {
            com.klnon.sablepanel.panel.ops.PauseService.applyOnMain(this.kit.server, uuids, paused);
            return null;
        });
        PauseService.persist();
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
        List<UUID> uuids = this.kit.expandToDependencyGroups(requested);
        Set<UUID> newlyFrozen = new HashSet<>();
        if (forced) for (UUID uuid : uuids) if (!FreezeService.isFrozen(uuid)) newlyFrozen.add(uuid);
        // 整批一次建链:多选往往是同一个依赖组的成员,分层 BFS 会把它们一趟解完。
        // 逐个建链会把同一批 .slvls 解压 N 遍 —— 全选 178 体的绳链时就是 178 遍。
        Map<UUID, OpKit.MemberPlan> chain = Map.of();
        if (forced) {
            // 已加载的体不用进链:ensureLoaded 第一行 resolveLoaded 就会返回。
            // 生产上曾为一个已加载的 178 依赖体白扫 16 分钟磁盘。
            List<UUID> cold = uuids.stream().filter(u -> !this.kit.index.isLoaded(u)).toList();
            if (!cold.isEmpty()) chain = this.kit.prepareChain(cold); // 作业线程做磁盘定位,不占主线程
        }
        Map<UUID, OpKit.MemberPlan> plans = chain;
        // ThreadLocal 到不了主线程,先在作业线程上取出来捕获进 lambda
        JobService.Job job = JobService.current();
        JsonObject out = this.kit.onMainUntilComplete(() -> {
            JsonArray failed = new JsonArray();
            Set<UUID> newlyTicketed = new HashSet<>();
            int done = 0;
            if (forced) FreezeService.applyOnMain(uuids, true);
            for (UUID uuid : uuids) {
                if (job != null) job.phase(forced ? "挂常驻票" : "摘常驻票");
                if (!forced) {
                    ForceLoadService.removeOnMain(this.kit.server, uuid);
                    done++;
                    continue;
                }
                try {
                    boolean alreadyForced = ForceLoadService.isForcedOnMain(this.kit.server, uuid);
                    ServerSubLevel body = this.kit.ensureLoaded(uuid, plans);
                    if (!PauseService.onBodyLoaded(body)) {
                        throw new IllegalStateException("固定物理失败");
                    }
                    if (!alreadyForced) newlyTicketed.add(uuid);
                    ForceLoadService.addOnMain(body);
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
                for (UUID uuid : newlyTicketed) {
                    try {
                        ForceLoadService.removeStrictOnMain(this.kit.server, uuid);
                    } catch (Throwable rollbackFailure) {
                        operationFailure.addSuppressed(rollbackFailure);
                    }
                }
                FreezeService.applyOnMain(newlyFrozen, false);
                PauseService.refreshOnMain(this.kit.server, newlyFrozen);
                throw operationFailure;
            }
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("forced", forced);
            o.addProperty("count", done);
            if (!failed.isEmpty()) o.add("failed", failed);
            return o;
        });
        // 摘票就解冻:冻结是常驻加载的附属品,票没了还冻着只会让用户莫名其妙
        if (!forced) {
            FreezeService.applyOnMain(uuids, false);
            this.kit.onMain(() -> {
                PauseService.refreshOnMain(this.kit.server, uuids);
                return null;
            });
        }
        out.addProperty("frozen", forced);
        out.addProperty("requested", requested.size());
        for (UUID uuid : uuids) this.kit.audit(forced ? "force_load" : "force_unload", uuid, null, null);
        return out;
    }

    /**
     * 用户可解冻单个组恢复它的 tick。会崩是常态,所以调用方(前端)必须先弹警告 ——
     * 后端只按体量给出 {@code heavy} 标记,拦不拦由用户决定(不设闸门是既定约定)。
     */
    public JsonObject setFrozen(List<UUID> requested, boolean frozen) throws Exception {
        List<UUID> uuids = this.kit.expandToDependencyGroups(requested);
        Set<UUID> changed = new HashSet<>();
        for (UUID uuid : uuids) if (FreezeService.isFrozen(uuid) != frozen) changed.add(uuid);
        this.kit.onMain(() -> {
            FreezeService.applyOnMain(uuids, frozen);
            Set<UUID> failed = PauseService.refreshOnMain(this.kit.server, uuids);
            if (!failed.isEmpty()) {
                FreezeService.applyOnMain(changed, !frozen);
                PauseService.refreshOnMain(this.kit.server, changed);
                throw new IllegalStateException("固定物理失败: " + failed);
            }
            return null;
        });
        for (UUID uuid : requested) this.kit.audit(frozen ? "freeze" : "thaw", uuid, null, null);
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("frozen", frozen);
        out.addProperty("count", uuids.size());
        return out;
    }

    static void finishTeleport(Runnable teleport, Runnable resetVelocity, Runnable updateLastPose, Runnable reanchor) {
        teleport.run();
        resetVelocity.run();
        updateLastPose.run();
        reanchor.run();
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
