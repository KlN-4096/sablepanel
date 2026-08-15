package com.klnon.sablepanel.panel.ops;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.audit.EventLog;
import com.klnon.sablepanel.SablePanel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
    private static final Gson GSON = new Gson();
    private static final int HISTORY_MAX = 200;
    /** 终态作业在内存里多留一会儿,够前端轮询看到并弹一次 toast */
    private static final int TRAIL_MAX = 64;
    /** 队列硬上限:worker 数有界但队列无界时,重复提交只会无限排队而不报容量不足 */
    private static final int QUEUE_CAPACITY = 64;
    /** 每次启动一个新日志文件,只保留最近这些个:长期运行 + 反复重启会无限累积 */
    private static final int LOG_KEEP_FILES = 20;
    /** 历史日志只从文件尾部读这么多字节,堆峰值与文件大小无关 */
    private static final long TAIL_BYTES = 4L << 20;
    /** 出口文本上限,见 {@link #clip} */
    private static final int MAX_TEXT_CHARS = 200;
    /** 单个作业最多回报这么多条警告,其余折成一句"另有 N 条" */
    private static final int MAX_WARNINGS = 50;

    /** 翻存档全局串行。磁盘竞争是 JVM 级的,每个服务端一个面板,静态即可 */
    private static final Semaphore LOCATE = new Semaphore(1);
    /** 让深层代码(prepareChain/locateMember)不必层层传 Job 就能回报进度 */
    private static final ThreadLocal<Job> CURRENT = new ThreadLocal<>();

    public enum State { QUEUED, RUNNING, DONE, FAILED }

    public static final class Job {
        public final long seq;
        public final String op;
        public final List<UUID> targets;
        /** 去重用的资源键:有目标体时就是 targets,全局操作时是按 op 派生的合成键 */
        final List<UUID> lockKeys;
        public final String targetName;
        public final long queuedAt = System.currentTimeMillis();
        volatile State state = State.QUEUED;
        volatile String phase = "";
        volatile String detail = "";
        volatile long startedAt;
        volatile long endedAt;
        volatile String message = "";
        /** 终态契约:ok / partial / fail,见 {@link #outcomeOf} */
        volatile String outcome = "";
        volatile JsonArray warnings;
        final List<String> trail = Collections.synchronizedList(new ArrayList<>());

        Job(long seq, String op, List<UUID> targets, String targetName, List<UUID> lockKeys) {
            this.seq = seq;
            this.op = op;
            this.targets = List.copyOf(targets);
            this.lockKeys = List.copyOf(lockKeys);
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
    private final EventLog.RollingWriter logWriter = new EventLog.RollingWriter("jobs-", LOG_KEEP_FILES);
    /** 关停后别再落日志:shutdownNow 只中断不等待,已在 append 里的 worker 会重开一个没人关的文件 */
    private volatile boolean logClosed;

    /**
     * @param afterJob 作业结束后的回调(用来把运行时快照标记为待刷新,
     *                 这样前端下一次拉取拿到的就是真值,不必再靠写死的 setTimeout 等)
     */
    public JobService(Runnable afterJob) {
        this.afterJob = afterJob;
        this.maxWorkers = Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
        // core=max + allowCoreThreadTimeOut:任务来了才建线程,建到上限为止,空闲 30 秒全部回收
        this.workers = new ThreadPoolExecutor(this.maxWorkers, this.maxWorkers, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
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
     * 入队一个作业。
     *
     * @param targets 受影响的体;空表示全局操作(重扫、回收站恢复),按 op 名派生一个合成键去重
     * @throws IllegalStateException        资源已被占用(调用方转成 409)
     * @throws RejectedExecutionException   worker 和队列都满了(调用方转成 503)
     */
    public Job submit(String op, List<UUID> targets, String targetName, Callable<JsonObject> work) {
        // 空 targets 从前当"无需去重",于是重复提交的重扫/恢复会一直往无界队列里堆
        List<UUID> keys = targets.isEmpty() ? List.of(globalKey(op)) : List.copyOf(targets);
        Job job;
        synchronized (this.lock) {
            for (UUID key : keys) {
                Long running = this.busy.get(key);
                if (running != null) {
                    // busy 与 active 在同一把锁下同增同删,busy 命中则 active 必有对应记录
                    String name = this.active.get(running).op;
                    throw new IllegalStateException(targets.isEmpty()
                            ? "该操作正在执行中(" + name + "),请等它结束"
                            : "该物理体正在处理中(" + name + "),请等它结束");
                }
            }
            job = new Job(this.nextSeq++, op, targets, targetName, keys);
            for (UUID key : keys) this.busy.put(key, job.seq);
            this.active.put(job.seq, job);
        }
        try {
            this.workers.execute(() -> run(job, work));
        } catch (RejectedExecutionException overload) {
            // 过载必须当场回滚,否则 active/busy 会留下一条永远不会结束的脏记录
            synchronized (this.lock) {
                for (UUID key : keys) this.busy.remove(key, job.seq);
                this.active.remove(job.seq);
            }
            throw overload;
        }
        return job;
    }

    /** 无目标体的全局操作按 op 名派生互斥键,复用同一套 busy 去重,又不污染对外的 targets */
    private static UUID globalKey(String op) {
        return UUID.nameUUIDFromBytes(("sablepanel:global:" + op).getBytes(StandardCharsets.UTF_8));
    }

    private void run(Job job, Callable<JsonObject> work) {
        CURRENT.set(job);
        job.state = State.RUNNING;
        job.startedAt = System.currentTimeMillis();
        try {
            JsonObject result = work.call();
            // state 是别的线程(/api/jobs 轮询)用来判"结束了没"的那个标志,
            // 所以派生字段必须先写完再翻 state,否则会被看到"已完成但没有终态"
            job.outcome = outcomeOf(result);
            job.message = summarize(result);
            if (result != null && result.has("warnings") && result.get("warnings").isJsonArray()) {
                job.warnings = result.getAsJsonArray("warnings");
            }
            job.state = State.DONE;
        } catch (Throwable error) {
            job.outcome = "fail";
            job.message = messageOf(error);
            job.state = State.FAILED;
            SablePanel.LOGGER.warn("sablepanel: job #{} {} failed", job.seq, job.op, error);
        } finally {
            CURRENT.remove();
            job.endedAt = System.currentTimeMillis();
            job.detail = "";
            synchronized (this.lock) {
                for (UUID key : job.lockKeys) this.busy.remove(key, job.seq);
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
     * {@code poll=true} 是面板每 2 秒那一次作业状态轮询要的份额。
     * <p>
     * {@code running} 照旧给全:行徽章要按 targets 展开。历史去掉 targets 和 trail ——
     * 轮询拿它只为给"本页提交过的作业"取一次终态弹 toast,那只用到 seq/op/name/message/outcome。
     * 满载的 200 条历史带上 targets(每条最多 500 个 uuid)和 trail 约 3.8~8.2 MiB,
     * 去掉之后是几十 KB。日志文件清单也不列:{@link #logFiles()} 是一次 {@code Files.list()},
     * 每 2 秒扫一遍磁盘目录,只有日志页需要它。
     */
    public JsonObject view(boolean poll) {
        // 锁内只复制引用。Job 的字段都是 volatile,warnings 在收尾时一次性赋值,trail 是
        // synchronizedList 且 toJson 自己再同步一次 —— 都不需要 this.lock。在锁里建 JSON 的话,
        // 日志页那一份(200 条 × 每条最多 500 个 uuid 和整份 trail)会把提交和收尾一起挡住
        List<Job> activeJobs;
        List<Job> recentJobs;
        synchronized (this.lock) {
            activeJobs = new ArrayList<>(this.active.values());
            recentJobs = new ArrayList<>(this.history);
        }
        JsonObject out = new JsonObject();
        JsonArray running = new JsonArray();
        JsonArray log = new JsonArray();
        for (Job job : activeJobs) running.add(toJson(job));
        for (Job job : recentJobs) log.add(toJson(job, poll));
        out.add("running", running);
        out.add("log", log);
        out.addProperty("workers", this.maxWorkers);
        if (poll) return out;
        Path logFile = this.logWriter.file();
        out.addProperty("file", logFile == null ? "" : logFile.getFileName().toString());
        JsonArray files = new JsonArray();
        for (String name : logFiles()) files.add(name);
        out.add("files", files);
        return out;
    }

    private static JsonObject toJson(Job job) {
        return toJson(job, false);
    }

    /**
     * {@code compact} 跳过 targets 和 trail,给每 2 秒一次的轮询用。
     * <p>
     * 是压根不构造,不是构造完再 {@code remove()} —— 后者字节数确实小了,但每 2 秒仍然要
     * 分配最多 500 个 uuid 字符串和整份 trail 数组,服务端的开销一点没省。
     */
    private static JsonObject toJson(Job job, boolean compact) {
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
        if (!job.targetName.isEmpty()) o.addProperty("name", clip(job.targetName));
        if (!job.message.isEmpty()) o.addProperty("message", clip(job.message));
        if (!job.outcome.isEmpty()) o.addProperty("outcome", job.outcome);
        o.addProperty("phase", clip(job.display()));
        if (!compact) {
            JsonArray targets = new JsonArray();
            for (UUID target : job.targets) targets.add(target.toString());
            o.add("targets", targets);
            JsonArray trail = new JsonArray();
            synchronized (job.trail) {
                for (String step : job.trail) trail.add(clip(step));
            }
            o.add("trail", trail);
        }
        if (job.warnings != null && !job.warnings.isEmpty()) o.add("warnings", clipAll(job.warnings));
        return o;
    }

    /**
     * 面板要显示的文本一律在出口截断。
     * <p>
     * {@code op} 和 {@code phase} 眼下都是内部常量,但 {@code targetName} 直接来自 NBT 的显示名、
     * {@code message} 和 {@code warnings} 会带上体名和异常文本,都没有长度上限。资源约束放在资源
     * 所有者这里,而不是指望每个读取方各自记账 —— 从前 busy 是在 {@code /api/bodies} 的字节预算
     * 跑完之后才追加的,预算里根本看不到它:满队列配 65,000 字符的名称,单是 busy 就有 28.9 MiB
     * (Gson 默认把 {@code <} 转义成 6 字节的 {@code <})。
     */
    private static String clip(String text) {
        return text.length() <= MAX_TEXT_CHARS ? text : text.substring(0, MAX_TEXT_CHARS) + "…";
    }

    /** warnings 是每个问题一条,组数多时条数本身也是无界的 */
    private static JsonArray clipAll(JsonArray values) {
        if (values.size() <= MAX_WARNINGS && values.asList().stream()
                .allMatch(v -> !v.isJsonPrimitive() || v.getAsString().length() <= MAX_TEXT_CHARS)) {
            return values;
        }
        JsonArray out = new JsonArray();
        for (int i = 0; i < Math.min(values.size(), MAX_WARNINGS); i++) {
            var value = values.get(i);
            out.add(value.isJsonPrimitive() ? new com.google.gson.JsonPrimitive(clip(value.getAsString())) : value);
        }
        if (values.size() > MAX_WARNINGS) out.add("……另有 " + (values.size() - MAX_WARNINGS) + " 条");
        return out;
    }

    // ---------- 持久化(每次重启一个新文件,便于事后查证) ----------

    private synchronized void append(Job job) {
        if (this.logClosed) return;
        try {
            this.logWriter.writeLine(GSON.toJson(toJson(job)));
        } catch (Exception error) {
            // 日志目录出任何问题都不该把作业的收尾流程炸掉(afterJob 还没跑);
            // 写手自己吞 IO 错误,这里兜的是序列化侧
            SablePanel.LOGGER.warn("sablepanel: failed to write job log", error);
        }
    }

    /** 历史日志文件名,新的在前。日志目录是可选的:它出问题不该让整个作业页打不开 */
    private static List<String> logFiles() {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(EventLog.logDir())) {
            stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("jobs-") && name.endsWith(".jsonl"))
                    .forEach(names::add);
        } catch (Exception ignored) {
            return List.of();
        }
        names.sort(Collections.reverseOrder());
        return names;
    }

    /** 读取指定历史日志文件;文件名归一化到日志目录内,合法值以 logFiles() 列表为准 */
    public static JsonObject readLog(String name) throws IOException {
        Path leaf = name == null || name.isBlank() ? null : Path.of(name).getFileName();
        if (leaf == null) {
            throw new IllegalArgumentException("日志文件名非法");
        }
        JsonArray log = new JsonArray();
        Path file = EventLog.logDir().resolve(leaf.toString());
        if (Files.isRegularFile(file)) {
            List<String> lines = tailLines(file);
            for (int i = lines.size() - 1; i >= 0 && log.size() < HISTORY_MAX * 5; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                try {
                    JsonObject entry = GSON.fromJson(line, JsonObject.class);
                    if (entry != null) log.add(entry);
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

    /**
     * 只读文件末尾 {@link #TAIL_BYTES} 字节并按行切分,堆峰值与文件大小无关。
     * <p>
     * 从前是 {@code Files.readAllLines} 整个读进来再截取最后 1000 条 —— 查一个跑了几个月的
     * 历史文件,堆峰值就等于整个文件。窗口起点会落在某行中间,所以首行必然不完整,直接丢掉;
     * 同理它也天然挡住了"单行异常长"把堆撑爆。
     */
    static List<String> tailLines(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long from = Math.max(0, size - TAIL_BYTES);
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - from));
            // read(ByteBuffer,long) 推进 buffer 但不动 channel 的 position,文件偏移要自己算
            while (buffer.hasRemaining() && channel.read(buffer, from + buffer.position()) > 0) {
                // 读满或读到文件尾为止
            }
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
            if (from > 0 && !lines.isEmpty()) lines.remove(0);
            return lines;
        }
    }

    @Override
    public void close() {
        this.workers.shutdownNow();
        this.logClosed = true;
        this.logWriter.close();
    }

    // ---------- 杂项 ----------

    /**
     * 终态契约:{@code ok} 全部成功 / {@code partial} 部分成功 / {@code fail} 全部失败。
     * <p>
     * 从前只要 {@code Callable} 没抛异常就是 DONE,于是 {@code 0/3}(三个全删失败)在界面上
     * 是绿色的"完成",连"仅失败"筛选都找不到它。前端不该去解析人看的 message 文本。
     */
    static String outcomeOf(JsonObject result) {
        if (result == null) return "ok";
        if (result.has("error") && result.get("error").isJsonPrimitive()) return "fail";
        int failed = result.has("failed") && result.get("failed").isJsonArray()
                ? result.getAsJsonArray("failed").size() : 0;
        if (result.has("ok") && result.get("ok").isJsonPrimitive()) {
            var ok = result.getAsJsonPrimitive("ok");
            // 单体操作(收养/删除/去重)返回布尔 ok:false —— 那就是彻底失败。
            // 从前只认数字型 ok+total,布尔 false 一路落到默认的 "ok",单体收养失败照样是绿色"完成"
            if (ok.isBoolean()) return !ok.getAsBoolean() ? "fail" : failed > 0 ? "partial" : "ok";
            // 批量删除/恢复走 ok(成功数)+total 这一对
            if (ok.isNumber() && result.has("total")) {
                int done = ok.getAsInt();
                int total = result.get("total").getAsInt();
                if (total > 0 && done == 0) return "fail";
                return done < total || failed > 0 ? "partial" : "ok";
            }
        }
        if (failed > 0) {
            int done = result.has("count") && result.get("count").isJsonPrimitive()
                    ? result.get("count").getAsInt() : 0;
            return done > 0 ? "partial" : "fail";
        }
        return "ok";
    }

    /** 把 op 返回的 JSON 压成一句人看的话 */
    static String summarize(JsonObject result) {
        if (result == null) return "";
        StringBuilder text = new StringBuilder();
        for (String key : new String[]{"count", "deleted", "restored", "removed"}) {
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
        if (result.has("warning") && result.get("warning").isJsonPrimitive()) {
            text.append(text.length() > 0 ? " " : "").append(result.get("warning").getAsString());
        }
        if (result.has("backup") && result.get("backup").isJsonPrimitive()) {
            text.append(text.length() > 0 ? " " : "").append("backup=")
                    .append(result.get("backup").getAsString());
        }
        // 布尔 ok:false 的单体操作(收养)没有任何可汇总的计数,别让日志行只有一个红标签没有话
        if (text.length() == 0 && "fail".equals(outcomeOf(result))) text.append("未成功");
        return text.toString();
    }

}
