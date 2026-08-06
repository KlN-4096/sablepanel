package com.klnon.sablepanel.panel.service;

import com.klnon.sablepanel.panel.data.CopyVersionScanner;
import com.klnon.sablepanel.panel.data.DiskScanner;
import com.klnon.sablepanel.panel.data.RecycleStore;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpsServiceCopyResolutionTest {
    private final UUID target = UUID.randomUUID();
    private final CopyVersionScanner.Version first = version("first");
    private final CopyVersionScanner.Version second = version("second");

    @Test
    void unknownOrMixedCurrentStateCannotCreateAResolutionPlan() {
        assertThrows(IllegalStateException.class, () -> OpsService.requireCopyResolution(
                scan(null, CopyVersionScanner.CurrentState.UNKNOWN), "first"));
        assertThrows(IllegalStateException.class, () -> OpsService.requireCopyResolution(
                scan(null, CopyVersionScanner.CurrentState.MIXED), "first"));
    }

    @Test
    void authoritativeRescanRejectsAChangedSelectedVersion() {
        assertThrows(IllegalStateException.class, () -> OpsService.requireCopyResolution(
                scan("first", CopyVersionScanner.CurrentState.KNOWN), "stale-version-id"));
    }

    @Test
    void authoritativeRescanRejectsPayloadChangedInTheSameSlot() {
        DiskScanner.EntryKey key = new DiskScanner.EntryKey("minecraft:overworld", 0, 0, 0, 0);
        CompoundTag before = new CompoundTag();
        before.putUUID("uuid", this.target);
        CompoundTag after = before.copy();
        after.putString("display_name", "changed");
        String active = key.id();
        String requested = assembled(key, before, active).currentVersion();
        CopyVersionScanner.Scan authoritative = assembled(key, after, active);

        assertThrows(IllegalStateException.class,
                () -> OpsService.requireCopyResolution(authoritative, requested));
    }

    @Test
    void rollbackAlwaysUsesTheKnownCurrentVersion() {
        OpsService.CopyResolutionPlan plan = OpsService.requireCopyResolution(
                scan("first", CopyVersionScanner.CurrentState.KNOWN), "second");

        assertEquals("second", plan.selected().id());
        assertEquals("first", plan.rollback().id());
    }

    @Test
    void deleteGateRejectsAnyPreparedSnapshotChange() {
        DiskScanner.EntryKey key = new DiskScanner.EntryKey("minecraft:overworld", 0, 0, 0, 0);
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", this.target);
        DiskScanner.LiveLocation pointer = new DiskScanner.LiveLocation(key, 0, 0);
        OpsService.CopySnapshot expected = snapshot(key, tag, List.of(pointer, pointer));

        assertDoesNotThrow(() -> OpsService.requireUnchangedCopySnapshot(expected,
                snapshot(key, tag.copy(), List.of(pointer, pointer))));

        CompoundTag changed = tag.copy();
        changed.putString("display_name", "changed");
        assertThrows(IllegalStateException.class, () -> OpsService.requireUnchangedCopySnapshot(
                expected, snapshot(key, changed, List.of(pointer, pointer))));
        assertThrows(IllegalStateException.class, () -> OpsService.requireUnchangedCopySnapshot(
                expected, snapshot(key, tag.copy(), List.of(pointer))));
        OpsService.CopySnapshot activeChanged = new OpsService.CopySnapshot(expected.members(), expected.entries(),
                expected.pointers(), Map.of(this.target, "other-entry"), expected.states());
        assertThrows(IllegalStateException.class, () -> OpsService.requireUnchangedCopySnapshot(
                expected, activeChanged));
        OpsService.CopySnapshot stateChanged = new OpsService.CopySnapshot(expected.members(), expected.entries(),
                expected.pointers(), expected.active(),
                Map.of(this.target, new RecycleStore.OperationalState(true, false)));
        assertThrows(IllegalStateException.class, () -> OpsService.requireUnchangedCopySnapshot(
                expected, stateChanged));
        OpsService.CopySnapshot membersChanged = new OpsService.CopySnapshot(Set.of(), expected.entries(),
                expected.pointers(), expected.active(), expected.states());
        assertThrows(IllegalStateException.class, () -> OpsService.requireUnchangedCopySnapshot(
                expected, membersChanged));
    }

    private CopyVersionScanner.Scan scan(String current, CopyVersionScanner.CurrentState state) {
        return new CopyVersionScanner.Scan(target, Set.of(target), List.of(first, second), List.of(),
                current, state, current == null ? 0 : 1);
    }

    private static CopyVersionScanner.Version version(String id) {
        return new CopyVersionScanner.Version(id, true, 0, List.of(), List.of(), List.of(), Set.of());
    }

    private CopyVersionScanner.Scan assembled(DiskScanner.EntryKey key, CompoundTag tag, String active) {
        CopyVersionScanner.Copy copy = new CopyVersionScanner.Copy(this.target, key, tag, 1,
                List.of(new DiskScanner.LiveLocation(key, 0, 0)));
        return CopyVersionScanner.assemble(this.target, Set.of(this.target), List.of(copy),
                Map.of(this.target, active));
    }

    private OpsService.CopySnapshot snapshot(DiskScanner.EntryKey key, CompoundTag tag,
                                             List<DiskScanner.LiveLocation> pointers) {
        return new OpsService.CopySnapshot(Set.of(this.target), Map.of(this.target, Map.of(key, tag)),
                Map.of(key, pointers), Map.of(this.target, key.id()),
                Map.of(this.target, new RecycleStore.OperationalState(false, false)));
    }
}
