package com.klnon.sablepanel.panel.preview;

import com.klnon.sablepanel.panel.preview.protocol.Spm2Codec;
import com.klnon.sablepanel.panel.preview.resources.ResourcePreparation;
import com.klnon.sablepanel.panel.preview.resources.VanillaResourceCache;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

class PreviewSubsystemTest {
    /** 生产装配点保证 ResourcePreparation 恒非空;测试给一个永不就绪的哑制备。 */
    private static ResourcePreparation stubResources() {
        return new ResourcePreparation(progress -> {
            throw new IllegalStateException("no resources in test");
        }, baseline -> new com.klnon.sablepanel.panel.preview.resources.ModResourceStack(
                baseline.archive(), java.util.List.of()));
    }

    @Test
    void emptyStructureEncodesThroughSpm2AndCloseStopsFurtherUse() throws Exception {
        PreviewSubsystem subsystem = new PreviewSubsystem(uuid -> null, stubResources());
        CompoundTag entry = new CompoundTag();
        entry.put("plot", new CompoundTag());

        var decoded = Spm2Codec.decode(awaitReady(subsystem, "empty", entry).payload());

        assertEquals(0, decoded.records().size());
        assertEquals(0, decoded.shellBitmap().length);
        assertTrue(decoded.metadata().contains("\"voxel_count\":0"));

        subsystem.close();
        assertThrows(IllegalStateException.class, () -> subsystem.renderSpm2Async("closed", () -> entry));
    }

    @Test
    void asyncMissesMergeAndEventuallyReturnSpm2() throws Exception {
        PreviewSubsystem subsystem = new PreviewSubsystem(uuid -> null, stubResources());
        CompoundTag entry = new CompoundTag();
        entry.put("plot", new CompoundTag());

        var first = subsystem.renderSpm2Async("same", () -> entry);
        assertEquals(PreviewSubsystem.Result.Status.ACCEPTED, first.status());
        // 同键必须合并进同一任务:MAX_ACTIVE_TASKS=4,不合并的实现连发第 5 次就会翻 BUSY
        for (int i = 0; i < 5; i++) {
            assertTrue(subsystem.renderSpm2Async("same", () -> entry).status()
                    != PreviewSubsystem.Result.Status.BUSY, "同键重复提交不得占用新任务槽");
        }

        PreviewSubsystem.Result ready;
        long deadline = System.nanoTime() + 2_000_000_000L;
        do {
            Thread.sleep(10);
            ready = subsystem.renderSpm2Async("same", () -> entry);
        } while (ready.status() == PreviewSubsystem.Result.Status.ACCEPTED
                && System.nanoTime() < deadline);
        assertEquals(PreviewSubsystem.Result.Status.READY, ready.status());
        subsystem.close();
    }

    @Test
    void onlineSourceIoRunsOnBoundedPreviewThread() throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        CompoundTag entry = new CompoundTag();
        entry.put("plot", new CompoundTag());
        PreviewSubsystem subsystem = new PreviewSubsystem(uuid -> {
            threadName.set(Thread.currentThread().getName());
            return new PreviewSource.Loaded(uuid + "@payload", entry);
        }, stubResources());

        UUID uuid = UUID.randomUUID();
        assertEquals(PreviewSubsystem.Result.Status.ACCEPTED, subsystem.onlineSpm2(uuid).status());
        PreviewSubsystem.Result result;
        long deadline = System.nanoTime() + 2_000_000_000L;
        do {
            Thread.sleep(10);
            result = subsystem.onlineSpm2(uuid);
        } while (result.status() == PreviewSubsystem.Result.Status.ACCEPTED && System.nanoTime() < deadline);

        assertEquals(PreviewSubsystem.Result.Status.READY, result.status());
        assertTrue(threadName.get().startsWith("sablepanel-preview"));
        subsystem.close();
    }

    @Test
    void sourceChangesAreRetryableAndAmbiguousCopiesAreConflicts() throws Exception {
        UUID uuid = UUID.randomUUID();
        PreviewSubsystem retryable = new PreviewSubsystem(ignored -> {
            throw new java.io.IOException("slot changed");
        }, stubResources());
        assertEquals(PreviewSubsystem.Result.Status.RETRYABLE, awaitOnline(retryable, uuid).status());
        retryable.close();

        PreviewSubsystem conflict = new PreviewSubsystem(ignored -> {
            throw new PreviewSource.Ambiguous("copies conflict");
        }, stubResources());
        assertEquals(PreviewSubsystem.Result.Status.CONFLICT, awaitOnline(conflict, uuid).status());
        conflict.close();
    }

    @Test
    void pendingResourceStateIsReencodedAfterClosureBecomesReady() throws Exception {
        Path baseline = Files.createTempDirectory("preview-resource-state").resolve("vanilla.zip");
        try (ZipOutputStream ignored = new ZipOutputStream(Files.newOutputStream(baseline))) { }
        CountDownLatch release = new CountDownLatch(1);
        ResourcePreparation resources = new ResourcePreparation(progress -> {
            if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test resource timeout");
            return new VanillaResourceCache.Baseline(baseline, "a".repeat(64));
        }, b -> new com.klnon.sablepanel.panel.preview.resources.ModResourceStack(b.archive(), java.util.List.of()));
        PreviewSubsystem subsystem = new PreviewSubsystem(uuid -> null, resources);
        CompoundTag entry = new CompoundTag();
        entry.put("plot", new CompoundTag());

        PreviewSubsystem.Result first = awaitReady(subsystem, "resource-state", entry);
        assertTrue(Spm2Codec.decode(first.payload()).metadata().contains("\"status\":\"accepted\""));
        release.countDown();

        PreviewSubsystem.Result refreshed = null;
        long deadline = System.nanoTime() + 2_000_000_000L;
        do {
            refreshed = awaitReady(subsystem, "resource-state", entry);
            if (Spm2Codec.decode(refreshed.payload()).metadata().contains("\"status\":\"ready\"")) break;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);

        String metadata = Spm2Codec.decode(refreshed.payload()).metadata();
        assertTrue(metadata.contains("\"status\":\"ready\""));
        assertTrue(metadata.contains("\"fingerprint\""));
        subsystem.close();
    }

    @Test
    void completedUnpolledTasksDoNotConsumeTheExtractionQueue() throws Exception {
        Path baseline = Files.createTempDirectory("preview-terminal-tasks").resolve("vanilla.zip");
        try (ZipOutputStream ignored = new ZipOutputStream(Files.newOutputStream(baseline))) { }
        CountDownLatch release = new CountDownLatch(1);
        ResourcePreparation resources = new ResourcePreparation(progress -> {
            release.await(2, TimeUnit.SECONDS);
            return new VanillaResourceCache.Baseline(baseline, "b".repeat(64));
        }, b -> new com.klnon.sablepanel.panel.preview.resources.ModResourceStack(b.archive(), java.util.List.of()));
        PreviewSubsystem subsystem = new PreviewSubsystem(uuid -> null, resources);
        CompoundTag entry = new CompoundTag();
        entry.put("plot", new CompoundTag());
        for (int index = 0; index < 4; index++) {
            assertEquals(PreviewSubsystem.Result.Status.ACCEPTED,
                    subsystem.renderSpm2Async("abandoned-" + index, () -> entry).status());
        }

        PreviewSubsystem.Result next;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            next = subsystem.renderSpm2Async("next", () -> entry);
            if (next.status() == PreviewSubsystem.Result.Status.BUSY) Thread.sleep(10);
        } while (next.status() == PreviewSubsystem.Result.Status.BUSY && System.nanoTime() < deadline);

        assertEquals(PreviewSubsystem.Result.Status.ACCEPTED, next.status());
        release.countDown();
        subsystem.close();
    }

    private static PreviewSubsystem.Result awaitReady(PreviewSubsystem subsystem, String key,
                                                        CompoundTag entry) throws Exception {
        PreviewSubsystem.Result result = subsystem.renderSpm2Async(key, () -> entry);
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (result.status() == PreviewSubsystem.Result.Status.ACCEPTED && System.nanoTime() < deadline) {
            Thread.sleep(10);
            result = subsystem.renderSpm2Async(key, () -> entry);
        }
        assertEquals(PreviewSubsystem.Result.Status.READY, result.status());
        return result;
    }

    private static PreviewSubsystem.Result awaitOnline(PreviewSubsystem subsystem, UUID uuid) throws Exception {
        PreviewSubsystem.Result result = subsystem.onlineSpm2(uuid);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (result.status() == PreviewSubsystem.Result.Status.ACCEPTED && System.nanoTime() < deadline) {
            Thread.sleep(10);
            result = subsystem.onlineSpm2(uuid);
        }
        return result;
    }
}
