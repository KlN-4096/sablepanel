package com.klnon.sablepanel.panel.preview.thumb;

import com.klnon.sablepanel.panel.storage.AtomicIo;
import com.klnon.sablepanel.panel.storage.DiskBudget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 缩略图磁盘缓存:一体一张 {@code <uuid>.png} + 边车索引(入库时的内容签名)。
 * 字节上限 LRU:超限按文件修改时间(=入库时间)淘汰最旧。索引与文件都落在 cache/ 下,
 * 重启后直接复用 —— 回收站里的体也因此能显示生前渲好的图。
 * <p>
 * 请求线程读写,全部方法共一把锁。
 * ponytail: 全局锁 + 每次写盘重列目录,规模=几百体、写入以秒计,够用;上万体再换增量记账。
 */
public final class ThumbStore {
    public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;
    private static final String INDEX_FILE = "index.tsv";

    private final Path dir;
    private final long maxBytes;
    private final Map<UUID, String> sigs = new HashMap<>();

    public ThumbStore(Path dir, long maxBytes) throws IOException {
        this.dir = dir;
        this.maxBytes = maxBytes;
        Files.createDirectories(dir);
        loadIndex();
    }

    /** 该体的图入库时的内容签名;没图返回 null */
    public synchronized String sig(UUID uuid) {
        return this.sigs.get(uuid);
    }

    /** @return PNG 字节;没有(未渲/文件丢失)返回 null */
    public synchronized byte[] read(UUID uuid) {
        if (!this.sigs.containsKey(uuid)) return null;
        try {
            return Files.readAllBytes(pngPath(uuid));
        } catch (IOException gone) {
            // 文件被 LRU 之外的力量清了(手动删/盘故障):条目作废,前端下轮重渲
            this.sigs.remove(uuid);
            saveIndexQuietly();
            return null;
        }
    }

    public synchronized void put(UUID uuid, String sig, byte[] png) throws IOException {
        Path target = pngPath(uuid);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, png);
        AtomicIo.move(temporary, target);
        this.sigs.put(uuid, sig);
        evictOverBudget();
        saveIndex();
    }

    private void evictOverBudget() throws IOException {
        List<DiskBudget.Sized> pngs = new ArrayList<>();
        try (var listing = Files.list(this.dir)) {
            for (Path file : listing.toList()) {
                if (!file.getFileName().toString().endsWith(".png")) continue;
                pngs.add(new DiskBudget.Sized(file, Files.size(file)));
            }
        }
        DiskBudget.evictByMtime(pngs, this.maxBytes, file -> false, victim -> {
            Files.deleteIfExists(victim);
            String name = victim.getFileName().toString();
            try {
                this.sigs.remove(UUID.fromString(name.substring(0, name.length() - 4)));
            } catch (IllegalArgumentException notOurs) {
            }
        });
    }

    private Path pngPath(UUID uuid) {
        return this.dir.resolve(uuid + ".png");
    }

    private void loadIndex() throws IOException {
        Path index = this.dir.resolve(INDEX_FILE);
        if (!Files.isRegularFile(index)) return;
        for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
            String[] parts = line.split("\t", -1);
            // 坏行与服务端渲染时代的三列旧行都丢弃:代价只是那一体重渲一次
            if (parts.length != 2) continue;
            try {
                this.sigs.put(UUID.fromString(parts[0]), parts[1]);
            } catch (IllegalArgumentException badLine) {
            }
        }
    }

    private void saveIndex() throws IOException {
        StringBuilder out = new StringBuilder(this.sigs.size() * 96);
        for (var entry : this.sigs.entrySet()) {
            out.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
        }
        AtomicIo.writeString(this.dir.resolve(INDEX_FILE), out.toString());
    }

    private void saveIndexQuietly() {
        try {
            saveIndex();
        } catch (IOException ignored) {
            // 索引写不动只影响重启后的增量判断,不值得让调用方失败
        }
    }
}
