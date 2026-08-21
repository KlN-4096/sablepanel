package com.klnon.sablepanel.panel.ops;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PauseServiceAnchorTest {
    @AfterEach
    void clearHandles() {
        PauseService.reset();
    }

    @Test
    void onlyExplicitPhysicsPauseRequiresAConstraint() {
        assertTrue(PauseService.holdRequired(true, false, false));
        assertFalse(PauseService.holdRequired(false, true, false));
        assertFalse(PauseService.holdRequired(false, false, true));
        assertFalse(PauseService.holdRequired(false, false, false));
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
        assertTrue(TeleportOps.unforceStateValid(true, false, false, false),
                "玩家自然加载不等于面板仍在常驻");
        assertFalse(TeleportOps.unforceStateValid(false, true, false, false));
        assertFalse(TeleportOps.unforceStateValid(false, false, true, false));
        assertFalse(TeleportOps.unforceStateValid(false, false, false, true));
        assertEquals("loaded", TeleportOps.savedPointer(null, "loaded"),
                "saveAll 内自然重载后必须从 loaded body 捕获保存指针");
        assertEquals("holding", TeleportOps.savedPointer("holding", "loaded"));
    }

    @Test
    void cancelForceLoadAcceptsLoadedPointerAfterSaveAllNaturallyReloadsTheBody() {
        AtomicBoolean paused = new AtomicBoolean(true);
        AtomicBoolean frozen = new AtomicBoolean(true);
        AtomicBoolean forced = new AtomicBoolean(true);
        AtomicReference<String> holdingPointer = new AtomicReference<>();
        AtomicReference<String> loadedPointer = new AtomicReference<>();
        AtomicReference<String> verifiedPointer = new AtomicReference<>();

        TeleportOps.finishUnforce(new TeleportOps.UnforceActions(
                () -> { paused.set(false); frozen.set(false); },
                () -> holdingPointer.set("saved-slot"),
                () -> {
                    loadedPointer.set(holdingPointer.get());
                    holdingPointer.set(null);
                    verifiedPointer.set(TeleportOps.savedPointer(
                            holdingPointer.get(), loadedPointer.get()));
                },
                () -> forced.set(false),
                () -> assertTrue(TeleportOps.unforceStateValid(
                        loadedPointer.get() != null, forced.get(), paused.get(), frozen.get()))));

        assertEquals("saved-slot", verifiedPointer.get());
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

    @Test
    void legacyPauseMigrationSkipsAStaleSnapshot() {
        AtomicInteger applied = new AtomicInteger();

        assertFalse(TeleportOps.applyMigrationIfUnchanged(4, () -> 5, applied::incrementAndGet));
        assertEquals(0, applied.get(), "用户恢复物理后不能用旧快照重新暂停整组");
        assertTrue(TeleportOps.applyMigrationIfUnchanged(5, () -> 5, applied::incrementAndGet));
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
