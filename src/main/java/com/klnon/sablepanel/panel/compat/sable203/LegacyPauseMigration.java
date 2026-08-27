package com.klnon.sablepanel.panel.compat.sable203;

import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.panel.ops.TeleportOps;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Sable 2.0.3 时代逐成员 paused.json 到完整物理组暂停意图的一次性迁移。 */
public final class LegacyPauseMigration {
    private static final int ATTEMPTS = 3;

    public record Context(TeleportOps teleport, ScheduledExecutorService control,
                          ExecutorService scans, BooleanSupplier active) {
    }

    private LegacyPauseMigration() {
    }

    public static void schedule(Context context) {
        schedule(context, 1);
    }

    private static void schedule(Context context, int attempt) {
        try {
            context.scans().execute(() -> {
                if (!context.active().getAsBoolean()) return;
                try {
                    int normalized = context.teleport().normalizePersistedPausedGroups();
                    if (normalized > 0) {
                        SablePanel.LOGGER.info("sablepanel: normalized {} legacy paused members to full groups",
                                normalized);
                    }
                } catch (Exception error) {
                    if (attempt >= ATTEMPTS) {
                        SablePanel.LOGGER.error("sablepanel: legacy paused-state group migration failed after {} attempts",
                                attempt, error);
                        return;
                    }
                    context.control().schedule(() -> schedule(context, attempt + 1),
                            1, TimeUnit.SECONDS);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    public static boolean applyIfUnchanged(long expectedRevision, LongSupplier currentRevision, Runnable apply) {
        if (currentRevision.getAsLong() != expectedRevision) return false;
        apply.run();
        return true;
    }
}
