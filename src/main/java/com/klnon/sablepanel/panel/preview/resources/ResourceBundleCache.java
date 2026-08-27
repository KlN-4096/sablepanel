package com.klnon.sablepanel.panel.preview.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klnon.sablepanel.panel.storage.AtomicIo;
import com.klnon.sablepanel.panel.storage.Digests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Disk-backed, content-addressed closure cache under the preview cache version directory. */
public final class ResourceBundleCache {
    private static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 512L * 1024 * 1024;
    private static final String MANIFEST = "manifest.json";
    private static final long LEASE_NANOS = TimeUnit.MINUTES.toNanos(5);
    private final Path root;
    private final Map<String, Long> leases = new HashMap<>();
    private final Map<String, Cached> validated = new HashMap<>();
    private final Map<String, String> manifestHashes = new HashMap<>();

    public ResourceBundleCache(Path resourceCacheRoot) {
        this.root = resourceCacheRoot.toAbsolutePath().normalize().resolve("closures");
    }

    public synchronized Cached store(String id, ModResourceStack.Bundle bundle) throws IOException {
        validateId(id);
        Path target = this.root.resolve(id);
        Cached cached = get(id);
        if (cached != null && cached.fingerprint().equals(bundle.fingerprint())) {
            lease(id);
            return cached;
        }
        Files.createDirectories(this.root);
        Path temporary = this.root.resolve("." + id + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(temporary.resolve("shards"));
            for (ModResourceStack.Shard shard : bundle.shards()) {
                byte[] bytes = shard.bytes();
                if (bytes.length > ModResourceStack.MAX_SHARD_BYTES || !Digests.sha256Hex(bytes).equals(shard.sha256())) {
                    throw new IOException("invalid resource shard " + shard.sha256());
                }
                Files.write(temporary.resolve("shards").resolve(shard.sha256()), bytes);
            }
            JsonObject manifest = JsonParser.parseString(bundle.manifestJson()).getAsJsonObject();
            manifest.addProperty("id", id);
            manifest.addProperty("closure_cache_version", ModResourceStack.CLOSURE_CACHE_VERSION);
            byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            if (manifestBytes.length > MAX_MANIFEST_BYTES) throw new IOException("resource manifest exceeds limit");
            Files.write(temporary.resolve(MANIFEST), manifestBytes);
            this.validated.remove(id);
            this.manifestHashes.remove(id);
            if (Files.exists(target)) deleteTree(target);
            AtomicIo.move(temporary, target);
            lease(id);
            trim();
            Cached stored = validate(target, id);
            if (stored == null) throw new IOException("resource closure validation failed after write");
            this.validated.put(id, stored);
            return stored;
        } finally {
            if (Files.exists(temporary)) deleteTree(temporary);
        }
    }

    public synchronized Cached get(String id) {
        try {
            validateId(id);
            Cached known = this.validated.get(id);
            String manifestHash = this.manifestHashes.get(id);
            if (known != null && manifestHash != null) {
                Path manifest = known.directory().resolve(MANIFEST);
                if (Files.isRegularFile(manifest)
                        && manifestHash.equals(Digests.sha256Hex(readBounded(manifest, MAX_MANIFEST_BYTES)))) {
                    lease(id);
                    return known;
                }
            }
            this.validated.remove(id);
            this.manifestHashes.remove(id);
            Cached cached = validate(this.root.resolve(id), id);
            if (cached != null) {
                this.validated.put(id, cached);
                lease(id);
            }
            return cached;
        } catch (Exception ignored) {
            return null;
        }
    }

    public synchronized byte[] manifest(String id) throws IOException {
        Cached cached = get(id);
        if (cached == null) return null;
        touch(cached.directory());
        return readBounded(cached.directory().resolve(MANIFEST), MAX_MANIFEST_BYTES);
    }

    public synchronized byte[] shard(String id, String sha256) throws IOException {
        validateId(id);
        validateHash(sha256);
        Cached cached = get(id);
        if (cached == null) return null;
        Path file = cached.directory().resolve("shards").resolve(sha256).normalize();
        if (!file.getParent().equals(cached.directory().resolve("shards")) || !Files.isRegularFile(file)) return null;
        byte[] bytes = readBounded(file, ModResourceStack.MAX_SHARD_BYTES);
        if (!Digests.sha256Hex(bytes).equals(sha256)) {
            this.validated.remove(id);
            this.manifestHashes.remove(id);
            deleteTree(cached.directory());
            throw new IOException("resource shard hash mismatch");
        }
        touch(cached.directory());
        return bytes;
    }

    public record Cached(String id, String fingerprint, Path directory) {
    }

    private Cached validate(Path directory, String id) {
        try {
            Path manifestFile = directory.resolve(MANIFEST);
            if (!Files.isRegularFile(manifestFile)) return null;
            byte[] manifestBytes = readBounded(manifestFile, MAX_MANIFEST_BYTES);
            JsonObject manifest = JsonParser.parseString(
                    new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!manifest.has("version") || manifest.get("version").getAsInt() != ModResourceStack.RESOURCE_PROTOCOL_VERSION) return null;
            if (!manifest.has("closure_cache_version")
                    || manifest.get("closure_cache_version").getAsInt() != ModResourceStack.CLOSURE_CACHE_VERSION) return null;
            if (!id.equals(manifest.get("id").getAsString())) return null;
            String fingerprint = manifest.get("fingerprint").getAsString();
            validateHash(fingerprint);
            var shardHashes = new java.util.LinkedHashSet<String>();
            for (var value : manifest.getAsJsonArray("entries")) {
                JsonObject entry = value.getAsJsonObject();
                String shard = entry.get("shard").getAsString();
                validateHash(shard);
                shardHashes.add(shard);
                long offset = entry.get("offset").getAsLong();
                long length = entry.get("length").getAsLong();
                Path file = directory.resolve("shards").resolve(shard);
                if (offset < 0 || length < 0 || offset + length > ModResourceStack.MAX_SHARD_BYTES
                        || !Files.isRegularFile(file) || Files.size(file) < offset + length) return null;
            }
            for (String shard : shardHashes) {
                Path file = directory.resolve("shards").resolve(shard);
                byte[] bytes = readBounded(file, ModResourceStack.MAX_SHARD_BYTES);
                if (!Digests.sha256Hex(bytes).equals(shard)) return null;
            }
            this.manifestHashes.put(id, Digests.sha256Hex(manifestBytes));
            return new Cached(id, fingerprint, directory);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void trim() throws IOException {
        if (!Files.isDirectory(this.root)) return;
        var items = new java.util.ArrayList<Map.Entry<Path, Long>>();
        long total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.root, path -> Files.isDirectory(path)
                && !path.getFileName().toString().startsWith("."))) {
            for (Path directory : stream) {
                long bytes = treeSize(directory);
                total = Math.addExact(total, bytes);
                items.add(Map.entry(directory, bytes));
            }
        }
        if (total <= MAX_CACHE_BYTES) return;
        items.sort(Comparator.comparingLong(item -> mtime(item.getKey())));
        for (Map.Entry<Path, Long> item : items) {
            if (total <= MAX_CACHE_BYTES) break;
            Path directory = item.getKey();
            String id = directory.getFileName().toString();
            if (leased(id)) continue;
            deleteTree(directory);
            this.validated.remove(id);
            this.manifestHashes.remove(id);
            total -= item.getValue();
        }
    }

    private static byte[] readBounded(Path file, long limit) throws IOException {
        long size = Files.size(file);
        if (size < 0 || size > limit || size > Integer.MAX_VALUE) throw new IOException("cached resource exceeds limit");
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes((int) size + 1);
            if (bytes.length != size) throw new IOException("cached resource size changed");
            return bytes;
        }
    }

    private static long treeSize(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); }
                catch (IOException ignored) { return 0; }
            }).sum();
        }
    }

    private static long mtime(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return 0L; }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void touch(Path directory) {
        try { Files.setLastModifiedTime(directory, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())); }
        catch (IOException ignored) { }
    }

    private void lease(String id) {
        long now = System.nanoTime();
        this.leases.entrySet().removeIf(entry -> entry.getValue() <= now);
        this.leases.put(id, now + LEASE_NANOS);
    }

    private boolean leased(String id) {
        Long until = this.leases.get(id);
        if (until == null) return false;
        if (until > System.nanoTime()) return true;
        this.leases.remove(id);
        return false;
    }

    private static void validateId(String value) {
        validateHash(value);
    }

    private static void validateHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid resource hash");
    }

}
