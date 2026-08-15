package com.klnon.sablepanel.panel.preview.thumb;

import com.klnon.sablepanel.panel.storage.AtomicIo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 缩略图磁盘缓存:一体一张 {@code <uuid>.png} + 边车索引(入库时的内容签名)。
 * 字节上限 LRU:超限按文件修改时间(=入库时间)淘汰最旧。索引与文件都落在 cache/ 下,
 * 重启后直接复用 —— 回收站里的体也因此能显示生前渲好的图。
 * <p>
 * 请求线程读写,全部方法共一把锁。启动时扫描一次文件并建字节账本；之后上传和淘汰只做
 * 增量记账。索引是追加日志，重启时和日志膨胀后压缩，避免首次填充时反复重写整表。
 */
public final class ThumbStore {
    public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;
    private static final String INDEX_FILE = "index.tsv";
    private static final int MIN_INDEX_RECORDS_BEFORE_COMPACT = 256;

    private final Path dir;
    private final long maxBytes;
    /** 插入顺序就是入库时间；覆盖时先删再放，队首始终是最旧图。 */
    private final LinkedHashMap<UUID, Cached> entries = new LinkedHashMap<>();
    private long totalBytes;
    private int indexRecords;

    private record Cached(String sig, long bytes) {
    }

    private record Existing(UUID uuid, String sig, long bytes, long modified) {
    }

    public ThumbStore(Path dir, long maxBytes) throws IOException {
        this.dir = dir;
        this.maxBytes = maxBytes;
        Files.createDirectories(dir);
        loadFiles(loadIndex());
        evictOverBudget();
        saveIndex();
    }

    /** 该体的图入库时的内容签名;没图返回 null */
    public synchronized String sig(UUID uuid) {
        Cached cached = this.entries.get(uuid);
        return cached == null ? null : cached.sig;
    }

    /** @return PNG 字节;没有(未渲/文件丢失)返回 null */
    public synchronized byte[] read(UUID uuid) {
        Cached cached = this.entries.get(uuid);
        if (cached == null) return null;
        try {
            return Files.readAllBytes(pngPath(uuid));
        } catch (IOException gone) {
            // 文件被 LRU 之外的力量清了(手动删/盘故障):条目作废,前端下轮重渲
            this.entries.remove(uuid);
            this.totalBytes -= cached.bytes;
            forgetQuietly(uuid);
            return null;
        }
    }

    public synchronized void put(UUID uuid, String sig, byte[] png) throws IOException {
        Path target = pngPath(uuid);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, png);
        AtomicIo.move(temporary, target);
        Cached previous = this.entries.remove(uuid);
        if (previous != null) this.totalBytes -= previous.bytes;
        this.entries.put(uuid, new Cached(sig, png.length));
        this.totalBytes += png.length;
        appendIndex(uuid, sig);
        evictOverBudget();
        compactIndexIfNeeded();
    }

    private void evictOverBudget() throws IOException {
        var iterator = this.entries.entrySet().iterator();
        while (this.totalBytes > this.maxBytes && iterator.hasNext()) {
            Map.Entry<UUID, Cached> victim = iterator.next();
            Files.deleteIfExists(pngPath(victim.getKey()));
            this.totalBytes -= victim.getValue().bytes;
            iterator.remove();
            appendIndex(victim.getKey(), "");
        }
    }

    private Path pngPath(UUID uuid) {
        return this.dir.resolve(uuid + ".png");
    }

    private Map<UUID, String> loadIndex() throws IOException {
        Map<UUID, String> signatures = new HashMap<>();
        Path index = this.dir.resolve(INDEX_FILE);
        if (!Files.isRegularFile(index)) return signatures;
        for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
            String[] parts = line.split("\t", -1);
            // 坏行与服务端渲染时代的三列旧行都丢弃:代价只是那一体重渲一次
            if (parts.length != 2) continue;
            try {
                UUID uuid = UUID.fromString(parts[0]);
                if (parts[1].isEmpty()) signatures.remove(uuid);
                else signatures.put(uuid, parts[1]);
            } catch (IllegalArgumentException badLine) {
            }
        }
        return signatures;
    }

    private void loadFiles(Map<UUID, String> signatures) throws IOException {
        List<Existing> existing = new ArrayList<>();
        try (var listing = Files.list(this.dir)) {
            for (Path file : listing.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".png")) continue;
                try {
                    UUID uuid = UUID.fromString(name.substring(0, name.length() - 4));
                    String sig = signatures.get(uuid);
                    if (sig == null) {
                        Files.deleteIfExists(file);
                        continue;
                    }
                    existing.add(new Existing(uuid, sig, Files.size(file),
                            Files.getLastModifiedTime(file).toMillis()));
                } catch (IllegalArgumentException notOurs) {
                    Files.deleteIfExists(file);
                }
            }
        }
        existing.sort(Comparator.comparingLong(Existing::modified));
        for (Existing item : existing) {
            this.entries.put(item.uuid, new Cached(item.sig, item.bytes));
            this.totalBytes += item.bytes;
        }
    }

    private void saveIndex() throws IOException {
        StringBuilder out = new StringBuilder(this.entries.size() * 96);
        for (var entry : this.entries.entrySet()) {
            out.append(entry.getKey()).append('\t').append(entry.getValue().sig).append('\n');
        }
        AtomicIo.writeString(this.dir.resolve(INDEX_FILE), out.toString());
        this.indexRecords = this.entries.size();
    }

    private void appendIndex(UUID uuid, String sig) throws IOException {
        Files.writeString(this.dir.resolve(INDEX_FILE), uuid + "\t" + sig + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.indexRecords++;
    }

    private void compactIndexIfNeeded() throws IOException {
        int threshold = Math.max(MIN_INDEX_RECORDS_BEFORE_COMPACT, this.entries.size() * 2);
        if (this.indexRecords >= threshold) saveIndex();
    }

    private void forgetQuietly(UUID uuid) {
        try {
            appendIndex(uuid, "");
            compactIndexIfNeeded();
        } catch (IOException ignored) {
            // 索引写不动只影响重启后的增量判断,不值得让调用方失败
        }
    }
}
