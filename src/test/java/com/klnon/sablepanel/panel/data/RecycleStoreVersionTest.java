package com.klnon.sablepanel.panel.data;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.PanelConfig;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecycleStoreVersionTest {

    @TempDir
    Path root;

    @Test
    void newerCommitMarksTheOverlappingGroupOld() throws Exception {
        RecycleStore store = store();
        UUID uuid = UUID.randomUUID();

        String older = commit(store, uuid, 0);
        String newer = commit(store, uuid, 1);

        assertEquals(List.of(newer), ids(store.view("latest", "", 10)));
        assertEquals(List.of(older), ids(store.view("old", "", 10)));
        assertFalse(store.loadGroup(newer).oldVersion());
        assertTrue(store.loadGroup(older).oldVersion());
    }

    @Test
    void oneOverlappingMemberMakesTheWholeDependencyGroupOld() throws Exception {
        RecycleStore store = store();
        UUID shared = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();

        String older = commitGroup(store, 0, shared, dependency);
        String newer = commit(store, shared, 2);

        assertEquals(List.of(newer), ids(store.view("latest", "", 10)));
        assertEquals(List.of(older), ids(store.view("old", "", 10)));
        assertEquals(2, store.loadGroup(older).bodies().size());
    }

    @Test
    void incompleteVersionMarkerTransactionRemainsCorrectAcrossRestart() throws Exception {
        RecycleStore store = store();
        UUID uuid = UUID.randomUUID();
        String older = commit(store, uuid, 0);
        Path blockedMarker = this.root.resolve(older).resolve(".old-version");
        Files.createDirectory(blockedMarker);

        String newer = commit(store, uuid, 1);

        assertEquals(List.of(newer), ids(store.view("latest", "", 10)));
        assertEquals(List.of(older), ids(store.view("old", "", 10)));
        assertTrue(Files.isRegularFile(this.root.resolve(newer).resolve(".supersedes")));

        RecycleStore stillBlocked = store();
        assertEquals(List.of(newer), ids(stillBlocked.view("latest", "", 10)));
        assertEquals(List.of(older), ids(stillBlocked.view("old", "", 10)));
        JsonObject blockedPurge = stillBlocked.purgeGroups(List.of(newer));
        assertEquals(0, blockedPurge.get("ok").getAsInt());
        assertTrue(Files.isDirectory(this.root.resolve(newer)));

        Files.delete(blockedMarker);
        RecycleStore recovered = store();
        assertTrue(Files.isRegularFile(this.root.resolve(older).resolve(".old-version")));
        assertFalse(Files.exists(this.root.resolve(newer).resolve(".supersedes")));
        assertEquals(List.of(newer), ids(recovered.view("latest", "", 10)));
        assertEquals(List.of(older), ids(recovered.view("old", "", 10)));
    }

    @Test
    void interruptedStageBecomesLatestWhenItActuallyEntersRecycleBin() throws Exception {
        RecycleStore running = store();
        UUID uuid = UUID.randomUUID();
        String committed = commit(running, uuid, 0);
        RecycleStore.Stage interrupted = running.stage(List.of(source(uuid, 1)), Map.of());
        Path manifest = this.root.resolve(".pending").resolve(interrupted.id()).resolve("manifest.json");
        Files.writeString(manifest, Files.readString(manifest, StandardCharsets.UTF_8)
                .replace("\"state\": \"pending\"", "\"state\": \"deleted\""), StandardCharsets.UTF_8);

        RecycleStore restarted = store();

        assertEquals(List.of(interrupted.id()), ids(restarted.view("latest", "", 10)));
        assertEquals(List.of(committed), ids(restarted.view("old", "", 10)));
        assertEquals("recovery_required", restarted.loadGroup(interrupted.id()).state());
    }

    @Test
    void currentGroupsReportVersionCountsAndActualDiskBytes() throws Exception {
        UUID shared = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        RecycleStore store = store();
        String older = commitGroup(store, 0, shared, dependency);
        String newer = commit(store, shared, 2);
        store.stage(List.of(source(UUID.randomUUID(), 9)), Map.of());
        JsonObject latest = store.view("latest", "", 10);
        JsonObject old = store.view("old", "", 10);

        assertEquals(List.of(newer), ids(latest));
        assertEquals(List.of(older), ids(old));
        assertEquals(1, latest.get("latest_groups").getAsInt());
        assertEquals(1, latest.get("old_groups").getAsInt());
        assertEquals(3, latest.get("file_count").getAsInt());
        assertEquals(actualBytes(), latest.get("disk_bytes").getAsLong());
    }

    @Test
    void purgingLatestNeverPromotesAnOldVersionEvenAfterRestart() throws Exception {
        UUID uuid = UUID.randomUUID();
        RecycleStore store = store();
        String older = commit(store, uuid, 0);
        String newer = commit(store, uuid, 1);

        JsonObject result = store.purgeGroups(List.of(newer));

        assertEquals(1, result.get("ok").getAsInt());
        assertEquals(1, result.get("total").getAsInt());
        assertFalse(Files.exists(this.root.resolve(newer)));
        RecycleStore restarted = store();
        assertTrue(ids(restarted.view("latest", "", 10)).isEmpty());
        assertEquals(List.of(older), ids(restarted.view("old", "", 10)));
    }

    @Test
    void purgeContinuesAfterAnInvalidGroup() throws Exception {
        RecycleStore store = store();
        String valid = commit(store, UUID.randomUUID(), 0);

        JsonObject result = store.purgeGroups(List.of("missing-group", valid));

        assertEquals(1, result.get("ok").getAsInt());
        assertEquals(2, result.get("total").getAsInt());
        assertFalse(Files.exists(this.root.resolve(valid)));
        assertFalse(result.getAsJsonArray("results").get(0).getAsJsonObject().get("ok").getAsBoolean());
        assertTrue(result.getAsJsonArray("results").get(1).getAsJsonObject().get("ok").getAsBoolean());
    }

    @Test
    void operationalStateAndSourceEntryRoundTrip() throws Exception {
        RecycleStore store = store();
        UUID uuid = UUID.randomUUID();
        RecycleStore.Source source = source(uuid, 7);

        String id = store.commit(store.stage(List.of(source), Map.of(
                uuid, new RecycleStore.OperationalState(true, true))));
        RecycleStore.RestoreBody body = store.loadGroup(id).bodies().get(0);

        assertEquals(source.key().id(), body.sourceEntry());
        assertTrue(body.paused());
        assertTrue(body.forced());
    }

    @Test
    void incompleteArchiveIsOldAndNeverBecomesLatest() throws Exception {
        RecycleStore store = store();
        UUID uuid = UUID.randomUUID();

        String id = store.commitIncomplete(store.stageArchived(List.of(source(uuid, 4)),
                Map.of(), "incomplete"));

        assertTrue(ids(store.view("latest", "", 10)).isEmpty());
        assertEquals(List.of(id), ids(store.view("old", "", 10)));
        assertEquals("incomplete", store.loadGroup(id).state());
        assertTrue(store.loadGroup(id).oldVersion());
    }

    @Test
    void interruptedArchivedStagesRecoverOnlyToTheOldTab() throws Exception {
        RecycleStore running = store();
        RecycleStore.Stage complete = running.stageArchived(
                List.of(source(UUID.randomUUID(), 4)), Map.of(), "deleted");
        RecycleStore.Stage incomplete = running.stageArchived(
                List.of(source(UUID.randomUUID(), 5)), Map.of(), "incomplete");

        RecycleStore restarted = store();

        assertTrue(ids(restarted.view("latest", "", 10)).isEmpty());
        assertEquals(Set.of(complete.id(), incomplete.id()), Set.copyOf(ids(restarted.view("old", "", 10))));
        assertEquals("deleted", restarted.loadGroup(complete.id()).state());
        assertEquals("incomplete", restarted.loadGroup(incomplete.id()).state());
    }

    @Test
    void pendingStagesReserveRecycleCapacity() throws Exception {
        PanelConfig config = new PanelConfig();
        config.recycleMaxFiles = 1;
        RecycleStore store = new RecycleStore(config, this.root);
        store.stage(List.of(source(UUID.randomUUID(), 0)), Map.of());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> store.stage(List.of(source(UUID.randomUUID(), 1)), Map.of()));
    }

    private RecycleStore store() {
        return new RecycleStore(new PanelConfig(), this.root);
    }

    private static String commit(RecycleStore store, UUID uuid, int slot) throws Exception {
        return store.commit(store.stage(List.of(source(uuid, slot)), Map.of()));
    }

    private static String commitGroup(RecycleStore store, int firstSlot, UUID... uuids) throws Exception {
        List<RecycleStore.Source> sources = new ArrayList<>();
        for (int index = 0; index < uuids.length; index++) {
            sources.add(source(uuids[index], firstSlot + index));
        }
        return store.commit(store.stage(sources, Map.of()));
    }

    private static RecycleStore.Source source(UUID uuid, int slot) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", uuid);
        return new RecycleStore.Source(uuid, RecycleStore.DEFAULT_DIMENSION,
                new DiskScanner.EntryKey(RecycleStore.DEFAULT_DIMENSION, 0, 0, 0, slot), tag);
    }

    private static List<String> ids(JsonObject page) {
        List<String> result = new ArrayList<>();
        for (var element : page.getAsJsonArray("groups")) {
            result.add(element.getAsJsonObject().get("id").getAsString());
        }
        return result;
    }

    private long actualBytes() throws Exception {
        try (var paths = Files.walk(this.root)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }).sum();
        }
    }
}
