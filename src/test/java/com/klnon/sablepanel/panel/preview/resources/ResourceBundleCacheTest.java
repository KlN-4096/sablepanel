package com.klnon.sablepanel.panel.preview.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceBundleCacheTest {
    @Test
    void storesValidatedManifestAndContentAddressedShard() throws Exception {
        byte[] bytes = "resource-bytes".getBytes(StandardCharsets.UTF_8);
        String shardHash = sha256(bytes);
        String fingerprint = "a".repeat(64);
        String id = "b".repeat(64);
        var bundle = new ModResourceStack.Bundle(fingerprint,
                List.of(new ModResourceStack.Entry("assets/test/models/block/a.json",
                        bytes.length, shardHash, 0, bytes.length, "mod-0")),
                List.of(new ModResourceStack.Shard(shardHash, bytes)), List.of(), List.of());
        ResourceBundleCache cache = new ResourceBundleCache(Files.createTempDirectory("preview-bundles"));

        assertNotNull(cache.store(id, bundle));
        JsonObject manifest = JsonParser.parseString(
                new String(cache.manifest(id), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(id, manifest.get("id").getAsString());
        assertEquals(ModResourceStack.CLOSURE_CACHE_VERSION, manifest.get("closure_cache_version").getAsInt());
        assertFalse(manifest.getAsJsonArray("entries").get(0).getAsJsonObject().has("sha256"));
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
                List.of(new ModResourceStack.Entry("assets/test/a.json", bytes.length,
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
                List.of(new ModResourceStack.Entry("assets/test/a.json", bytes.length,
                        hash, 0, bytes.length, "test")),
                List.of(new ModResourceStack.Shard(hash, bytes)), List.of(), List.of()));
        Files.writeString(root.resolve("closures").resolve(id).resolve("manifest.json"), "{}");

        assertNull(cache.get(id));
    }

    /**
     * 陈旧闭包事故(2026-08-26,发射多拉贡):旧实例建的闭包缺 connection/ 子目录兄弟,
     * 缓存校验只看协议版本 + 哈希完整性,新代码重启后继续永续服务旧闭包 ——
     * 管道臂永远缺失且 missing/failures 全空,无从发现。闭包内容依赖构建器逻辑,
     * 校验必须绑定显式闭包缓存版本:字段不匹配或缺失(历史缓存)都要作废重建。
     */
    @Test
    void closureFromADifferentCacheVersionIsInvalidated() throws Exception {
        Path root = Files.createTempDirectory("preview-bundle-cache");
        String id = "ab".repeat(32);
        try (ModResourceStack stack = new ModResourceStack(archive(Map.of(
                "assets/test/blockstates/stone.json", "{\"variants\":{\"\":{\"model\":\"test:block/stone\"}}}",
                "assets/test/models/block/stone.json", "{\"elements\":[]}")), List.of())) {
            new ResourceBundleCache(root).store(id, stack.closure(Set.of("assets/test/blockstates/stone.json")));
        }
        assertNotNull(new ResourceBundleCache(root).get(id), "现构建器自己写入的闭包必须有效");

        Path manifestFile = root.resolve("closures").resolve(id).resolve("manifest.json");
        JsonObject manifest = JsonParser.parseString(Files.readString(manifestFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        manifest.addProperty("closure_cache_version", ModResourceStack.CLOSURE_CACHE_VERSION + 1);
        Files.writeString(manifestFile, manifest.toString(), StandardCharsets.UTF_8);
        assertNull(new ResourceBundleCache(root).get(id), "其它缓存版本产出的闭包必须作废重建");

        manifest.remove("closure_cache_version");
        Files.writeString(manifestFile, manifest.toString(), StandardCharsets.UTF_8);
        assertNull(new ResourceBundleCache(root).get(id), "没有缓存版本字段的历史闭包同样必须作废");
    }

    private static Path archive(Map<String, String> entries) throws Exception {
        Path file = Files.createTempFile("preview-cache-resources", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
