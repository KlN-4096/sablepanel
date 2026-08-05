package com.klnon.sablepanel.panel.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                List.of(rootA, dependencyA, rootB, dependencyB, orphan, broken), rootB.key().id());

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
        assertTrue(scan.versions().stream().filter(version -> version.id().equals(scan.currentVersion()))
                .findFirst().orElseThrow().active());
    }

    @Test
    void duplicateUuidInsideOneHoldingVersionIsIncomplete() {
        UUID root = UUID.randomUUID();
        var first = copy(root, 0, tag(root), 0, 0);
        CompoundTag changed = tag(root);
        changed.putString("display_name", "different");
        var second = copy(root, 1, changed, 0, 0);

        CopyVersionScanner.Scan scan = CopyVersionScanner.assemble(
                root, Set.of(root), List.of(first, second), first.key().id());

        assertEquals(1, scan.versions().size());
        assertFalse(scan.versions().get(0).complete());
        assertEquals(2, scan.incomplete().size());
        assertNull(scan.currentVersion(), "活动条目不属于完整版本时不能猜当前版本");
    }

    @Test
    void versionIdChangesWhenPayloadChangesInPlace() {
        UUID root = UUID.randomUUID();
        var original = copy(root, 0, tag(root), 0, 0);
        CompoundTag changedTag = tag(root);
        changedTag.putString("display_name", "changed");
        var changed = copy(root, 0, changedTag, 0, 0);

        String originalId = CopyVersionScanner.assemble(root, Set.of(root), List.of(original), original.key().id())
                .versions().get(0).id();
        String changedId = CopyVersionScanner.assemble(root, Set.of(root), List.of(changed), changed.key().id())
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
