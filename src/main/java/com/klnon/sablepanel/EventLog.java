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

/**
 * 追加式 JSONL 事件日志,落在 <gamedir>/logs/sablepanel/events-<启动时间>.jsonl。
 * 事件量级低(生命周期 + 每分钟 stats),同步写 + 即时 flush 足够。
 */
public final class EventLog {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static PrintWriter out;

    private EventLog() {
    }

    public static synchronized void write(JsonObject o) {
        try {
            if (out == null) {
                Path dir = logDir();
                Files.createDirectories(dir);
                Path file = dir.resolve("events-" + TS.format(LocalDateTime.now()) + ".jsonl");
                out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                SablePanel.LOGGER.info("sablepanel: event log -> {}", file);
            }
            o.addProperty("ts", System.currentTimeMillis());
            out.println(GSON.toJson(o));
            out.flush();
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
}
