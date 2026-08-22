package com.klnon.sablepanel.panel.ops;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpKitLoadOrderTest {
    @Test
    void preparedLoadRejectsContentChangesAtTheSelectedEntry() {
        CompoundTag expected = new CompoundTag();
        expected.putString("state", "prepared");
        CompoundTag changed = expected.copy();
        changed.putString("state", "changed");

        assertTrue(OpKit.preparedTagMatches(expected, expected.copy()));
        assertFalse(OpKit.preparedTagMatches(expected, changed));
        assertFalse(OpKit.preparedTagMatches(expected, null));
    }

    @Test
    void loadedDependencyExpansionUsesTheRuntimeGroupWithoutHistoricalDiskNeighbors() {
        UUID diskRoot = UUID.randomUUID();
        UUID bridge = UUID.randomUUID();
        UUID runtimeOnly = UUID.randomUUID();
        UUID diskTail = UUID.randomUUID();
        Map<UUID, List<com.klnon.sablepanel.panel.storage.DiskScanner.EntryMeta>> disk = Map.of(
                diskRoot, List.of(meta(0, List.of(bridge))),
                bridge, List.of(meta(1, List.of(diskRoot))),
                runtimeOnly, List.of(meta(2, List.of(diskTail))),
                diskTail, List.of(meta(3, List.of(runtimeOnly))));
        Set<UUID> runtimeGroup = Set.of(bridge, runtimeOnly);

        List<Set<UUID>> expanded = OpKit.selectDependencyGroupSets(List.of(bridge), disk,
                Map.of(bridge, runtimeGroup, runtimeOnly, runtimeGroup));

        assertEquals(List.of(runtimeGroup), expanded);
    }

    @Test
    void forceLoadDropsADetachedHistoricalMemberAfterRuntimeResolution() {
        UUID balloon = UUID.randomUUID();
        UUID balloonPart = UUID.randomUUID();
        UUID ancientCity = UUID.randomUUID();
        Set<UUID> prepared = Set.of(balloon, balloonPart, ancientCity);
        Set<UUID> runtime = Set.of(balloon, balloonPart);

        TeleportOps.ForceTicketPlan plan = TeleportOps.forceTicketPlan(prepared, runtime);

        assertEquals(runtime, plan.keep());
        assertEquals(Set.of(ancientCity), plan.release());
    }

    @Test
    void forceLoadAddsColdDependenciesButNotHistoricalInboundNeighbors() {
        UUID root = UUID.randomUUID();
        UUID loadedPart = UUID.randomUUID();
        UUID coldDependency = UUID.randomUUID();
        UUID historicalInbound = UUID.randomUUID();
        Map<UUID, List<com.klnon.sablepanel.panel.storage.DiskScanner.EntryMeta>> disk = Map.of(
                root, List.of(meta(0, List.of(loadedPart, coldDependency))),
                loadedPart, List.of(meta(1, List.of(root))),
                coldDependency, List.of(meta(2, List.of(root))),
                historicalInbound, List.of(meta(3, List.of(root))));
        Set<UUID> runtime = Set.of(root, loadedPart);
        Map<UUID, String> selected = Map.of(
                root, disk.get(root).get(0).key().id(),
                loadedPart, disk.get(loadedPart).get(0).key().id(),
                coldDependency, disk.get(coldDependency).get(0).key().id(),
                historicalInbound, disk.get(historicalInbound).get(0).key().id());

        List<Set<UUID>> groups = OpKit.selectForceLoadCandidateGroupSets(
                List.of(root), disk, Map.of(root, runtime), selected, new ArrayList<>());

        assertEquals(List.of(Set.of(root, loadedPart, coldDependency)), groups);
        assertFalse(groups.get(0).contains(historicalInbound));
    }

    @Test
    void asymmetricRuntimeChainsStayAnchoredToTheRequestedBody() {
        UUID balloon = UUID.randomUUID();
        UUID balloonPart = UUID.randomUUID();
        UUID ancientCity = UUID.randomUUID();
        Set<UUID> balloonChain = Set.of(balloon, balloonPart);
        Set<UUID> cityChain = Set.of(ancientCity, balloon, balloonPart);

        List<Set<UUID>> selected = OpKit.selectDependencyGroupSets(List.of(balloon), Map.of(), Map.of(
                balloon, balloonChain, ancientCity, cityChain));

        assertEquals(List.of(balloonChain), selected);
        assertFalse(selected.get(0).contains(ancientCity));
    }

    @Test
    void overlappingRequestedRuntimeChainsMergeForOneForceLoadTransaction() {
        UUID balloon = UUID.randomUUID();
        UUID balloonPart = UUID.randomUUID();
        UUID ancientCity = UUID.randomUUID();
        Set<UUID> balloonChain = Set.of(balloon, balloonPart);
        Set<UUID> cityChain = Set.of(ancientCity, balloon, balloonPart);

        List<Set<UUID>> groups = OpKit.selectForceLoadGroupSets(
                List.of(balloon, ancientCity), Map.of(), Map.of(
                        balloon, balloonChain, ancientCity, cityChain));

        assertEquals(List.of(cityChain), groups);
        assertThrows(IllegalStateException.class, () -> OpKit.selectDependencyGroupSets(
                List.of(balloon, ancientCity), Map.of(), Map.of(
                        balloon, balloonChain, ancientCity, cityChain)));
    }

    @Test
    void forceLoadObservesEveryRequestedRootInMergedComponents() {
        UUID first = UUID.randomUUID();
        UUID firstMember = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var selection = new OpKit.DependencySelection(
                List.of(firstMember, first, second), List.of(first, firstMember, second),
                List.of(Set.of(first, firstMember), Set.of(second)));

        assertEquals(List.of(firstMember, first, second), TeleportOps.forceLoadAnchors(selection));
    }

    @Test
    void forceLoadRejectsAStaleActiveEntryInsteadOfDroppingColdDependencies() {
        UUID root = UUID.randomUUID();
        UUID coldDependency = UUID.randomUUID();
        Map<UUID, List<com.klnon.sablepanel.panel.storage.DiskScanner.EntryMeta>> disk = Map.of(
                root, List.of(meta(0, List.of(coldDependency)), meta(1, List.of())),
                coldDependency, List.of(meta(2, List.of())));

        assertThrows(IllegalStateException.class, () -> OpKit.directedDependencyClosure(
                Set.of(root), disk, Map.of(root, "missing-entry")));
        assertThrows(IllegalStateException.class, () -> OpKit.directedDependencyClosure(
                Set.of(root), Map.of(), Map.of(root, "missing-entry")));
        // 闭包单元照常抛;成组层把它降级为该根的失败明细,不再整单连坐
        List<String> failures = new ArrayList<>();
        assertEquals(List.of(), OpKit.selectForceLoadCandidateGroupSets(
                List.of(root), disk, Map.of(), Map.of(), failures));
        assertEquals(1, failures.size());
    }

    @Test
    void forceLoadSelectionDropsMissingRootsInsteadOfFailingTheBatch() {
        // 2026-08-22 job#5/#13:勾选残留刚删除的体,几个死 UUID 让 288/285 个活体整单连坐
        UUID living = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        Map<UUID, List<com.klnon.sablepanel.panel.storage.DiskScanner.EntryMeta>> disk =
                Map.of(living, List.of(meta(0, List.of())));
        List<String> failures = new ArrayList<>();

        List<UUID> known = OpKit.knownForceLoadRoots(
                List.of(living, deleted), Map.of(), disk, failures);

        assertEquals(List.of(living), known);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("依赖组根成员不存在"));
        assertTrue(failures.get(0).contains(deleted.toString().substring(0, 8)));
        assertEquals(List.of(living, deleted),
                OpKit.knownForceLoadRoots(List.of(living, deleted),
                        Map.of(deleted, Set.of(deleted)), disk, new ArrayList<>()));
    }

    @Test
    void forceLoadSelectionSurvivesAPoisonedChainAndReportsIt() {
        // 2026-08-22 job#7/#14:一条链的依赖成员(糖音气球)多副本,285/119 个体整单连坐;
        // 两个根撞同一病灶时按病因聚合成一条失败明细
        UUID cleanRoot = UUID.randomUUID();
        UUID poisonedRoot = UUID.randomUUID();
        UUID otherPoisonedRoot = UUID.randomUUID();
        UUID balloon = UUID.randomUUID();
        Map<UUID, List<com.klnon.sablepanel.panel.storage.DiskScanner.EntryMeta>> disk = Map.of(
                cleanRoot, List.of(meta(0, List.of())),
                poisonedRoot, List.of(meta(1, List.of(balloon))),
                otherPoisonedRoot, List.of(meta(2, List.of(balloon))),
                balloon, List.of(meta(3, List.of()), meta(4, List.of())));
        List<String> failures = new ArrayList<>();

        List<Set<UUID>> groups = OpKit.selectForceLoadCandidateGroupSets(
                List.of(cleanRoot, poisonedRoot, otherPoisonedRoot), disk,
                Map.of(), Map.of(), failures);

        assertEquals(List.of(Set.of(cleanRoot)), groups);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains(balloon.toString()));
        assertTrue(failures.get(0).contains(poisonedRoot.toString().substring(0, 8)));
        assertTrue(failures.get(0).contains(otherPoisonedRoot.toString().substring(0, 8)));
    }

    @Test
    void coldDuplicateUsesTheOnlyEntryWithHoldingEvidence() {
        UUID root = UUID.randomUUID();
        var first = meta(0, List.of());
        var pointed = meta(1, List.of());
        Map<UUID, List<com.klnon.sablepanel.panel.storage.DiskScanner.EntryMeta>> disk =
                Map.of(root, List.of(first, pointed));
        var location = new com.klnon.sablepanel.panel.storage.DiskScanner.LiveLocation(
                pointed.key(), 12, -4);

        Map<UUID, String> selected = OpKit.pointedForceLoadEntries(
                disk, Map.of(), Map.of(pointed.key(), List.of(location)));

        assertEquals(pointed.key().id(), selected.get(root));
        assertEquals(Set.of(root), OpKit.directedDependencyClosure(Set.of(root), disk, selected));
    }

    @Test
    void startupRestorePartitionsIndependentIntentGroups() {
        UUID first = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID independent = UUID.randomUUID();
        Map<UUID, List<com.klnon.sablepanel.panel.storage.DiskScanner.EntryMeta>> disk = Map.of(
                first, List.of(meta(0, List.of(member))),
                member, List.of(meta(1, List.of(first))),
                independent, List.of(meta(2, List.of())));

        List<List<UUID>> groups = OpKit.forceLoadIntentGroups(
                disk, List.of(first, member, independent));

        assertEquals(2, groups.size());
        assertTrue(groups.stream().anyMatch(group -> Set.copyOf(group).equals(Set.of(first, member))));
        assertTrue(groups.stream().anyMatch(group -> Set.copyOf(group).equals(Set.of(independent))));
    }

    @Test
    void unforceResolvesClosuresAtExecutionInsteadOfExactSnapshotMatch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID rope = UUID.randomUUID();
        Set<UUID> universe = Set.of(a, b, c, d, rope);
        Set<UUID> core = Set.of(a, b, c, d);

        // 脱开瞬间:四体核心与绳子是两个干净闭包,各自可卸 —— 旧精确比对在此整组失败
        Map<UUID, Set<UUID>> detached = Map.of(a, core, b, core, c, core, d, core,
                rope, Set.of(rope));
        List<Set<UUID>> closures = TeleportOps.executionClosures(universe, detached);
        assertEquals(Set.of(core, Set.of(rope)), Set.copyOf(closures));
        assertEquals(a, TeleportOps.exactGroupAnchor(core, List.of(a), detached));

        // 挂接进行到一半(非对称重叠):合并成一个闭包但无覆盖锚点 —— 仍中止,重试即过
        Map<UUID, Set<UUID>> midAttach = new java.util.LinkedHashMap<>(detached);
        midAttach.put(rope, Set.of(rope, a));
        assertEquals(List.of(universe), TeleportOps.executionClosures(universe, midAttach));
        assertThrows(IllegalStateException.class,
                () -> TeleportOps.exactGroupAnchor(universe, List.of(a), midAttach));
    }

    @Test
    void unforceStillAbortsWhenABystanderRopesIntoTheChain() {
        UUID target = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> TeleportOps.executionClosures(
                Set.of(target), Map.of(target, Set.of(target, bystander))));
    }

    @Test
    void cancelForceLoadUsesTheRootThatCoversTheWholeMergedComponent() {
        UUID balloon = UUID.randomUUID();
        UUID balloonPart = UUID.randomUUID();
        UUID ancientCity = UUID.randomUUID();
        Set<UUID> complete = Set.of(balloon, balloonPart, ancientCity);

        UUID anchor = TeleportOps.exactGroupAnchor(complete, List.of(balloon, ancientCity), Map.of(
                balloon, Set.of(balloon, balloonPart), ancientCity, complete));

        assertEquals(ancientCity, anchor);
        assertThrows(IllegalStateException.class, () -> TeleportOps.exactGroupAnchor(
                complete, List.of(balloon), Map.of(balloon, Set.of(balloon, balloonPart))));
    }

    @Test
    void cancelForceLoadIncludesOnlyForcedComponentsIntersectingTheSelection() {
        UUID selected = UUID.randomUUID();
        Set<UUID> selectedGroup = Set.of(selected, UUID.randomUUID());
        Set<UUID> unrelatedGroup = Set.of(UUID.randomUUID(), UUID.randomUUID());

        assertEquals(List.of(selectedGroup), OpKit.intersectingComponents(
                List.of(selectedGroup, unrelatedGroup), List.of(selected)));
        assertDoesNotThrow(() -> TeleportOps.requireForcedTicketSnapshot(
                selectedGroup, Set.copyOf(selectedGroup)));
        assertThrows(IllegalStateException.class, () -> TeleportOps.requireForcedTicketSnapshot(
                selectedGroup, Set.of(selected)));
    }

    @Test
    void forceLoadAccumulatesChangingChainsAcrossDistinctServerTicks() throws Exception {
        UUID balloon = UUID.randomUUID();
        UUID delayedPart = UUID.randomUUID();
        List<TeleportOps.RuntimeObservation> samples = List.of(
                new TeleportOps.RuntimeObservation(10, Set.of(balloon)),
                new TeleportOps.RuntimeObservation(10, Set.of(balloon)),
                new TeleportOps.RuntimeObservation(11, Set.of(balloon, delayedPart)),
                new TeleportOps.RuntimeObservation(12, Set.of(balloon)),
                new TeleportOps.RuntimeObservation(13, Set.of(balloon)),
                new TeleportOps.RuntimeObservation(14, Set.of(balloon)));
        AtomicInteger sample = new AtomicInteger();

        Set<UUID> settled = TeleportOps.awaitSettledRuntimeMembers(
                () -> samples.get(Math.min(sample.getAndIncrement(), samples.size() - 1)),
                () -> { }, 3, 20);

        assertEquals(Set.of(balloon, delayedPart), settled);
        assertEquals(6, sample.get(), "同一服务器 tick 的重复采样不能计入静默 tick");
        assertEquals(Set.of(balloon, delayedPart),
                TeleportOps.settledForceMembers(settled, Set.of(balloon)));
        assertThrows(IllegalStateException.class, () -> TeleportOps.settledForceMembers(
                Set.of(balloon), Set.of(balloon, delayedPart)));
    }

    @Test
    void forceLoadRollbackContinuesAfterOneTicketRemovalFails() {
        TeleportOps.TicketRef first = new TeleportOps.TicketRef(UUID.randomUUID(), "minecraft:overworld");
        TeleportOps.TicketRef second = new TeleportOps.TicketRef(UUID.randomUUID(), "minecraft:the_nether");
        List<TeleportOps.TicketRef> attempted = new ArrayList<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TeleportOps.rollbackNewTickets(List.of(first, second), ticket -> {
                    attempted.add(ticket);
                    if (ticket.equals(first)) throw new IllegalStateException("stuck");
                }, ticket -> ticket.equals(first)));

        assertEquals(List.of(first, second), attempted);
        assertTrue(failure.getMessage().contains(first.toString()));
        assertEquals("stuck", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void unloadedDependencyExpansionStillUsesTheStrictDiskComponent() {
        UUID root = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        Map<UUID, List<com.klnon.sablepanel.panel.storage.DiskScanner.EntryMeta>> disk = Map.of(
                root, List.of(meta(0, List.of(dependency))),
                dependency, List.of(meta(1, List.of(root))));

        List<Set<UUID>> expanded = OpKit.selectDependencyGroupSets(List.of(root), disk, Map.of());

        assertEquals(List.of(Set.of(root, dependency)), expanded);
    }

    @Test
    void destructiveOperationRejectsARuntimeChainChangedAfterPreparation() {
        UUID root = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID joinedLater = UUID.randomUUID();
        Set<UUID> expected = Set.of(root, member);

        assertDoesNotThrow(() -> OpKit.requireExactRuntimeGroup(expected, expected, Set.of()));
        assertThrows(IllegalStateException.class, () -> OpKit.requireExactRuntimeGroup(
                expected, Set.of(root, member, joinedLater), Set.of()));
        assertThrows(IllegalStateException.class, () -> OpKit.requireExactRuntimeGroup(
                expected, Set.of(root), Set.of(member)));
    }

    @Test
    void loadedGroupSelectionUsesOneRuntimeSnapshot() {
        UUID root = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        Set<UUID> current = Set.of(root, member);

        List<Set<UUID>> groups = OpKit.selectDependencyGroupSets(
                List.of(root), Map.of(), Map.of(root, current));

        assertEquals(List.of(current), groups);
    }

    @Test
    void multiSelectionKeepsPreparedRuntimeGroupPartitions() {
        UUID firstRoot = UUID.randomUUID();
        UUID firstMember = UUID.randomUUID();
        UUID secondRoot = UUID.randomUUID();
        Set<UUID> first = Set.of(firstRoot, firstMember);
        Set<UUID> second = Set.of(secondRoot);

        List<Set<UUID>> groups = OpKit.selectDependencyGroupSets(
                List.of(firstRoot, firstMember, secondRoot), Map.of(), Map.of(
                        firstRoot, first, firstMember, first, secondRoot, second));

        assertEquals(Set.of(first, second), Set.copyOf(groups));
    }

    @Test
    void preparedSuccessNeverTouchesHistoricalHolding() {
        List<String> calls = new ArrayList<>();

        boolean loaded = OpKit.loadSelectedFirst(false, new OpKit.LoadAttempts(
                () -> { calls.add("cold"); return false; },
                () -> { calls.add("prepared"); return true; },
                () -> { calls.add("holding"); return true; }));

        assertTrue(loaded);
        assertEquals(List.of("cold", "prepared"), calls);
    }

    @Test
    void requestedAnchorIsAlwaysLoadedBeforeItsDependencyCandidates() {
        UUID root = UUID.randomUUID();
        UUID firstDependency = UUID.randomUUID();
        UUID secondDependency = UUID.randomUUID();

        assertEquals(List.of(root, firstDependency, secondDependency),
                OpKit.loadOrder(root, List.of(firstDependency, root, secondDependency)));
    }

    @Test
    void queuedColdLoadIsDrainedBeforeTryingAnotherLoader() {
        List<String> calls = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean loaded = new java.util.concurrent.atomic.AtomicBoolean();

        assertTrue(OpKit.loadNow(() -> calls.add("snatch"), () -> {
            calls.add("processChanges");
            loaded.set(true);
        }, loaded::get));
        assertEquals(List.of("snatch", "processChanges"), calls);

        calls.clear();
        loaded.set(true);
        assertTrue(OpKit.loadNow(() -> calls.add("snatch"), () -> calls.add("processChanges"), loaded::get));
        assertEquals(List.of("snatch"), calls);
    }

    @Test
    void occupiedSelectedPlotDoesNotTryAnyCandidate() {
        List<String> calls = new ArrayList<>();

        boolean loaded = OpKit.loadSelectedFirst(true, new OpKit.LoadAttempts(
                () -> { calls.add("cold"); return true; },
                () -> { calls.add("prepared"); return true; },
                () -> { calls.add("holding"); return true; }));

        assertFalse(loaded);
        assertEquals(List.of(), calls);
    }

    @Test
    void historicalHoldingMustMatchSelectedContentAndHaveAFreePlot() {
        CompoundTag selected = new CompoundTag();
        selected.putString("copy", "selected");
        CompoundTag same = selected.copy();
        CompoundTag different = selected.copy();
        different.putString("copy", "other");

        assertTrue(OpKit.matchingHolding(selected, same, false));
        assertFalse(OpKit.matchingHolding(selected, different, false));
        assertFalse(OpKit.matchingHolding(selected, same, true));
    }

    @Test
    void staleColdPointerIsSkippedBeforeCallingSableSnatch() {
        var selected = new GlobalSavedSubLevelPointer(new ChunkPos(20, -211), (short) 0, (short) 7);
        var current = new GlobalSavedSubLevelPointer(new ChunkPos(19, -213), (short) 0, (short) 7);

        assertTrue(OpKit.samePointer(selected, selected));
        assertFalse(OpKit.samePointer(selected, current));
        assertFalse(OpKit.samePointer(selected, null));
    }

    @Test
    void perGroupTransactionTakesOnlyTheComponentButSharesThePlans() {
        UUID first = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<UUID, OpKit.MemberPlan> plans = Map.of(first,
                new OpKit.MemberPlan(meta(0, List.of()).key(), new CompoundTag(), null));
        var all = new OpKit.DependencySelection(List.of(first, second), List.of(first, member, second),
                List.of(Set.of(first, member), Set.of(second)), plans);

        var group = TeleportOps.componentSelection(all, Set.of(first, member));

        assertEquals(List.of(first), group.roots());
        assertEquals(Set.of(first, member), Set.copyOf(group.members()));
        assertEquals(List.of(Set.of(first, member)), group.components());
        assertEquals(plans, group.plans());
    }

    @Test
    void bulkForceOutcomeSeparatesPartialFromTotalFailure() {
        UUID survivor = UUID.randomUUID();
        JsonObject allOk = TeleportOps.forcedBatchResponse(false, 2, 2, List.of(survivor), List.of());
        JsonObject partial = TeleportOps.forcedBatchResponse(
                false, 2, 2, List.of(survivor), List.of("组[a]: 运行组变化"));
        JsonObject allFailed = TeleportOps.forcedBatchResponse(
                false, 2, 2, List.of(), List.of("组[a]: 运行组变化", "组[b]: 根成员不存在"));

        assertEquals("ok", JobService.outcomeOf(allOk));
        assertEquals("partial", JobService.outcomeOf(partial));
        assertEquals("fail", JobService.outcomeOf(allFailed));
        // 周期恢复的失败闩靠异常升格:部分失败不抛,失败组会被每 30 秒无谓重试
        assertDoesNotThrow(() -> TeleportOps.requireForcedGroupsSucceeded(allOk));
        assertThrows(IllegalStateException.class,
                () -> TeleportOps.requireForcedGroupsSucceeded(partial));
    }

    @Test
    void stopTicketDetachRetriesButNeverSilentlySucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        ForceLoadService.retryStopDetach(3, () -> {
            if (attempts.incrementAndGet() < 3) throw new IllegalStateException("still attached");
        });
        assertEquals(3, attempts.get());

        AtomicInteger failed = new AtomicInteger();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ForceLoadService.retryStopDetach(3, () -> {
                    failed.incrementAndGet();
                    throw new IllegalStateException("still attached");
                }));
        assertEquals(3, failed.get());
        assertEquals("still attached", error.getCause().getMessage());
    }

    private static com.klnon.sablepanel.panel.storage.DiskScanner.EntryMeta meta(
            int index, List<UUID> dependencies) {
        return new com.klnon.sablepanel.panel.storage.DiskScanner.EntryMeta(
                new com.klnon.sablepanel.panel.storage.DiskScanner.EntryKey("minecraft:overworld", 0, 0, 0, index),
                dependencies, index, index);
    }
}
