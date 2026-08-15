package com.klnon.sablepanel.panel.bodies;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 磁盘扫描的合并门闩。周期任务从前直接调原始 scanOnce、只有手动重扫过门闩,于是周期扫描
 * 进行时点一次重扫就会有第二个线程把同一批磁盘数据再全量解压一遍。约定:同一时刻最多一次
 * 扫描在跑,扫描期间的额外请求最多合并成一次补跑。
 */
class PanelRuntimeScanGateTest {

    @Test
    void concurrentPeriodicAndManualRequestsCollapseToOneRerun() throws Exception {
        AtomicInteger state = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Runnable work = () -> {
                maxConcurrent.accumulateAndGet(running.incrementAndGet(), Math::max);
                executions.incrementAndGet();
                started.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                }
            };
            Runnable gate = PanelRuntime.mergedRunner(state, executor, () -> true, work);

            gate.run();                                   // 周期任务
            assertTrue(started.await(5, TimeUnit.SECONDS), "第一次扫描应当开始");
            for (int i = 0; i < 20; i++) gate.run();       // 扫描进行中的手动重扫
            assertEquals(1, executions.get(), "补跑不能与当前全量扫描并发");

            release.countDown();
            assertTrue(waitUntil(() -> executions.get() == 2 && running.get() == 0, 10_000));
            assertEquals(2, executions.get(), "扫描期间的请求应当只合并成一次补跑");
            assertEquals(1, maxConcurrent.get(), "任何时刻最多一次完整扫描");

            // 上一轮结束后门闩必须复位,不能把后续扫描永久挡住
            CountDownLatch second = new CountDownLatch(1);
            Runnable again = PanelRuntime.mergedRunner(state, executor, () -> true, second::countDown);
            again.run();
            assertTrue(second.await(5, TimeUnit.SECONDS), "门闩必须复位");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void deadLifecycleAndRejectedExecutionLeaveTheGateReusable() throws Exception {
        AtomicInteger state = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 生命周期已结束:直接返回,且不得把扫描状态置脏
            PanelRuntime.mergedRunner(state, executor, () -> false, executions::incrementAndGet).run();
            assertEquals(0, executions.get());
            assertEquals(0, state.get(), "被跳过的请求不能留下占位");

            executor.shutdownNow();
            // 执行器已关闭:execute 抛 RejectedExecutionException,门闩要回滚
            PanelRuntime.mergedRunner(state, executor, () -> true, executions::incrementAndGet).run();
            assertEquals(0, executions.get());
            assertEquals(0, state.get(), "拒绝执行后状态必须回滚,否则以后再也扫不了");
        } finally {
            executor.shutdownNow();
        }
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
