package com.klnon.sablepanel;

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

/**
 * 追加式 JSONL 事件日志,落在 <gamedir>/logs/sablepanel/events-<启动时间>.jsonl。
 * 事件量级低(生命周期 + 每分钟 stats),同步写 + 即时 flush 足够。
 */
public final class EventLog {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 每次启动一个新文件,只保留最近这些个 */
    private static final int KEEP_FILES = 20;
    /** 单个日志文件的字节上限,超过就换新文件(否则一个长跑的服务端就是一个无限增长的文件) */
    public static final long MAX_LOG_BYTES = 16L << 20;
    private static PrintWriter out;
    private static long bytes;

    private EventLog() {
    }

    public static synchronized void write(JsonObject o) {
        try {
            if (out == null) {
                Path dir = logDir();
                Files.createDirectories(dir);
                Path file = nextFile(dir, "events-");
                bytes = Files.exists(file) ? Files.size(file) : 0;
                out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                prune("events-", KEEP_FILES);
                SablePanel.LOGGER.info("sablepanel: event log -> {}", file);
            }
            o.addProperty("ts", System.currentTimeMillis());
            String line = GSON.toJson(o);
            out.println(line);
            out.flush();
            bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes >= MAX_LOG_BYTES) {
                out.close();
                out = null;   // 下一条重新开文件
            }
        } catch (IOException e) {
            SablePanel.LOGGER.warn("sablepanel: failed to write event log", e);
        }
    }

    public static synchronized void close() {
        if (out != null) {
            out.close();
            out = null;
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
    public static Path nextFile(Path dir, String prefix) throws IOException {
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
    public static void prune(String prefix, int keep) {
        prune(logDir(), prefix, keep);
    }

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
