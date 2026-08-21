package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.panel.recycle.RecycleStore;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreOpsTest {
    @Test
    void asymmetricDependentIsRestoredBeforeItsDependencyCycle() {
        UUID coreA = UUID.randomUUID();
        UUID coreB = UUID.randomUUID();
        UUID coreC = UUID.randomUUID();
        UUID coreD = UUID.randomUUID();
        UUID inbound = UUID.randomUUID();
        Map<UUID, List<UUID>> dependencies = new LinkedHashMap<>();
        dependencies.put(coreA, List.of(coreB, coreC, coreD));
        dependencies.put(coreB, List.of(coreA, coreC, coreD));
        dependencies.put(coreC, List.of(coreA, coreB, coreD));
        dependencies.put(coreD, List.of(coreA, coreB, coreC));
        dependencies.put(inbound, List.of(coreA, coreB, coreC, coreD));

        List<UUID> order = RestoreOps.restoreOrder(dependencies);

        assertEquals(inbound, order.get(0));
        assertTrue(order.subList(1, order.size()).containsAll(List.of(coreA, coreB, coreC, coreD)));
    }

    @Test
    void directedChainRestoresEveryDependentBeforeItsDependency() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        assertEquals(List.of(first, second, third), RestoreOps.restoreOrder(Map.of(
                first, List.of(second), second, List.of(third), third, List.of())));
    }

    @Test
    void recoveryBackupRequiresEveryExternalDependencyToExistExactlyOnce() {
        UUID survivor = UUID.randomUUID();
        UUID external = UUID.randomUUID();
        RecycleStore.RestoreGroup group = new RecycleStore.RestoreGroup(
                "recovery", "recovery_required", false,
                List.of(new RecycleStore.RestoreBody(survivor, "minecraft:overworld",
                        tagWithDependencies(external), false, false, false)));
        DiskScanner.EntryMeta first = meta(0);
        DiskScanner.EntryMeta duplicate = meta(1);

        assertDoesNotThrow(() -> RestoreOps.requireExternalDependenciesPresent(
                group, Map.of(external, List.of(first))));
        assertThrows(IllegalStateException.class, () -> RestoreOps.requireExternalDependenciesPresent(
                group, Map.of()));
        assertThrows(IllegalStateException.class, () -> RestoreOps.requireExternalDependenciesPresent(
                group, Map.of(external, List.of(first, duplicate))));
    }

    private static CompoundTag tagWithDependencies(UUID... dependencies) {
        CompoundTag tag = new CompoundTag();
        ListTag values = new ListTag();
        for (UUID dependency : dependencies) values.add(NbtUtils.createUUID(dependency));
        tag.put("loading_dependencies", values);
        return tag;
    }

    private static DiskScanner.EntryMeta meta(int index) {
        return new DiskScanner.EntryMeta(new DiskScanner.EntryKey(
                "minecraft:overworld", 0, 0, 0, index), List.of(), 0, 0);
    }
}
