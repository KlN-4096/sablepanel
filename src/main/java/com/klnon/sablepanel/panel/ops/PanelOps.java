package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.bodies.BodyIndex;
import com.klnon.sablepanel.panel.recycle.RecycleStore;
import net.minecraft.server.MinecraftServer;
import com.klnon.sablepanel.panel.compat.sable203.ConsistencyService;

/**
 * 面板操作服务的装配根。各服务共享 OpKit(单把变更锁)与同一个回收站;
 * 变更型入口(删除/恢复/副本处理/一致性修复)全部串行 —— 原 OpsService 单实例监视器语义不变。
 */
public record PanelOps(OpKit kit, TeleportOps teleport, AdoptOps adopt, DeleteOps delete,
                       RestoreOps restore, CopyOps copies, RecycleStore recycle,
                       ConsistencyService consistency) {

    public static PanelOps create(MinecraftServer server, BodyIndex index, Runnable rescan,
                                  PanelConfig config) {
        OpKit kit = new OpKit(server, index, rescan);
        RecycleStore recycle = new RecycleStore(config);
        DeleteTx tx = new DeleteTx(kit);
        RestoreOps restore = new RestoreOps(kit, tx, recycle);
        return new PanelOps(kit, new TeleportOps(kit), new AdoptOps(kit),
                new DeleteOps(kit, tx, restore, recycle), restore,
                new CopyOps(kit, tx, restore, recycle), recycle,
                new ConsistencyService(server, kit.lock, rescan));
    }
}
