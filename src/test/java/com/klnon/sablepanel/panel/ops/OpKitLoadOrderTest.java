package com.klnon.sablepanel.panel.ops;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    private static com.klnon.sablepanel.panel.storage.DiskScanner.EntryMeta meta(
            int index, List<UUID> dependencies) {
        return new com.klnon.sablepanel.panel.storage.DiskScanner.EntryMeta(
                new com.klnon.sablepanel.panel.storage.DiskScanner.EntryKey("minecraft:overworld", 0, 0, 0, index),
                dependencies, index, index);
    }
}
