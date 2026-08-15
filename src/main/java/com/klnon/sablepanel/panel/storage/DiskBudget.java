package com.klnon.sablepanel.panel.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 磁盘字节预算的 mtime 淘汰:总量超限时按修改时间从旧到新删,直到落回预算。
 * 缩略图缓存(单文件)与资源闭包缓存(目录树)共用;内存版 LRU 不在此列。
 */
public final class DiskBudget {
    /** 一个可淘汰项:路径 + 调用方已算好的体量(文件大小或整树大小)。 */
    public record Sized(Path path, long bytes) {
    }

    @FunctionalInterface
    public interface Eviction {
        void delete(Path path) throws IOException;
    }

    private DiskBudget() {
    }

    /**
     * 按 mtime 升序淘汰直到 {@code total ≤ maxBytes}。{@code skip} 命中的项保留但体量仍占预算
     * (与资源缓存"租约中的闭包不删"语义一致);mtime 读不出按 0 处理(视为最旧)。
     */
    public static void evictByMtime(List<Sized> items, long maxBytes,
                                    Predicate<Path> skip, Eviction delete) throws IOException {
        long total = 0;
        for (Sized item : items) total = Math.addExact(total, item.bytes());
        if (total <= maxBytes) return;
        items.sort(Comparator.comparingLong(item -> mtime(item.path())));
        for (Sized victim : items) {
            if (total <= maxBytes) break;
            if (skip.test(victim.path())) continue;
            delete.delete(victim.path());
            total -= victim.bytes();
        }
    }

    private static long mtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException gone) {
            return 0L;
        }
    }
}
