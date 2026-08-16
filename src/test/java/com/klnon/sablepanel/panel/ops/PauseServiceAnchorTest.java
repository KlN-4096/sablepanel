package com.klnon.sablepanel.panel.ops;

import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.core.BlockPos;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PauseServiceAnchorTest {
    @AfterEach
    void clearHandles() {
        PauseService.reset();
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
    void teleportFinishesInVelocityPoseAnchorOrderAndPropagatesFailure() {
        List<String> calls = new ArrayList<>();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> TeleportOps.finishTeleport(
                () -> calls.add("teleport"),
                () -> calls.add("resetVelocity"),
                () -> calls.add("updateLastPose"),
                () -> { calls.add("reanchor"); throw new IllegalStateException("rejected"); }));

        assertEquals(List.of("teleport", "resetVelocity", "updateLastPose", "reanchor"), calls);
        assertEquals("rejected", error.getMessage());
    }

}
