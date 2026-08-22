package com.klnon.sablepanel.panel.preview;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import com.klnon.sablepanel.panel.preview.protocol.Spm2Codec;
import com.klnon.sablepanel.panel.preview.structure.PreviewTooLargeException;
import com.klnon.sablepanel.panel.preview.structure.StateStructureExtractor;
import com.klnon.sablepanel.panel.preview.resources.ResourcePreparation;
import com.klnon.sablepanel.panel.storage.Digests;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.TreeSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Backend boundary for all structure preview rendering.
 *
 * The facade owns structure extraction, the SPM2 cache and asynchronous resource preparation.
 */
public final class PreviewSubsystem implements AutoCloseable {
    private static final long CACHE_LIMIT = 24L * 1024 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 30 * 1024 * 1024;
    private static final int MAX_ACTIVE_TASKS = 4;
    private static final int MAX_TERMINAL_TASKS = 4;
    private static final long TERMINAL_TASK_RETENTION_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final PreviewSource onlineSource;
    private final StateStructureExtractor structureExtractor = new StateStructureExtractor();
    private final ResourcePreparation resourcePreparation;
    private final LinkedHashMap<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Mailbox<Result>> tasks = new java.util.HashMap<>();
    private final ExecutorService extraction = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), r -> {
        Thread thread = new Thread(r, "sablepanel-preview");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.setDaemon(true);
        return thread;
    });
    private long cacheBytes;
    private boolean closed;

    public PreviewSubsystem(PreviewSource onlineSource, ResourcePreparation resourcePreparation) {
        this.onlineSource = Objects.requireNonNull(onlineSource, "onlineSource");
        this.resourcePreparation = Objects.requireNonNull(resourcePreparation, "resourcePreparation");
    }

    public Result onlineSpm2(UUID uuid) throws Exception {
        ensureOpen();
        this.resourcePreparation.start();
        return submitLoaded("online@" + uuid, () -> this.onlineSource.load(uuid));
    }

    public Result renderSpm2Async(String key, Callable<CompoundTag> loader) {
        ensureOpen();
        this.resourcePreparation.start();
        Objects.requireNonNull(loader, "loader");
        return submitLoaded("load@" + key, () -> {
            CompoundTag tag = loader.call();
            return tag == null ? null : new PreviewSource.Loaded(key + "@" + tagHash(tag), tag);
        });
    }

    public void retryResources() {
        this.resourcePreparation.retry();
    }

    /**
     * 二进制 NBT 直接流进摘要。
     * <p>
     * 这里以前是 {@code tag.toString()}:先把整份结构物化成 SNBT 文本再哈希。SNBT 是二进制 NBT 的
     * 十倍量级且一次性全部驻留,一个几万方块的体就能在提取线程上顶出几十 MB 的临时字符串 ——
     * 后台线程的 young-gen GC 一样是 stop-the-world,游戏 tick 跟着停。
     */
    private static String tagHash(CompoundTag tag) {
        try {
            MessageDigest digest = Digests.sha256();
            NbtIo.write(tag, new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    public record Result(Status status, byte[] payload) {
        public enum Status { READY, ACCEPTED, TOO_LARGE, NOT_FOUND, BUSY, RETRYABLE, CONFLICT, FAILED }
        static Result ready(byte[] payload) { return new Result(Status.READY, payload); }
        static Result accepted() { return new Result(Status.ACCEPTED, null); }
        static Result tooLarge() { return new Result(Status.TOO_LARGE, null); }
        static Result notFound() { return new Result(Status.NOT_FOUND, null); }
        static Result busy() { return new Result(Status.BUSY, null); }
        static Result retryable() { return new Result(Status.RETRYABLE, null); }
        static Result conflict() { return new Result(Status.CONFLICT, null); }
        static Result failed() { return new Result(Status.FAILED, null); }
    }

    public record ResourceResult(ResourceStatus status, byte[] payload,
                                 com.klnon.sablepanel.panel.preview.resources.VanillaResourceCache.Progress progress) {
        public enum ResourceStatus { READY, ACCEPTED, BUSY, NOT_FOUND, FAILED }
        static ResourceResult ready(byte[] payload,
                                    com.klnon.sablepanel.panel.preview.resources.VanillaResourceCache.Progress progress) {
            return new ResourceResult(ResourceStatus.READY, payload, progress);
        }
        static ResourceResult accepted(com.klnon.sablepanel.panel.preview.resources.VanillaResourceCache.Progress progress) {
            return new ResourceResult(ResourceStatus.ACCEPTED, null, progress);
        }
        static ResourceResult busy(com.klnon.sablepanel.panel.preview.resources.VanillaResourceCache.Progress progress) {
            return new ResourceResult(ResourceStatus.BUSY, null, progress);
        }
        static ResourceResult notFound() { return new ResourceResult(ResourceStatus.NOT_FOUND, null, null); }
        static ResourceResult failed(com.klnon.sablepanel.panel.preview.resources.VanillaResourceCache.Progress progress) {
            return new ResourceResult(ResourceStatus.FAILED, null, progress);
        }
    }

    @Override
    public void close() {
        synchronized (this.cache) {
            this.closed = true;
            this.cache.clear();
            this.tasks.clear();
            this.cacheBytes = 0;
        }
        this.resourcePreparation.close();
        this.extraction.shutdownNow();
        try { this.extraction.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private Encoded encodeSpm2(CompoundTag tag,
                               com.klnon.sablepanel.panel.preview.structure.ContraptionSource contraptions)
            throws PreviewTooLargeException {
        var structure = this.structureExtractor.extract(tag, contraptions);
        TreeSet<String> roots = new TreeSet<>();
        for (var entry : structure.palette()) {
            try {
                roots.add(blockstatePath(entry.id()));
                if (entry.id().equals("minecraft:water") || entry.state().contains("waterlogged=true")) {
                    roots.add("assets/minecraft/textures/block/water_still.png");
                    roots.add("assets/minecraft/textures/block/water_still.png.mcmeta");
                    roots.add("assets/minecraft/textures/block/water_flow.png");
                    roots.add("assets/minecraft/textures/block/water_flow.png.mcmeta");
                } else if (entry.id().equals("minecraft:lava")) {
                    roots.add("assets/minecraft/textures/block/lava_still.png");
                    roots.add("assets/minecraft/textures/block/lava_still.png.mcmeta");
                    roots.add("assets/minecraft/textures/block/lava_flow.png");
                    roots.add("assets/minecraft/textures/block/lava_flow.png.mcmeta");
                }
            } catch (IllegalArgumentException invalid) {
                SablePanel.LOGGER.debug("sablepanel: preview resource id skipped {}", entry.id());
            }
        }
        String closureId = closureId(roots);
        JsonObject resources = new JsonObject();
        resources.addProperty("closure_id", closureId);
        resources.addProperty("manifest", "/api/preview/resources/" + closureId + "/manifest");
        ResourcePreparation.Closure closure = this.resourcePreparation.requestClosure(closureId, roots);
        resources.addProperty("status", closure.status().name().toLowerCase(java.util.Locale.ROOT));
        if (closure.fingerprint() != null) resources.addProperty("fingerprint", closure.fingerprint());
        boolean cacheable = closure.status() == ResourcePreparation.Closure.Status.READY;
        JsonObject additions = new JsonObject();
        additions.add("resources", resources);
        return new Encoded(Spm2Codec.encode(structure.toSpm2(additions)), cacheable);
    }

    private Result submitLoaded(String requestKey, Callable<PreviewSource.Loaded> source) {
        synchronized (this.cache) {
            Mailbox.prune(this.tasks, System.nanoTime() - TERMINAL_TASK_RETENTION_NANOS);
            Mailbox<Result> existing = this.tasks.get(requestKey);
            if (existing != null) {
                if (existing.result == null) return Result.accepted();
                this.tasks.remove(requestKey);
                return existing.result;
            }
            if (Mailbox.active(this.tasks) >= MAX_ACTIVE_TASKS) return Result.busy();
            Mailbox.trim(this.tasks, MAX_TERMINAL_TASKS - 1);
            Mailbox<Result> task = new Mailbox<>();
            this.tasks.put(requestKey, task);
            try {
                this.extraction.submit(() -> {
                    try {
                        PreviewSource.Loaded loaded = source.call();
                        if (loaded == null) {
                            task.complete(Result.notFound());
                            return;
                        }
                        String baseKey = "spm2:v2@" + loaded.cacheKey();
                        String contentKey = readyCacheKey(baseKey);
                        synchronized (this.cache) {
                            byte[] value = contentKey == null ? null : this.cache.get(contentKey);
                            if (value != null) {
                                task.complete(Result.ready(value));
                                return;
                            }
                        }
                        Encoded encoded = encodeSpm2(loaded.tag(), loaded.contraptions());
                        byte[] payload = encoded.payload();
                        if (payload.length > MAX_PAYLOAD_BYTES) task.complete(Result.tooLarge());
                        else {
                            contentKey = readyCacheKey(baseKey);
                            if (encoded.cacheable() && contentKey != null) cache(contentKey, payload);
                            task.complete(Result.ready(payload));
                        }
                    } catch (PreviewTooLargeException error) {
                        task.complete(Result.tooLarge());
                    } catch (PreviewSource.Ambiguous error) {
                        task.complete(Result.conflict());
                    } catch (java.io.IOException error) {
                        task.complete(Result.retryable());
                    } catch (Throwable error) {
                        SablePanel.LOGGER.warn("sablepanel: preview source extraction failed {}", requestKey, error);
                        task.complete(Result.failed());
                    }
                });
            } catch (RuntimeException rejected) {
                this.tasks.remove(requestKey);
                return Result.busy();
            }
            return Result.accepted();
        }
    }

    private record Encoded(byte[] payload, boolean cacheable) {}

    public ResourceResult resource(String id, String hash) {
        try {
            ResourcePreparation.Closure status = this.resourcePreparation.status(id);
            var progress = this.resourcePreparation.progress();
            if (status.status() == ResourcePreparation.Closure.Status.ACCEPTED) return ResourceResult.accepted(progress);
            if (status.status() == ResourcePreparation.Closure.Status.BUSY) return ResourceResult.busy(progress);
            if (status.status() == ResourcePreparation.Closure.Status.FAILED) return ResourceResult.failed(progress);
            ResourcePreparation.ResourceRead read = this.resourcePreparation.read(id, hash);
            return switch (read.status()) {
                case READY -> ResourceResult.ready(read.payload(), progress);
                case ACCEPTED -> ResourceResult.accepted(progress);
                case BUSY -> ResourceResult.busy(progress);
                case NOT_FOUND -> ResourceResult.notFound();
                case FAILED -> ResourceResult.failed(progress);
            };
        } catch (IllegalArgumentException invalid) {
            return ResourceResult.notFound();
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: preview resource read failed {}", id, error);
            return ResourceResult.failed(this.resourcePreparation.progress());
        }
    }

    private static String blockstatePath(String blockId) {
        int colon = blockId.indexOf(':');
        String namespace = colon >= 0 ? blockId.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? blockId.substring(colon + 1) : blockId;
        if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_./-]+") || path.contains("..")) {
            throw new IllegalArgumentException("invalid block resource id " + blockId);
        }
        return "assets/" + namespace + "/blockstates/" + path + ".json";
    }

    private static String closureId(Iterable<String> roots) {
        MessageDigest digest = Digests.sha256();
        for (String root : roots) {
            digest.update(root.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String readyCacheKey(String base) {
        String fingerprint = this.resourcePreparation.readyFingerprint();
        return fingerprint == null ? null : base + "@resources:" + fingerprint;
    }

    private boolean cache(String key, byte[] value) {
        synchronized (this.cache) {
            ensureOpenLocked();
            if (value.length > CACHE_LIMIT) return false;
            byte[] previous = this.cache.put(key, value);
            this.cacheBytes += value.length - (previous != null ? previous.length : 0);
            var iterator = this.cache.entrySet().iterator();
            while (this.cacheBytes > CACHE_LIMIT && iterator.hasNext()) {
                var victim = iterator.next();
                this.cacheBytes -= victim.getValue().length;
                iterator.remove();
            }
            return true;
        }
    }

    private void ensureOpen() {
        synchronized (this.cache) {
            ensureOpenLocked();
        }
    }

    private void ensureOpenLocked() {
        if (this.closed) throw new IllegalStateException("preview subsystem is closed");
    }
}
