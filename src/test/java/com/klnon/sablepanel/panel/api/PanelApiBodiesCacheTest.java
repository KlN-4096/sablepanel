package com.klnon.sablepanel.panel.api;

import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.bodies.BodyIndex;
import com.klnon.sablepanel.panel.preview.PreviewSubsystem;
import com.klnon.sablepanel.panel.preview.resources.ModResourceStack;
import com.klnon.sablepanel.panel.preview.resources.ResourcePreparation;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class PanelApiBodiesCacheTest {
    @Test
    void sameIndexVersionReusesEncodedBody() {
        PanelConfig config = new PanelConfig();
        BodyIndex index = new BodyIndex();
        ResourcePreparation resources = new ResourcePreparation(progress -> {
            throw new IllegalStateException("unused");
        }, baseline -> new ModResourceStack(baseline.archive(), List.of()));
        try (PreviewSubsystem preview = new PreviewSubsystem(uuid -> null, resources)) {
            PanelApiService api = new PanelApiService(config, index, null, null, preview, null);
            PanelRequest request = new PanelRequest("GET", "/api/bodies", Map.of(),
                    new byte[0], config.token, "");

            byte[] first = api.dispatch(request).body();
            byte[] sameVersion = api.dispatch(request).body();
            assertSame(first, sameVersion);

            UUID uuid = UUID.randomUUID();
            DiskScanner.EntryKey key = new DiskScanner.EntryKey("minecraft:overworld", 0, 0, 0, 0);
            index.updateDisk(List.of(new DiskScanner.DiskEntry(key, uuid, "body",
                    new double[]{0, 64, 0}, new double[]{1, 1, 1}, 1, List.of(), true,
                    0, 0, List.of("minecraft:stone"), false, 0, 0)));
            assertNotSame(first, api.dispatch(request).body());
        }
    }
}
