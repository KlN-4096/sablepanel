package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.panel.storage.DiskScanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackingPointServiceTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void onlyReportsSubLevelTrackingPointsWhosePayloadIsMissing() {
        UUID id = UUID.randomUUID();
        UUID body = UUID.randomUUID();
        TrackingPointService.Snapshot tracking = new TrackingPointService.Snapshot(
                id, body, DIMENSION, true, 415, 98, 0, 1);

        List<TrackingPointService.Issue> missing = TrackingPointService.stale(
                List.of(tracking), Set.of());

        assertEquals(1, missing.size());
        assertEquals(id, missing.getFirst().trackingId());
        assertEquals(body, missing.getFirst().body());
        assertEquals(new DiskScanner.EntryKey(DIMENSION, 12, 3, 0, 1), missing.getFirst().key());

        Set<DiskScanner.EntryKey> occupied = Set.of(missing.getFirst().key());
        assertTrue(TrackingPointService.stale(List.of(tracking), occupied).isEmpty());
        TrackingPointService.Snapshot worldPoint = new TrackingPointService.Snapshot(
                id, null, DIMENSION, false, 0, 0, 0, 0);
        assertTrue(TrackingPointService.stale(List.of(worldPoint), Set.of()).isEmpty());
    }
}
