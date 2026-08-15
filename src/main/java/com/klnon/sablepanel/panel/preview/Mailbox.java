package com.klnon.sablepanel.panel.preview;

import java.util.Comparator;
import java.util.Map;

/**
 * 异步结果信箱:工作线程 {@link #complete} 投递,HTTP 线程轮询 {@link #result} 取件。
 * PreviewSubsystem 的渲染任务表与 ResourcePreparation 的读取任务表此前各手写一份同形实现。
 */
public final class Mailbox<T> {
    public volatile T result;
    public volatile long completedAtNanos;

    public void complete(T value) {
        this.completedAtNanos = System.nanoTime();
        this.result = value;
    }

    /** 未出结果的在途任务数(并发上限判定用)。 */
    public static long active(Map<String, ? extends Mailbox<?>> table) {
        return table.values().stream().filter(cell -> cell.result == null).count();
    }

    /** 已完成但没人来取的信箱只留 keep 个,按完成时间淘汰最旧 —— 表的大小必须有界。 */
    public static void trim(Map<String, ? extends Mailbox<?>> table, int keep) {
        while (table.values().stream().filter(cell -> cell.result != null).count() > keep) {
            String oldest = table.entrySet().stream().filter(entry -> entry.getValue().result != null)
                    .min(Comparator.comparingLong(entry -> entry.getValue().completedAtNanos))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest == null) return;
            table.remove(oldest);
        }
    }

    /** 完成时间早于 cutoff 的信箱整批清掉(取件方早已放弃的请求)。 */
    public static void prune(Map<String, ? extends Mailbox<?>> table, long cutoff) {
        table.entrySet().removeIf(entry -> entry.getValue().result != null
                && entry.getValue().completedAtNanos <= cutoff);
    }
}
