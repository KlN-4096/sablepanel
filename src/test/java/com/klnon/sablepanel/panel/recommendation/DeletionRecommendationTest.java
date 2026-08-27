package com.klnon.sablepanel.panel.recommendation;

import com.klnon.sablepanel.panel.PanelConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DeletionRecommendationTest {
    @Test
    void protectsNamedOrSubstantialGroups() {
        var result = DeletionRecommendation.evaluate(new PanelConfig(),
                new DeletionRecommendation.Input(20, 20, 1, 0, 0, true, false, false,
                        0, 1, false, false));

        assertFalse(result.has("reasons"));
        assertEquals(2, result.getAsJsonArray("protected_by").size());
    }

    @Test
    void explainsSmallOrphanDuplicates() {
        var result = DeletionRecommendation.evaluate(new PanelConfig(),
                new DeletionRecommendation.Input(4, 4, 1, 0, 0, false, false, false,
                        1, 0, true, false));

        assertEquals(3, result.getAsJsonArray("reasons").size());
        assertEquals("fragment", result.getAsJsonArray("reasons").get(0).getAsString());
    }
}
