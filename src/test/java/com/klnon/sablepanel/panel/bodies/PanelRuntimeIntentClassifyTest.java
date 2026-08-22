package com.klnon.sablepanel.panel.bodies;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 30 秒常驻意图核对合并成单跳后的分类纯函数。对抗审查抓出的回归:批量化把逐 uuid 的
 * try/catch 丢了,一个坏 uuid(isLoaded 抛出)会让整轮 return——30 秒一轮每轮撞同一个,
 * 常驻恢复永久停摆。分类必须保持"单个失败只跳过自己"。
 */
class PanelRuntimeIntentClassifyTest {

    private final UUID alive = UUID.randomUUID();
    private final UUID dropped = UUID.randomUUID();
    private final UUID gone = UUID.randomUUID();

    @Test
    void aliveDroppedAndGoneClassifyLikeTheOldPerUuidLoop() {
        List<UUID> detached = new ArrayList<>();
        List<UUID> stale = PanelRuntime.classifyStaleIntents(
                Set.of(this.alive, this.dropped), List.of(this.alive, this.dropped, this.gone),
                uuid -> uuid.equals(this.alive), detached::add);

        assertEquals(List.of(this.dropped, this.gone), stale, "活着的不进恢复;掉线与丢票的都进");
        assertEquals(List.of(this.dropped), detached, "只有'票在体不在'的剥原生票");
    }

    /** F2 复现:分类查询抛出只跳过那一个 uuid,其余照常分类——不许整轮报废。 */
    @Test
    void aPoisonUuidOnlySkipsItselfNotTheWholeRound() {
        UUID poison = UUID.randomUUID();
        List<UUID> stale = PanelRuntime.classifyStaleIntents(
                Set.of(this.alive, poison), List.of(poison, this.alive, this.gone),
                uuid -> {
                    if (uuid.equals(poison)) throw new IllegalStateException("坏 uuid");
                    return uuid.equals(this.alive);
                },
                uuid -> { });

        assertEquals(List.of(this.gone), stale, "毒 uuid 本轮跳过,活着的照常排除,丢票的照常进恢复");
    }

    /** 剥离失败的那一个本轮不进恢复(与逐个时代同语义),不连坐别人。 */
    @Test
    void aFailingDetachSkipsOnlyThatIntent() {
        UUID stuck = UUID.randomUUID();
        List<UUID> stale = PanelRuntime.classifyStaleIntents(
                Set.of(stuck, this.dropped), List.of(stuck, this.dropped, this.gone),
                uuid -> false,
                uuid -> {
                    if (uuid.equals(stuck)) throw new IllegalStateException("剥不掉");
                });

        assertEquals(List.of(this.dropped, this.gone), stale);
    }
}
