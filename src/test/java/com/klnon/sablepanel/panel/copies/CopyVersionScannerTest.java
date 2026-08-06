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
