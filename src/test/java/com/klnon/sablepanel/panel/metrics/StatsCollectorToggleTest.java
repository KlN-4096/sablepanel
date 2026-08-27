package com.klnon.sablepanel.panel.metrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.api.PanelApiService;
import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import com.klnon.sablepanel.panel.bodies.BodyIndex;
import com.klnon.sablepanel.panel.preview.PreviewSubsystem;
import com.klnon.sablepanel.panel.preview.resources.ModResourceStack;
import com.klnon.sablepanel.panel.preview.resources.ResourcePreparation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsCollectorToggleTest {
    @AfterEach
    void disableCollector() {
        StatsCollector.INSTANCE.setEnabled(false);
    }

    @Test
    void statsRouteStartsDisabledAndTogglesInPlace() {
        StatsCollector.INSTANCE.start();
        PanelConfig config = new PanelConfig();
        ResourcePreparation resources = new ResourcePreparation(progress -> {
            throw new IllegalStateException("unused");
        }, baseline -> new ModResourceStack(baseline.archive(), List.of()));
        try (PreviewSubsystem preview = new PreviewSubsystem(uuid -> null, resources)) {
            PanelApiService api = new PanelApiService(config, new BodyIndex(), null, null, preview, null);

            JsonObject initial = dispatch(api, config, "GET", new byte[0]);
            assertFalse(initial.get("enabled").getAsBoolean());

            JsonObject enabled = dispatch(api, config, "POST", "{\"enabled\":true}".getBytes(StandardCharsets.UTF_8));
            assertTrue(enabled.get("enabled").getAsBoolean());
            assertTrue(BodyCostTracker.ENABLED);
            assertTrue(PhysicsTimer.ENABLED);

            JsonObject disabled = dispatch(api, config, "POST", "{\"enabled\":false}".getBytes(StandardCharsets.UTF_8));
            assertFalse(disabled.get("enabled").getAsBoolean());
            assertFalse(BodyCostTracker.ENABLED);
            assertFalse(PhysicsTimer.ENABLED);
        }
    }

    @Test
    void disablingClearsUnconsumedBodySamples() {
        UUID uuid = UUID.randomUUID();
        StatsCollector.INSTANCE.setEnabled(true);
        BodyCostTracker.add(uuid, 2_000_000);
        assertFalse(BodyCostTracker.drain(1, Set.of(uuid)).isEmpty());

        BodyCostTracker.add(uuid, 2_000_000);
        StatsCollector.INSTANCE.setEnabled(false);
        StatsCollector.INSTANCE.setEnabled(true);

        assertTrue(BodyCostTracker.drain(1, Set.of(uuid)).isEmpty());
    }

    private static JsonObject dispatch(PanelApiService api, PanelConfig config, String method, byte[] body) {
        PanelResponse response = api.dispatch(new PanelRequest(method, "/api/stats", Map.of(),
                body, config.token, ""));
        return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
