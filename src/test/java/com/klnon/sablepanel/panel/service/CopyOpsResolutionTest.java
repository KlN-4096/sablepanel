package com.klnon.sablepanel.panel.service;

import com.klnon.sablepanel.panel.data.CopyVersionScanner;
import com.klnon.sablepanel.panel.data.DiskScanner;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CopyOpsResolutionTest {
    private final UUID target = UUID.randomUUID();
    private final CopyVersionScanner.Version first = version("first");
    private final CopyVersionScanner.Version second = version("second");

    @Test
    void unknownOrMixedCurrentStateCannotCreateAResolutionPlan() {
        assertThrows(IllegalStateException.class, () -> CopyOps.requireCopyResolution(
                scan(null, CopyVersionScanner.CurrentState.UNKNOWN), "first"));
        assertThrows(IllegalStateException.class, () -> CopyOps.requireCopyResolution(
                scan(null, CopyVersionScanner.CurrentState.MIXED), "first"));
    }

    @Test
    void authoritativeRescanRejectsAChangedSelectedVersion() {
        assertThrows(IllegalStateException.class, () -> CopyOps.requireCopyResolution(
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
                () -> CopyOps.requireCopyResolution(authoritative, requested));
    }

    @Test
    void rollbackAlwaysUsesTheKnownCurrentVersion() {
        CopyOps.CopyResolutionPlan plan = CopyOps.requireCopyResolution(
                scan("first", CopyVersionScanner.CurrentState.KNOWN), "second");

        assertEquals("second", plan.selected().id());
        assertEquals("first", plan.rollback().id());
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
}
