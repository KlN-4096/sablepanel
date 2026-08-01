package com.klnon.sablepanel;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSableSubLevelContainerReadyEvent;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Map;

@Mod(SablePanel.MOD_ID)
public class SablePanel {
    public static final String MOD_ID = "sablepanel";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** stats 汇总输出周期(tick),1200 = 60s */
    private static final int STATS_INTERVAL_TICKS = 1200;
    private int tickCounter;

    public SablePanel() {
        NeoForge.EVENT_BUS.addListener(this::onContainerReady);
        NeoForge.EVENT_BUS.addListener(this::onPrePhysics);
        NeoForge.EVENT_BUS.addListener(this::onPostPhysics);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        LOGGER.info("SablePanel instrumentation loaded");
    }

    private void onContainerReady(ForgeSableSubLevelContainerReadyEvent event) {
        try {
            if (!(event.getContainer() instanceof ServerSubLevelContainer container)) {
                return;
            }
            String dim = container.getLevel().dimension().location().toString();
            container.addObserver(new PanelObserver(dim));

            JsonObject o = new JsonObject();
            o.addProperty("ev", "container_ready");
            o.addProperty("dim", dim);
            o.addProperty("occupancy", container.getOccupancy().cardinality());
            o.addProperty("loaded", container.getLoadedCount());
            EventLog.write(o);
        } catch (Throwable t) {
            LOGGER.warn("sablepanel: container_ready hook failed", t);
        }
    }

    private void onPrePhysics(ForgeSablePrePhysicsTickEvent event) {
        PhysicsTimer.begin(event.getPhysicsSystem());
    }

    private void onPostPhysics(ForgeSablePostPhysicsTickEvent event) {
        PhysicsTimer.end(event.getPhysicsSystem(), event.getTimeStep());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        if (++this.tickCounter % STATS_INTERVAL_TICKS != 0) {
            return;
        }
        try {
            Map<String, PhysicsTimer.Snapshot> phys = PhysicsTimer.drain();
            for (ServerLevel level : event.getServer().getAllLevels()) {
                String dim = level.dimension().location().toString();
                ServerSubLevelContainer container;
                try {
                    container = SubLevelContainer.getContainer(level);
                } catch (Throwable t) {
                    continue;
                }
                PhysicsTimer.Snapshot ps = phys.remove(dim);
                if (container == null) {
                    continue;
                }
                int loaded = container.getLoadedCount();
                int occupancy = container.getOccupancy().cardinality();
                // 无任何 sable 活动的维度不刷日志
                if (loaded == 0 && occupancy == 0 && ps == null) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("ev", "stats");
                o.addProperty("dim", dim);
                o.addProperty("loaded", loaded);
                o.addProperty("occupancy", occupancy);
                o.addProperty("tickets", container.getAllTickets().size());
                if (ps != null) {
                    o.addProperty("phys_steps", ps.count());
                    o.addProperty("phys_avg_ms", ps.avgMs());
                    o.addProperty("phys_max_ms", ps.maxMs());
                }
                EventLog.write(o);
            }
        } catch (Throwable t) {
            LOGGER.warn("sablepanel: stats tick failed", t);
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        PanelCommand.register(event.getDispatcher());
    }

    // ServerStopped(而非 Stopping):sable 在停服晚期才逐体 UNLOADED,writer 必须活到那之后
    private void onServerStopped(ServerStoppedEvent event) {
        EventLog.close();
    }
}
