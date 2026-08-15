package com.klnon.sablepanel.panel.preview.resources;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePreparationTest {
    /** 生产默认的资源栈工厂;1 参便捷构造已随测试专用 API 一并退役 */
    private static ResourcePreparation prep(ResourcePreparation.Task task) {
        return new ResourcePreparation(task, b -> new ModResourceStack(b.archive(), java.util.List.of()));
    }

    @Test
    void concurrentStartsShareOneTask() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ResourcePreparation preparation = prep(progress -> {
            runs.incrementAndGet();
            return new VanillaResourceCache.Baseline(Path.of("cache.zip"), "a".repeat(64));
        });

        var first = preparation.start();
        var second = preparation.start();

        assertSame(first, second);
        assertEquals("cache.zip", first.get().archive().toString());
        assertEquals(1, runs.get());
        preparation.close();
    }

    @Test
    void retryReplacesCompletedFailure() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ResourcePreparation preparation = prep(progress -> {
            if (runs.incrementAndGet() == 1) throw new IllegalStateException("first");
            return new VanillaResourceCache.Baseline(Path.of("ok.zip"), "b".repeat(64));
        });

        preparation.start().handle((value, error) -> null).get();
        assertEquals("ok.zip", preparation.retry().get().archive().toString());
        assertEquals(2, runs.get());
        preparation.close();
    }

    @Test
    void closureRunsOnSharedWorkerAndPersistsManifestAndShard() throws Exception {
        Path root = Files.createTempDirectory("resource-preparation");
        Path baseline = root.resolve("vanilla.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(baseline))) {
            out.putNextEntry(new ZipEntry("assets/minecraft/blockstates/stone.json"));
            out.write("{}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        ResourcePreparation preparation = prep(progress ->
                new VanillaResourceCache.Baseline(baseline, "c".repeat(64)));
        String id = sha256("stone");
        Set<String> roots = Set.of("assets/minecraft/blockstates/stone.json");

        assertEquals(ResourcePreparation.Closure.Status.ACCEPTED,
                preparation.requestClosure(id, roots).status());
        ResourcePreparation.Closure result = awaitClosure(preparation, id, roots);

        assertEquals(ResourcePreparation.Closure.Status.READY, result.status());
        ResourcePreparation.ResourceRead manifest = awaitRead(() -> preparation.read(id, null));
        assertEquals(ResourcePreparation.ResourceRead.Status.READY, manifest.status());
        assertTrue(new String(manifest.payload(), StandardCharsets.UTF_8).contains("blockstates/stone.json"));
        preparation.close();
    }

    @Test
    void sameRootsAreRebuiltWhenTheResourceStackFingerprintChanges() throws Exception {
        Path root = Files.createTempDirectory("resource-fingerprint");
        Path baseline = root.resolve("vanilla.zip");
        writeArchive(baseline, "{\"first\":1}");
        String id = sha256("same-roots");
        Set<String> roots = Set.of("assets/minecraft/blockstates/stone.json");

        ResourcePreparation first = prep(progress ->
                new VanillaResourceCache.Baseline(baseline, "d".repeat(64)));
        first.requestClosure(id, roots);
        String firstFingerprint = awaitClosure(first, id, roots).fingerprint();
        first.close();

        writeArchive(baseline, "{\"second\":2}");
        ResourcePreparation second = prep(progress ->
                new VanillaResourceCache.Baseline(baseline, "d".repeat(64)));
        assertEquals(ResourcePreparation.Closure.Status.ACCEPTED, second.requestClosure(id, roots).status(),
                "新进程不得在资源栈比对前直接复用旧闭包");
        String secondFingerprint = awaitClosure(second, id, roots).fingerprint();

        assertNotEquals(firstFingerprint, secondFingerprint);
        second.close();
    }

    private static ResourcePreparation.Closure awaitClosure(ResourcePreparation preparation, String id,
                                                            Set<String> roots) throws Exception {
        ResourcePreparation.Closure result;
        long deadline = System.nanoTime() + 2_000_000_000L;
        do {
            Thread.sleep(10);
            result = preparation.requestClosure(id, roots);
        } while (result.status() == ResourcePreparation.Closure.Status.ACCEPTED
                && System.nanoTime() < deadline);
        return result;
    }

    private static ResourcePreparation.ResourceRead awaitRead(java.util.function.Supplier<ResourcePreparation.ResourceRead> read)
            throws Exception {
        ResourcePreparation.ResourceRead result;
        long deadline = System.nanoTime() + 2_000_000_000L;
        do {
            result = read.get();
            if (result.status() == ResourcePreparation.ResourceRead.Status.ACCEPTED) Thread.sleep(10);
        } while (result.status() == ResourcePreparation.ResourceRead.Status.ACCEPTED
                && System.nanoTime() < deadline);
        return result;
    }

    private static void writeArchive(Path path, String json) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry("assets/minecraft/blockstates/stone.json"));
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
