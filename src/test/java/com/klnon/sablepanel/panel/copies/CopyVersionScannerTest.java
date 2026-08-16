package com.klnon.sablepanel.panel.copies;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.klnon.sablepanel.panel.storage.DiskScanner;

class CopyVersionScannerTest {
    private static final String DIM = "minecraft:overworld";

    @Test
    void assemblesCompleteHoldingVersionsWithoutMixingMembers() {
        UUID root = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        var rootA = copy(root, 0, tag(root, dependency), 0, 0);
        var dependencyA = copy(dependency, 1, tag(dependency), 0, 0);
        var rootB = copy(root, 2, tag(root, dependency), 1, 0);
        var dependencyB = copy(dependency, 3, tag(dependency), 1, 0);
        var orphan = copy(root, 4, tag(root));
        var broken = copy(root, 5, tag(root, missing), 2, 0);

        CopyVersionScanner.Scan scan = CopyVersionScanner.assemble(root, Set.of(root, dependency),
                List.of(rootA, dependencyA, rootB, dependencyB, orphan, broken),
                Map.of(root, rootB.key().id(), dependency, dependencyB.key().id()));

        List<CopyVersionScanner.Version> complete = scan.versions().stream()
                .filter(CopyVersionScanner.Version::complete).toList();
        assertEquals(2, complete.size());
        assertTrue(complete.stream().anyMatch(version -> entries(version).equals(Set.of(
                rootA.key().id(), dependencyA.key().id()))));
        assertTrue(complete.stream().anyMatch(version -> entries(version).equals(Set.of(
                rootB.key().id(), dependencyB.key().id()))));
        assertTrue(scan.versions().stream().anyMatch(version -> !version.complete()
                && version.missingDependencies().contains(missing)));
        assertEquals(Set.of(orphan.key().id(), broken.key().id()),
                scan.incomplete().stream().map(copy -> copy.key().id()).collect(java.util.stream.Collectors.toSet()));
        CopyVersionScanner.Version current = scan.versions().stream()
                .filter(version -> version.id().equals(scan.currentVersion())).findFirst().orElseThrow();
        assertTrue(current.active());
        assertEquals(2, current.activeMembers());
        assertEquals(CopyVersionScanner.CurrentState.KNOWN, scan.currentState());
        assertEquals(2, scan.activeMembers());
    }

    @Test
    void mixedRuntimeEvidenceDoesNotGuessCurrentVersion() {
        UUID root = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        var rootA = copy(root, 0, tag(root, dependency), 0, 0);
        var dependencyA = copy(dependency, 1, tag(dependency), 0, 0);
        var rootB = copy(root, 2, tag(root, dependency), 1, 0);
        var dependencyB = copy(dependency, 3, tag(dependency), 1, 0);

        CopyVersionScanner.Scan scan = CopyVersionScanner.assemble(root, Set.of(root, dependency),
                List.of(rootA, dependencyA, rootB, dependencyB),
                Map.of(root, rootA.key().id(), dependency, dependencyB.key().id()));

        assertNull(scan.currentVersion());
        assertEquals(CopyVersionScanner.CurrentState.MIXED, scan.currentState());
        assertEquals(2, scan.activeMembers());
    }

    @Test
    void partialRuntimeEvidenceIdentifiesOneCompatibleVersion() {
        UUID root = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        var rootA = copy(root, 0, tag(root, dependency), 0, 0);
        var dependencyA = copy(dependency, 1, tag(dependency), 0, 0);
        var rootB = copy(root, 2, tag(root, dependency), 1, 0);
        var dependencyB = copy(dependency, 3, tag(dependency), 1, 0);

        CopyVersionScanner.Scan scan = CopyVersionScanner.assemble(root, Set.of(root, dependency),
                List.of(rootA, dependencyA, rootB, dependencyB), Map.of(dependency, dependencyB.key().id()));

        assertEquals(scan.versions().stream().filter(version -> entries(version).contains(dependencyB.key().id()))
                .findFirst().orElseThrow().id(), scan.currentVersion());
        assertEquals(CopyVersionScanner.CurrentState.KNOWN, scan.currentState());
        assertEquals(1, scan.activeMembers());
    }

    @Test
    void sharedRuntimeEvidenceLeavesCurrentVersionUnknown() {
        UUID root = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        DiskScanner.EntryKey rootKey = new DiskScanner.EntryKey(DIM, 0, 0, 0, 0);
        var sharedRoot = new CopyVersionScanner.Copy(root, rootKey, tag(root, dependency), 1, List.of(
                new DiskScanner.LiveLocation(rootKey, 0, 0), new DiskScanner.LiveLocation(rootKey, 1, 0)));
        var dependencyA = copy(dependency, 1, tag(dependency), 0, 0);
        var dependencyB = copy(dependency, 2, tag(dependency), 1, 0);

        CopyVersionScanner.Scan scan = CopyVersionScanner.assemble(root, Set.of(root, dependency),
                List.of(sharedRoot, dependencyA, dependencyB), Map.of(root, rootKey.id()));

        assertEquals(2, scan.versions().stream().filter(CopyVersionScanner.Version::complete).count());
        assertNull(scan.currentVersion());
        assertEquals(CopyVersionScanner.CurrentState.UNKNOWN, scan.currentState());
    }

    @Test
    void duplicateUuidInsideOneHoldingVersionIsIncomplete() {
        UUID root = UUID.randomUUID();
        var first = copy(root, 0, tag(root), 0, 0);
        CompoundTag changed = tag(root);
        changed.putString("display_name", "different");
        var second = copy(root, 1, changed, 0, 0);

        CopyVersionScanner.Scan scan = CopyVersionScanner.assemble(
                root, Set.of(root), List.of(first, second), Map.of(root, first.key().id()));

        assertEquals(1, scan.versions().size());
        assertFalse(scan.versions().get(0).complete());
        assertEquals(2, scan.incomplete().size());
        assertNull(scan.currentVersion(), "活动条目不属于完整版本时不能猜当前版本");
        assertEquals(CopyVersionScanner.CurrentState.UNKNOWN, scan.currentState());
    }

    @Test
    void fullyActiveVersionWithOnlyMissingDependenciesIsRepairable() {
        UUID root = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        var rootCopy = copy(root, 0, tag(root, dependency, missing), 0, 0);
        var dependencyCopy = copy(dependency, 1, tag(dependency), 0, 0);

        CopyVersionScanner.Scan scan = CopyVersionScanner.assemble(root, Set.of(root, dependency),
                List.of(rootCopy, dependencyCopy),
                Map.of(root, rootCopy.key().id(), dependency, dependencyCopy.key().id()));

        CopyVersionScanner.Version version = scan.versions().get(0);
        assertFalse(version.complete());
        assertTrue(CopyVersionScanner.repairableCurrent(scan, version));
        assertEquals(version.id(), scan.currentVersion());
        assertEquals(CopyVersionScanner.CurrentState.KNOWN, scan.currentState());
        assertTrue(scan.incomplete().isEmpty(), "可修复版本必须按组备份，不能拆成残缺条目");
    }

    @Test
    void activeUnassignedResidualDoesNotHideTheFullyActiveCurrentVersion() {
        UUID root = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        UUID residual = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        var rootCopy = copy(root, 0, tag(root, dependency, missing), 0, 0);
        var dependencyCopy = copy(dependency, 1, tag(dependency), 0, 0);
        var residualCopy = copy(residual, 2, tag(residual, root), 1, 0);

        CopyVersionScanner.Scan scan = CopyVersionScanner.assemble(root, Set.of(root, dependency, residual),
                List.of(rootCopy, dependencyCopy, residualCopy), Map.of(
                        root, rootCopy.key().id(),
                        dependency, dependencyCopy.key().id(),
                        residual, residualCopy.key().id()));

        CopyVersionScanner.Version current = scan.versions().stream()
                .filter(version -> version.id().equals(scan.currentVersion())).findFirst().orElseThrow();
        assertEquals(3, scan.activeMembers());
        assertEquals(2, current.activeMembers());
        assertTrue(CopyVersionScanner.repairableCurrent(scan, current));
        assertEquals(List.of(residualCopy), scan.incomplete());
    }

    @Test
    void activityInsideAnotherCandidateStillBlocksRepairableCurrentSelection() {
        UUID root = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        UUID residual = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        var rootCurrent = copy(root, 0, tag(root, dependency, missing), 0, 0);
        var dependencyCurrent = copy(dependency, 1, tag(dependency), 0, 0);
        var rootOther = copy(root, 2, tag(root, residual), 1, 0);
        var residualOther = copy(residual, 3, tag(residual, root), 1, 0);

        CopyVersionScanner.Scan scan = CopyVersionScanner.assemble(root, Set.of(root, dependency, residual),
                List.of(rootCurrent, dependencyCurrent, rootOther, residualOther), Map.of(
                        root, rootCurrent.key().id(),
                        dependency, dependencyCurrent.key().id(),
                        residual, residualOther.key().id()));

        CopyVersionScanner.Version competing = scan.versions().stream()
                .filter(version -> entries(version).contains(rootOther.key().id())).findFirst().orElseThrow();
        assertTrue(competing.active(), "反例必须确实包含另一个候选的活动证据");
        assertNull(scan.currentVersion());
        assertEquals(CopyVersionScanner.CurrentState.UNKNOWN, scan.currentState());
    }

    @Test
    void provenMixedEvidenceWinsOverAnAdditionalStaleEntry() {
        UUID root = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        var rootFirst = copy(root, 0, tag(root, dependency, stale), 0, 0);
        var dependencyFirst = copy(dependency, 1, tag(dependency), 0, 0);
        var staleFirst = copy(stale, 2, tag(stale), 0, 0);
        var rootSecond = copy(root, 3, tag(root, dependency, stale), 10, 10);
        var dependencySecond = copy(dependency, 4, tag(dependency), 10, 10);
        var staleSecond = copy(stale, 5, tag(stale), 10, 10);
        Map<UUID, String> active = new LinkedHashMap<>();
        active.put(stale, "stale-entry");
        active.put(root, rootFirst.key().id());
        active.put(dependency, dependencySecond.key().id());

        CopyVersionScanner.Scan scan = CopyVersionScanner.assemble(root,
                new LinkedHashSet<>(List.of(root, dependency, stale)),
                List.of(rootFirst, dependencyFirst, staleFirst, rootSecond, dependencySecond, staleSecond), active);

        assertNull(scan.currentVersion());
        assertEquals(CopyVersionScanner.CurrentState.MIXED, scan.currentState());
    }

    @Test
    void versionIdDoesNotDependOnWhichIdenticalCopyIsActive() {
        UUID root = UUID.randomUUID();
        CompoundTag payload = tag(root);
        var first = copy(root, 0, payload, 0, 0);
        var second = copy(root, 1, payload.copy(), 0, 0);

        String firstActive = CopyVersionScanner.assemble(root, Set.of(root), List.of(first, second),
                Map.of(root, first.key().id())).versions().get(0).id();
        String secondActive = CopyVersionScanner.assemble(root, Set.of(root), List.of(first, second),
                Map.of(root, second.key().id())).versions().get(0).id();

        assertEquals(firstActive, secondActive);
    }

    @Test
    void versionIdChangesWhenPayloadChangesInPlace() {
        UUID root = UUID.randomUUID();
        var original = copy(root, 0, tag(root), 0, 0);
        CompoundTag changedTag = tag(root);
        changedTag.putString("display_name", "changed");
        var changed = copy(root, 0, changedTag, 0, 0);

        String originalId = CopyVersionScanner.assemble(root, Set.of(root), List.of(original),
                        Map.of(root, original.key().id()))
                .versions().get(0).id();
        String changedId = CopyVersionScanner.assemble(root, Set.of(root), List.of(changed),
                        Map.of(root, changed.key().id()))
                .versions().get(0).id();

        assertNotEquals(originalId, changedId);
    }

    /**
     * 版本身份不能被运行态字段左右,否则已加载的体永远处理不了副本。
     * <p>
     * 真机抓到的:面板列版本走 {@code CopyOps.inspectCopies}(不 flush),确认却走
     * {@code prepareCopyResolution} —— 它第一件事就是无条件 {@code flushLoadedTargets},
     * 而 {@code saveAll} 会把已加载体的当前 pose/速度重新序列化下去。物理体一直在跑,
     * 于是含该副本的版本 flush 前后 id 必然不同,{@code requireVersion} 报
     * "副本版本已经变化,请重新扫描";重扫也没用,下一次确认又 flush 一遍。
     * 对常驻+加载中的体(如 J-15)是 100% 复现,而"保留正在跑的那份"恰好就是必失败的选择。
     */
    @Test
    void versionIdIgnoresRuntimePoseSoALoadedBodyStaysResolvable() {
        UUID root = UUID.randomUUID();
        CompoundTag before = tag(root);
        runtimeState(before, 4219.5, 60.9, -1251.6);
        CompoundTag after = tag(root);
        runtimeState(after, 4221.0, 61.4, -1250.2);

        assertEquals(versionIdOf(root, before), versionIdOf(root, after),
                "只有位置和速度变了(saveAll 重写),版本身份不能跟着变");
    }

    @Test
    void versionIdStillChangesWhenTheStructureItselfChanges() {
        UUID root = UUID.randomUUID();
        CompoundTag original = tag(root);
        runtimeState(original, 0, 0, 0);
        CompoundTag mined = tag(root);
        runtimeState(mined, 0, 0, 0);
        mined.getCompound("plot").putInt("plot_x", 7);

        assertNotEquals(versionIdOf(root, original), versionIdOf(root, mined),
                "方块内容变了必须换版本身份,守卫不能因为放宽 pose 就整体失效");
    }

    private static String versionIdOf(UUID root, CompoundTag payload) {
        return CopyVersionScanner.assemble(root, Set.of(root), List.of(copy(root, 0, payload, 0, 0)), Map.of())
                .versions().get(0).id();
    }

    /** 体 NBT 里随物理每 tick 变化、并被 saveAll 落盘的那几个顶层键(键名取自实盘 349 个体的清点)。 */
    private static void runtimeState(CompoundTag tag, double x, double y, double z) {
        CompoundTag position = new CompoundTag();
        position.putDouble("x", x);
        position.putDouble("y", y);
        position.putDouble("z", z);
        CompoundTag pose = new CompoundTag();
        pose.put("position", position);
        tag.put("pose", pose);
        CompoundTag bounds = new CompoundTag();
        bounds.putDouble("minX", x - 8);
        bounds.putDouble("maxX", x + 8);
        tag.put("world_bounds", bounds);
        CompoundTag linear = new CompoundTag();
        linear.putDouble("x", x / 100);
        tag.put("linear_velocity", linear);
        CompoundTag angular = new CompoundTag();
        angular.putDouble("y", y / 100);
        tag.put("angular_velocity", angular);
    }

    private static Set<String> entries(CopyVersionScanner.Version version) {
        return version.copies().stream().map(copy -> copy.key().id())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static CopyVersionScanner.Copy copy(UUID uuid, int slot, CompoundTag tag, int chunkX, int chunkZ) {
        DiskScanner.EntryKey key = new DiskScanner.EntryKey(DIM, 0, 0, 0, slot);
        return new CopyVersionScanner.Copy(uuid, key, tag, 1,
                List.of(new DiskScanner.LiveLocation(key, chunkX, chunkZ)));
    }

    private static CopyVersionScanner.Copy copy(UUID uuid, int slot, CompoundTag tag) {
        DiskScanner.EntryKey key = new DiskScanner.EntryKey(DIM, 0, 0, 0, slot);
        return new CopyVersionScanner.Copy(uuid, key, tag, 1, List.of());
    }

    private static CompoundTag tag(UUID uuid, UUID... dependencies) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", uuid);
        ListTag values = new ListTag();
        for (UUID dependency : dependencies) values.add(NbtUtils.createUUID(dependency));
        tag.put("loading_dependencies", values);
        tag.put("plot", new CompoundTag());
        return tag;
    }
}
