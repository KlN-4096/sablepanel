package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.panel.storage.DiskScanner;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PauseServiceAnchorTest {
    @AfterEach
    void clearHandles() {
        PauseService.reset();
    }

    @Test
    void physicsPauseLocksBeforeClearingVelocity() {
        List<String> calls = new ArrayList<>();

        TeleportOps.pauseAndStop(() -> calls.add("lock"), () -> calls.add("resetVelocity"),
                () -> calls.add("rollback"));

        assertEquals(List.of("lock", "resetVelocity"), calls);

        calls.clear();
        assertThrows(IllegalStateException.class, () -> TeleportOps.pauseAndStop(
                () -> { calls.add("lock"); throw new IllegalStateException("rejected"); },
                () -> calls.add("resetVelocity"), () -> calls.add("rollback")));
        assertEquals(List.of("lock", "rollback"), calls,
                "整组固定失败时不能先清除任何速度，且必须进入补偿");
    }

    @Test
    void fixedConstraintTransformsTheCurrentPlotAnchorIntoWorldSpace() {
        BlockPos plotCenter = new BlockPos(20_480_016, 128, 20_480_016);
        Pose3d pose = new Pose3d(new Vector3d(12, 34, 56), new Quaterniond().rotateY(0.7),
                new Vector3d(20_481_027, 181, 20_581_406), new Vector3d(1, 1, 1));
        Vector3d expectedLocal = PauseService.plotAnchor(plotCenter);
        Vector3d expectedWorld = pose.transformPosition(expectedLocal, new Vector3d());

        var config = PauseService.fixedConstraint(pose, plotCenter);

        assertEquals(expectedWorld, config.pos1(), "pos1 必须是普通世界中的锚点");
        assertEquals(expectedLocal, config.pos2(), "pos2 必须位于物理体当前 plot 内");
        assertEquals(pose.orientation(), config.orientation());
    }

    @Test
    void failedReplacementKeepsThePreviousConstraint() {
        AtomicInteger removals = new AtomicInteger();

        assertThrows(IllegalStateException.class,
                () -> PauseService.createBeforeRemoving("previous",
                        () -> { throw new IllegalStateException("rejected"); },
                        ignored -> removals.incrementAndGet(), ignored -> removals.incrementAndGet()));
        assertEquals(0, removals.get(), "新约束创建失败时不能先移除旧约束");

        String replacement = PauseService.createBeforeRemoving("previous", () -> "replacement",
                ignored -> removals.incrementAndGet(), ignored -> removals.incrementAndGet());
        assertEquals("replacement", replacement);
        assertEquals(1, removals.get(), "只有新约束创建成功后才能移除旧约束");
    }

    @Test
    void failedPreviousRemovalRollsBackTheReplacement() {
        AtomicInteger replacementRemovals = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> PauseService.createBeforeRemoving(
                "previous", () -> "replacement", ignored -> { throw new IllegalStateException("stuck"); },
                ignored -> replacementRemovals.incrementAndGet()));

        assertEquals(1, replacementRemovals.get(), "旧约束拆不掉时必须撤掉刚创建的新约束");
    }

    @Test
    void heldMoveDetachesBeforeMovingAndRestoresOriginalLockOnFailure() {
        List<String> calls = new ArrayList<>();

        PauseService.moveWithConstraint(true, () -> calls.add("detach"), new PauseService.MoveActions(
                () -> calls.add("move"), () -> { calls.add("lock"); return true; },
                () -> calls.add("rollback")));
        assertEquals(List.of("detach", "move", "lock"), calls);

        calls.clear();
        AtomicInteger locks = new AtomicInteger();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PauseService.moveWithConstraint(true, () -> calls.add("detach"),
                        new PauseService.MoveActions(() -> calls.add("move"), () -> {
                            calls.add("lock");
                            return locks.incrementAndGet() > 1;
                        }, () -> calls.add("rollback"))));

        assertEquals(List.of("detach", "move", "lock", "rollback", "lock"), calls);
        assertEquals("移动后固定物理失败", error.getMessage());
    }

    @Test
    void rollbackFailureStillRelocksTheBody() {
        List<String> calls = new ArrayList<>();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PauseService.moveWithConstraint(true, () -> calls.add("detach"),
                        new PauseService.MoveActions(() -> { throw new IllegalStateException("move"); },
                                () -> { calls.add("lock"); return true; },
                                () -> { calls.add("rollback"); throw new IllegalStateException("rollback"); })));

        assertEquals(List.of("detach", "rollback", "lock"), calls);
        assertEquals("rollback", error.getSuppressed()[0].getMessage());
    }

    @Test
    void teleportClearsVelocityBeforeUpdatingPoseAndBounds() {
        List<String> calls = new ArrayList<>();

        TeleportOps.finishMove(() -> calls.add("teleport"), () -> calls.add("resetVelocity"),
                () -> TeleportOps.updatePoseAndBounds(() -> calls.add("updateLastPose"),
                        () -> calls.add("updateBoundingBox"), () -> calls.add("syncLastBounds")));

        assertEquals(List.of("teleport", "resetVelocity", "updateLastPose", "updateBoundingBox",
                "syncLastBounds"), calls);
    }

    @Test
    void teleportCorrectsASettledBoundingBoxOffset() {
        AtomicReference<Vector3d> pose = new AtomicReference<>(new Vector3d(10, 20, 30));
        AtomicReference<Vector3d> anchor = new AtomicReference<>(new Vector3d(12, 20, 33));
        AtomicInteger moves = new AtomicInteger();
        Vector3d desired = new Vector3d(100, 80, 300);

        TeleportOps.alignBottomCenter(() -> new Vector3d(pose.get()), () -> new Vector3d(anchor.get()), target -> {
            pose.set(new Vector3d(target));
            if (moves.incrementAndGet() == 1) anchor.set(new Vector3d(100, 79.76, 300));
            else anchor.set(new Vector3d(desired));
        }, desired);

        assertEquals(2, moves.get());
        assertEquals(new Vector3d(98, 80.24, 297), pose.get());
    }

    @Test
    void teleportDiskVerificationUsesTheSamePositionTolerance() {
        assertTrue(TeleportOps.positionMatches(new double[]{10.05, 20.05, 30.05}, 10, 20, 30));
        assertFalse(TeleportOps.positionMatches(new double[]{10.11, 20, 30}, 10, 20, 30));
        assertFalse(TeleportOps.positionMatches(new double[]{10, 20}, 10, 20, 30));
    }

    @Test
    void teleportDiskVerificationFailureRunsTheMoveRollback() {
        List<String> calls = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> PauseService.moveWithConstraint(false,
                () -> calls.add("detach"), new PauseService.MoveActions(
                        () -> TeleportOps.finishPersistence(() -> calls.add("persist"), () -> {
                            calls.add("verify");
                            throw new IllegalStateException("disk mismatch");
                        }), () -> true, () -> calls.add("rollback"))));

        assertEquals(List.of("persist", "verify", "rollback"), calls);
    }

    @Test
    void cancelForceLoadReturnsTheGroupToDiskBeforeReportingSuccess() {
        List<String> calls = new ArrayList<>();

        TeleportOps.finishUnforce(new TeleportOps.UnforceActions(
                () -> calls.add("clearPanelState"), () -> calls.add("unload"),
                () -> calls.add("save"), () -> calls.add("removeTickets"),
                () -> calls.add("verify")));

        assertEquals(List.of("clearPanelState", "unload", "save", "removeTickets", "verify"), calls);

        calls.clear();
        assertThrows(IllegalStateException.class, () -> TeleportOps.finishUnforce(new TeleportOps.UnforceActions(
                () -> calls.add("clearPanelState"), () -> calls.add("unload"),
                () -> { calls.add("save"); throw new IllegalStateException("disk"); },
                () -> calls.add("removeTickets"), () -> calls.add("verify"))));
        assertEquals(List.of("clearPanelState", "unload", "save"), calls,
                "落盘失败时不能摘票并报告取消成功");
    }

    @Test
    void naturalReloadDoesNotMakeTicketRemovalFail() {
        // 取消常驻的复核只看常驻票:自然加载、暂停意图、冻结意图都不构成失败
        // (2026-08-22 起暂停/冻结意图在取消常驻时刻意保留,属独立功能)。
        assertEquals("loaded", TeleportOps.savedPointer(null, "loaded"),
                "saveAll 内自然重载后必须从 loaded body 捕获保存指针");
        assertEquals("holding", TeleportOps.savedPointer("holding", "loaded"));
    }

    @Test
    void cancelForceLoadAcceptsLoadedPointerAfterSaveAllNaturallyReloadsTheBody() {
        AtomicBoolean forced = new AtomicBoolean(true);
        AtomicReference<String> holdingPointer = new AtomicReference<>();
        AtomicReference<String> loadedPointer = new AtomicReference<>();
        AtomicReference<String> verifiedPointer = new AtomicReference<>();

        TeleportOps.finishUnforce(new TeleportOps.UnforceActions(
                () -> {},
                () -> holdingPointer.set("saved-slot"),
                () -> {
                    loadedPointer.set(holdingPointer.get());
                    holdingPointer.set(null);
                    verifiedPointer.set(TeleportOps.savedPointer(
                            holdingPointer.get(), loadedPointer.get()));
                },
                () -> forced.set(false),
                () -> assertFalse(forced.get(),
                        "取消常驻复核只看票;暂停/冻结意图保留不算失败")));

        assertEquals("saved-slot", verifiedPointer.get());
    }

    @Test
    void delayedRestoreDropsIntentsCancelledWhileItWasWaitingForTheOperationLock() {
        UUID cancelled = UUID.randomUUID();
        UUID retained = UUID.randomUUID();

        assertEquals(List.of(retained), TeleportOps.requestedRestoreCandidates(
                List.of(cancelled, retained, retained), Set.of(retained)));
        assertEquals(List.of(), TeleportOps.requestedRestoreCandidates(
                List.of(cancelled), Set.of()));
    }

    @Test
    void delayedRestoreFiltersAndPersistsInsideTheSameOperationLock() throws Exception {
        Object lock = new Object();
        UUID cancelled = UUID.randomUUID();
        AtomicBoolean restored = new AtomicBoolean();
        AtomicBoolean persisted = new AtomicBoolean();

        TeleportOps.restoreForcedIntents(lock, List.of(cancelled), Set::of,
                ignored -> restored.set(true), () -> {
                    assertTrue(Thread.holdsLock(lock), "持久化必须仍在共享操作锁内");
                    persisted.set(true);
                });

        assertFalse(restored.get(), "锁内复核已取消后不能重新挂票");
        assertTrue(persisted.get(), "即使过滤为空也必须持久化当前空意图");
    }

    @Test
    void backgroundRestoreContinuesWithIndependentGroupsAfterOneFails() {
        Object lock = new Object();
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        List<UUID> restored = new ArrayList<>();
        AtomicBoolean persisted = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () -> TeleportOps.restoreForcedIntentGroups(
                lock, List.of(List.of(broken), List.of(healthy)), () -> Set.of(broken, healthy), group -> {
                    if (group.contains(broken)) throw new IllegalStateException("ambiguous copies");
                    restored.addAll(group);
                }, () -> persisted.set(true)));

        assertEquals(List.of(healthy), restored);
        assertTrue(persisted.get());
    }

    @Test
    void cancelRollbackRestoresTheCapturedTagInsteadOfStaleDiskData() {
        CompoundTag captured = new CompoundTag();
        captured.putInt("state", 2);
        CompoundTag staleDisk = new CompoundTag();
        staleDisk.putInt("state", 1);
        AtomicReference<CompoundTag> loaded = new AtomicReference<>(staleDisk);

        CompoundTag restored = TeleportOps.restoreExactSnapshot(captured,
                tag -> { loaded.set(tag); return tag; }, CompoundTag::copy);

        assertEquals(captured, loaded.get());
        assertEquals(captured, restored);
    }

    @Test
    void unloadSnapshotTreatsDependencyOrderAsSet() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CompoundTag expected = snapshotWithDependencies(first, second);
        CompoundTag reordered = snapshotWithDependencies(second, first);

        assertDoesNotThrow(() -> TeleportOps.restoreExactSnapshot(
                expected, ignored -> reordered, CompoundTag::copy));
    }

    @Test
    void unloadAcceptsFreshStoredContentButRejectsIdentityOrDependencyChanges() {
        UUID body = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        CompoundTag stored = snapshotWithDependencies(dependency);
        stored.putUUID("uuid", body);
        stored.putString("plot", "new runtime content");

        assertTrue(TeleportOps.unloadedSnapshotMatches(body, List.of(body, dependency), stored));
        assertFalse(TeleportOps.unloadedSnapshotMatches(UUID.randomUUID(),
                List.of(body, dependency), stored));
        assertFalse(TeleportOps.unloadedSnapshotMatches(body,
                List.of(body, UUID.randomUUID()), stored));
    }

    @Test
    void storedEntryVerificationAllowsRuntimeChangesButRejectsWrongPlot() {
        UUID body = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        CompoundTag expected = storedEntry(body, dependency, 4, 7, 1);
        CompoundTag runtimeChanged = storedEntry(body, dependency, 4, 7, 99);

        assertTrue(TeleportOps.storedEntryMatches(expected, runtimeChanged));
        assertFalse(TeleportOps.storedEntryMatches(expected,
                storedEntry(body, dependency, 5, 7, 99)));
        assertFalse(TeleportOps.storedEntryMatches(expected,
                storedEntry(UUID.randomUUID(), dependency, 4, 7, 99)));
    }

    @Test
    void storedEntrySelectionRejectsOneMatchingAndOneConflictingCopy() {
        UUID body = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        CompoundTag expected = storedEntry(body, dependency, 4, 7, 1);
        DiskScanner.EntryKey first = new DiskScanner.EntryKey(
                "minecraft:overworld", 0, -7, 0, 1);
        DiskScanner.EntryKey second = new DiskScanner.EntryKey(
                "minecraft:overworld", 0, -7, 0, 2);

        assertThrows(IllegalStateException.class, () -> TeleportOps.selectStoredEntry(
                expected, Map.of(first, storedEntry(body, dependency, 4, 7, 2),
                        second, storedEntry(body, dependency, 5, 7, 3)), first));
        assertEquals(first, TeleportOps.selectStoredEntry(expected,
                Map.of(first, storedEntry(body, dependency, 4, 7, 2),
                        second, storedEntry(body, dependency, 4, 7, 3)), first));
    }

    @Test
    void rollbackVerificationRequiresTheFullRuntimeSnapshot() {
        CompoundTag expected = new CompoundTag();
        expected.putString("plot", "blocks");
        CompoundTag expectedPose = new CompoundTag();
        expectedPose.putDouble("x", 1);
        expected.put("pose", expectedPose);
        CompoundTag moved = expected.copy();
        moved.getCompound("pose").putDouble("x", 2);
        CompoundTag changed = moved.copy();
        changed.putString("plot", "different");

        assertThrows(IllegalStateException.class, () -> TeleportOps.restoreExactSnapshot(
                expected, ignored -> moved, CompoundTag::copy));
        assertThrows(IllegalStateException.class, () -> TeleportOps.restoreExactSnapshot(
                expected, ignored -> changed, CompoundTag::copy));
    }

    private static CompoundTag snapshotWithDependencies(UUID... dependencies) {
        CompoundTag tag = new CompoundTag();
        tag.putString("plot", "same");
        ListTag values = new ListTag();
        for (UUID dependency : dependencies) values.add(NbtUtils.createUUID(dependency));
        tag.put("loading_dependencies", values);
        return tag;
    }

    private static CompoundTag storedEntry(UUID uuid, UUID dependency,
                                           int plotX, int plotZ, int runtimeValue) {
        CompoundTag tag = snapshotWithDependencies(dependency);
        tag.putUUID("uuid", uuid);
        CompoundTag plot = new CompoundTag();
        plot.putInt("plot_x", plotX);
        plot.putInt("plot_z", plotZ);
        tag.put("plot", plot);
        CompoundTag pose = new CompoundTag();
        pose.putInt("runtime", runtimeValue);
        tag.put("pose", pose);
        return tag;
    }

    @Test
    void legacyPauseMigrationSkipsAStaleSnapshot() {
        AtomicInteger applied = new AtomicInteger();

        assertFalse(com.klnon.sablepanel.panel.compat.sable203.LegacyPauseMigration
                .applyIfUnchanged(4, () -> 5, applied::incrementAndGet));
        assertEquals(0, applied.get(), "用户恢复物理后不能用旧快照重新暂停整组");
        assertTrue(com.klnon.sablepanel.panel.compat.sable203.LegacyPauseMigration
                .applyIfUnchanged(5, () -> 5, applied::incrementAndGet));
        assertEquals(1, applied.get());
    }

    @Test
    void invalidBoundsCannotPassTeleportVerification() {
        assertThrows(IllegalStateException.class, () -> TeleportOps.bottomCenter(
                new BoundingBox3d(Double.NaN, 0, 0, 1, 1, 1)));
        assertThrows(IllegalStateException.class, () -> TeleportOps.bottomCenter(
                new BoundingBox3d().setUnchecked(2, 0, 0, 1, 1, 1)));
    }

}
