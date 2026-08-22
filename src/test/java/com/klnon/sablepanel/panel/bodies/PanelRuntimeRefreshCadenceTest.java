package com.klnon.sablepanel.panel.bodies;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行时刷新的节奏记账。曾经的坑:计数器只在 refreshRuntime 成功后归零,一旦守护循环持续
 * 抛出(一张摘不掉的残留票就够),刷新会静默退化成每 tick 重试全量刷新直到重启——
 * 全票表遍历、全体扫描、snatch 重试和 WARN 刷屏全部变成 20 次/秒。
 */
class PanelRuntimeRefreshCadenceTest {

    /** 修复对象本身:持续失败必须保持 100t 节奏。修复前 1000 tick 里是 901 次(每 tick 重试)。 */
    @Test
    void aPersistentlyFailingRefreshKeepsTheNormalCadence() {
        PanelRuntime.RefreshCadence cadence = new PanelRuntime.RefreshCadence();
        int attempts = 0;
        for (int tick = 0; tick < 1000; tick++) {
            if (cadence.due(false, true)) {
                cadence.begin();
                attempts++; // 模拟 refreshRuntime 抛出:不调 succeeded()
            }
        }
        assertEquals(10, attempts, "失败也要守住 100t 间隔,不能退化成每 tick 重试");
    }

    /** 脏标记 + 持续失败:退到 20t 事件地板,同样不是每 tick。 */
    @Test
    void aDirtyFlagWithFailuresRetriesAtTheEventFloor() {
        PanelRuntime.RefreshCadence cadence = new PanelRuntime.RefreshCadence();
        int attempts = 0;
        for (int tick = 0; tick < 200; tick++) {
            if (cadence.due(true, true)) {
                cadence.begin();
                attempts++;
            }
        }
        assertEquals(10, attempts, "脏标记地板是 20t");
    }

    /** 正常节奏不因重构而变:活跃 100t、空闲 1200t,elapsed 如实上报。 */
    @Test
    void successfulRefreshesKeepTheirIntervals() {
        PanelRuntime.RefreshCadence active = new PanelRuntime.RefreshCadence();
        int attempts = 0;
        for (int tick = 0; tick < 1000; tick++) {
            if (active.due(false, true)) {
                assertEquals(100, active.begin(), "elapsed 必须是真实间隔");
                active.succeeded();
                attempts++;
            }
        }
        assertEquals(10, attempts);

        PanelRuntime.RefreshCadence idle = new PanelRuntime.RefreshCadence();
        int idleAttempts = 0;
        for (int tick = 0; tick < 2400; tick++) {
            if (idle.due(false, false)) {
                idle.begin();
                idle.succeeded();
                idleAttempts++;
            }
        }
        assertEquals(2, idleAttempts, "空闲间隔 1200t");
    }

    /** elapsed = 距上次成功:失败轮 BodyCostTracker 没 drain、纳秒还在囤,除数必须跟着累计。 */
    @Test
    void elapsedAccumulatesAcrossFailedAttempts() {
        PanelRuntime.RefreshCadence cadence = new PanelRuntime.RefreshCadence();
        for (int tick = 0; tick < 100; tick++) cadence.due(false, true);
        assertEquals(100, cadence.begin(), "第一次到点");
        // 不调 succeeded():模拟刷新抛出
        for (int tick = 0; tick < 100; tick++) cadence.due(false, true);
        assertEquals(200, cadence.begin(), "失败后的下一次尝试,除数是距上次成功的 200");
        cadence.succeeded();
        for (int tick = 0; tick < 100; tick++) cadence.due(false, true);
        assertEquals(100, cadence.begin(), "成功清零后回到单间隔");
    }

    /** 失败日志限频:第一次必打,之后每 64 次一条;成功后重新从"第一次"算。 */
    @Test
    void failureLoggingIsRateLimitedAndResetsOnSuccess() {
        PanelRuntime.RefreshCadence cadence = new PanelRuntime.RefreshCadence();
        assertTrue(cadence.failedShouldLog(), "第一个失败必须可见——静默吞 Throwable 就是原来的病");
        for (int i = 0; i < 63; i++) {
            assertFalse(cadence.failedShouldLog(), "持续故障不许刷屏");
        }
        assertTrue(cadence.failedShouldLog(), "第 65 次再报一条,证明还没好");

        cadence.succeeded();
        assertTrue(cadence.failedShouldLog(), "成功清零后,下一个失败又是第一条");
    }

    /** 停服 reset 后节奏与失败计数都从头开始。 */
    @Test
    void resetClearsBothTheTickCounterAndFailureCount() {
        PanelRuntime.RefreshCadence cadence = new PanelRuntime.RefreshCadence();
        for (int tick = 0; tick < 99; tick++) {
            assertFalse(cadence.due(false, true));
        }
        cadence.failedShouldLog();
        cadence.reset();
        assertFalse(cadence.due(false, true), "reset 后计数从 1 开始,不该立即到点");
        assertTrue(cadence.failedShouldLog(), "失败计数同样清零");
    }
}
