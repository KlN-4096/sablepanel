package com.klnon.sablepanel;

import com.klnon.sablepanel.panel.service.PauseService;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.api.PanelApiService;
import com.klnon.sablepanel.panel.client.ClientPanelBootstrap;
import com.klnon.sablepanel.panel.data.BodyIndex;
import com.klnon.sablepanel.panel.data.DiskScanner;
import com.klnon.sablepanel.panel.service.OpsService;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.transport.PanelClusterNode;
import com.klnon.sablepanel.panel.web.PanelWebGateway;
import com.klnon.sablepanel.panel.data.StatsCollector;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSableSubLevelContainerReadyEvent;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
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
    /** 运行时索引刷新周期(tick):面板活跃时 100 = 5s */
    private static final int RUNTIME_REFRESH_TICKS = 100;
    /** 面板空闲时降到 1200 = 60s,只维持耗时采样 drain 与加载数,别的都不做 */
    private static final int RUNTIME_REFRESH_IDLE_TICKS = 1200;
    private int tickCounter;
    private int ticksSinceRefresh;

    private final BodyIndex bodyIndex = new BodyIndex();
    private volatile PanelClusterNode panelNode;
    private volatile PanelWebGateway panelWeb;
    private volatile boolean scanPauseLogged;
    private ScheduledExecutorService scanExecutor;

    private long tickStartNanos;

    public SablePanel() {
        if (FMLEnvironment.dist == Dist.CLIENT) ClientPanelBootstrap.start();
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
        if (this.panelNode != null) {
            this.tickStartNanos = System.nanoTime();
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        try {
            PanelConfig config = PanelConfig.load();
            if (!config.enabled) {
                return;
            }
            BodyCostTracker.ENABLED = true;
            this.bodyIndex.setConfig(config);
            PauseService.load();
            var server = event.getServer();
            // 2 线程:磁盘扫描可能跑几百毫秒,不能把集群心跳挤过 TTL
            this.scanExecutor = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "sablepanel-scan");
                t.setDaemon(true);
                return t;
            });
            Runnable scanOnce = () -> {
                try {
                    // 面板空闲 → 扫描暂停(唤醒时 markActivity 会立即补一轮,数据几秒内追平)
                    PanelClusterNode p = this.panelNode;
                    if (p != null && !p.isActive()) {
                        if (!this.scanPauseLogged) {
                            this.scanPauseLogged = true;
                            LOGGER.info("sablepanel: panel idle, disk scans paused");
                        }
                        return;
                    }
                    this.scanPauseLogged = false;
                    Map<String, java.nio.file.Path> dims = DiskScanner.sublevelDirs(server);
                    this.bodyIndex.updateDisk(DiskScanner.scan(dims));
                } catch (Throwable t) {
                    LOGGER.warn("sablepanel: disk scan cycle failed", t);
                }
            };
            this.scanExecutor.scheduleWithFixedDelay(scanOnce, 5, 120, TimeUnit.SECONDS);
            var scanExec = this.scanExecutor;
            OpsService ops = new OpsService(server, this.bodyIndex, () -> scanExec.execute(scanOnce), config);
            StatsCollector.INSTANCE.start(config);
            PanelApiService api = new PanelApiService(config, server, this.bodyIndex, ops);
            this.panelNode = new PanelClusterNode(config, api);
            this.panelNode.start();
            if (this.panelNode.isHost()) startServerWeb(config);
            var panel = this.panelNode;
            this.scanExecutor.scheduleWithFixedDelay(() -> {
                try {
                    if (panel.clusterTick()) startServerWeb(config);
                } catch (Throwable t) {
                    LOGGER.warn("sablepanel: cluster tick failed", t);
                }
            }, PanelClusterNode.HEARTBEAT_SECONDS, PanelClusterNode.HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        } catch (Throwable t) {
            StatsCollector.INSTANCE.stop();
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
        this.tickCounter++;
        // 面板未启用(enabled=false 或启动失败)→ 每 tick 只剩这一个 null 判断
        if (this.panelNode != null) {
            if (this.tickStartNanos != 0) {
                StatsCollector.INSTANCE.tick(System.nanoTime() - this.tickStartNanos);
            }
            this.ticksSinceRefresh++;
            int interval = this.panelNode.isActive() ? RUNTIME_REFRESH_TICKS : RUNTIME_REFRESH_IDLE_TICKS;
            if (this.ticksSinceRefresh >= interval) {
                try {
                    // 传真实 tick 数:空闲降频后 drain 的 ms/tick 折算才是对的
                    this.bodyIndex.refreshRuntime(event.getServer(), this.ticksSinceRefresh);
                } catch (Throwable ignored) {
                }
                this.ticksSinceRefresh = 0;
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
        BodyCostTracker.ENABLED = false;
        closeServerWeb();
        if (this.panelNode != null) this.panelNode.close();
        this.panelNode = null;
        if (this.scanExecutor != null) {
            this.scanExecutor.shutdownNow();
            this.scanExecutor = null;
        }
        StatsCollector.INSTANCE.stop();
        PauseService.reset();
        EventLog.close();
    }

    private synchronized void startServerWeb(PanelConfig config) {
        if (!config.webEnabled || this.panelWeb != null || this.panelNode == null || !this.panelNode.isHost()) return;
        PanelWebGateway gateway = PanelWebGateway.server(config, this.panelNode.identity().fingerprint());
        try {
            gateway.start();
            this.panelWeb = gateway;
        } catch (Exception error) {
            gateway.close();
            LOGGER.warn("sablepanel: web gateway {}:{} unavailable", config.webBind, config.webPort, error);
        }
    }

    private synchronized void closeServerWeb() {
        if (this.panelWeb != null) this.panelWeb.close();
        this.panelWeb = null;
    }
}
