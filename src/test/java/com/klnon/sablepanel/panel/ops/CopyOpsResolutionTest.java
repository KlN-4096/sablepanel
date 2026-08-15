package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.panel.copies.CopyVersionScanner;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CopyOpsResolutionTest {
    private final UUID target = UUID.randomUUID();
    private final CopyVersionScanner.Version first = version("first");
    private final CopyVersionScanner.Version second = version("second");

    @Test
    void unknownOrMixedCurrentStateCannotCreateAResolutionPlan() {
        assertThrows(IllegalStateException.class, () -> CopyOps.requireCopyResolution(
                scan(null, CopyVersionScanner.CurrentState.UNKNOWN), "first", false));
        assertThrows(IllegalStateException.class, () -> CopyOps.requireCopyResolution(
                scan(null, CopyVersionScanner.CurrentState.MIXED), "first", false));
    }

    @Test
    void authoritativeRescanRejectsAStaticVersionThatNoLongerExists() {
        assertThrows(IllegalStateException.class, () -> CopyOps.requireCopyResolution(
                scan("first", CopyVersionScanner.CurrentState.KNOWN), "vanished", false));
    }

    /**
     * flush 会把活着的体重新写盘,sable 可以把它落到完全不同的 holding chunk / 区域文件 ——
     * 实测 3167d6b2 的活动副本一次 flush 之后槽位从 {@code the_end/-1.0.0:2} 变成 {@code -4.21.0:0},
     * 内容哈希也跟着变(Create 的轴承角度、应力网络每 tick 都在动),两个判据同时失效。
     * 这是我们自己 flush 造成的,不是用户选错了,所以活着那份靠运行证据认领。
     * <p>
     * 这条以前断的是相反的行为(id 或槽位一对不上就拒绝),对机器还在转的船等于 100% 失败。
     */
    @Test
    void theLiveSelectionIsReclaimedAfterTheFlushMovesBothItsSlotAndItsId() {
        CopyVersionScanner.Scan authoritative = new CopyVersionScanner.Scan(target, Set.of(target),
                List.of(version("moved", 1, key(7)), version("stale", 0, key(3))), List.of(),
                "moved", CopyVersionScanner.CurrentState.KNOWN, 1);

        CopyOps.CopyResolutionPlan plan = CopyOps.requireCopyResolution(authoritative, "vanished", true);

        assertEquals("moved", plan.selected().id());
    }

    /** 没人写的静态副本 flush 前后 id 不变(实测同一轮里另两份纹丝不动),照旧按 id 找回。 */
    @Test
    void aStaticSelectionSurvivesTheFlushByItsId() {
        CopyOps.CopyResolutionPlan plan = CopyOps.requireCopyResolution(
                scan("first", CopyVersionScanner.CurrentState.KNOWN), "second", false);

        assertEquals("second", plan.selected().id());
        assertEquals("first", plan.rollback().id());
    }

    private static DiskScanner.EntryKey key(int slot) {
        return new DiskScanner.EntryKey("minecraft:overworld", 0, 0, slot, slot);
    }

    private CopyVersionScanner.Scan scan(String current, CopyVersionScanner.CurrentState state) {
        return new CopyVersionScanner.Scan(target, Set.of(target), List.of(first, second), List.of(),
                current, state, current == null ? 0 : 1);
    }

    private CopyVersionScanner.Version version(String id) {
        return version(id, 0, key(id.equals("first") ? 0 : 1));
    }

    private CopyVersionScanner.Version version(String id, int activeMembers, DiskScanner.EntryKey slot) {
        CopyVersionScanner.Copy copy = new CopyVersionScanner.Copy(this.target, slot, new CompoundTag(), 1,
                List.of(new DiskScanner.LiveLocation(slot, 0, 0)));
        return new CopyVersionScanner.Version(id, true, activeMembers, List.of(copy),
                List.of(), List.of(), Set.of());
    }
}
