package com.klnon.sablepanel.panel.preview.resources;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceBundleCacheTest {
    @Test
    void storesValidatedManifestAndContentAddressedShard() throws Exception {
        byte[] bytes = "resource-bytes".getBytes(StandardCharsets.UTF_8);
        String shardHash = sha256(bytes);
        String fileHash = sha256(bytes);
        String fingerprint = "a".repeat(64);
        String id = "b".repeat(64);
        var bundle = new ModResourceStack.Bundle(fingerprint,
                List.of(new ModResourceStack.Entry("assets/test/models/block/a.json", fileHash,
                        bytes.length, shardHash, 0, bytes.length, "mod-0")),
                List.of(new ModResourceStack.Shard(shardHash, bytes)), List.of(), List.of());
        ResourceBundleCache cache = new ResourceBundleCache(Files.createTempDirectory("preview-bundles"));

        assertNotNull(cache.store(id, bundle));
        assertTrue(new String(cache.manifest(id), StandardCharsets.UTF_8).contains("\"id\":\"" + id + "\""));
        assertArrayEquals(bytes, cache.shard(id, shardHash));
        assertNull(cache.shard("c".repeat(64), shardHash));
        assertThrows(IllegalArgumentException.class, () -> cache.shard(id, "../bad"));
    }

    @Test
    void restartRejectsAContentAddressedShardWhoseBytesWereCorrupted() throws Exception {
        byte[] bytes = "resource-bytes".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes), id = "d".repeat(64);
        Path root = Files.createTempDirectory("preview-corrupt-bundle");
        ResourceBundleCache cache = new ResourceBundleCache(root);
        cache.store(id, new ModResourceStack.Bundle("e".repeat(64),
                List.of(new ModResourceStack.Entry("assets/test/a.json", hash, bytes.length,
                        hash, 0, bytes.length, "test")),
                List.of(new ModResourceStack.Shard(hash, bytes)), List.of(), List.of()));
        Files.write(root.resolve("closures").resolve(id).resolve("shards").resolve(hash),
                "corrupt-bytes!".getBytes(StandardCharsets.UTF_8));

        assertNull(new ResourceBundleCache(root).get(id));
    }

    @Test
    void inMemoryValidationCannotHideAReplacedManifest() throws Exception {
        byte[] bytes = "resource-bytes".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes), id = "f".repeat(64);
        Path root = Files.createTempDirectory("preview-replaced-manifest");
        ResourceBundleCache cache = new ResourceBundleCache(root);
        cache.store(id, new ModResourceStack.Bundle("a".repeat(64),
                List.of(new ModResourceStack.Entry("assets/test/a.json", hash, bytes.length,
                        hash, 0, bytes.length, "test")),
                List.of(new ModResourceStack.Shard(hash, bytes)), List.of(), List.of()));
        Files.writeString(root.resolve("closures").resolve(id).resolve("manifest.json"), "{}");

        assertNull(cache.get(id));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
