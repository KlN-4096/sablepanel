package com.klnon.sablepanel.panel.audit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.klnon.sablepanel.SablePanel;

/**
 * 追加式 JSONL 事件日志,落在 <gamedir>/logs/sablepanel/events-<启动时间>.jsonl。
 * <p>
 * 事件来自主线程(体 add/remove、孤儿告警)与作业线程。写盘与 flush 全部在专用后台线程:
 * 碎片风暴时每个新体一条事件,主线程只做一次有界入队,不再直接做磁盘 IO。
 * 队列满时丢弃最新事件并计数——风暴时黑匣子保尽力而为,绝不反压主线程。
 */
public final class EventLog {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 每次启动一个新文件,只保留最近这些个 */
    private static final int KEEP_FILES = 20;
    /** 单个日志文件的字节上限,超过就换新文件(否则一个长跑的服务端就是一个无限增长的文件) */
    static final long MAX_LOG_BYTES = 16L << 20;
    private static final int QUEUE_CAPACITY = 512;

    private static final LinkedBlockingQueue<JsonObject> QUEUE = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final RollingWriter WRITER = new RollingWriter("events-", KEEP_FILES);
    private static volatile boolean running;
    private static Thread writerThread;   // 与 running 一起由类锁保护

    private EventLog() {
    }

    /** 任意线程可调:打事件时间戳后入队,不做任何 IO */
    public static void write(JsonObject o) {
        o.addProperty("ts", System.currentTimeMillis());
        if (!running) ensureWriter();
        if (!QUEUE.offer(o)) {
            long dropped = DROPPED.incrementAndGet();
            if (dropped == 1 || dropped % 1000 == 0) {
                SablePanel.LOGGER.warn("sablepanel: event log queue full, {} events dropped so far", dropped);
            }
        }
    }

    private static synchronized void ensureWriter() {
        if (running) return;
        running = true;
        writerThread = new Thread(EventLog::drainLoop, "sablepanel-eventlog");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private static void drainLoop() {
        while (running || !QUEUE.isEmpty()) {
            JsonObject o;
            try {
                o = QUEUE.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                continue;   // close() 用中断催醒,退出条件只看 running + 队列排空
            }
            if (o != null) WRITER.writeLine(GSON.toJson(o));
        }
        WRITER.close();
    }

    /**
     * 逐行 JSONL 滚动写手:懒开文件(名带启动时间戳)、逐行 flush、写满 {@link #MAX_LOG_BYTES}
     * 换新文件并淘汰旧档。events-/jobs- 两条日志共用;写失败只告警不抛 ——
     * 日志坏了不该拖垮事件线程或作业收尾。
     */
    public static final class RollingWriter {
        private final String prefix;
        private final int keep;
        private PrintWriter out;
        private Path file;
        private long bytes;

        public RollingWriter(String prefix, int keep) {
            this.prefix = prefix;
            this.keep = keep;
        }

        /** 当前正在写的文件;还没写过任何行时为 null */
        public synchronized Path file() {
            return this.file;
        }

        public synchronized void writeLine(String line) {
            try {
                if (this.out == null) {
                    Path directory = logDir();
                    Files.createDirectories(directory);
                    this.file = nextFile(directory, this.prefix);
                    this.bytes = Files.exists(this.file) ? Files.size(this.file) : 0;
                    this.out = new PrintWriter(Files.newBufferedWriter(this.file, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                    prune(directory, this.prefix, this.keep);
                    SablePanel.LOGGER.info("sablepanel: {} log -> {}", label(), this.file);
                }
                this.out.println(line);
                this.out.flush();
                this.bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (this.bytes >= MAX_LOG_BYTES) {
                    this.out.close();
                    this.out = null;   // 下一条重新开文件(名带当前时间戳),顺带再淘汰一次
                }
            } catch (Exception error) {
                SablePanel.LOGGER.warn("sablepanel: failed to write {} log", label(), error);
            }
        }

        /**
         * 关掉当前文件。刻意不设「关停后永久拒写」的闸:事件写手是 static final、活过整个 JVM,
         * 退回主菜单再进第二个世界时下一行必须能按新时间戳重开(带闸就是静默死到重启游戏)。
         * 作业侧那个"关停后别再重开文件"的需求由 {@code JobService} 自己的标志管——
         * 它的写手是每实例的,语义天然终身。
         */
        public synchronized void close() {
            if (this.out != null) {
                this.out.close();
                this.out = null;
            }
        }

        /** 日志行里的自称:文案与拆分前逐字一致(events-→event、jobs-→job) */
        private String label() {
            String name = this.prefix.endsWith("-") ? this.prefix.substring(0, this.prefix.length() - 1) : this.prefix;
            return name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
        }
    }

    /** 停服收尾:排空队列后关闭文件。sable 在停服晚期逐体 UNLOADED,这些事件都已在队列里 */
    public static void close() {
        Thread thread;
        synchronized (EventLog.class) {
            if (!running) return;
            running = false;
            thread = writerThread;
            writerThread = null;
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(TimeUnit.SECONDS.toMillis(3));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static Path logDir() {
        return FMLPaths.GAMEDIR.get().resolve("logs").resolve("sablepanel");
    }

    /**
     * 挑一个还没写满的日志文件名。
     * <p>
     * 文件名只精确到秒。单秒内写满 {@link #MAX_LOG_BYTES} 时,下一条会用同一个名字以 APPEND 重开,
     * 分片就等于没做 —— 高频写入下文件会一路涨下去。撞上就往后加 {@code -1}、{@code -2}。
     * 序号形式排在下一秒之前({@code -120000-1} < {@code -120001}),{@link #prune} 的字典序仍是时间序。
     */
    private static Path nextFile(Path dir, String prefix) throws IOException {
        return nextFile(dir, prefix, TS.format(LocalDateTime.now()));
    }

    /** 时间戳单独传:精确到秒的话,测试要靠"几次调用恰好落在同一秒"才成立,那是竞态不是用例 */
    static Path nextFile(Path dir, String prefix, String time) throws IOException {
        String stamp = prefix + time;
        Path file = dir.resolve(stamp + ".jsonl");
        for (int n = 1; Files.exists(file) && Files.size(file) >= MAX_LOG_BYTES; n++) {
            file = dir.resolve(stamp + "-" + n + ".jsonl");
        }
        return file;
    }

    /**
     * 只保留最近 {@code keep} 个同前缀日志文件,其余删除。
     * <p>
     * 文件名带启动时间戳,字典序即时间序。每次重启新建一个文件,没有这道清理就是无限累积。
     * 新文件已经建好才调用,所以它自己也算在 keep 里面。
     */
    static void prune(Path dir, String prefix, int keep) {
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted(Comparator.reverseOrder())
                    .skip(keep)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
