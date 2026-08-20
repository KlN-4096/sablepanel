package com.klnon.sablepanel.panel.ops;

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

        List<UUID> expanded = OpKit.selectDependencyGroups(List.of(bridge), disk,
                Map.of(bridge, runtimeGroup, runtimeOnly, runtimeGroup));

        assertEquals(runtimeGroup, Set.copyOf(expanded));
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
    void asymmetricRuntimeChainsStayAnchoredToTheRequestedBody() {
        UUID balloon = UUID.randomUUID();
        UUID balloonPart = UUID.randomUUID();
        UUID ancientCity = UUID.randomUUID();
        Set<UUID> balloonChain = Set.of(balloon, balloonPart);
        Set<UUID> cityChain = Set.of(ancientCity, balloon, balloonPart);

        List<UUID> selected = OpKit.selectDependencyGroups(List.of(balloon), Map.of(), Map.of(
                balloon, balloonChain, ancientCity, cityChain));

        assertEquals(balloonChain, Set.copyOf(selected));
        assertFalse(selected.contains(ancientCity));
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
    void forceLoadStartsOneRequestedAnchorPerDependencyComponent() {
        UUID first = UUID.randomUUID();
        UUID firstMember = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var selection = new OpKit.DependencySelection(
                List.of(firstMember, first, second), List.of(first, firstMember, second),
                List.of(Set.of(first, firstMember), Set.of(second)));

        assertEquals(List.of(firstMember, second), TeleportOps.forceLoadAnchors(selection));
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

        List<UUID> expanded = OpKit.selectDependencyGroups(List.of(root), disk, Map.of());

        assertEquals(Set.of(root, dependency), Set.copyOf(expanded));
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
