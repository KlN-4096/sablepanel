package com.klnon.sablepanel.panel.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冻结意图的语义。真正的拦截发生在 vanilla 方块实体 tick 的 mixin 里(需要跑起来的服务端),
 * 这里守住的是纯逻辑那半:意图集合本身,以及"没有任何冻结体时必须直接短路" ——
 * 那条快路径是每个方块实体每 tick 都要过的,退化成 plot 反查就是全服性能问题。
 */
class FreezeServiceTest {
    private final UUID body = UUID.randomUUID();

    @AfterEach
    void clear() {
        FreezeService.reset();
    }

    @Test
    void freezingIsPerBodyAndReversible() {
        UUID other = UUID.randomUUID();
        FreezeService.applyOnMain(List.of(this.body), true);

        assertTrue(FreezeService.isFrozen(this.body));
        assertFalse(FreezeService.isFrozen(other), "只冻点名的体");

        FreezeService.applyOnMain(List.of(this.body), false);
        assertFalse(FreezeService.isFrozen(this.body));
    }

    /**
     * 冻结集合为空时 {@code shouldSkipTick} 必须在碰 sable 之前就返回 —— 这里用 null level
     * 当探针:一旦哪天改成先查 plot 再看集合,这条会 NPE 或走进 sable 而不是安静地 false。
     */
    @Test
    void anEmptyFrozenSetShortCircuitsBeforeTouchingSable() {
        assertFalse(FreezeService.shouldSkipTick(null, null));
    }

    /** 冻结集合非空但查询失败(维度没有容器/坐标不在任何 plot 里)时放行,不能让整个世界停摆。 */
    @Test
    void aLookupFailureLetsTheBlockEntityTick() {
        FreezeService.applyOnMain(List.of(this.body), true);

        assertFalse(FreezeService.shouldSkipTick(null, null));
    }
}
