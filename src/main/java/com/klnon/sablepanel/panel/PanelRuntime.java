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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Owns the panel service lifecycle independently from NeoForge event wiring. */
public final class PanelRuntime implements AutoCloseable {
    private static final int RUNTIME_REFRESH_TICKS = 100;
    private static final int RUNTIME_REFRESH_IDLE_TICKS = 1200;
    private static final int EVENT_REFRESH_TICKS = 5;
    private static final int EXECUTOR_SHUTDOWN_SECONDS = 3;

    private final BodyIndex bodyIndex = new BodyIndex();
    private final Object lifecycleLock = new Object();
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final AtomicBoolean scanPending = new AtomicBoolean();
    private final AtomicBoolean refreshRequested = new AtomicBoolean();
    private final AtomicLong bodiesRevision = new AtomicLong();
    private volatile PanelClusterNode panelNode;
    private volatile PanelWebGateway panelWeb;
    private volatile JobService jobService;
    private volatile boolean stopping = true;
    private volatile boolean scanPauseLogged;
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
            this.refreshRequested.set(true);
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
            Runnable requestScan = mergedRunner(this.scanPending, executor,
                    () -> isLifecycleCurrent(generation) && !executor.isShutdown(), scanOnce);
            OpsService ops = new OpsService(server, this.bodyIndex, requestScan, config);
            StatsCollector.INSTANCE.start(config);
            JobService jobs = new JobService(this::requestRuntimeRefresh);
            this.jobService = jobs;
            SablePanel.LOGGER.info("sablepanel: operation workers <= {} ({} cores)",
                    jobs.maxWorkers(), Runtime.getRuntime().availableProcessors());
            PanelApiService api = new PanelApiService(config, server, this.bodyIndex, ops, jobs);
            createdNode = new PanelClusterNode(config, api);
            createdNode.start();

            PanelClusterNode panel = createdNode;
            // 周期扫描和手动重扫走同一道 scanPending 门闩:从前周期任务直接跑 scanOnce,
            // 扫描期间点重扫就会有第二个线程把同一批磁盘数据再全量解压一遍
            executor.scheduleWithFixedDelay(requestScan, 5, 120, TimeUnit.SECONDS);
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

    /** Sable/作业线程只置脏标记；真正读取容器统一延迟到服务器 Tick 末尾。 */
    public void requestRuntimeRefresh() {
        this.refreshRequested.set(true);
    }

    public void onServerTick(MinecraftServer server, long durationNanos) {
        PanelClusterNode panel = this.panelNode;
        if (panel == null) return;
        if (durationNanos > 0) StatsCollector.INSTANCE.tick(durationNanos);
        this.ticksSinceRefresh++;
        int interval = panel.isActive() ? RUNTIME_REFRESH_TICKS : RUNTIME_REFRESH_IDLE_TICKS;
        boolean dirty = this.refreshRequested.get();
        // 事件刷新最多每 5 tick 一次：正常变化约 250ms 内可见，碎片风暴不会每 tick 全量扫描。
        int nextRefresh = dirty ? Math.min(EVENT_REFRESH_TICKS, interval) : interval;
        if (this.ticksSinceRefresh < nextRefresh) return;
        boolean requested = this.refreshRequested.getAndSet(false);
        try {
            this.bodyIndex.refreshRuntime(server, Math.max(1, this.ticksSinceRefresh));
        } catch (Throwable ignored) {
            if (requested) this.refreshRequested.set(true);
            return;
        }
        this.ticksSinceRefresh = 0;
        if (requested) publishBodiesChanged(panel);
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
        this.refreshRequested.set(false);
        this.ticksSinceRefresh = 0;
    }

    /**
     * 周期扫描和手动重扫共用的合并门闩:任何时刻最多一次完整扫描在跑,扫描期间的额外请求
     * 直接丢弃(不排队成后续扫描 —— 扫描本来就是拿全量快照,再跑一遍没有新信息)。
     * <p>
     * 从前周期任务直接调原始 scanOnce、只有手动入口过门闩,于是周期扫描进行时点一次重扫,
     * 就会有第二个线程把同一批磁盘数据再全量解压一遍,还占着扫描/心跳共用的调度池。
     */
    static Runnable mergedRunner(AtomicBoolean pending, Executor executor, BooleanSupplier alive, Runnable work) {
        return () -> {
            if (!alive.getAsBoolean()) return;
            if (!pending.compareAndSet(false, true)) return;
            try {
                executor.execute(() -> {
                    try {
                        work.run();
                    } finally {
                        pending.set(false);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                pending.set(false);
            }
        };
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
                var entries = DiskScanner.scan(dimensions);
                if (!isLifecycleCurrent(generation)) return;
                if (this.bodyIndex.updateDisk(entries)) publishBodiesChanged(panel);
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
        this.refreshRequested.set(false);
    }

    private void publishBodiesChanged(PanelClusterNode panel) {
        try {
            panel.publishBodiesChanged(this.bodiesRevision.incrementAndGet());
        } catch (Throwable error) {
            SablePanel.LOGGER.warn("sablepanel: body change event publish failed", error);
        }
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
