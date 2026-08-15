package com.klnon.sablepanel.panel.preview.resources;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class VanillaResourceCacheTest {
    @Test void compactsAdminJarAndReusesValidCache() throws Exception {
        Path root = Files.createTempDirectory("vanilla-cache");
        Path admin = root.resolve("config/sablepanel/assets/minecraft-1.21.1-client.jar");
        Files.createDirectories(admin.getParent());
        writeJar(admin);
        String hash = sha256(admin); long size = Files.size(admin);
        AtomicInteger downloads = new AtomicInteger();
        var progress = new ArrayList<VanillaResourceCache.Progress>();
        VanillaResourceCache cache = new VanillaResourceCache(root,
                (uri, target) -> downloads.incrementAndGet(), size, hash, progress::add);
        VanillaResourceCache.Baseline first = cache.prepare();
        VanillaResourceCache.Baseline second = cache.prepare();
        assertEquals(first, second);
        assertEquals(0, downloads.get());
        assertTrue(progress.stream().anyMatch(value -> value.phase() == VanillaResourceCache.Phase.VALIDATING));
        assertEquals(VanillaResourceCache.Phase.READY, progress.get(progress.size() - 1).phase());
        try (var zip = new java.util.zip.ZipFile(first.archive().toFile())) {
            assertNotNull(zip.getEntry("assets/minecraft/blockstates/stone.json"));
            assertNull(zip.getEntry("assets/other/not-a-block.json"));
            assertNotNull(zip.getEntry("META-INF/sablepanel-vanilla.properties"));
        }
    }

    @Test void invalidAdminFallsBackToDownloaderAndBadCacheIsRebuilt() throws Exception {
        Path root = Files.createTempDirectory("vanilla-cache");
        Path admin = root.resolve("config/sablepanel/assets/minecraft-1.21.1-client.jar");
        Files.createDirectories(admin.getParent());
        Files.writeString(admin, "bad");
        Path source = Files.createTempFile("source", ".jar"); writeJar(source);
        String hash = sha256(source); long size = Files.size(source);
        AtomicInteger downloads = new AtomicInteger();
        VanillaResourceCache cache = new VanillaResourceCache(root, (uri, target) -> {
            downloads.incrementAndGet(); Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }, size, hash);
        VanillaResourceCache.Baseline first = cache.prepare();
        assertEquals(1, downloads.get());
        Files.write(first.archive(), new byte[]{1});
        VanillaResourceCache.Baseline rebuilt = cache.prepare();
        assertEquals(2, downloads.get());
        assertTrue(Files.size(rebuilt.archive()) > 1);
    }

    @Test void clientValidationRejectsSizeOrHashMismatch() throws Exception {
        Path root = Files.createTempDirectory("vanilla-cache");
        Path admin = root.resolve("config/sablepanel/assets/minecraft-1.21.1-client.jar");
        Files.createDirectories(admin.getParent()); Files.writeString(admin, "bad");
        AtomicInteger downloads = new AtomicInteger();
        VanillaResourceCache cache = new VanillaResourceCache(root, (uri, target) -> { downloads.incrementAndGet(); throw new java.io.IOException("no source"); }, 3, "00".repeat(32));
        assertThrows(java.io.IOException.class, cache::prepare);
        assertEquals(2, downloads.get());
    }

    private static void writeJar(Path path) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            put(out, "assets/minecraft/blockstates/stone.json", "{}" );
            put(out, "assets/minecraft/models/block/stone.json", "{}" );
            put(out, "assets/minecraft/textures/block/stone.png", "png" );
            put(out, "assets/minecraft/textures/colormap/grass.png", "png" );
            put(out, "assets/minecraft/atlases/blocks.json", "{}" );
            put(out, "assets/other/not-a-block.json", "ignored" );
        }
    }
    private static void put(ZipOutputStream out, String name, String value) throws Exception { out.putNextEntry(new ZipEntry(name)); out.write(value.getBytes()); out.closeEntry(); }
    private static String sha256(Path path) throws Exception { MessageDigest d=MessageDigest.getInstance("SHA-256"); try(InputStream in=Files.newInputStream(path)){in.transferTo(new java.io.OutputStream(){public void write(int b){d.update((byte)b);} public void write(byte[] b,int o,int l){d.update(b,o,l);}});} return HexFormat.of().formatHex(d.digest()); }
}
