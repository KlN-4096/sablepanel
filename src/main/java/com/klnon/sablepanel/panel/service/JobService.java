package com.klnon.sablepanel.panel.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.EventLog;
import com.klnon.sablepanel.SablePanel;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 面板操作作业化。
 * <p>
 * 起因是一次生产事故:强加载一个 178 依赖的巨型体时,{@code prepareChain} 在传输层的
 * callback 线程上做了 64 次全盘 gunzip 扫描,单次请求跑了 16 分钟还没完。传输层每连接
 * 只有 4 个在飞槽位,而浏览器 30 秒就超时——用户看不到任何进展,自然会再点几次,
 * 4 个槽位占满后整个面板永久 503。
 * <p>
 * 所以改成:请求只负责入队并立刻返回 jobId,真正的活在后台线程上跑,进度通过
 * {@link Job#phase} 回报给面板。三层保护:
 * <ul>
 *   <li>同一个体同时只允许一个作业({@link #busy}),重复点击直接被拒——事故的放大器就是它;</li>
 *   <li>worker 池上限 = 核心数/3,空闲 30 秒自动回收(见 {@link #JobService});</li>
 *   <li>翻存档这一段全局串行({@link #LOCATE}):多个线程读同一批文件、做同一份解压,
 *       并行没有收益,只会互相抢 IO 和 CPU。</li>
 * </ul>
 */
public final class JobService implements AutoCloseable {
    /** 日志文件名白名单:客户端可指定读哪个文件,必须防路径穿越 */
    public static final Pattern LOG_FILE = Pattern.compile("jobs-\\d{8}-\\d{6}\\.jsonl");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Gson GSON = new Gson();
    private static final int HISTORY_MAX = 200;
    /** 终态作业在内存里多留一会儿,够前端轮询看到并弹一次 toast */
    private static final int TRAIL_MAX = 64;

    /** 翻存档全局串行。磁盘竞争是 JVM 级的,每个服务端一个面板,静态即可 */
    private static final Semaphore LOCATE = new Semaphore(1);
    /** 让深层代码(prepareChain/locateMember)不必层层传 Job 就能回报进度 */
    private static final ThreadLocal<Job> CURRENT = new ThreadLocal<>();

    public enum State { QUEUED, RUNNING, DONE, FAILED }

    @FunctionalInterface
    public interface Work {
        JsonObject run() throws Exception;
    }

    public static final class Job {
        public final long seq;
        public final String op;
        public final List<UUID> targets;
        public final String targetName;
        public final long queuedAt = System.currentTimeMillis();
        volatile State state = State.QUEUED;
        volatile String phase = "";
        volatile String detail = "";
        volatile long startedAt;
        volatile long endedAt;
        volatile String message = "";
        volatile JsonArray warnings;
        final List<String> trail = Collections.synchronizedList(new ArrayList<>());

        Job(long seq, String op, List<UUID> targets, String targetName) {
            this.seq = seq;
            this.op = op;
            this.targets = List.copyOf(targets);
            this.targetName = targetName == null ? "" : targetName;
        }

        /** 阶段切换:只在标签变化时记进轨迹,避免 64 次计数把日志刷爆 */
        public void phase(String label) {
            if (label == null || label.equals(this.phase)) return;
            this.phase = label;
            this.detail = "";
            synchronized (this.trail) {
                if (this.trail.size() < TRAIL_MAX) this.trail.add(label);
            }
        }

        /** 同一阶段内的实时计数(如 12/64),只影响界面显示,不进轨迹 */
        public void detail(String text) {
            this.detail = text == null ? "" : text;
        }

        public State state() {
            return this.state;
        }

        String display() {
            return this.detail.isEmpty() ? this.phase : this.phase + " " + this.detail;
        }
    }

    private final Object lock = new Object();
    private final Map<UUID, Long> busy = new HashMap<>();
    private final Map<Long, Job> active = new LinkedHashMap<>();
    private final Deque<Job> history = new ArrayDeque<>();
    private final ThreadPoolExecutor workers;
    private final Runnable afterJob;
    private final int maxWorkers;
    private long nextSeq = 1;
    private PrintWriter out;
    private Path logFile;

    /**
     * @param afterJob 作业结束后的回调(用来把运行时快照标记为待刷新,
     *                 这样前端下一次拉取拿到的就是真值,不必再靠写死的 setTimeout 等)
     */
    public JobService(Runnable afterJob) {
        this.afterJob = afterJob;
        this.maxWorkers = Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
        // core=max + allowCoreThreadTimeOut:任务来了才建线程,建到上限为止,空闲 30 秒全部回收
        this.workers = new ThreadPoolExecutor(this.maxWorkers, this.maxWorkers, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "sablepanel-op");
            thread.setDaemon(true);
            return thread;
        });
        this.workers.allowCoreThreadTimeOut(true);
    }

    public int maxWorkers() {
        return this.maxWorkers;
    }

    // ---------- 提交 ----------

    /**
     * 入队一个作业。目标体已有在跑的作业时抛异常(调用方转成 409)。
     *
     * @param targets 受影响的体;空表示全局操作(如重扫),不参与去重
     */
    public Job submit(String op, List<UUID> targets, String targetName, Work work) {
        Job job;
        synchronized (this.lock) {
            for (UUID target : targets) {
                Long running = this.busy.get(target);
                if (running != null) {
                    Job other = this.active.get(running);
                    throw new IllegalStateException("该物理体正在处理中("
                            + (other != null ? other.op : "作业 #" + running) + "),请等它结束");
                }
            }
            job = new Job(this.nextSeq++, op, targets, targetName);
            for (UUID target : targets) this.busy.put(target, job.seq);
            this.active.put(job.seq, job);
        }
        this.workers.execute(() -> run(job, work));
        return job;
    }

    private void run(Job job, Work work) {
        CURRENT.set(job);
        job.state = State.RUNNING;
        job.startedAt = System.currentTimeMillis();
        try {
            JsonObject result = work.run();
            job.state = State.DONE;
            job.message = summarize(result);
            if (result != null && result.has("warnings") && result.get("warnings").isJsonArray()) {
                job.warnings = result.getAsJsonArray("warnings");
            }
        } catch (Throwable error) {
            job.state = State.FAILED;
            job.message = messageOf(error);
            SablePanel.LOGGER.warn("sablepanel: job #{} {} failed", job.seq, job.op, error);
        } finally {
            CURRENT.remove();
            job.endedAt = System.currentTimeMillis();
            job.detail = "";
            synchronized (this.lock) {
                for (UUID target : job.targets) this.busy.remove(target, job.seq);
                this.active.remove(job.seq);
                this.history.addFirst(job);
                while (this.history.size() > HISTORY_MAX) this.history.removeLast();
            }
            append(job);
            try {
                if (this.afterJob != null) this.afterJob.run();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------- 进度回报(深层代码用) ----------

    /**
     * 当前线程正在执行的作业,没有则 null。
     * <p>
     * 主线程任务里报进度要先在作业线程上取出它再捕获进 lambda——ThreadLocal 跟着线程走,
     * 到了主线程就取不到了。
     */
    public static Job current() {
        return CURRENT.get();
    }

    public static void phase(String label) {
        Job job = CURRENT.get();
        if (job != null) job.phase(label);
    }

    public static void detail(String text) {
        Job job = CURRENT.get();
        if (job != null) job.detail(text);
    }

    /**
     * 在全局磁盘串行锁下执行。拿不到锁时把阶段改成"等待磁盘扫描",
     * 让用户知道是在排队而不是卡死。
     */
    public static <T> T underLocate(Callable<T> work) throws Exception {
        boolean queued = !LOCATE.tryAcquire();
        if (queued) {
            phase("等待磁盘扫描");
            LOCATE.acquire();
        }
        try {
            return work.call();
        } finally {
            LOCATE.release();
        }
    }

    // ---------- 读取 ----------

    /**
     * 给 /api/bodies 用:正在排队/执行的作业,<b>每个作业一条</b>,受影响的体放在 targets 里。
     * <p>
     * 早先是按体展开的,于是"回收站恢复""重扫磁盘"这类没有目标体的作业一条都不输出 ——
     * 界面上既看不到进度、也判不出它结束了(前端靠"从 busy 消失"认完成,它压根没进去过)。
     * 体已经被删掉时本来就没有行可以挂徽章,指示器不能依赖体行存在。
     */
    public JsonArray busyView() {
        JsonArray arr = new JsonArray();
        synchronized (this.lock) {
            for (Job job : this.active.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("seq", job.seq);
                o.addProperty("op", job.op);
                o.addProperty("state", job.state.name().toLowerCase());
                o.addProperty("phase", job.display());
                o.addProperty("since", job.startedAt > 0 ? job.startedAt : job.queuedAt);
                if (!job.targetName.isEmpty()) o.addProperty("name", job.targetName);
                JsonArray targets = new JsonArray();
                for (UUID target : job.targets) targets.add(target.toString());
                o.add("targets", targets);
                arr.add(o);
            }
        }
        return arr;
    }

    /** 日志页:内存里的当前一轮(正在跑的 + 最近完成的) */
    public JsonObject view() {
        JsonObject out = new JsonObject();
        JsonArray running = new JsonArray();
        JsonArray log = new JsonArray();
        synchronized (this.lock) {
            for (Job job : this.active.values()) running.add(toJson(job));
            for (Job job : this.history) log.add(toJson(job));
        }
        out.add("running", running);
        out.add("log", log);
        out.addProperty("workers", this.maxWorkers);
        out.addProperty("file", this.logFile == null ? "" : this.logFile.getFileName().toString());
        JsonArray files = new JsonArray();
        for (String name : logFiles()) files.add(name);
        out.add("files", files);
        return out;
    }

    private static JsonObject toJson(Job job) {
        JsonObject o = new JsonObject();
        o.addProperty("seq", job.seq);
        o.addProperty("op", job.op);
        o.addProperty("state", job.state.name().toLowerCase());
        o.addProperty("queued_at", job.queuedAt);
        if (job.startedAt > 0) o.addProperty("started_at", job.startedAt);
        if (job.endedAt > 0) {
            o.addProperty("ended_at", job.endedAt);
            o.addProperty("ms", job.endedAt - job.startedAt);
        }
        if (!job.targetName.isEmpty()) o.addProperty("name", job.targetName);
        if (!job.message.isEmpty()) o.addProperty("message", job.message);
        o.addProperty("phase", job.display());
        JsonArray targets = new JsonArray();
        for (UUID target : job.targets) targets.add(target.toString());
        o.add("targets", targets);
        JsonArray trail = new JsonArray();
        synchronized (job.trail) {
            for (String step : job.trail) trail.add(step);
        }
        o.add("trail", trail);
        if (job.warnings != null && !job.warnings.isEmpty()) o.add("warnings", job.warnings);
        return o;
    }

    // ---------- 持久化(每次重启一个新文件,便于事后查证) ----------

    private synchronized void append(Job job) {
        try {
            if (this.out == null) {
                Path dir = EventLog.logDir();
                Files.createDirectories(dir);
                this.logFile = dir.resolve("jobs-" + TS.format(LocalDateTime.now()) + ".jsonl");
                this.out = new PrintWriter(Files.newBufferedWriter(this.logFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                SablePanel.LOGGER.info("sablepanel: job log -> {}", this.logFile);
            }
            this.out.println(GSON.toJson(toJson(job)));
            this.out.flush();
        } catch (IOException error) {
            SablePanel.LOGGER.warn("sablepanel: failed to write job log", error);
        }
    }

    /** 历史日志文件名,新的在前 */
    public static List<String> logFiles() {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(EventLog.logDir())) {
            stream.map(path -> path.getFileName().toString())
                    .filter(name -> LOG_FILE.matcher(name).matches())
                    .forEach(names::add);
        } catch (IOException ignored) {
            return List.of();
        }
        names.sort(Collections.reverseOrder());
        return names;
    }

    /** 读取指定历史日志文件(文件名必须过白名单,防路径穿越) */
    public static JsonObject readLog(String name) throws IOException {
        if (name == null || !LOG_FILE.matcher(name).matches()) {
            throw new IllegalArgumentException("日志文件名非法");
        }
        JsonArray log = new JsonArray();
        Path file = EventLog.logDir().resolve(name);
        if (Files.exists(file)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && log.size() < HISTORY_MAX * 5; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                try {
                    log.add(GSON.fromJson(line, JsonObject.class));
                } catch (Exception ignored) {
                }
            }
        }
        JsonObject out = new JsonObject();
        out.add("running", new JsonArray());
        out.add("log", log);
        out.addProperty("file", name);
        JsonArray files = new JsonArray();
        for (String other : logFiles()) files.add(other);
        out.add("files", files);
        return out;
    }

    @Override
    public void close() {
        this.workers.shutdownNow();
        synchronized (this) {
            if (this.out != null) {
                this.out.close();
                this.out = null;
            }
        }
    }

    // ---------- 杂项 ----------

    /** 把 op 返回的 JSON 压成一句人看的话 */
    private static String summarize(JsonObject result) {
        if (result == null) return "";
        StringBuilder text = new StringBuilder();
        for (String key : new String[]{"count", "deleted", "restored", "adopted", "removed"}) {
            if (result.has(key) && result.get(key).isJsonPrimitive()) {
                if (text.length() > 0) text.append(' ');
                text.append(key).append('=').append(result.get(key).getAsString());
            }
        }
        // 删除/恢复返回的是 ok(成功数)+total 这一对,与上面那批单值键不同,单独处理
        if (text.length() == 0 && result.has("total") && result.has("ok")
                && result.get("ok").isJsonPrimitive() && result.getAsJsonPrimitive("ok").isNumber()) {
            text.append(result.get("ok").getAsString()).append('/').append(result.get("total").getAsString());
        }
        if (result.has("failed") && result.get("failed").isJsonArray()) {
            int failed = result.getAsJsonArray("failed").size();
            if (failed > 0) text.append(text.length() > 0 ? " " : "").append("failed=").append(failed);
        }
        if (result.has("warnings") && result.get("warnings").isJsonArray()) {
            int warns = result.getAsJsonArray("warnings").size();
            if (warns > 0) text.append(text.length() > 0 ? " " : "").append("warnings=").append(warns);
        }
        if (result.has("error") && result.get("error").isJsonPrimitive()) {
            text.append(text.length() > 0 ? " " : "").append(result.get("error").getAsString());
        }
        return text.toString();
    }

    private static String messageOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }
}
