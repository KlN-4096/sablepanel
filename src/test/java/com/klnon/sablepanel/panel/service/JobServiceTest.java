package com.klnon.sablepanel.panel.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作业服务的三条边界:队列容量、全局操作互斥、终态契约,外加历史日志的尾部读取。
 * <p>
 * 队列从前是无参 {@code LinkedBlockingQueue}(容量 21 亿),worker 有界而队列无界 ——
 * 重复提交长时间的恢复/重扫只会无限排队,过载永远不会向调用方报告。
 */
class JobServiceTest {

    @TempDir
    Path temp;

    /** 阻塞到 latch 放开的作业体;返回值不重要,占住 worker 才是目的 */
    private static java.util.concurrent.Callable<JsonObject> blockOn(CountDownLatch latch) {
        return () -> {
            latch.await(20, TimeUnit.SECONDS);
            return new JsonObject();
        };
    }

    @Test
    void queueRejectsOnceWorkersAndQueueAreFullThenRecovers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (JobService jobs = new JobService(null)) {
            List<UUID> accepted = new ArrayList<>();
            UUID rejectedTarget = null;
            // worker 数随机器核心数变化,所以不假设具体数字:一直提交到被拒为止
            for (int i = 0; i < 500 && rejectedTarget == null; i++) {
                UUID target = UUID.randomUUID();
                try {
                    jobs.submit("阻塞", List.of(target), "", blockOn(release));
                    accepted.add(target);
                } catch (RejectedExecutionException overload) {
                    rejectedTarget = target;
                }
            }
            assertNotNull(rejectedTarget, "队列必须有硬上限,500 次提交内应当被拒绝");
            assertTrue(accepted.size() >= jobs.maxWorkers(), "被拒之前至少要装满 worker");

            // 过载不能留下脏记录:被拒的目标体既不在 busy 里,也不该在 active 列表里
            JsonArray busy = running(jobs);
            UUID missing = rejectedTarget;
            assertFalse(containsTarget(busy, missing), "被拒作业不得残留在 active/busy 中");
            assertEquals(accepted.size(), busy.size(), "active 条数应当等于成功入队的作业数");

            release.countDown();
            // 放开后队列腾空,同一个体可以重新提交(说明 busy 也回滚干净了)
            assertTrue(waitUntil(() -> running(jobs).isEmpty(), 20_000), "作业应当全部结束");
            assertNotNull(jobs.submit("阻塞", List.of(missing), "", () -> new JsonObject()));
        } finally {
            release.countDown();
        }
    }

    @Test
    void globalJobsWithoutTargetsStillDeduplicate() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (JobService jobs = new JobService(null)) {
            jobs.submit("重扫磁盘", List.of(), "", blockOn(release));
            // 空 targets 从前当"无需去重",第二次重扫会静悄悄进队列继续排
            IllegalStateException conflict = assertThrows(IllegalStateException.class,
                    () -> jobs.submit("重扫磁盘", List.of(), "", blockOn(release)));
            assertTrue(conflict.getMessage().contains("重扫磁盘"), conflict.getMessage());
            // 不同的全局操作互不影响
            assertNotNull(jobs.submit("回收站恢复", List.of(), "", blockOn(release)));
            release.countDown();
            assertTrue(waitUntil(() -> running(jobs).isEmpty(), 20_000));
            // 上一轮结束后同名全局操作可以再提交
            assertNotNull(jobs.submit("重扫磁盘", List.of(), "", () -> new JsonObject()));
        } finally {
            release.countDown();
        }
    }

    @Test
    void outcomeSeparatesFullSuccessPartialAndTotalFailure() {
        assertEquals("ok", JobService.outcomeOf(okTotal(3, 3)));
        assertEquals("partial", JobService.outcomeOf(okTotal(1, 3)));
        assertEquals("fail", JobService.outcomeOf(okTotal(0, 3)));
        assertEquals("ok", JobService.outcomeOf(null));

        // warnings 不改变终态:磁盘损坏跳过之类的提示不等于失败
        JsonObject warned = okTotal(3, 3);
        JsonArray warnings = new JsonArray();
        warnings.add("跳过一个损坏条目");
        warned.add("warnings", warnings);
        assertEquals("ok", JobService.outcomeOf(warned));

        // count + failed[] 这一族(批量收养/常驻加载)
        JsonObject counted = new JsonObject();
        counted.addProperty("count", 2);
        JsonArray failed = new JsonArray();
        failed.add(UUID.randomUUID().toString());
        counted.add("failed", failed);
        assertEquals("partial", JobService.outcomeOf(counted));
        counted.addProperty("count", 0);
        assertEquals("fail", JobService.outcomeOf(counted));

        // ok 是布尔值的结果(如重扫)不能被误读成 0 个成功
        JsonObject flag = new JsonObject();
        flag.addProperty("ok", true);
        flag.addProperty("total", 1);
        assertEquals("ok", JobService.outcomeOf(flag));
    }

    /** 单体收养/删除返回的是布尔 ok,从前一路落到默认的 "ok",失败照样是绿色"完成" */
    @Test
    void booleanOkFalseIsAFailureNotASuccess() {
        JsonObject adoptFailed = new JsonObject();
        adoptFailed.addProperty("ok", false);
        adoptFailed.add("chain", new JsonObject());
        assertEquals("fail", JobService.outcomeOf(adoptFailed));
        // 没有任何计数可汇总时也要有一句话,别让日志行只剩一个红标签
        assertEquals("未成功", JobService.summarize(adoptFailed));

        // 单体删除:{ok:false, deleted:0, total:1}
        JsonObject deleteFailed = new JsonObject();
        deleteFailed.addProperty("ok", false);
        deleteFailed.addProperty("deleted", 0);
        deleteFailed.addProperty("total", 1);
        assertEquals("fail", JobService.outcomeOf(deleteFailed));

        JsonObject adoptOk = new JsonObject();
        adoptOk.addProperty("ok", true);
        assertEquals("ok", JobService.outcomeOf(adoptOk));

        // 布尔 ok:true 但带失败项(部分成员没加载出来)算部分成功
        JsonObject mixed = new JsonObject();
        mixed.addProperty("ok", true);
        JsonArray failed = new JsonArray();
        failed.add("x");
        mixed.add("failed", failed);
        assertEquals("partial", JobService.outcomeOf(mixed));
    }

    @Test
    void singleBodyFailureReachesTheFrontendAsFail() throws Exception {
        try (JobService jobs = new JobService(null)) {
            JsonObject adoptFailed = new JsonObject();
            adoptFailed.addProperty("ok", false);
            JobService.Job job = jobs.submit("收养", List.of(UUID.randomUUID()), "飞艇",
                    () -> adoptFailed);
            assertTrue(waitUntil(() -> job.state == JobService.State.DONE, 10_000));
            assertEquals("fail", job.outcome, "单体收养失败不得显示为完成");
        }
    }

    @Test
    void failedJobIsReportedAsFailNotDone() throws Exception {
        try (JobService jobs = new JobService(null)) {
            JobService.Job job = jobs.submit("炸", List.of(UUID.randomUUID()), "", () -> {
                throw new IllegalStateException("boom");
            });
            assertTrue(waitUntil(() -> job.state == JobService.State.FAILED, 10_000));
            assertEquals("fail", job.outcome);
            assertEquals("boom", job.message);
        }
    }

    @Test
    void partialResultIsNotReportedAsDone() throws Exception {
        try (JobService jobs = new JobService(null)) {
            JobService.Job job = jobs.submit("批量删除", List.of(UUID.randomUUID()), "", () -> okTotal(0, 3));
            assertTrue(waitUntil(() -> job.state == JobService.State.DONE, 10_000));
            // state 是 DONE,但 outcome 必须是 fail —— 前端从前只看 state,把 0/3 画成了绿色"完成"
            assertEquals("fail", job.outcome);
            assertEquals("0/3", job.message);
        }
    }

    // ---------- 历史日志尾部读取(LIMIT-02) ----------

    @Test
    void tailReadsOnlyTheEndOfTheFileAndSurvivesBrokenLines() throws Exception {
        Path file = this.temp.resolve("jobs-20260101-000000.jsonl");
        StringBuilder text = new StringBuilder();
        text.append("{\"seq\":1}\n");
        text.append("这是一行损坏的内容\n");
        text.append("x".repeat(6 << 20)).append('\n');   // 6 MiB 单行,超过 4 MiB 的尾部窗口
        text.append("{\"seq\":2}\n");
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);

        List<String> tail = JobService.tailLines(file);
        // 窗口只覆盖文件末尾,所以超长行会被切断,首行(不完整的那半截)被丢弃
        assertTrue(tail.contains("{\"seq\":2}"), "最后一条记录必须读得到");
        assertFalse(tail.contains("{\"seq\":1}"), "窗口之外的旧记录不应出现");
        long bytes = tail.stream().mapToLong(line -> line.length()).sum();
        assertTrue(bytes <= (4L << 20), "读入量必须封顶在尾部窗口内,实际 " + bytes);
    }

    @Test
    void tailHandlesEmptyAndSmallFiles() throws Exception {
        Path empty = this.temp.resolve("empty.jsonl");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        assertEquals(List.of(""), JobService.tailLines(empty));

        Path small = this.temp.resolve("small.jsonl");
        Files.writeString(small, "{\"seq\":1}\n{\"seq\":2}\n", StandardCharsets.UTF_8);
        List<String> lines = JobService.tailLines(small);
        // 文件比窗口小,首行是完整的,不能被当成截断丢掉
        assertTrue(lines.contains("{\"seq\":1}"));
        assertTrue(lines.contains("{\"seq\":2}"));
    }

    @Test
    void tailReadsAFileThatIsStillBeingAppended() throws Exception {
        Path file = this.temp.resolve("live.jsonl");
        Files.writeString(file, "{\"seq\":1}\n", StandardCharsets.UTF_8);
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND)) {
            writer.write("{\"seq\":2}\n");
            writer.flush();
            List<String> lines = JobService.tailLines(file);
            assertTrue(lines.contains("{\"seq\":2}"), "正在追加的文件也要能读到已 flush 的内容");
        }
    }

    // ---------- 工具 ----------

    /** /api/jobs 里的活动作业清单;前端的忙碌徽章读的就是这一段 */
    private static JsonArray running(JobService jobs) {
        return jobs.view().getAsJsonArray("running");
    }

    private static JsonObject okTotal(int ok, int total) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", ok);
        result.addProperty("total", total);
        return result;
    }

    private static boolean containsTarget(JsonArray busy, UUID uuid) {
        for (var element : busy) {
            for (var target : element.getAsJsonObject().getAsJsonArray("targets")) {
                if (target.getAsString().equals(uuid.toString())) return true;
            }
        }
        return false;
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
