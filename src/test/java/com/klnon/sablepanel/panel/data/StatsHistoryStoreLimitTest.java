package com.klnon.sablepanel.panel.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatsHistoryStoreLimitTest {
    @TempDir
    Path root;

    @Test
    void extremeRetentionCannotCreateAnUnboundedDateRange() throws Exception {
        StatsHistoryStore store = new StatsHistoryStore(this.root, Integer.MAX_VALUE, ZoneId.of("UTC"));
        try {
            Field retention = StatsHistoryStore.class.getDeclaredField("retentionDays");
            retention.setAccessible(true);
            assertEquals(365, retention.getInt(store));
        } finally {
            store.close();
        }
    }
}
