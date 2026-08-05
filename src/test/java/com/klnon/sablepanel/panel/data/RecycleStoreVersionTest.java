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
        RecycleStore.Stage interrupted = running.stage(List.of(source(uuid, 1)));
        Path manifest = this.root.resolve(".pending").resolve(interrupted.id()).resolve("manifest.json");
        Files.writeString(manifest, Files.readString(manifest, StandardCharsets.UTF_8)
                .replace("\"state\": \"pending\"", "\"state\": \"deleted\""), StandardCharsets.UTF_8);

        RecycleStore restarted = store();

        assertEquals(List.of(interrupted.id()), ids(restarted.view("latest", "", 10)));
        assertEquals(List.of(committed), ids(restarted.view("old", "", 10)));
        assertEquals("recovery_required", restarted.loadGroup(interrupted.id()).state());
    }

    @Test
    void isolatedMigrationClassifiesExistingGroupsAndReportsActualDiskBytes() throws Exception {
        UUID shared = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        String older = writeLegacyGroup("20260101-000001-000", "recovery_required", shared, dependency);
        String newer = writeLegacyGroup("20260101-000002-000", "restored", shared);

        RecycleStore store = store();
        store.stage(List.of(source(UUID.randomUUID(), 9)));
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
        String older = writeLegacyGroup("20260101-000001-000", "restored", uuid);
        String newer = writeLegacyGroup("20260101-000002-000", "restored", uuid);
        RecycleStore store = store();

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
        String valid = writeLegacyGroup("20260101-000001-000", "deleted", UUID.randomUUID());
        RecycleStore store = store();

        JsonObject result = store.purgeGroups(List.of("missing-group", valid));

        assertEquals(1, result.get("ok").getAsInt());
        assertEquals(2, result.get("total").getAsInt());
        assertFalse(Files.exists(this.root.resolve(valid)));
        assertFalse(result.getAsJsonArray("results").get(0).getAsJsonObject().get("ok").getAsBoolean());
        assertTrue(result.getAsJsonArray("results").get(1).getAsJsonObject().get("ok").getAsBoolean());
    }

    @Test
    void retentionMayRemoveTheOldTargetWithoutLosingTheNewOwnersTransaction() throws Exception {
        PanelConfig config = new PanelConfig();
        config.recycleMaxFiles = 10;
        RecycleStore store = new RecycleStore(config, this.root);
        UUID uuid = UUID.randomUUID();
        String older = commit(store, uuid, 0);
        Files.createDirectory(this.root.resolve(older).resolve(".old-version"));
        String newer = commit(store, uuid, 1);
        setDeletedAt(older, 1);
        setDeletedAt(newer, 2);

        config.recycleMaxFiles = 1;
        store.prune();

        assertFalse(Files.exists(this.root.resolve(older)));
        assertTrue(Files.isRegularFile(this.root.resolve(newer).resolve(".supersedes")));
        RecycleStore restarted = new RecycleStore(config, this.root);
        assertEquals(List.of(newer), ids(restarted.view("latest", "", 10)));
        assertTrue(ids(restarted.view("old", "", 10)).isEmpty());
        assertFalse(Files.exists(this.root.resolve(newer).resolve(".supersedes")));
    }

    @Test
    void retentionNeverDeletesATransactionOwnerBeforeItCanClassifyTheProtectedTarget() throws Exception {
        PanelConfig config = new PanelConfig();
        config.recycleMaxFiles = 10;
        RecycleStore store = new RecycleStore(config, this.root);
        UUID uuid = UUID.randomUUID();
        String protectedOlder = commitRecoveryRequired(store, uuid, 0);
        Files.createDirectory(this.root.resolve(protectedOlder).resolve(".old-version"));
        String newer = commit(store, uuid, 1);

        config.recycleMaxFiles = 1;
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, store::prune);

        assertTrue(Files.isDirectory(this.root.resolve(protectedOlder)));
        assertTrue(Files.isDirectory(this.root.resolve(newer)));
        assertTrue(Files.isRegularFile(this.root.resolve(newer).resolve(".supersedes")));
        assertEquals(List.of(newer), ids(store.view("latest", "", 10)));
        assertEquals(List.of(protectedOlder), ids(store.view("old", "", 10)));
    }

    private RecycleStore store() {
        return new RecycleStore(new PanelConfig(), this.root);
    }

    private static String commit(RecycleStore store, UUID uuid, int slot) throws Exception {
        return store.commit(store.stage(List.of(source(uuid, slot))));
    }

    private static String commitRecoveryRequired(RecycleStore store, UUID uuid, int slot) throws Exception {
        return store.commitRecoveryRequired(store.stage(List.of(source(uuid, slot))));
    }

    private static String commitGroup(RecycleStore store, int firstSlot, UUID... uuids) throws Exception {
        List<RecycleStore.Source> sources = new ArrayList<>();
        for (int index = 0; index < uuids.length; index++) {
            sources.add(source(uuids[index], firstSlot + index));
        }
        return store.commit(store.stage(sources));
    }

    private static RecycleStore.Source source(UUID uuid, int slot) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", uuid);
        return new RecycleStore.Source(uuid, RecycleStore.DEFAULT_DIMENSION,
                new DiskScanner.EntryKey(RecycleStore.DEFAULT_DIMENSION, 0, 0, 0, slot), tag);
    }

    private void setDeletedAt(String id, long value) throws Exception {
        Path manifest = this.root.resolve(id).resolve("manifest.json");
        JsonObject json = com.google.gson.JsonParser.parseString(Files.readString(manifest,
                StandardCharsets.UTF_8)).getAsJsonObject();
        json.addProperty("deleted_at", value);
        Files.writeString(manifest, json.toString(), StandardCharsets.UTF_8);
    }

    private String writeLegacyGroup(String stamp, String state, UUID... uuids) throws Exception {
        String id = stamp + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = this.root.resolve(id);
        Files.createDirectories(directory);
        StringBuilder bodies = new StringBuilder();
        for (int index = 0; index < uuids.length; index++) {
            if (index > 0) bodies.append(',');
            String backup = uuids[index] + ".nbt.gz";
            Files.writeString(directory.resolve(backup), "backup-" + index, StandardCharsets.UTF_8);
            bodies.append("{\"uuid\":\"").append(uuids[index]).append("\",\"blocks\":1,")
                    .append("\"block_ids\":[],\"backups\":[\"").append(backup).append("\"]}");
        }
        String manifest = "{\"version\":1,\"id\":\"" + id + "\",\"state\":\"" + state
                + "\",\"deleted_at\":1,\"file_count\":" + uuids.length + ",\"members\":"
                + uuids.length + ",\"blocks\":" + uuids.length + ",\"bodies\":[" + bodies + "]}";
        Files.writeString(directory.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
        return id;
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
