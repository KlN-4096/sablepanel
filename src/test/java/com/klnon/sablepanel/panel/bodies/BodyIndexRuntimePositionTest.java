package com.klnon.sablepanel.panel.bodies;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.klnon.sablepanel.panel.storage.DiskScanner;

class BodyIndexRuntimePositionTest {

    @Test
    void runtimeOnlyBodyIsImmediatelyVisibleAndCanRequestAThumbnail() {
        BodyIndex index = new BodyIndex();
        UUID uuid = UUID.randomUUID();

        index.updateRuntimePosition(uuid, "minecraft:overworld", new double[]{12.25, 64.0, -7.5});

        JsonObject body = index.view().getAsJsonArray("groups").get(0).getAsJsonObject()
                .getAsJsonArray("bodies").get(0).getAsJsonObject();
        JsonObject runtime = body.getAsJsonObject("runtime");
        assertEquals("minecraft:overworld", runtime.get("dim").getAsString());
        assertEquals(12.25, runtime.get("x").getAsDouble());
        assertEquals(64.0, runtime.get("y").getAsDouble());
        assertEquals(-7.5, runtime.get("z").getAsDouble());
        assertEquals("f2-runtime|0x0x0", index.thumbnailSignature(uuid));
    }

    @Test
    void diskSnapshotOnlyReportsMaterialChanges() {
        BodyIndex index = new BodyIndex();
        UUID uuid = UUID.randomUUID();
        DiskScanner.EntryKey key = new DiskScanner.EntryKey("minecraft:overworld", 1, 2, 3, 4);

        long initialVersion = index.version();
        assertEquals(0, index.diskRevision());
        assertTrue(index.updateDisk(List.of(entry(key, uuid, false))));
        assertTrue(index.version() > initialVersion);
        assertEquals(1, index.diskRevision());
        assertFalse(index.updateDisk(List.of(entry(key, uuid, false))));
        assertEquals(1, index.diskRevision(), "内容不变的周期扫描不能唤醒失败的常驻恢复");
        assertTrue(index.updateDisk(List.of(entry(key, uuid, true))));
        assertEquals(2, index.diskRevision());
    }

    @Test
    void diskLookupPrecomputesBestEntryAndThumbnailSignature() {
        BodyIndex index = new BodyIndex();
        UUID uuid = UUID.randomUUID();
        DiskScanner.DiskEntry unreachable = entry(
                new DiskScanner.EntryKey("minecraft:overworld", 1, 2, 0, 1), uuid, false);
        DiskScanner.DiskEntry reachable = entry(
                new DiskScanner.EntryKey("minecraft:overworld", 1, 2, 0, 2), uuid, true);

        index.updateDisk(List.of(unreachable, reachable));

        assertEquals(reachable, index.findEntry(uuid));
        assertEquals(com.klnon.sablepanel.panel.preview.thumb.ThumbService.signature(
                List.of(unreachable, reachable)), index.thumbnailSignature(uuid));
    }

    @Test
    void previewSelectionNeverGuessesBetweenMultipleReachableCopies() {
        BodyIndex index = new BodyIndex();
        UUID uuid = UUID.randomUUID();
        DiskScanner.EntryKey first = new DiskScanner.EntryKey("minecraft:overworld", 1, 2, 0, 1);
        DiskScanner.EntryKey second = new DiskScanner.EntryKey("minecraft:overworld", 1, 2, 0, 2);

        index.updateDisk(List.of(entry(first, uuid, true), entry(second, uuid, true)));
        assertTrue(index.previewSelection(uuid).ambiguous());

        index.updateDisk(List.of(entry(first, uuid, true), entry(second, uuid, false)));
        assertEquals(first, index.previewSelection(uuid).entry().key());
    }

    /** 传送后磁盘条目还是旧坐标(要等 autosave),列表必须显示运行时坐标 */
    @Test
    void displayPosPrefersRuntimeForLoadedBodies() {
        double[] disk = {-11433, -6557, 796};
        BodyIndex.RuntimeBody runtime = BodyIndex.RuntimeBody.positionOnly(
                "minecraft:overworld", 1000, 400, 796);

        assertArrayEquals(new double[]{1000, 400, 796}, BodyIndex.displayPos(runtime, disk));
        assertArrayEquals(disk, BodyIndex.displayPos(null, disk));
    }

    private static DiskScanner.DiskEntry entry(DiskScanner.EntryKey key, UUID uuid, boolean reachable) {
        return new DiskScanner.DiskEntry(key, uuid, "name", new double[]{1, 2, 3},
                new double[]{4, 5, 6}, 7, List.of(), reachable, 8, 9,
                List.of("minecraft:stone"), false, 0, 0);
    }
}
