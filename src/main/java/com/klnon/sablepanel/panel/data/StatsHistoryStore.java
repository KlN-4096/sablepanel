package com.klnon.sablepanel.panel.data;

import com.klnon.sablepanel.panel.PanelConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klnon.sablepanel.SablePanel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** 每秒性能样本的异步日文件存储，以及跨日期范围查询。 */
public final class StatsHistoryStore implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 4096;
    private static final long LIVE_SECONDS = 7200;
    private static final int FLUSH_EVERY = 10;
    private static final int MAX_POINTS = 2000;
    private static final long RAW_QUERY_MAX_SECONDS = 6 * 3600L;

    record Sample(long t, int ticks, long sumNs, long maxNs, Map<String, long[]> phys,
                  double bodyCostMs) {
        Sample copy() {
            Map<String, long[]> copied = new HashMap<>();
            for (var entry : this.phys.entrySet()) copied.put(entry.getKey(), entry.getValue().clone());
            return new Sample(this.t, this.ticks, this.sumNs, this.maxNs, Map.copyOf(copied), this.bodyCostMs);
        }
    }

    private final Path rawDirectory;
    private final Path minuteDirectory;
    private final int retentionDays;
    private final ZoneId zone;
    private final LinkedBlockingQueue<Sample> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final TreeMap<Long, Sample> live = new TreeMap<>();
    private final Thread writerThread;
    private volatile boolean running = true;
    private volatile long lastWarningMs;

    private BufferedWriter rawWriter;
    private BufferedWriter minuteWriter;
    private LocalDate rawDate;
    private LocalDate minuteDate;
    private MinuteAccumulator minute;
    private int unflushed;

    StatsHistoryStore(Path root, int retentionDays) throws IOException {
        this(root, retentionDays, ZoneId.systemDefault());
    }

    StatsHistoryStore(Path root, int retentionDays, ZoneId zone) throws IOException {
        this.rawDirectory = root.resolve("raw");
        this.minuteDirectory = root.resolve("minute");
        // 配置文件不是 API:即使绕过 PanelConfig.load,日期遍历也必须有硬上限。
        this.retentionDays = Math.max(1,
                Math.min(PanelConfig.MAX_STATS_RETENTION_DAYS, retentionDays));
        this.zone = zone;
        Files.createDirectories(this.rawDirectory);
        Files.createDirectories(this.minuteDirectory);
        maintainFiles(LocalDate.now(this.zone));
        this.writerThread = new Thread(this::writerLoop, "sablepanel-stats-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    void offer(Sample sample) {
        Sample immutable = sample.copy();
        synchronized (this.live) {
            this.live.put(immutable.t(), immutable);
            this.live.headMap(immutable.t() - LIVE_SECONDS, false).clear();
        }
        if (!this.queue.offer(immutable)) warnRateLimited("性能历史写入队列已满，本秒样本未落盘", null);
    }

    JsonObject query(long requestedFrom, long requestedTo, int requestedMaxPoints) {
        long now = System.currentTimeMillis() / 1000;
        long minimum = now - (long) this.retentionDays * 86400L;
        long from = Math.max(minimum, Math.min(requestedFrom, requestedTo));
        long to = Math.min(now, Math.max(requestedFrom, requestedTo));
        int maxPoints = Math.max(1, Math.min(MAX_POINTS, requestedMaxPoints));
        if (to < from) from = to;
        long span = Math.max(1, to - from + 1);
        boolean useMinute = span > RAW_QUERY_MAX_SECONDS || ceilDiv(span, maxPoints) >= 60;
        int sourceStep = useMinute ? 60 : 1;
        TreeMap<Long, Aggregate> source = readRange(from, to, useMinute);
        mergeLive(source, from, to, useMinute);
        long bucketSeconds = Math.max(sourceStep, ceilDiv(span, maxPoints));
        if (useMinute) bucketSeconds = ceilDiv(bucketSeconds, 60) * 60;
        TreeMap<Long, Aggregate> buckets = new TreeMap<>();
        for (Aggregate sample : source.values()) {
            long bucket = from + Math.floorDiv(sample.t - from, bucketSeconds) * bucketSeconds;
            buckets.computeIfAbsent(bucket, Aggregate::new).merge(sample);
        }
        return historyJson(from, to, bucketSeconds, sourceStep, buckets);
    }

    private TreeMap<Long, Aggregate> readRange(long from, long to, boolean useMinute) {
        TreeMap<Long, Aggregate> out = new TreeMap<>();
        Path directory = useMinute ? this.minuteDirectory : this.rawDirectory;
        LocalDate first = dateOf(from);
        LocalDate last = dateOf(to);
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            readFile(directory.resolve(date + ".jsonl.gz"), from, to, useMinute, out);
            readFile(directory.resolve(date + ".jsonl"), from, to, useMinute, out);
        }
        return out;
    }

    private void readFile(Path file, long from, long to, boolean minuteRows, TreeMap<Long, Aggregate> out) {
        if (!Files.isRegularFile(file)) return;
        int invalid = 0;
        try (BufferedReader reader = reader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonObject json = JsonParser.parseString(line).getAsJsonObject();
                    Aggregate value = minuteRows ? Aggregate.fromMinute(json) : Aggregate.fromRaw(json);
                    if (value.t < from || value.t > to) continue;
                    if (minuteRows) out.computeIfAbsent(value.t, Aggregate::new).merge(value);
                    else out.put(value.t, value);
                } catch (Exception ignored) {
                    invalid++;
                }
            }
        } catch (Exception error) {
            warnRateLimited("读取性能历史失败: " + file.getFileName(), error);
            return;
        }
        if (invalid > 0) warnRateLimited("性能历史中跳过 " + invalid + " 条损坏记录: " + file.getFileName(), null);
    }

    private BufferedReader reader(Path file) throws IOException {
        InputStream input = Files.newInputStream(file);
        if (file.getFileName().toString().endsWith(".gz")) input = new GZIPInputStream(input);
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private void mergeLive(TreeMap<Long, Aggregate> target, long from, long to, boolean minuteRows) {
        List<Sample> snapshot;
        synchronized (this.live) {
            snapshot = new ArrayList<>(this.live.subMap(from, true, to, true).values());
        }
        if (snapshot.isEmpty()) return;
        if (!minuteRows) {
            for (Sample sample : snapshot) target.put(sample.t(), Aggregate.fromSample(sample));
            return;
        }
        long currentMinute = Math.floorDiv(System.currentTimeMillis() / 1000, 60) * 60;
        Map<Long, Aggregate> pendingMinutes = new TreeMap<>();
        for (Sample sample : snapshot) {
            long minuteStart = Math.floorDiv(sample.t(), 60) * 60;
            if (minuteStart != currentMinute && target.containsKey(minuteStart)) continue;
            pendingMinutes.computeIfAbsent(minuteStart, Aggregate::new).add(sample);
        }
        for (var entry : pendingMinutes.entrySet()) {
            target.computeIfAbsent(entry.getKey(), Aggregate::new).merge(entry.getValue());
        }
    }

    private JsonObject historyJson(long from, long to, long bucketSeconds, int sourceStep,
                                   TreeMap<Long, Aggregate> buckets) {
        Set<String> dims = new TreeSet<>();
        for (Aggregate bucket : buckets.values()) dims.addAll(bucket.phys.keySet());
        JsonArray times = new JsonArray();
        JsonArray mspt = new JsonArray();
        JsonArray msptMax = new JsonArray();
        JsonArray bodyLogic = new JsonArray();
        Map<String, JsonArray> dimensionSeries = new TreeMap<>();
        for (String dim : dims) dimensionSeries.put(dim, new JsonArray());
        for (Aggregate bucket : buckets.values()) {
            times.add(bucket.t);
            mspt.add(round2(bucket.ticks > 0 ? bucket.sumNs / 1e6 / bucket.ticks : 0));
            msptMax.add(round2(bucket.maxNs / 1e6));
            bodyLogic.add(round2(bucket.bodyPeakMs));
            for (String dim : dims) {
                dimensionSeries.get(dim).add(round2(bucket.physPeakMs.getOrDefault(dim, 0.0)));
            }
        }
        JsonObject phys = new JsonObject();
        for (var entry : dimensionSeries.entrySet()) phys.add(entry.getKey(), entry.getValue());
        JsonObject out = new JsonObject();
        out.addProperty("range_from", from);
        out.addProperty("range_to", to);
        out.addProperty("step_seconds", bucketSeconds);
        out.addProperty("aggregation", bucketSeconds > 1 ? "peak" : "raw");
        out.add("t", times);
        out.add("mspt", mspt);
        out.add("mspt_max", msptMax);
        out.add("phys", phys);
        out.add("body_logic", bodyLogic);
        return out;
    }

    private void writerLoop() {
        try {
            while (this.running || !this.queue.isEmpty()) {
                Sample sample;
                try {
                    sample = this.queue.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    continue;
                }
                if (sample == null) {
                    try {
                        flushWriters();
                    } catch (Exception error) {
                        warnRateLimited("刷新性能历史失败，稍后重试", error);
                        closeWriters();
                        this.rawDate = null;
                        this.minuteDate = null;
                    }
                    continue;
                }
                try {
                    append(sample);
                } catch (Exception error) {
                    warnRateLimited("写入性能历史失败，稍后重试", error);
                    closeWriters();
                    this.rawDate = null;
                    this.minuteDate = null;
                }
            }
            try {
                flushMinute();
                flushWriters();
            } catch (Exception error) {
                warnRateLimited("关闭前刷新性能历史失败", error);
            }
        } catch (Throwable error) {
            warnRateLimited("性能历史写入线程停止", error);
        } finally {
            closeWriters();
        }
    }

    private void append(Sample sample) throws IOException {
        LocalDate date = dateOf(sample.t());
        if (!date.equals(this.rawDate)) rotateRaw(date);
        this.rawWriter.write(rawJson(sample).toString());
        this.rawWriter.newLine();
        long minuteStart = Math.floorDiv(sample.t(), 60) * 60;
        if (this.minute == null || this.minute.t != minuteStart) {
            flushMinute();
            this.minute = new MinuteAccumulator(minuteStart);
        }
        this.minute.add(sample);
        if (++this.unflushed >= FLUSH_EVERY) flushWriters();
    }

    private void rotateRaw(LocalDate date) throws IOException {
        LocalDate previous = this.rawDate;
        closeRawWriter();
        this.rawDate = date;
        this.rawWriter = appendWriter(this.rawDirectory.resolve(date + ".jsonl"));
        if (previous != null && previous.isBefore(date)) {
            compress(this.rawDirectory.resolve(previous + ".jsonl"));
            maintainFiles(date);
        }
    }

    private void rotateMinute(LocalDate date) throws IOException {
        LocalDate previous = this.minuteDate;
        closeMinuteWriter();
        this.minuteDate = date;
        this.minuteWriter = appendWriter(this.minuteDirectory.resolve(date + ".jsonl"));
        if (previous != null && previous.isBefore(date)) compress(this.minuteDirectory.resolve(previous + ".jsonl"));
    }

    private BufferedWriter appendWriter(Path file) throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void flushMinute() throws IOException {
        if (this.minute == null || this.minute.seconds == 0) return;
        LocalDate date = dateOf(this.minute.t);
        if (!date.equals(this.minuteDate)) rotateMinute(date);
        this.minuteWriter.write(this.minute.toJson().toString());
        this.minuteWriter.newLine();
        this.minute = null;
    }

    private void flushWriters() throws IOException {
        if (this.rawWriter != null) this.rawWriter.flush();
        if (this.minuteWriter != null) this.minuteWriter.flush();
        this.unflushed = 0;
    }

    private void closeWriters() {
        closeRawWriter();
        closeMinuteWriter();
    }

    private void closeRawWriter() {
        if (this.rawWriter == null) return;
        try {
            this.rawWriter.close();
        } catch (IOException error) {
            warnRateLimited("关闭秒级历史文件失败", error);
        }
        this.rawWriter = null;
    }

    private void closeMinuteWriter() {
        if (this.minuteWriter == null) return;
        try {
            this.minuteWriter.close();
        } catch (IOException error) {
            warnRateLimited("关闭分钟历史文件失败", error);
        }
        this.minuteWriter = null;
    }

    private void maintainFiles(LocalDate today) throws IOException {
        compressCompleted(this.rawDirectory, today);
        compressCompleted(this.minuteDirectory, today);
        LocalDate keepFrom = today.minusDays(this.retentionDays - 1L);
        deleteExpired(this.rawDirectory, keepFrom);
        deleteExpired(this.minuteDirectory, keepFrom);
    }

    private void compressCompleted(Path directory, LocalDate today) throws IOException {
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".jsonl")) continue;
                LocalDate date = fileDate(name);
                if (date != null && date.isBefore(today)) compress(file);
            }
        }
    }

    private void deleteExpired(Path directory, LocalDate keepFrom) throws IOException {
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                LocalDate date = fileDate(file.getFileName().toString());
                if (date != null && date.isBefore(keepFrom)) Files.deleteIfExists(file);
            }
        }
    }

    private static LocalDate fileDate(String name) {
        if (name.length() < 10) return null;
        try {
            return LocalDate.parse(name.substring(0, 10));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private void compress(Path source) throws IOException {
        if (!Files.isRegularFile(source)) return;
        Path target = source.resolveSibling(source.getFileName() + ".gz");
        if (Files.exists(target)) return;
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (InputStream input = Files.newInputStream(source);
             GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(temp))) {
            input.transferTo(output);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.delete(source);
    }

    private JsonObject rawJson(Sample sample) {
        JsonObject out = new JsonObject();
        out.addProperty("t", sample.t());
        out.addProperty("ticks", sample.ticks());
        out.addProperty("sum_ns", sample.sumNs());
        out.addProperty("max_ns", sample.maxNs());
        out.addProperty("body_ms", sample.bodyCostMs());
        out.add("phys", physJson(sample.phys(), null));
        return out;
    }

    private static JsonObject physJson(Map<String, long[]> values, Map<String, Double> peaks) {
        JsonObject out = new JsonObject();
        for (var entry : values.entrySet()) {
            JsonArray value = new JsonArray();
            value.add(entry.getValue()[0]);
            value.add(entry.getValue()[1]);
            if (peaks != null) value.add(peaks.getOrDefault(entry.getKey(), 0.0));
            out.add(entry.getKey(), value);
        }
        return out;
    }

    private LocalDate dateOf(long epochSecond) {
        return Instant.ofEpochSecond(epochSecond).atZone(this.zone).toLocalDate();
    }

    private void warnRateLimited(String message, Throwable error) {
        long now = System.currentTimeMillis();
        if (now - this.lastWarningMs < 60_000L) return;
        this.lastWarningMs = now;
        if (error == null) SablePanel.LOGGER.warn("sablepanel: {}", message);
        else SablePanel.LOGGER.warn("sablepanel: {}", message, error);
    }

    @Override
    public void close() {
        this.running = false;
        this.writerThread.interrupt();
        try {
            this.writerThread.join(10_000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static long ceilDiv(long value, long divisor) {
        return Math.floorDiv(value + divisor - 1, divisor);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final class MinuteAccumulator {
        private final long t;
        private int seconds;
        private long ticks;
        private long sumNs;
        private long maxNs;
        private final Map<String, long[]> phys = new HashMap<>();
        private final Map<String, Double> physPeakMs = new HashMap<>();
        private double bodySumMs;
        private double bodyPeakMs;

        MinuteAccumulator(long t) {
            this.t = t;
        }

        void add(Sample sample) {
            this.seconds++;
            this.ticks += sample.ticks();
            this.sumNs += sample.sumNs();
            this.maxNs = Math.max(this.maxNs, sample.maxNs());
            this.bodySumMs += sample.bodyCostMs();
            this.bodyPeakMs = Math.max(this.bodyPeakMs, sample.bodyCostMs());
            for (var entry : sample.phys().entrySet()) {
                long[] total = this.phys.computeIfAbsent(entry.getKey(), ignored -> new long[2]);
                total[0] += entry.getValue()[0];
                total[1] += entry.getValue()[1];
                double perTick = sample.ticks() > 0 ? entry.getValue()[0] / 1e6 / sample.ticks() : 0;
                this.physPeakMs.merge(entry.getKey(), perTick, Math::max);
            }
        }

        JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("t", this.t);
            out.addProperty("seconds", this.seconds);
            out.addProperty("ticks", this.ticks);
            out.addProperty("sum_ns", this.sumNs);
            out.addProperty("max_ns", this.maxNs);
            out.addProperty("body_sum_ms", this.bodySumMs);
            out.addProperty("body_peak_ms", this.bodyPeakMs);
            out.add("phys", physJson(this.phys, this.physPeakMs));
            return out;
        }
    }

    private static final class Aggregate {
        private final long t;
        private int seconds;
        private long ticks;
        private long sumNs;
        private long maxNs;
        private final Map<String, long[]> phys = new HashMap<>();
        private final Map<String, Double> physPeakMs = new HashMap<>();
        private double bodySumMs;
        private double bodyPeakMs;

        Aggregate(long t) {
            this.t = t;
        }

        static Aggregate fromSample(Sample sample) {
            Aggregate out = new Aggregate(sample.t());
            out.add(sample);
            return out;
        }

        static Aggregate fromRaw(JsonObject json) {
            Sample sample = new Sample(json.get("t").getAsLong(), json.get("ticks").getAsInt(),
                    json.get("sum_ns").getAsLong(), json.get("max_ns").getAsLong(),
                    parsePhys(json.getAsJsonObject("phys")),
                    json.has("body_ms") ? json.get("body_ms").getAsDouble() : 0);
            return fromSample(sample);
        }

        static Aggregate fromMinute(JsonObject json) {
            Aggregate out = new Aggregate(json.get("t").getAsLong());
            out.seconds = json.get("seconds").getAsInt();
            out.ticks = json.get("ticks").getAsLong();
            out.sumNs = json.get("sum_ns").getAsLong();
            out.maxNs = json.get("max_ns").getAsLong();
            out.bodySumMs = json.has("body_sum_ms") ? json.get("body_sum_ms").getAsDouble() : 0;
            out.bodyPeakMs = json.has("body_peak_ms") ? json.get("body_peak_ms").getAsDouble() : 0;
            JsonObject phys = json.getAsJsonObject("phys");
            out.phys.putAll(parsePhys(phys));
            for (var entry : phys.entrySet()) {
                JsonArray value = entry.getValue().getAsJsonArray();
                if (value.size() > 2) out.physPeakMs.put(entry.getKey(), value.get(2).getAsDouble());
            }
            return out;
        }

        private static Map<String, long[]> parsePhys(JsonObject phys) {
            Map<String, long[]> out = new HashMap<>();
            if (phys == null) return out;
            for (var entry : phys.entrySet()) {
                JsonArray value = entry.getValue().getAsJsonArray();
                out.put(entry.getKey(), new long[]{value.get(0).getAsLong(), value.get(1).getAsLong()});
            }
            return out;
        }

        void add(Sample sample) {
            this.seconds++;
            this.ticks += sample.ticks();
            this.sumNs += sample.sumNs();
            this.maxNs = Math.max(this.maxNs, sample.maxNs());
            this.bodySumMs += sample.bodyCostMs();
            this.bodyPeakMs = Math.max(this.bodyPeakMs, sample.bodyCostMs());
            for (var entry : sample.phys().entrySet()) {
                long[] total = this.phys.computeIfAbsent(entry.getKey(), ignored -> new long[2]);
                total[0] += entry.getValue()[0];
                total[1] += entry.getValue()[1];
                double perTick = sample.ticks() > 0 ? entry.getValue()[0] / 1e6 / sample.ticks() : 0;
                this.physPeakMs.merge(entry.getKey(), perTick, Math::max);
            }
        }

        void merge(Aggregate other) {
            this.seconds += other.seconds;
            this.ticks += other.ticks;
            this.sumNs += other.sumNs;
            this.maxNs = Math.max(this.maxNs, other.maxNs);
            this.bodySumMs += other.bodySumMs;
            this.bodyPeakMs = Math.max(this.bodyPeakMs, other.bodyPeakMs);
            for (var entry : other.phys.entrySet()) {
                long[] total = this.phys.computeIfAbsent(entry.getKey(), ignored -> new long[2]);
                total[0] += entry.getValue()[0];
                total[1] += entry.getValue()[1];
            }
            for (var entry : other.physPeakMs.entrySet()) {
                this.physPeakMs.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
    }
}
