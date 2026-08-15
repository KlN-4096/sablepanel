package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.panel.storage.DiskScanner;
import com.klnon.sablepanel.panel.recycle.RecycleStore;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeleteTxSnapshotTest {
    private final UUID target = UUID.randomUUID();
    private final DiskScanner.EntryKey key = new DiskScanner.EntryKey("minecraft:overworld", 0, 0, 0, 0);

    /** 磁盘侧闸门(作业线程):成员/槽位/内容/指针任一变化都必须中止 */
    @Test
    void diskGateRejectsAnyPreparedSnapshotChange() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", this.target);
        DiskScanner.LiveLocation pointer = new DiskScanner.LiveLocation(this.key, 0, 0);
        DeleteTx.DiskSnapshot expected = disk(tag, List.of(pointer, pointer));

        assertDoesNotThrow(() -> DeleteTx.requireUnchangedDiskSnapshot(expected,
                disk(tag.copy(), List.of(pointer, pointer))));

        CompoundTag changed = tag.copy();
        changed.putString("display_name", "changed");
        assertThrows(IllegalStateException.class, () -> DeleteTx.requireUnchangedDiskSnapshot(
                expected, disk(changed, List.of(pointer, pointer))));
        assertThrows(IllegalStateException.class, () -> DeleteTx.requireUnchangedDiskSnapshot(
                expected, disk(tag.copy(), List.of(pointer))));
        assertThrows(IllegalStateException.class, () -> DeleteTx.requireUnchangedDiskSnapshot(
                expected, new DeleteTx.DiskSnapshot(Set.of(), expected.entries(), expected.pointers())));
    }

    /** 运行态闸门(主线程执行块内):active 指针或暂停/常驻状态变化都必须中止 */
    @Test
    void operationalGateRejectsActiveOrStateChange() {
        DeleteTx.OperationalSnapshot expected = operational(this.key.id(), false);

        assertDoesNotThrow(() -> DeleteTx.requireUnchangedOperationalSnapshot(
                expected, operational(this.key.id(), false)));
        assertThrows(IllegalStateException.class, () -> DeleteTx.requireUnchangedOperationalSnapshot(
                expected, operational("other-entry", false)));
        assertThrows(IllegalStateException.class, () -> DeleteTx.requireUnchangedOperationalSnapshot(
                expected, operational(this.key.id(), true)));
    }

    @Test
    void batchHoldingRemovalOnlyDropsSelectedTargets() {
        UUID kept = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        Map<UUID, String> values = new LinkedHashMap<>();
        values.put(kept, "keep");
        values.put(removed, "drop");

        assertEquals(1, DeleteTx.removeKeys(values, Set.of(removed, UUID.randomUUID())));
        assertEquals(Map.of(kept, "keep"), values);
    }

    private DeleteTx.DiskSnapshot disk(CompoundTag tag, List<DiskScanner.LiveLocation> pointers) {
        return new DeleteTx.DiskSnapshot(Set.of(this.target), Map.of(this.target, Map.of(this.key, tag)),
                Map.of(this.key, pointers));
    }

    private DeleteTx.OperationalSnapshot operational(String active, boolean paused) {
        return new DeleteTx.OperationalSnapshot(Map.of(this.target, active),
                Map.of(this.target, new RecycleStore.OperationalState(paused, false)));
    }
}
