package com.klnon.sablepanel.panel.data;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyIndexRuntimePositionTest {

    @Test
    void runtimePositionUpdateIsImmediatelyVisibleInCache() throws Exception {
        BodyIndex index = new BodyIndex();
        UUID uuid = UUID.randomUUID();

        index.updateRuntimePosition(uuid, "minecraft:overworld", new double[]{12.25, 64.0, -7.5});

        Field runtimeField = BodyIndex.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, JsonObject> states = (Map<UUID, JsonObject>) runtimeField.get(index);
        JsonObject runtime = states.get(uuid);
        assertEquals("minecraft:overworld", runtime.get("dim").getAsString());
        assertEquals(12.25, runtime.get("x").getAsDouble());
        assertEquals(64.0, runtime.get("y").getAsDouble());
        assertEquals(-7.5, runtime.get("z").getAsDouble());
    }

    @Test
    void diskSnapshotOnlyReportsMaterialChanges() {
        BodyIndex index = new BodyIndex();
        UUID uuid = UUID.randomUUID();
        DiskScanner.EntryKey key = new DiskScanner.EntryKey("minecraft:overworld", 1, 2, 3, 4);

        assertTrue(index.updateDisk(List.of(entry(key, uuid, false))));
        assertFalse(index.updateDisk(List.of(entry(key, uuid, false))));
        assertTrue(index.updateDisk(List.of(entry(key, uuid, true))));
    }

    private static DiskScanner.DiskEntry entry(DiskScanner.EntryKey key, UUID uuid, boolean reachable) {
        return new DiskScanner.DiskEntry(key, uuid, "name", new double[]{1, 2, 3},
                new double[]{4, 5, 6}, 7, List.of(), reachable, 8, 9,
                List.of("minecraft:stone"), false, 0, 0);
    }
}
