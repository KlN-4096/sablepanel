package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.panel.storage.DiskScanner;
import com.klnon.sablepanel.panel.recycle.RecycleStore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void coldOperationalGateRejectsATargetLoadedAfterPreparation() {
        UUID other = UUID.randomUUID();

        assertDoesNotThrow(() -> DeleteTx.requireColdTargetsUnloaded(
                Set.of(this.target, other), ignored -> false));
        assertThrows(IllegalStateException.class, () -> DeleteTx.requireColdTargetsUnloaded(
                Set.of(this.target, other), uuid -> uuid.equals(other)));
    }

    @Test
    void diskGateCanTrackAnExternalHistoricalMemberWithoutDeletingIt() {
        UUID external = UUID.randomUUID();
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", this.target);
        Map<UUID, Map<DiskScanner.EntryKey, CompoundTag>> entries =
                Map.of(this.target, Map.of(this.key, tag));
        DeleteTx.DiskSnapshot snapshot = new DeleteTx.DiskSnapshot(
                Set.of(this.target, external), entries, Map.of());

        assertDoesNotThrow(() -> DeleteTx.requireUnchangedDiskSnapshot(snapshot, snapshot));
        DeleteTx.DiskSnapshot externalDisappeared = new DeleteTx.DiskSnapshot(
                Set.of(this.target), entries, Map.of());
        assertThrows(IllegalStateException.class,
                () -> DeleteTx.requireUnchangedDiskSnapshot(snapshot, externalDisappeared));
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

    @Test
    void everyKnownHoldingPointerIsQueuedEvenAfterRemovingTheLoadedBody() {
        DiskScanner.LiveLocation pointer = new DiskScanner.LiveLocation(this.key, 17, -9);
        DeleteTx.DeleteCopy copy = new DeleteTx.DeleteCopy(this.key, new CompoundTag(), 1,
                List.of(pointer, pointer));

        assertEquals(List.of(DeleteTx.toPointer(pointer), DeleteTx.toPointer(pointer)),
                DeleteTx.deletionPointers(List.of(copy)));
    }

    @Test
    void finalVerificationRepairsOnlyPointersWhoseDeletedEntryIsAlreadyEmpty() {
        DeleteTx.DeleteStatus removed = new DeleteTx.DeleteStatus(this.target);
        removed.removed = true;
        removed.entryKeys.add(this.key);
        Map<UUID, DeleteTx.DeleteStatus> statuses = Map.of(this.target, removed);

        assertTrue(DeleteTx.hasDanglingDeletedPointers(
                new DeleteTx.DiskVerification(Map.of(this.target, 0), Map.of(this.key, 1)), statuses));
        org.junit.jupiter.api.Assertions.assertFalse(DeleteTx.hasDanglingDeletedPointers(
                new DeleteTx.DiskVerification(Map.of(this.target, 1), Map.of(this.key, 1)), statuses));
        org.junit.jupiter.api.Assertions.assertFalse(DeleteTx.hasDanglingDeletedPointers(
                new DeleteTx.DiskVerification(Map.of(this.target, 0), Map.of()), statuses));
    }

    @Test
    void danglingPointerCleanupRechecksSlotOwnershipAndExactPointerCounts() {
        DiskScanner.LiveLocation first = new DiskScanner.LiveLocation(this.key, 17, -9);
        DiskScanner.LiveLocation second = new DiskScanner.LiveLocation(this.key, 18, -9);
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> expected =
                Map.of(this.key, List.of(first, first, second));

        assertDoesNotThrow(() -> DeleteTx.requirePointerCleanupSnapshot(
                Set.of(this.key), Set.of(), expected, Map.of(this.key, List.of(second, first, first))));
        assertThrows(IllegalStateException.class, () -> DeleteTx.requirePointerCleanupSnapshot(
                Set.of(this.key), Set.of(this.key), expected, expected));
        assertThrows(IllegalStateException.class, () -> DeleteTx.requirePointerCleanupSnapshot(
                Set.of(this.key), Set.of(), expected, Map.of(this.key, List.of(first, second))));
    }

    @Test
    void deletionDoesNotClaimPointersAfterTheStorageSlotWasReused() {
        UUID replacement = UUID.randomUUID();
        Map<DiskScanner.EntryKey, Integer> pointers = Map.of(this.key, 1);
        Map<DiskScanner.EntryKey, UUID> original = Map.of(this.key, this.target);

        assertEquals(Map.of(this.key, 1), DeleteTx.targetPointerCounts(pointers, original, Map.of()));
        assertEquals(Map.of(this.key, 1), DeleteTx.targetPointerCounts(
                pointers, original, Map.of(this.key, this.target)));
        assertEquals(Map.of(), DeleteTx.targetPointerCounts(
                pointers, original, Map.of(this.key, replacement)));
    }

    @Test
    void detachedCleanupPrunesOnlyDeletedDependenciesWithoutMutatingSource() {
        UUID kept = UUID.randomUUID();
        CompoundTag source = new CompoundTag();
        ListTag dependencies = new ListTag();
        dependencies.add(NbtUtils.createUUID(this.target));
        dependencies.add(NbtUtils.createUUID(kept));
        source.put("loading_dependencies", dependencies);

        CompoundTag pruned = DeleteTx.pruneDependencies(source, Set.of(this.target));

        assertEquals(List.of(kept), DiskScanner.dependencies(pruned));
        assertEquals(List.of(this.target, kept), DiskScanner.dependencies(source));
        assertNull(DeleteTx.pruneDependencies(source, Set.of(UUID.randomUUID())));
    }

    @Test
    void partialCleanupBuildsDependencyStateFromSuccessfulTargetsOnly() {
        UUID failed = UUID.randomUUID();
        CompoundTag source = new CompoundTag();
        source.putUUID("uuid", UUID.randomUUID());
        ListTag dependencies = new ListTag();
        dependencies.add(NbtUtils.createUUID(this.target));
        dependencies.add(NbtUtils.createUUID(failed));
        source.put("loading_dependencies", dependencies);
        DeleteTx.DependencyRewrite base = new DeleteTx.DependencyRewrite(source.getUUID("uuid"), this.key,
                source, CopyOps.retainDependencies(source, Set.of()), null);

        DeleteTx.DependencyRewrite state = DeleteTx.dependencyState(List.of(base), Set.of(this.target)).get(0);

        assertEquals(List.of(failed), DiskScanner.dependencies(state.updated()));
    }

    @Test
    void partialDeleteKeepsOnlyAbsentTargetsInDependencyState() {
        UUID failed = UUID.randomUUID();
        DeleteTx.DeleteStatus deleted = new DeleteTx.DeleteStatus(this.target);
        deleted.removed = true;
        deleted.ok = true;
        DeleteTx.DeleteStatus untouched = new DeleteTx.DeleteStatus(failed);
        untouched.fail("delete failed");

        assertEquals(Set.of(this.target), DeleteOps.dependencyTargets(
                Map.of(this.target, deleted, failed, untouched), Set.of()));

        deleted.ok = false;
        assertEquals(Set.of(this.target), DeleteOps.dependencyTargets(
                Map.of(this.target, deleted), Set.of(this.target)));
        deleted.restored = true;
        assertEquals(Set.of(), DeleteOps.dependencyTargets(
                Map.of(this.target, deleted), Set.of(this.target)));
    }

    @Test
    void survivorDependenciesAdvanceAsSoonAsSableExecutedTheDelete() {
        UUID untouchedUuid = UUID.randomUUID();
        DeleteTx.DeleteStatus removed = new DeleteTx.DeleteStatus(this.target);
        removed.removed = true;
        DeleteTx.DeleteStatus absent = new DeleteTx.DeleteStatus(UUID.randomUUID());
        absent.alreadyAbsent = true;
        DeleteTx.DeleteStatus untouched = new DeleteTx.DeleteStatus(untouchedUuid);

        assertEquals(Set.of(this.target, absent.uuid), DeleteOps.executedDeleteTargets(
                Map.of(this.target, removed, absent.uuid, absent, untouchedUuid, untouched)));
    }

    @Test
    void invalidHoldingClosureIsRemovedBeforeSableSaveAll() {
        UUID missing = UUID.randomUUID();
        UUID direct = UUID.randomUUID();
        UUID transitive = UUID.randomUUID();
        UUID validA = UUID.randomUUID();
        UUID validB = UUID.randomUUID();
        Map<UUID, List<UUID>> dependencies = Map.of(
                direct, List.of(missing),
                transitive, List.of(direct),
                validA, List.of(validB),
                validB, List.of(validA));

        assertEquals(Set.of(direct, transitive), DeleteTx.invalidHoldingRecords(dependencies));
    }

    @Test
    void dependencyWriteVerificationFailureRestoresPreviousState() throws Exception {
        AtomicReference<String> state = new AtomicReference<>("before");

        assertThrows(IllegalStateException.class,
                () -> DeleteTx.applyRewriteTransaction(new DeleteTx.RewriteActions(
                        () -> assertEquals("before", state.get()),
                        () -> state.set("after"),
                        () -> { throw new IllegalStateException("verify"); },
                        () -> {
                            state.set("before");
                            assertEquals("before", state.get());
                        })));

        assertEquals("before", state.get());
    }

    @Test
    void dependencyRewriteRebindsMovedSlotAndPreservesCurrentRuntimeState() {
        UUID survivor = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        DiskScanner.EntryKey movedKey = new DiskScanner.EntryKey(
                "minecraft:overworld", 0, -7, 0, 9);
        CompoundTag preparedTag = dependencyTag(survivor, 1, this.target, kept);
        CompoundTag currentTag = dependencyTag(survivor, 99, kept, this.target);
        DeleteTx.DependencyRewrite prepared = new DeleteTx.DependencyRewrite(
                survivor, this.key, preparedTag, preparedTag, null);
        DiskScanner.LiveLocation pointer = new DiskScanner.LiveLocation(movedKey, 19, -213);
        DeleteTx.DeleteCopy current = new DeleteTx.DeleteCopy(
                movedKey, currentTag, 1, List.of(pointer));

        DeleteTx.DependencyTransition transition = DeleteTx.rebaseDependencyTransition(
                List.of(prepared), Map.of(survivor, List.of(current)),
                Set.of(), Set.of(this.target));

        assertEquals(movedKey, transition.before().get(0).key());
        assertEquals(currentTag, transition.before().get(0).updated());
        assertEquals(99, transition.after().get(0).updated().getCompound("pose").getInt("x"));
        assertEquals(List.of(kept), DiskScanner.dependencies(transition.after().get(0).updated()));
        assertEquals(List.of(kept, this.target), DiskScanner.dependencies(currentTag));
        assertEquals(currentTag, DeleteOps.dependencyBackupSources(transition.before()).get(0).tag());
    }

    @Test
    void forwardDependencyRewritePreservesOtherFreshDependencyChanges() {
        UUID survivor = UUID.randomUUID();
        UUID unexpected = UUID.randomUUID();
        CompoundTag preparedTag = dependencyTag(survivor, 1, this.target);
        CompoundTag changed = dependencyTag(survivor, 2, this.target, unexpected);
        DeleteTx.DependencyRewrite prepared = new DeleteTx.DependencyRewrite(
                survivor, this.key, preparedTag, preparedTag, null);
        DeleteTx.DeleteCopy current = new DeleteTx.DeleteCopy(this.key, changed, 1, List.of());

        DeleteTx.DependencyTransition transition = DeleteTx.rebaseDependencyTransition(
                List.of(prepared), Map.of(survivor, List.of(current)),
                Set.of(), Set.of(this.target));

        assertEquals(List.of(unexpected), DiskScanner.dependencies(transition.after().get(0).updated()));
        assertEquals(2, transition.after().get(0).updated().getCompound("pose").getInt("x"));
    }

    @Test
    void rollbackDependencyRewriteRejectsUnrelatedFreshDependencyChanges() {
        UUID survivor = UUID.randomUUID();
        UUID unexpected = UUID.randomUUID();
        CompoundTag preparedTag = dependencyTag(survivor, 1, this.target);
        CompoundTag changed = dependencyTag(survivor, 2, unexpected);
        DeleteTx.DependencyRewrite prepared = new DeleteTx.DependencyRewrite(
                survivor, this.key, preparedTag, preparedTag, null);
        DeleteTx.DeleteCopy current = new DeleteTx.DeleteCopy(this.key, changed, 1, List.of());

        assertThrows(IllegalStateException.class, () -> DeleteTx.rebaseDependencyTransition(
                List.of(prepared), Map.of(survivor, List.of(current)),
                Set.of(this.target), Set.of()));
    }

    @Test
    void dependencyRewriteAcceptsSaveAllAlreadyApplyingDesiredState() {
        UUID survivor = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        DiskScanner.EntryKey movedKey = new DiskScanner.EntryKey(
                "minecraft:overworld", 0, -7, 0, 9);
        CompoundTag preparedTag = dependencyTag(survivor, 1, this.target, kept);
        CompoundTag alreadyPruned = dependencyTag(survivor, 99, kept);
        DeleteTx.DependencyRewrite prepared = new DeleteTx.DependencyRewrite(
                survivor, this.key, preparedTag, preparedTag, null);
        DeleteTx.DeleteCopy current = new DeleteTx.DeleteCopy(
                movedKey, alreadyPruned, 1, List.of());

        DeleteTx.DependencyTransition transition = DeleteTx.rebaseDependencyTransition(
                List.of(prepared), Map.of(survivor, List.of(current)),
                Set.of(), Set.of(this.target));

        assertTrue(transition.before().isEmpty());
        assertTrue(transition.after().isEmpty());
        assertEquals(99, alreadyPruned.getCompound("pose").getInt("x"));
    }

    @Test
    void dependencyRewriteAcceptsAnAdditionalFreshCopyAlreadyAtTheDesiredState() {
        UUID survivor = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        CompoundTag preparedTag = dependencyTag(survivor, 1, this.target, kept);
        DeleteTx.DependencyRewrite prepared = new DeleteTx.DependencyRewrite(
                survivor, this.key, preparedTag, preparedTag, null);
        DeleteTx.DeleteCopy first = new DeleteTx.DeleteCopy(this.key,
                dependencyTag(survivor, 2, this.target, kept), 1, List.of());
        DiskScanner.EntryKey extraKey = new DiskScanner.EntryKey(
                "minecraft:overworld", 0, -7, 0, 10);
        DeleteTx.DeleteCopy extra = new DeleteTx.DeleteCopy(extraKey,
                dependencyTag(survivor, 3, kept), 1, List.of());

        DeleteTx.DependencyTransition transition = DeleteTx.rebaseDependencyTransition(
                List.of(prepared), Map.of(survivor, List.of(first, extra)),
                Set.of(), Set.of(this.target));

        assertEquals(List.of(this.key), transition.before().stream().map(DeleteTx.DependencyRewrite::key).toList());
        assertEquals(List.of(kept), DiskScanner.dependencies(transition.after().get(0).updated()));
    }

    @Test
    void dependencyRewriteConsumesSurvivorWhoseTargetWasNotDeleted() {
        UUID failedTarget = UUID.randomUUID();
        UUID changedSurvivor = UUID.randomUUID();
        UUID unchangedSurvivor = UUID.randomUUID();
        CompoundTag changedTag = dependencyTag(changedSurvivor, 1, this.target);
        CompoundTag unchangedTag = dependencyTag(unchangedSurvivor, 2, failedTarget);
        DiskScanner.EntryKey unchangedKey = new DiskScanner.EntryKey(
                "minecraft:overworld", 0, -7, 0, 11);
        DeleteTx.DependencyRewrite changed = new DeleteTx.DependencyRewrite(
                changedSurvivor, this.key, changedTag, changedTag, null);
        DeleteTx.DependencyRewrite unchanged = new DeleteTx.DependencyRewrite(
                unchangedSurvivor, unchangedKey, unchangedTag, unchangedTag, null);

        DeleteTx.DependencyTransition transition = DeleteTx.rebaseDependencyTransition(
                List.of(changed, unchanged),
                Map.of(changedSurvivor, List.of(new DeleteTx.DeleteCopy(
                                this.key, changedTag, 1, List.of())),
                        unchangedSurvivor, List.of(new DeleteTx.DeleteCopy(
                                unchangedKey, unchangedTag, 1, List.of()))),
                Set.of(), Set.of(this.target));

        assertEquals(1, transition.before().size());
        assertEquals(changedSurvivor, transition.before().get(0).uuid());
    }

    @Test
    void dependencyRollbackFailureIsPreservedForRecoveryMaterial() {
        List<String> calls = new java.util.ArrayList<>();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DeleteTx.applyRewriteTransaction(new DeleteTx.RewriteActions(
                        () -> calls.add("verifyBefore"), () -> calls.add("writeAfter"),
                        () -> { calls.add("verifyAfter"); throw new IllegalStateException("verify"); },
                        () -> { calls.add("rollback"); throw new IllegalStateException("rollback"); })));

        assertEquals(List.of("verifyBefore", "writeAfter", "verifyAfter", "rollback"), calls);
        assertEquals("rollback", error.getSuppressed()[0].getMessage());
        assertTrue(DeleteOps.dependencyRecoveryRequired(Set.of(), error));
    }

    private DeleteTx.DiskSnapshot disk(CompoundTag tag, List<DiskScanner.LiveLocation> pointers) {
        return new DeleteTx.DiskSnapshot(Set.of(this.target), Map.of(this.target, Map.of(this.key, tag)),
                Map.of(this.key, pointers));
    }

    private DeleteTx.OperationalSnapshot operational(String active, boolean paused) {
        return new DeleteTx.OperationalSnapshot(Map.of(this.target, active),
                Map.of(this.target, new RecycleStore.OperationalState(paused, false, false)));
    }

    private static CompoundTag dependencyTag(UUID uuid, int poseX, UUID... dependencies) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", uuid);
        tag.putString("plot", "same");
        CompoundTag pose = new CompoundTag();
        pose.putInt("x", poseX);
        tag.put("pose", pose);
        ListTag values = new ListTag();
        for (UUID dependency : dependencies) values.add(NbtUtils.createUUID(dependency));
        tag.put("loading_dependencies", values);
        return tag;
    }
}
