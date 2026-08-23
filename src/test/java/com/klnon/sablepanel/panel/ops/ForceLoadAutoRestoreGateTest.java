package com.klnon.sablepanel.panel.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForceLoadAutoRestoreGateTest {
    @AfterEach
    void reset() {
        ForceLoadService.reset();
    }

    @Test
    void onlyRequestedPermanentRemovalBlocksBackgroundRestore() {
        UUID requested = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();

        assertFalse(ForceLoadService.blockAutoRestoreAfterRemoval(unrelated, Set.of(requested)));
        assertTrue(ForceLoadService.autoRestoreAllowed(unrelated));
        assertTrue(ForceLoadService.blockAutoRestoreAfterRemoval(requested, Set.of(requested)));
        assertFalse(ForceLoadService.autoRestoreAllowed(requested));
    }

    @Test
    void explicitComponentTemporarilyTakesBlockAndFailureCanRestoreIt() {
        UUID blocked = UUID.randomUUID();
        ForceLoadService.restoreAutoRestoreBlocks(Set.of(blocked));

        assertEquals(Set.of(blocked), ForceLoadService.takeAutoRestoreBlocks(Set.of(blocked)));
        assertTrue(ForceLoadService.autoRestoreAllowed(blocked));

        ForceLoadService.restoreAutoRestoreBlocks(Set.of(blocked));
        assertFalse(ForceLoadService.autoRestoreAllowed(blocked));
    }

    @Test
    void oneBlockedMemberSuppressesTheWholeDependencyGroup() {
        UUID blocked = UUID.randomUUID();
        UUID sibling = UUID.randomUUID();
        UUID allowed = UUID.randomUUID();
        ForceLoadService.restoreAutoRestoreBlocks(Set.of(blocked));

        assertFalse(TeleportOps.autoRestoreGroupAllowed(
                Set.of(blocked, sibling), ForceLoadService::autoRestoreAllowed));
        assertTrue(TeleportOps.autoRestoreGroupAllowed(
                Set.of(allowed), ForceLoadService::autoRestoreAllowed));
    }

    @Test
    void restoreChecksTheGateAfterEnteringTheOperationLock() throws Exception {
        Object lock = new Object();
        UUID blocked = UUID.randomUUID();
        List<List<UUID>> groupsPreparedBeforeRemoval = List.of(List.of(blocked));
        AtomicBoolean restored = new AtomicBoolean();
        ForceLoadService.restoreAutoRestoreBlocks(Set.of(blocked));

        TeleportOps.restoreForcedIntentGroups(lock, groupsPreparedBeforeRemoval,
                () -> Set.of(blocked), current -> {
                    assertTrue(Thread.holdsLock(lock));
                    if (TeleportOps.autoRestoreGroupAllowed(
                            current, ForceLoadService::autoRestoreAllowed)) restored.set(true);
                }, () -> { });

        assertFalse(restored.get());
    }
}
