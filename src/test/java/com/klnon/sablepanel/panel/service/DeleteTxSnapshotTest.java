package com.klnon.sablepanel.panel.service;

import com.klnon.sablepanel.panel.data.DiskScanner;
import com.klnon.sablepanel.panel.data.RecycleStore;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeleteTxSnapshotTest {
    private final UUID target = UUID.randomUUID();

    @Test
    void deleteGateRejectsAnyPreparedSnapshotChange() {
        DiskScanner.EntryKey key = new DiskScanner.EntryKey("minecraft:overworld", 0, 0, 0, 0);
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", this.target);
        DiskScanner.LiveLocation pointer = new DiskScanner.LiveLocation(key, 0, 0);
        DeleteTx.CopySnapshot expected = snapshot(key, tag, List.of(pointer, pointer));

        assertDoesNotThrow(() -> DeleteTx.requireUnchangedCopySnapshot(expected,
                snapshot(key, tag.copy(), List.of(pointer, pointer))));

        CompoundTag changed = tag.copy();
        changed.putString("display_name", "changed");
        assertThrows(IllegalStateException.class, () -> DeleteTx.requireUnchangedCopySnapshot(
                expected, snapshot(key, changed, List.of(pointer, pointer))));
        assertThrows(IllegalStateException.class, () -> DeleteTx.requireUnchangedCopySnapshot(
                expected, snapshot(key, tag.copy(), List.of(pointer))));
        DeleteTx.CopySnapshot activeChanged = new DeleteTx.CopySnapshot(expected.members(), expected.entries(),
                expected.pointers(), Map.of(this.target, "other-entry"), expected.states());
        assertThrows(IllegalStateException.class, () -> DeleteTx.requireUnchangedCopySnapshot(
                expected, activeChanged));
        DeleteTx.CopySnapshot stateChanged = new DeleteTx.CopySnapshot(expected.members(), expected.entries(),
                expected.pointers(), expected.active(),
                Map.of(this.target, new RecycleStore.OperationalState(true, false)));
        assertThrows(IllegalStateException.class, () -> DeleteTx.requireUnchangedCopySnapshot(
                expected, stateChanged));
        DeleteTx.CopySnapshot membersChanged = new DeleteTx.CopySnapshot(Set.of(), expected.entries(),
                expected.pointers(), expected.active(), expected.states());
        assertThrows(IllegalStateException.class, () -> DeleteTx.requireUnchangedCopySnapshot(
                expected, membersChanged));
    }

    private DeleteTx.CopySnapshot snapshot(DiskScanner.EntryKey key, CompoundTag tag,
                                           List<DiskScanner.LiveLocation> pointers) {
        return new DeleteTx.CopySnapshot(Set.of(this.target), Map.of(this.target, Map.of(key, tag)),
                Map.of(key, pointers), Map.of(this.target, key.id()),
                Map.of(this.target, new RecycleStore.OperationalState(false, false)));
    }
}
