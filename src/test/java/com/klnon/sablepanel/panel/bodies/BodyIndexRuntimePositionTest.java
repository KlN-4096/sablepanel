package com.klnon.sablepanel.panel.bodies;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.klnon.sablepanel.panel.storage.DiskScanner;

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

    /** 传送后磁盘条目还是旧坐标(要等 autosave),列表必须显示运行时坐标 */
    @Test
    void displayPosPrefersRuntimeForLoadedBodies() {
        double[] disk = {-11433, -6557, 796};
        JsonObject runtime = new JsonObject();
        runtime.addProperty("x", 1000.0);
        runtime.addProperty("y", 400.0);
        runtime.addProperty("z", 796.0);

        assertArrayEquals(new double[]{1000, 400, 796}, BodyIndex.displayPos(runtime, disk));
        // 未加载的体(没有运行时状态)只能用磁盘快照
        assertArrayEquals(disk, BodyIndex.displayPos(null, disk));
        assertArrayEquals(disk, BodyIndex.displayPos(new JsonObject(), disk));
    }

    private static DiskScanner.DiskEntry entry(DiskScanner.EntryKey key, UUID uuid, boolean reachable) {
        return new DiskScanner.DiskEntry(key, uuid, "name", new double[]{1, 2, 3},
                new double[]{4, 5, 6}, 7, List.of(), reachable, 8, 9,
                List.of("minecraft:stone"), false, 0, 0);
    }
}
