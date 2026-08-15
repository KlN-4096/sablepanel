package com.klnon.sablepanel.panel.preview.resources;

import com.klnon.sablepanel.panel.preview.Mailbox;

import java.util.Objects;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/** One lazy, bounded preparation task shared by all previews of an instance. */
public final class ResourcePreparation implements AutoCloseable {
    @FunctionalInterface
    public interface Task {
        VanillaResourceCache.Baseline run(VanillaResourceCache.ProgressListener progress) throws Exception;
    }

    private static final int QUEUE_LIMIT = 8;
    private static final int SHUTDOWN_SECONDS = 3;

    private final Task task;
    private final Function<VanillaResourceCache.Baseline, ModResourceStack> stackFactory;
    private final ExecutorService executor;
    private final Object lock = new Object();
    private final Map<String, CompletableFuture<ResourceBundleCache.Cached>> closures = new HashMap<>();
    private final Map<String, Failure> closureFailures = new HashMap<>();
    private final Map<String, ResourceBundleCache.Cached> readyClosures = new HashMap<>();
    private final Map<String, Mailbox<ResourceRead>> reads = new HashMap<>();
    private CompletableFuture<VanillaResourceCache.Baseline> current;
    private Failure failure;
    private volatile VanillaResourceCache.Progress progress = new VanillaResourceCache.Progress(
            VanillaResourceCache.Phase.IDLE, "", 0, -1, "");
    private volatile String resourceFingerprint;
    private ModResourceStack resourceStack;
    private ResourceBundleCache bundleCache;
    private boolean closed;

    public ResourcePreparation(Task task,
                               Function<VanillaResourceCache.Baseline, ModResourceStack> stackFactory) {
        this.task = Objects.requireNonNull(task, "task");
        this.stackFactory = Objects.requireNonNull(stackFactory, "stackFactory");
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_LIMIT), runnable -> {
                    Thread thread = new Thread(runnable, "sablepanel-preview-resource");
                    thread.setPriority(Thread.MIN_PRIORITY);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Starts once and returns the shared result for concurrent callers. */
    public CompletableFuture<VanillaResourceCache.Baseline> start() {
        synchronized (this.lock) {
            if (this.closed) return CompletableFuture.failedFuture(new IllegalStateException("resource preparation is closed"));
            if (this.current != null) {
                if (!this.current.isCompletedExceptionally()
                        || this.failure == null || this.failure.readyAtMillis() > System.currentTimeMillis()) {
                    return this.current;
                }
                this.current = null;
            }
            CompletableFuture<VanillaResourceCache.Baseline> future = new CompletableFuture<>();
            this.current = future;
            try {
                this.executor.execute(() -> run(future));
            } catch (RejectedExecutionException rejected) {
                this.current = null;
                future.completeExceptionally(rejected);
            }
            return future;
        }
    }

    /** Explicit UI retry: only a completed failure can be replaced. */
    public CompletableFuture<VanillaResourceCache.Baseline> retry() {
        synchronized (this.lock) {
            this.closureFailures.clear();
            if (this.current != null && !this.current.isCompletedExceptionally()) return this.current;
            this.current = null;
            this.failure = null;
        }
        return start();
    }

    public VanillaResourceCache.Progress progress() {
        return this.progress;
    }

    public String readyFingerprint() {
        return this.resourceFingerprint;
    }

    /** Queue one referenced resource closure on the same single resource worker. */
    public Closure requestClosure(String id, Set<String> roots) {
        validateId(id);
        Set<String> requested = roots == null ? Set.of() : Set.copyOf(roots);
        CompletableFuture<VanillaResourceCache.Baseline> baseline = start();
        synchronized (this.lock) {
            if (this.closed) return Closure.failed();
            ResourceBundleCache.Cached cached = readyClosure(id);
            if (cached != null) return Closure.ready(cached.fingerprint());
            Failure deferred = this.closureFailures.get(id);
            if (deferred != null && deferred.readyAtMillis() > System.currentTimeMillis()) {
                return Closure.failed();
            }
            CompletableFuture<ResourceBundleCache.Cached> existing = this.closures.get(id);
            if (existing != null) {
                if (!existing.isDone()) return Closure.accepted();
                try {
                    ResourceBundleCache.Cached value = existing.getNow(null);
                    return value == null ? Closure.failed() : Closure.ready(value.fingerprint());
                } catch (RuntimeException failed) {
                    return Closure.failed();
                }
            }
            if (this.closures.size() >= QUEUE_LIMIT) return Closure.busy();
            CompletableFuture<ResourceBundleCache.Cached> future = new CompletableFuture<>();
            this.closures.put(id, future);
            try {
                this.executor.execute(() -> buildClosure(id, requested, baseline, future));
            } catch (RejectedExecutionException rejected) {
                this.closures.remove(id);
                future.completeExceptionally(rejected);
                return Closure.busy();
            }
            return Closure.accepted();
        }
    }

    public Closure status(String id) {
        validateId(id);
        synchronized (this.lock) {
            ResourceBundleCache.Cached cached = readyClosure(id);
            if (cached != null) return Closure.ready(cached.fingerprint());
            CompletableFuture<ResourceBundleCache.Cached> pending = this.closures.get(id);
            if (pending != null && !pending.isDone()) return Closure.accepted();
            return Closure.failed();
        }
    }

    public record Closure(Status status, String fingerprint) {
        public enum Status { READY, ACCEPTED, BUSY, FAILED }
        static Closure ready(String fingerprint) { return new Closure(Status.READY, fingerprint); }
        static Closure accepted() { return new Closure(Status.ACCEPTED, null); }
        static Closure busy() { return new Closure(Status.BUSY, null); }
        static Closure failed() { return new Closure(Status.FAILED, null); }
    }

    public record ResourceRead(Status status, byte[] payload) {
        public enum Status { READY, ACCEPTED, BUSY, NOT_FOUND, FAILED }
        static ResourceRead ready(byte[] payload) { return new ResourceRead(Status.READY, payload); }
        static ResourceRead accepted() { return new ResourceRead(Status.ACCEPTED, null); }
        static ResourceRead busy() { return new ResourceRead(Status.BUSY, null); }
        static ResourceRead notFound() { return new ResourceRead(Status.NOT_FOUND, null); }
        static ResourceRead failed() { return new ResourceRead(Status.FAILED, null); }
    }

    private record Failure(int attempt, long readyAtMillis) {
        static Failure next(int previousAttempt) {
            long[] delays = {60, 300, 1_800, 7_200, 21_600};
            int attempt = Math.min(previousAttempt + 1, delays.length);
            return new Failure(attempt, System.currentTimeMillis() + delays[attempt - 1] * 1000L);
        }
    }

    @Override
    public void close() {
        ModResourceStack stack;
        synchronized (this.lock) {
            this.closed = true;
            if (this.current != null && !this.current.isDone()) {
                this.current.completeExceptionally(new IllegalStateException("resource preparation stopped"));
            }
            for (CompletableFuture<ResourceBundleCache.Cached> closure : this.closures.values()) {
                if (!closure.isDone()) closure.completeExceptionally(
                        new IllegalStateException("resource preparation stopped"));
            }
            this.closures.clear();
            this.closureFailures.clear();
            this.readyClosures.clear();
            this.reads.clear();
            stack = this.resourceStack;
            this.resourceStack = null;
            this.resourceFingerprint = null;
        }
        this.executor.shutdownNow();
        try {
            this.executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (stack != null) stack.close();
    }

    private void run(CompletableFuture<VanillaResourceCache.Baseline> future) {
        try {
            future.complete(this.task.run(this::updateProgress));
        } catch (Throwable error) {
            synchronized (this.lock) {
                int previous = this.failure == null ? 0 : this.failure.attempt();
                this.failure = Failure.next(previous);
            }
            future.completeExceptionally(error);
        }
    }

    private void buildClosure(String id, Set<String> roots,
                              CompletableFuture<VanillaResourceCache.Baseline> baseline,
                              CompletableFuture<ResourceBundleCache.Cached> future) {
        try {
            VanillaResourceCache.Baseline prepared = baseline.join();
            ModResourceStack stack;
            ResourceBundleCache cache;
            synchronized (this.lock) {
                if (this.closed) throw new IllegalStateException("resource preparation is closed");
                if (this.resourceStack == null) this.resourceStack = this.stackFactory.apply(prepared);
                if (this.bundleCache == null) this.bundleCache = new ResourceBundleCache(prepared.archive().getParent());
                stack = this.resourceStack;
                cache = this.bundleCache;
            }
            ResourceBundleCache.Cached cached = cache.get(id);
            String fingerprint = stack.fingerprint();
            this.resourceFingerprint = fingerprint;
            if (cached == null || !fingerprint.equals(cached.fingerprint())) {
                updateProgress(new VanillaResourceCache.Progress(VanillaResourceCache.Phase.EXTRACTING,
                        "模组资源", 0, -1, "构建当前结构资源闭包"));
                cached = cache.store(id, stack.closure(roots));
            }
            updateProgress(new VanillaResourceCache.Progress(VanillaResourceCache.Phase.READY,
                    "预览资源", 0, 0, "资源已就绪"));
            synchronized (this.lock) { this.readyClosures.put(id, cached); }
            future.complete(cached);
        } catch (Throwable error) {
            // 不记日志的话,面板只会显示"资源简化",服务端一个字都没有,失败原因无从查起。
            com.klnon.sablepanel.SablePanel.LOGGER.warn(
                    "sablepanel: preview resource closure failed {}", id, error);
            updateProgress(new VanillaResourceCache.Progress(VanillaResourceCache.Phase.FAILED,
                    "预览资源", 0, -1, error.getMessage()));
            synchronized (this.lock) {
                this.readyClosures.remove(id);
                Failure previous = this.closureFailures.get(id);
                this.closureFailures.put(id, Failure.next(previous == null ? 0 : previous.attempt()));
            }
            future.completeExceptionally(error);
        } finally {
            synchronized (this.lock) {
                this.closures.remove(id, future);
                if (!future.isCompletedExceptionally()) this.closureFailures.remove(id);
            }
        }
    }

    /** hash == null 读 manifest,否则读对应分片 */
    public ResourceRead read(String id, String hash) {
        validateId(id);
        if (hash != null && !hash.matches("[0-9a-f]{64}")) return ResourceRead.notFound();
        String key = id + "@" + (hash == null ? "manifest" : hash);
        synchronized (this.lock) {
            if (this.closed) return ResourceRead.failed();
            if (readyClosure(id) == null || this.bundleCache == null) return ResourceRead.notFound();
            Mailbox<ResourceRead> existing = this.reads.get(key);
            if (existing != null) {
                if (existing.result == null) return ResourceRead.accepted();
                this.reads.remove(key);
                return existing.result;
            }
            if (Mailbox.active(this.reads) >= QUEUE_LIMIT) {
                return ResourceRead.busy();
            }
            Mailbox.trim(this.reads, 3);
            Mailbox<ResourceRead> task = new Mailbox<>();
            this.reads.put(key, task);
            ResourceBundleCache cache = this.bundleCache;
            try {
                this.executor.execute(() -> {
                    try {
                        byte[] payload = hash == null ? cache.manifest(id) : cache.shard(id, hash);
                        task.complete(payload == null ? ResourceRead.notFound() : ResourceRead.ready(payload));
                        if (payload == null) synchronized (this.lock) { this.readyClosures.remove(id); }
                    } catch (Throwable error) {
                        synchronized (this.lock) { this.readyClosures.remove(id); }
                        task.complete(ResourceRead.failed());
                    }
                });
            } catch (RejectedExecutionException rejected) {
                this.reads.remove(key);
                return ResourceRead.busy();
            }
            return ResourceRead.accepted();
        }
    }

    private ResourceBundleCache.Cached readyClosure(String id) {
        ResourceBundleCache.Cached cached = this.readyClosures.get(id);
        String fingerprint = this.resourceFingerprint;
        if (cached == null || fingerprint == null) return null;
        if (cached.fingerprint().equals(fingerprint)) return cached;
        this.readyClosures.remove(id);
        return null;
    }

    private void updateProgress(VanillaResourceCache.Progress progress) {
        if (progress != null) this.progress = progress;
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid resource closure id");
    }
}
