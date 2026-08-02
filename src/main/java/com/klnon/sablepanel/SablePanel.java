package com.klnon.sablepanel;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.BodyIndex;
import com.klnon.sablepanel.panel.DiskScanner;
import com.klnon.sablepanel.panel.OpsService;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.PanelHttpServer;
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
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Mod(SablePanel.MOD_ID)
public class SablePanel {
    public static final String MOD_ID = "sablepanel";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** stats 汇总输出周期(tick),1200 = 60s */
    private static final int STATS_INTERVAL_TICKS = 1200;
    /** 运行时索引刷新周期(tick),100 = 5s */
    private static final int RUNTIME_REFRESH_TICKS = 100;
    private int tickCounter;

    private final BodyIndex bodyIndex = new BodyIndex();
    private PanelHttpServer panelServer;
    private ScheduledExecutorService scanExecutor;

    private long tickStartNanos;

    public SablePanel() {
        NeoForge.EVENT_BUS.addListener(this::onContainerReady);
        NeoForge.EVENT_BUS.addListener(this::onPrePhysics);
        NeoForge.EVENT_BUS.addListener(this::onPostPhysics);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        LOGGER.info("SablePanel instrumentation loaded");
    }

    private void onServerTickPre(ServerTickEvent.Pre event) {
        this.tickStartNanos = System.nanoTime();
    }

    private void onServerStarted(ServerStartedEvent event) {
        try {
            PanelConfig config = PanelConfig.load();
            if (!config.enabled) {
                return;
            }
            this.bodyIndex.setConfig(config);
            var server = event.getServer();
            // 2 线程:磁盘扫描可能跑几百毫秒,不能把集群心跳挤过 TTL
            this.scanExecutor = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "sablepanel-scan");
                t.setDaemon(true);
                return t;
            });
            Runnable scanOnce = () -> {
                try {
                    Map<String, java.nio.file.Path> dims = DiskScanner.sublevelDirs(server);
                    this.bodyIndex.updateDisk(DiskScanner.scan(dims));
                } catch (Throwable t) {
                    LOGGER.warn("sablepanel: disk scan cycle failed", t);
                }
            };
            this.scanExecutor.scheduleWithFixedDelay(scanOnce, 5, 120, TimeUnit.SECONDS);
            var scanExec = this.scanExecutor;
            OpsService ops = new OpsService(server, this.bodyIndex, () -> scanExec.execute(scanOnce));
            this.panelServer = new PanelHttpServer(config, server, this.bodyIndex, ops);
            this.panelServer.start();
            var panel = this.panelServer;
            this.scanExecutor.scheduleWithFixedDelay(() -> {
                try {
                    panel.clusterTick();
                } catch (Throwable t) {
                    LOGGER.warn("sablepanel: cluster tick failed", t);
                }
            }, PanelHttpServer.HEARTBEAT_SECONDS, PanelHttpServer.HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        } catch (Throwable t) {
            LOGGER.error("sablepanel: panel startup failed", t);
        }
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
        if (this.tickStartNanos != 0) {
            com.klnon.sablepanel.panel.StatsCollector.INSTANCE.tick(System.nanoTime() - this.tickStartNanos);
        }
        this.tickCounter++;
        if (this.tickCounter % RUNTIME_REFRESH_TICKS == 0) {
            try {
                this.bodyIndex.refreshRuntime(event.getServer(), RUNTIME_REFRESH_TICKS);
            } catch (Throwable ignored) {
            }
        }
        if (this.tickCounter % STATS_INTERVAL_TICKS != 0) {
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
        if (this.panelServer != null) {
            this.panelServer.stop();
            this.panelServer = null;
        }
        if (this.scanExecutor != null) {
            this.scanExecutor.shutdownNow();
            this.scanExecutor = null;
        }
        EventLog.close();
    }
}
