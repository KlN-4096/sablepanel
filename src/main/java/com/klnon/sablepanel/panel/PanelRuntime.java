package com.klnon.sablepanel.panel;

import com.klnon.sablepanel.BodyCostTracker;
import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.panel.api.PanelApiService;
import com.klnon.sablepanel.panel.data.BodyIndex;
import com.klnon.sablepanel.panel.data.DiskScanner;
import com.klnon.sablepanel.panel.data.StatsCollector;
import com.klnon.sablepanel.panel.service.JobService;
import com.klnon.sablepanel.panel.service.OpsService;
import com.klnon.sablepanel.panel.service.PauseService;
import com.klnon.sablepanel.panel.transport.PanelClusterNode;
import com.klnon.sablepanel.panel.web.PanelWebGateway;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns the panel service lifecycle independently from NeoForge event wiring. */
public final class PanelRuntime implements AutoCloseable {
    private static final int RUNTIME_REFRESH_TICKS = 100;
    private static final int RUNTIME_REFRESH_IDLE_TICKS = 1200;
    private static final int EXECUTOR_SHUTDOWN_SECONDS = 3;

    private final BodyIndex bodyIndex = new BodyIndex();
    private final Object lifecycleLock = new Object();
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final AtomicBoolean scanPending = new AtomicBoolean();
    private volatile PanelClusterNode panelNode;
    private volatile PanelWebGateway panelWeb;
    private volatile JobService jobService;
    private volatile boolean stopping = true;
    private volatile boolean scanPauseLogged;
    /** 作业结束后由 worker 线程置位,让下一 tick 立刻刷新运行时快照(见 onServerTick) */
    private volatile boolean refreshRequested;
    private ScheduledExecutorService scanExecutor;
    private ScheduledFuture<?> heartbeatTask;
    private int ticksSinceRefresh;

    public synchronized boolean start(MinecraftServer server) throws Exception {
        long generation;
        synchronized (this.lifecycleLock) {
            if (this.panelNode != null || !this.stopping) throw new IllegalStateException("面板已启动");
            generation = this.lifecycleGeneration.incrementAndGet();
            this.stopping = false;
            this.scanPending.set(false);
            BodyCostTracker.ENABLED = false;
        }
        ScheduledExecutorService createdExecutor = null;
        PanelClusterNode createdNode = null;
        ScheduledFuture<?> createdHeartbeat = null;
        try {
            PanelConfig config = PanelConfig.load();
            if (!config.enabled) {
                synchronized (this.lifecycleLock) {
                    if (this.lifecycleGeneration.get() == generation) this.stopping = true;
                }
                return false;
            }
            this.bodyIndex.setConfig(config);
            PauseService.load();
            createdExecutor = Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "sablepanel-scan");
                thread.setDaemon(true);
                return thread;
            });
            ScheduledExecutorService executor = createdExecutor;
            Runnable scanOnce = scanTask(server, generation);
            Runnable requestScan = () -> {
                if (!isLifecycleCurrent(generation) || executor.isShutdown()) return;
                if (!this.scanPending.compareAndSet(false, true)) return;
                try {
                    executor.execute(() -> {
                        try {
                            scanOnce.run();
                        } finally {
                            this.scanPending.set(false);
                        }
                    });
                } catch (RejectedExecutionException rejected) {
                    this.scanPending.set(false);
                }
            };
            OpsService ops = new OpsService(server, this.bodyIndex, requestScan, config);
            StatsCollector.INSTANCE.start(config);
            JobService jobs = new JobService(() -> this.refreshRequested = true);
            this.jobService = jobs;
            SablePanel.LOGGER.info("sablepanel: operation workers <= {} ({} cores)",
                    jobs.maxWorkers(), Runtime.getRuntime().availableProcessors());
            PanelApiService api = new PanelApiService(config, server, this.bodyIndex, ops, jobs);
            createdNode = new PanelClusterNode(config, api);
            createdNode.start();

            PanelClusterNode panel = createdNode;
            executor.scheduleWithFixedDelay(scanOnce, 5, 120, TimeUnit.SECONDS);
            createdHeartbeat = executor.scheduleWithFixedDelay(() -> clusterTick(config, panel, generation),
                    PanelClusterNode.HEARTBEAT_SECONDS, PanelClusterNode.HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            synchronized (this.lifecycleLock) {
                if (!isLifecycleCurrent(generation)) throw new IllegalStateException("面板启动已取消");
                this.scanExecutor = executor;
                this.panelNode = panel;
                this.heartbeatTask = createdHeartbeat;
                BodyCostTracker.ENABLED = true;
            }
            if (panel.isHost()) startServerWeb(config, panel, generation);
            return true;
        } catch (Exception error) {
            rollbackStartup(generation, createdExecutor, createdNode, createdHeartbeat);
            throw error;
        } catch (Error error) {
            rollbackStartup(generation, createdExecutor, createdNode, createdHeartbeat);
            throw error;
        }
    }

    public boolean isRunning() {
        return this.panelNode != null;
    }

    public void onServerTick(MinecraftServer server, long durationNanos) {
        PanelClusterNode panel = this.panelNode;
        if (panel == null) return;
        if (durationNanos > 0) StatsCollector.INSTANCE.tick(durationNanos);
        this.ticksSinceRefresh++;
        int interval = panel.isActive() ? RUNTIME_REFRESH_TICKS : RUNTIME_REFRESH_IDLE_TICKS;
        // 作业刚结束时插队刷新:否则前端要等最多 5 秒(空闲时 60 秒)才看得到状态变化,
        // 这正是从前要在前端写死 setTimeout(loadBodies, 1200) 的原因
        if (!this.refreshRequested && this.ticksSinceRefresh < interval) return;
        this.refreshRequested = false;
        try {
            this.bodyIndex.refreshRuntime(server, Math.max(1, this.ticksSinceRefresh));
        } catch (Throwable ignored) {
        }
        this.ticksSinceRefresh = 0;
    }

    @Override
    public synchronized void close() {
        ScheduledFuture<?> heartbeat;
        ScheduledExecutorService executor;
        PanelClusterNode node;
        JobService jobs;
        synchronized (this.lifecycleLock) {
            this.stopping = true;
            this.lifecycleGeneration.incrementAndGet();
            BodyCostTracker.ENABLED = false;
            heartbeat = this.heartbeatTask;
            executor = this.scanExecutor;
            node = this.panelNode;
            jobs = this.jobService;
            this.heartbeatTask = null;
            this.scanExecutor = null;
            this.panelNode = null;
            this.jobService = null;
        }
        if (heartbeat != null) heartbeat.cancel(true);
        closeServerWeb();
        if (node != null) node.close();
        if (jobs != null) jobs.close();
        shutdownExecutor(executor);
        StatsCollector.INSTANCE.stop();
        PauseService.reset();
        this.scanPauseLogged = false;
        this.scanPending.set(false);
        this.ticksSinceRefresh = 0;
    }

    private Runnable scanTask(MinecraftServer server, long generation) {
        return () -> {
            try {
                if (!isLifecycleCurrent(generation)) return;
                PanelClusterNode panel = this.panelNode;
                if (panel == null) return;
                if (!panel.isActive()) {
                    if (!this.scanPauseLogged) {
                        this.scanPauseLogged = true;
                        SablePanel.LOGGER.info("sablepanel: panel idle, disk scans paused");
                    }
                    return;
                }
                this.scanPauseLogged = false;
                Map<String, java.nio.file.Path> dimensions = DiskScanner.sublevelDirs(server);
                this.bodyIndex.updateDisk(DiskScanner.scan(dimensions));
            } catch (Throwable error) {
                SablePanel.LOGGER.warn("sablepanel: disk scan cycle failed", error);
            }
        };
    }

    private void clusterTick(PanelConfig config, PanelClusterNode panel, long generation) {
        if (!isCurrentPanel(generation, panel)) return;
        try {
            panel.clusterTick();
            if (!isCurrentPanel(generation, panel)) return;
            if (panel.isHost()) startServerWeb(config, panel, generation);
            else closeServerWeb();
        } catch (Throwable error) {
            SablePanel.LOGGER.warn("sablepanel: cluster tick failed", error);
        }
    }

    private synchronized void startServerWeb(PanelConfig config, PanelClusterNode panel, long generation) {
        if (!config.webEnabled || this.panelWeb != null || !isCurrentPanel(generation, panel)
                || !panel.isHost()) return;
        PanelWebGateway gateway = PanelWebGateway.server(config, panel.identity().fingerprint());
        try {
            gateway.start();
            if (isCurrentPanel(generation, panel) && panel.isHost()) this.panelWeb = gateway;
            else gateway.close();
        } catch (Exception error) {
            gateway.close();
            SablePanel.LOGGER.warn("sablepanel: web gateway {}:{} unavailable",
                    config.webBind, config.webPort, error);
        }
    }

    private synchronized void closeServerWeb() {
        if (this.panelWeb != null) this.panelWeb.close();
        this.panelWeb = null;
    }

    private void rollbackStartup(long generation, ScheduledExecutorService executor, PanelClusterNode node,
                                 ScheduledFuture<?> heartbeat) {
        JobService jobs;
        synchronized (this.lifecycleLock) {
            boolean ownsLifecycle = this.lifecycleGeneration.compareAndSet(generation, generation + 1);
            if (ownsLifecycle) {
                this.stopping = true;
                BodyCostTracker.ENABLED = false;
            }
            if (this.panelNode == node) this.panelNode = null;
            if (this.scanExecutor == executor) this.scanExecutor = null;
            if (this.heartbeatTask == heartbeat) this.heartbeatTask = null;
            jobs = this.jobService;
            this.jobService = null;
        }
        if (heartbeat != null) heartbeat.cancel(true);
        closeServerWeb();
        if (node != null) node.close();
        if (jobs != null) jobs.close();
        shutdownExecutor(executor);
        StatsCollector.INSTANCE.stop();
        PauseService.reset();
        this.scanPauseLogged = false;
        this.scanPending.set(false);
    }

    private boolean isLifecycleCurrent(long generation) {
        return !this.stopping && this.lifecycleGeneration.get() == generation;
    }

    private boolean isCurrentPanel(long generation, PanelClusterNode panel) {
        return isLifecycleCurrent(generation) && this.panelNode == panel;
    }

    private static void shutdownExecutor(ScheduledExecutorService executor) {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(EXECUTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
