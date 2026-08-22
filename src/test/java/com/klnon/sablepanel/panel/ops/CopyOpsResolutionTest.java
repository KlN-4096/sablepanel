package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.panel.copies.CopyVersionScanner;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyOpsResolutionTest {
    private final UUID target = UUID.randomUUID();
    private final CopyVersionScanner.Version first = version("first");
    private final CopyVersionScanner.Version second = version("second");

    @Test
    void coldSelectionUsesTheChosenVersionAsItsRollback() {
        CopyOps.CopyResolutionPlan plan = CopyOps.requireCopyResolution(
                scan(null, CopyVersionScanner.CurrentState.UNKNOWN), "first",
                CopyOps.CopySelectionBasis.COLD);

        assertEquals("first", plan.selected().id());
        assertEquals("first", plan.rollback().id());
    }

    @Test
    void ambiguousRuntimeCurrentStateCannotCreateAResolutionPlan() {
        assertThrows(IllegalStateException.class, () -> CopyOps.requireCopyResolution(
                scan(null, CopyVersionScanner.CurrentState.MIXED), "first",
                CopyOps.CopySelectionBasis.LIVE));
    }

    @Test
    void holdingOnlyEvidenceUsesTheKnownCurrentVersionAsRollback() {
        CopyOps.CopyResolutionPlan plan = CopyOps.requireCopyResolution(
                scan("first", CopyVersionScanner.CurrentState.KNOWN), "second",
                CopyOps.CopySelectionBasis.POINTED);

        assertEquals("second", plan.selected().id());
        assertEquals("first", plan.rollback().id());
    }

    @Test
    void coldOrHoldingOnlyResolutionRejectsChangedOrAmbiguousEvidence() {
        CopyVersionScanner.Scan evidenceAppeared = new CopyVersionScanner.Scan(
                this.target, Set.of(this.target), List.of(version("first", 1, key(0))), List.of(),
                "first", CopyVersionScanner.CurrentState.KNOWN, 1);
        CopyVersionScanner.Scan pointedUnknown = new CopyVersionScanner.Scan(
                this.target, Set.of(this.target), List.of(version("first", 1, key(0))), List.of(),
                null, CopyVersionScanner.CurrentState.UNKNOWN, 1);

        assertThrows(IllegalStateException.class, () -> CopyOps.requireCopyResolution(
                evidenceAppeared, "first", CopyOps.CopySelectionBasis.COLD));
        assertThrows(IllegalStateException.class, () -> CopyOps.requireCopyResolution(
                pointedUnknown, "first", CopyOps.CopySelectionBasis.POINTED));
    }

    @Test
    void authoritativeRescanRejectsAStaticVersionThatNoLongerExists() {
        assertThrows(IllegalStateException.class, () -> CopyOps.requireCopyResolution(
                scan("first", CopyVersionScanner.CurrentState.KNOWN), "vanished",
                CopyOps.CopySelectionBasis.STATIC));
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

        CopyOps.CopyResolutionPlan plan = CopyOps.requireCopyResolution(
                authoritative, "vanished", CopyOps.CopySelectionBasis.LIVE);

        assertEquals("moved", plan.selected().id());
    }

    /** 没人写的静态副本 flush 前后 id 不变(实测同一轮里另两份纹丝不动),照旧按 id 找回。 */
    @Test
    void aStaticSelectionSurvivesTheFlushByItsId() {
        CopyOps.CopyResolutionPlan plan = CopyOps.requireCopyResolution(
                scan("first", CopyVersionScanner.CurrentState.KNOWN), "second",
                CopyOps.CopySelectionBasis.STATIC);

        assertEquals("second", plan.selected().id());
        assertEquals("first", plan.rollback().id());
    }

    @Test
    void coldDeleteLoadsTheExplicitlySelectedCopyInsteadOfTheLexicalDefault() {
        DiskScanner.EntryKey oldKey = key(0);
        DiskScanner.EntryKey selectedKey = key(1);
        DeleteTx.DeleteCopy oldCopy = new DeleteTx.DeleteCopy(oldKey, new CompoundTag(), 1, List.of());
        DeleteTx.DeleteCopy selectedCopy = new DeleteTx.DeleteCopy(
                selectedKey, new CompoundTag(), 1, List.of());
        DeleteTx.DeleteComponent component = new DeleteTx.DeleteComponent();
        component.addTarget(this.target, List.of(oldCopy, selectedCopy));
        component.canonical.put(this.target, oldCopy);
        CopyVersionScanner.Version selected = new CopyVersionScanner.Version("selected", true, 0,
                List.of(new CopyVersionScanner.Copy(this.target, selectedKey,
                        selectedCopy.tag(), 1, List.of())), List.of(), List.of(), Set.of());

        CopyOps.preferSelectedCopies(component, selected);

        assertEquals(selectedKey, component.canonical.get(this.target).key());
    }

    @Test
    void fullyProvenRepairableCurrentVersionCanBeTheRollbackAndSelection() {
        CopyVersionScanner.Version repairable = new CopyVersionScanner.Version("repairable", false, 1,
                this.first.copies(), List.of(), List.of(), Set.of(UUID.randomUUID()));
        CopyVersionScanner.Scan scan = new CopyVersionScanner.Scan(this.target, Set.of(this.target),
                List.of(repairable, this.second), List.of(), "repairable",
                CopyVersionScanner.CurrentState.KNOWN, 1);

        CopyOps.CopyResolutionPlan plan = CopyOps.requireCopyResolution(
                scan, "repairable", CopyOps.CopySelectionBasis.LIVE);

        assertFalse(CopyOps.preSaveAllowed(scan), "可修复路径不能触发任何删除前 saveAll");
        assertEquals("repairable", plan.selected().id());
        assertEquals("repairable", plan.rollback().id());
    }

    @Test
    void dependencyRepairKeepsOnlySelectedMembersWithoutMutatingTheBackup() {
        UUID missing = UUID.randomUUID();
        CompoundTag source = new CompoundTag();
        ListTag dependencies = new ListTag();
        dependencies.add(NbtUtils.createUUID(this.target));
        dependencies.add(NbtUtils.createUUID(missing));
        source.put("loading_dependencies", dependencies);

        CompoundTag repaired = CopyOps.retainDependencies(source, Set.of(this.target));

        assertEquals(List.of(this.target), DiskScanner.dependencies(repaired));
        assertEquals(List.of(this.target, missing), DiskScanner.dependencies(source));
    }

    @Test
    void completeCurrentVersionStillUsesTheNormalPreSavePath() {
        assertTrue(CopyOps.preSaveAllowed(scan("first", CopyVersionScanner.CurrentState.KNOWN)));
    }

    @Test
    void runtimeCurrentSelectionBacksUpAndRestoresTheRuntimeSnapshot() {
        CompoundTag disk = new CompoundTag();
        disk.putString("state", "disk");
        DiskScanner.EntryKey key = key(0);
        CopyVersionScanner.Version version = new CopyVersionScanner.Version("runtime", true, 0,
                List.of(new CopyVersionScanner.Copy(this.target, key, disk, 1, List.of())),
                List.of(), List.of(), Set.of());
        CompoundTag live = new CompoundTag();
        live.putString("state", "live");
        Map<UUID, OpKit.RuntimeSnapshot> snapshots = Map.of(this.target,
                new OpKit.RuntimeSnapshot("minecraft:overworld", live));

        List<com.klnon.sablepanel.panel.recycle.RecycleStore.Source> sources =
                CopyOps.resolutionSources(version, "runtime", snapshots);
        var restored = CopyOps.restoreSelection(version, "runtime", snapshots,
                Map.of(this.target, new com.klnon.sablepanel.panel.recycle.RecycleStore.OperationalState(
                        false, false, true)), "selection");

        assertEquals("live", sources.get(0).tag().getString("state"));
        assertEquals("live", restored.bodies().get(0).tag().getString("state"));
        org.junit.jupiter.api.Assertions.assertTrue(restored.bodies().get(0).frozen());
    }

    @Test
    void laterCommitFailureRollsBackExternalDependencyNormalization() {
        List<String> calls = new ArrayList<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CopyOps.finishExternalNormalization(
                        () -> calls.add("normalize"),
                        () -> {
                            calls.add("finish");
                            throw new IllegalStateException("commit failed");
                        }, () -> calls.add("rollback"), () -> calls.add("discard"),
                        () -> calls.add("recovery")));

        assertEquals("commit failed", failure.getMessage());
        assertEquals(List.of("normalize", "finish", "rollback", "discard"), calls);
    }

    @Test
    void failedExternalRollbackKeepsRecoveryMaterial() {
        List<String> calls = new ArrayList<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CopyOps.finishExternalNormalization(
                        () -> calls.add("normalize"),
                        () -> { throw new IllegalStateException("finish"); },
                        () -> { calls.add("rollback"); throw new IllegalStateException("rollback"); },
                        () -> calls.add("discard"), () -> calls.add("recovery")));

        assertEquals(List.of("normalize", "rollback", "recovery"), calls);
        assertEquals("rollback", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void partialNormalizeFailureKeepsRecoveryMaterial() {
        List<String> calls = new ArrayList<>();
        IllegalStateException normalize = new IllegalStateException("normalize");
        normalize.addSuppressed(new IllegalStateException("internal rollback"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CopyOps.finishExternalNormalization(
                        () -> { throw normalize; }, () -> calls.add("finish"),
                        () -> calls.add("rollback"), () -> calls.add("discard"),
                        () -> calls.add("recovery")));

        assertEquals(normalize, failure);
        assertEquals(List.of("recovery"), calls);
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
