package com.klnon.sablepanel.panel.bodies;

import com.klnon.sablepanel.panel.metrics.BodyCostTracker;
import com.klnon.sablepanel.panel.metrics.PhysicsTimer;
import com.klnon.sablepanel.panel.audit.PanelObserver;
import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.panel.api.PanelApiService;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import com.klnon.sablepanel.panel.metrics.StatsCollector;
import com.klnon.sablepanel.panel.ops.JobService;
import com.klnon.sablepanel.panel.ops.PanelOps;
import com.klnon.sablepanel.panel.ops.PauseService;
import com.klnon.sablepanel.panel.MainThread;
import com.klnon.sablepanel.panel.transport.PanelClusterNode;
import com.klnon.sablepanel.panel.gateway.PanelWebGateway;
import com.klnon.sablepanel.panel.preview.DiskPreviewSource;
import com.klnon.sablepanel.panel.preview.PreviewSubsystem;
import com.klnon.sablepanel.panel.preview.resources.ResourcePreparation;
import com.klnon.sablepanel.panel.preview.resources.VanillaResourceCache;
import com.klnon.sablepanel.panel.preview.resources.ModResourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import com.klnon.sablepanel.panel.PanelConfig;

/** Owns the panel service lifecycle independently from NeoForge event wiring. */
public final class PanelRuntime implements AutoCloseable {
    private static final int RUNTIME_REFRESH_TICKS = 100;
    private static final int RUNTIME_REFRESH_IDLE_TICKS = 1200;
    private static final int EVENT_REFRESH_TICKS = 20;
    private static final int LEGACY_MIGRATION_ATTEMPTS = 3;
    private static final int EXECUTOR_SHUTDOWN_SECONDS = 3;
    private static final int SCAN_IDLE = 0;
    private static final int SCAN_RUNNING = 1;
    private static final int SCAN_RERUN = 2;

    private final BodyIndex bodyIndex = new BodyIndex();
    private final Object lifecycleLock = new Object();
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final AtomicBoolean refreshRequested = new AtomicBoolean();
    private final AtomicLong bodiesRevision = new AtomicLong();
    private volatile PanelClusterNode panelNode;
    private volatile PanelWebGateway panelWeb;
    private volatile JobService jobService;
    private volatile PreviewSubsystem previewSubsystem;
    private PanelConfig preparedConfig;
    private volatile boolean stopping = true;
    private volatile boolean scanPauseLogged;
    private ScheduledExecutorService controlExecutor;
    private ExecutorService scanExecutor;
    private ScheduledFuture<?> heartbeatTask;
    private final RefreshCadence cadence = new RefreshCadence();
    private volatile ForceRestoreAttempt failedForceRestore;

    record ForceRestoreAttempt(List<UUID> pending, long diskRevision) {
        ForceRestoreAttempt {
            pending = List.copyOf(pending);
        }
    }

    /** 世界加载前读取安全意图，确保 Sable 恢复常驻体时约束判据已经可用。 */
    public synchronized void prepareForServer() {
        if (this.preparedConfig != null) return;
        PanelConfig config = PanelConfig.load();
        if (config.enabled) {
            PauseService.load();
            com.klnon.sablepanel.panel.ops.FreezeService.load();
            com.klnon.sablepanel.panel.ops.ForceLoadService.load();
            com.klnon.sablepanel.panel.ops.PhysicsService.load();
        }
        this.preparedConfig = config;
    }

    public synchronized boolean start(MinecraftServer server) throws Exception {
        long generation;
        synchronized (this.lifecycleLock) {
            if (this.panelNode != null || !this.stopping) throw new IllegalStateException("面板已启动");
            generation = this.lifecycleGeneration.incrementAndGet();
            this.stopping = false;
            this.refreshRequested.set(true);
            this.failedForceRestore = null;
            BodyCostTracker.ENABLED = false;
            PhysicsTimer.ENABLED = false;
            PanelObserver.ENABLED = false;
        }
        ScheduledExecutorService createdControlExecutor = null;
        ExecutorService createdScanExecutor = null;
        PanelClusterNode createdNode = null;
        ScheduledFuture<?> createdHeartbeat = null;
        try {
            prepareForServer();
            PanelConfig config = this.preparedConfig;
            if (!config.enabled) {
                synchronized (this.lifecycleLock) {
                    if (this.lifecycleGeneration.get() == generation) this.stopping = true;
                }
                return false;
            }
            this.bodyIndex.setConfig(config);
            MainThread.onUntilComplete(server, () -> {
                com.klnon.sablepanel.panel.ops.ForceLoadService.captureNativeIntentsOnMain(server);
                return null;
            });
            createdControlExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sablepanel-control");
                thread.setDaemon(true);
                return thread;
            });
            createdScanExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sablepanel-scan");
                thread.setDaemon(true);
                return thread;
            });
            ScheduledExecutorService control = createdControlExecutor;
            ExecutorService scans = createdScanExecutor;
            AtomicInteger scanState = new AtomicInteger();
            Runnable scanOnce = scanTask(server, generation);
            Runnable requestScan = mergedRunner(scanState, scans,
                    () -> isLifecycleCurrent(generation) && !scans.isShutdown(), scanOnce);
            PanelOps ops = PanelOps.create(server, this.bodyIndex, requestScan, config);
            StatsCollector.INSTANCE.start();
            JobService jobs = new JobService(this::requestRuntimeRefresh);
            this.jobService = jobs;
            SablePanel.LOGGER.info("sablepanel: operation workers <= {} ({} cores)",
                    jobs.maxWorkers(), Runtime.getRuntime().availableProcessors());
            ResourcePreparation resources = new ResourcePreparation(
                    progress -> new VanillaResourceCache(
                            net.neoforged.fml.loading.FMLPaths.GAMEDIR.get(), progress).prepare(),
                    baseline -> ModResourceStack.loaded(baseline.archive()));
            DiskPreviewSource previewSource = new DiskPreviewSource(server, this.bodyIndex);
            PreviewSubsystem preview = new PreviewSubsystem(previewSource, resources);
            this.previewSubsystem = preview;
            // 缩略图缓存(渲染在浏览器):缓存目录建不起来只降级(卡片保持占位),不拦面板启动
            com.klnon.sablepanel.panel.preview.thumb.ThumbService thumbs = null;
            try {
                thumbs = new com.klnon.sablepanel.panel.preview.thumb.ThumbService(
                        net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("cache/sablepanel/thumbs"),
                        this.bodyIndex::thumbnailSignature);
            } catch (java.io.IOException unavailable) {
                SablePanel.LOGGER.warn("sablepanel: thumbnail cache unavailable", unavailable);
            }
            PanelApiService api = new PanelApiService(config, this.bodyIndex, ops, jobs, preview, thumbs);
            createdNode = new PanelClusterNode(config, api);
            createdNode.start();

            PanelClusterNode panel = createdNode;
            // 周期扫描和手动重扫走同一道门闩；运行期请求只合并成一次补跑。
            control.scheduleWithFixedDelay(requestScan, 5, 120, TimeUnit.SECONDS);
            control.scheduleWithFixedDelay(
                    () -> restoreForcedIntents(server, ops, generation), 2, 30, TimeUnit.SECONDS);
            // 和首次普通扫描排在同一个单线程磁盘队列里，避免启动期重复解压同一份存档。
            control.schedule(() -> {
                if (!isLifecycleCurrent(generation) || scans.isShutdown()) return;
                try {
                    scans.execute(() -> {
                        if (isLifecycleCurrent(generation)) ops.consistency().scan();
                    });
                } catch (RejectedExecutionException ignored) {
                }
            }, 10, TimeUnit.SECONDS);
            createdHeartbeat = control.scheduleWithFixedDelay(() -> clusterTick(config, panel, generation),
                    PanelClusterNode.HEARTBEAT_SECONDS, PanelClusterNode.HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            var lockFailures = PauseService.refreshAllOnMain(server);
            if (!lockFailures.isEmpty()) {
                SablePanel.LOGGER.error("sablepanel: startup physical lock failed for bodies {}; "
                        + "their explicit physics pause is not active", lockFailures);
            }
            synchronized (this.lifecycleLock) {
                if (!isLifecycleCurrent(generation)) throw new IllegalStateException("面板启动已取消");
                this.controlExecutor = control;
                this.scanExecutor = scans;
                this.panelNode = panel;
                this.heartbeatTask = createdHeartbeat;
                BodyCostTracker.ENABLED = true;
                PhysicsTimer.ENABLED = true;
                PanelObserver.ENABLED = true;
            }
            scheduleLegacyPauseMigration(ops, control, scans, generation, 1);
            if (panel.isHost()) startServerWeb(config, panel, generation);
            return true;
        } catch (Exception error) {
            rollbackStartup(generation, createdControlExecutor, createdScanExecutor,
                    createdNode, createdHeartbeat);
            throw error;
        } catch (Error error) {
            rollbackStartup(generation, createdControlExecutor, createdScanExecutor,
                    createdNode, createdHeartbeat);
            throw error;
        }
    }

    private void scheduleLegacyPauseMigration(PanelOps ops, ScheduledExecutorService control,
                                              ExecutorService scans, long generation, int attempt) {
        try {
            scans.execute(() -> {
                if (!isLifecycleCurrent(generation)) return;
                try {
                    int normalized = ops.teleport().normalizePersistedPausedGroups();
                    if (normalized > 0) {
                        SablePanel.LOGGER.info("sablepanel: normalized {} legacy paused members to full groups",
                                normalized);
                    }
                } catch (Exception error) {
                    if (attempt >= LEGACY_MIGRATION_ATTEMPTS) {
                        SablePanel.LOGGER.error("sablepanel: legacy paused-state group migration failed after {} attempts",
                                attempt, error);
                        return;
                    }
                    control.schedule(() -> scheduleLegacyPauseMigration(
                            ops, control, scans, generation, attempt + 1), 1, TimeUnit.SECONDS);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** Sable/作业线程只置脏标记；真正读取容器统一延迟到服务器 Tick 末尾。 */
    public void requestRuntimeRefresh() {
        this.refreshRequested.set(true);
    }

    public void onServerTick(MinecraftServer server) {
        PanelClusterNode panel = this.panelNode;
        if (panel == null) return;
        StatsCollector.INSTANCE.tick();
        if (!this.cadence.due(this.refreshRequested.get(), panel.isActive())) return;
        boolean requested = this.refreshRequested.getAndSet(false);
        int elapsed = this.cadence.begin();
        try {
            this.bodyIndex.refreshRuntime(server, elapsed);
        } catch (Throwable failure) {
            if (requested) this.refreshRequested.set(true);
            if (this.cadence.failedShouldLog()) {
                SablePanel.LOGGER.warn("sablepanel: runtime refresh failed (consecutive #{})",
                        this.cadence.failures(), failure);
            }
            return;
        }
        this.cadence.succeeded();
        if (requested) publishBodiesChanged(panel);
    }

    /** 刷新节奏与失败记账。失败也归零计数:否则 refreshRuntime 持续抛出会退化成每 tick 重试全量刷新。 */
    static final class RefreshCadence {
        private int ticksSinceRefresh;
        private int sinceSuccess;
        private long consecutiveFailures;

        /** 每 tick 调用;true = 到点,调用方随后必须 {@link #begin()}。 */
        boolean due(boolean dirty, boolean active) {
            this.ticksSinceRefresh++;
            int interval = active ? RUNTIME_REFRESH_TICKS : RUNTIME_REFRESH_IDLE_TICKS;
            // 事件刷新最多每秒一次：主体变化仍及时可见，碎片风暴不会每 5 tick 重建全量运行态。
            int next = dirty ? Math.min(EVENT_REFRESH_TICKS, interval) : interval;
            return this.ticksSinceRefresh >= next;
        }

        /**
         * 归零到点计数,返回距上次成功的真实间隔——elapsed 是 BodyCostTracker 的除数,
         * 失败轮 drain 没跑、纳秒还在囤,除数必须跟着累计,否则首次成功时单体耗时虚高。
         */
        int begin() {
            this.sinceSuccess += this.ticksSinceRefresh;
            this.ticksSinceRefresh = 0;
            return Math.max(1, this.sinceSuccess);
        }

        void succeeded() {
            this.consecutiveFailures = 0;
            this.sinceSuccess = 0;
        }

        /** 失败限频:首个失败与之后每 64 个打一条,持续故障不刷屏也不静默。 */
        boolean failedShouldLog() {
            return this.consecutiveFailures++ % 64 == 0;
        }

        long failures() {
            return this.consecutiveFailures;
        }

        void reset() {
            this.ticksSinceRefresh = 0;
            this.sinceSuccess = 0;
            this.consecutiveFailures = 0;
        }
    }

    @Override
    public synchronized void close() {
        ScheduledFuture<?> heartbeat;
        ScheduledExecutorService control;
        ExecutorService scans;
        PanelClusterNode node;
        JobService jobs;
        PreviewSubsystem preview;
        synchronized (this.lifecycleLock) {
            this.stopping = true;
            this.lifecycleGeneration.incrementAndGet();
            BodyCostTracker.ENABLED = false;
            PhysicsTimer.ENABLED = false;
            PanelObserver.ENABLED = false;
            heartbeat = this.heartbeatTask;
            control = this.controlExecutor;
            scans = this.scanExecutor;
            node = this.panelNode;
            jobs = this.jobService;
            preview = this.previewSubsystem;
            this.heartbeatTask = null;
            this.controlExecutor = null;
            this.scanExecutor = null;
            this.panelNode = null;
            this.jobService = null;
            this.previewSubsystem = null;
        }
        if (heartbeat != null) heartbeat.cancel(true);
        closeServerWeb();
        if (node != null) node.close();
        if (jobs != null) jobs.close();
        if (preview != null) preview.close();
        shutdownExecutor(control);
        shutdownExecutor(scans);
        PauseService.reset();
        com.klnon.sablepanel.panel.ops.FreezeService.reset();
        com.klnon.sablepanel.panel.ops.ForceLoadService.reset();
        com.klnon.sablepanel.panel.ops.PhysicsService.reset();
        this.preparedConfig = null;
        this.scanPauseLogged = false;
        this.refreshRequested.set(false);
        this.cadence.reset();
    }

    public void prepareForStop(MinecraftServer server) {
        try {
            MainThread.onUntilComplete(server, () -> {
                com.klnon.sablepanel.panel.ops.ForceLoadService.prepareForStopOnMain(server);
                return null;
            });
        } catch (Throwable error) {
            SablePanel.LOGGER.error("sablepanel: failed to detach native force-load tickets before shutdown", error);
            throw new IllegalStateException("停服前常驻票清理失败，已中止正常停服保存", error);
        }
    }

    private void restoreForcedIntents(MinecraftServer server, PanelOps ops, long generation) {
        List<UUID> requested = com.klnon.sablepanel.panel.ops.ForceLoadService.requestedSnapshot()
                .stream().sorted().toList();
        if (requested.isEmpty() || !isLifecycleCurrent(generation)) {
            if (requested.isEmpty()) this.failedForceRestore = null;
            return;
        }
        List<UUID> pending;
        try {
            // 一次往返核对全部意图:逐个往返是 O(意图数) 次任务分发,且每 uuid 都全扫一遍票表
            pending = MainThread.onUntilComplete(server, () -> classifyStaleIntents(
                    com.klnon.sablepanel.panel.ops.ForceLoadService.forcedOnMain(server), requested,
                    uuid -> com.klnon.sablepanel.panel.ops.ForceLoadService.isLoadedOnMain(server, uuid),
                    uuid -> com.klnon.sablepanel.panel.ops.ForceLoadService
                            .detachNativeTicketsOnMain(server, List.of(uuid))));
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: checking force-load intents failed", error);
            return;
        }
        if (!pending.isEmpty() && isLifecycleCurrent(generation)) {
            long diskRevision = this.bodyIndex.diskRevision();
            if (diskRevision == 0) return;
            ForceRestoreAttempt attempt = new ForceRestoreAttempt(pending, diskRevision);
            if (!shouldAttemptForceRestore(this.failedForceRestore, attempt)) return;
            try {
                ops.teleport().restoreForcedIntents(pending);
                this.failedForceRestore = null;
            } catch (Exception error) {
                this.failedForceRestore = attempt;
                SablePanel.LOGGER.warn("sablepanel: restoring {} force-load intents failed", pending.size(), error);
            }
        } else if (pending.isEmpty()) {
            this.failedForceRestore = null;
        }
    }

    /**
     * 主线程单跳内的意图分类:forced∧loaded=活着跳过;forced∧!loaded 先剥原生票再进 stale;
     * !forced 直接进 stale。任何单 uuid 的查询/剥离失败只跳过它自己(与逐个往返时代同语义,
     * 30 秒后重试)—— 一个坏 uuid 不许连坐整轮,否则常驻恢复会永久停摆。
     */
    static List<UUID> classifyStaleIntents(Set<UUID> forced, List<UUID> requested,
                                           Predicate<UUID> loaded, Consumer<UUID> detach) {
        List<UUID> stale = new java.util.ArrayList<>();
        for (UUID uuid : requested) {
            try {
                if (forced.contains(uuid) && loaded.test(uuid)) continue;
                if (forced.contains(uuid)) detach.accept(uuid);
                stale.add(uuid);
            } catch (Throwable error) {
                SablePanel.LOGGER.warn("sablepanel: checking force-load intent {} failed", uuid, error);
            }
        }
        return stale;
    }

    static boolean shouldAttemptForceRestore(ForceRestoreAttempt failed, ForceRestoreAttempt current) {
        return !current.equals(failed);
    }

    /**
     * 周期扫描和手动重扫共用的合并门闩:任何时刻最多一次完整扫描在跑,扫描期间的额外请求
     * 最多合并成一次补跑。这样操作完成后的重扫不会被较早开始的扫描覆盖。
     * <p>
     * 从前周期任务直接调原始 scanOnce、只有手动入口过门闩,于是周期扫描进行时点一次重扫,
     * 就会有第二个线程把同一批磁盘数据再全量解压一遍,还占着扫描/心跳共用的调度池。
     */
    static Runnable mergedRunner(AtomicInteger state, Executor executor, BooleanSupplier alive, Runnable work) {
        return () -> {
            if (!alive.getAsBoolean()) return;
            int previous = state.getAndUpdate(current -> current == SCAN_IDLE ? SCAN_RUNNING : SCAN_RERUN);
            if (previous != SCAN_IDLE) return;
            try {
                executor.execute(() -> {
                    while (alive.getAsBoolean()) {
                        work.run();
                        if (state.compareAndSet(SCAN_RUNNING, SCAN_IDLE)) return;
                        if (!state.compareAndSet(SCAN_RERUN, SCAN_RUNNING)) return;
                    }
                    state.set(SCAN_IDLE);
                });
            } catch (RejectedExecutionException rejected) {
                state.set(SCAN_IDLE);
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
                var entries = JobService.underLocate(() -> DiskScanner.scan(dimensions));
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

    private void rollbackStartup(long generation, ScheduledExecutorService control,
                                 ExecutorService scans, PanelClusterNode node,
                                 ScheduledFuture<?> heartbeat) {
        JobService jobs;
        PreviewSubsystem preview;
        synchronized (this.lifecycleLock) {
            boolean ownsLifecycle = this.lifecycleGeneration.compareAndSet(generation, generation + 1);
            if (ownsLifecycle) {
                this.stopping = true;
                BodyCostTracker.ENABLED = false;
                PhysicsTimer.ENABLED = false;
                PanelObserver.ENABLED = false;
            }
            if (this.panelNode == node) this.panelNode = null;
            if (this.controlExecutor == control) this.controlExecutor = null;
            if (this.scanExecutor == scans) this.scanExecutor = null;
            if (this.heartbeatTask == heartbeat) this.heartbeatTask = null;
            jobs = this.jobService;
            this.jobService = null;
            preview = this.previewSubsystem;
            this.previewSubsystem = null;
        }
        if (heartbeat != null) heartbeat.cancel(true);
        closeServerWeb();
        if (node != null) node.close();
        if (jobs != null) jobs.close();
        if (preview != null) preview.close();
        shutdownExecutor(control);
        shutdownExecutor(scans);
        // 安全意图和约束属于服务器生命周期；面板网关启动失败不能留下不可追踪的孤儿约束。
        this.scanPauseLogged = false;
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

    private static void shutdownExecutor(ExecutorService executor) {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(EXECUTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
