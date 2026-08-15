package com.klnon.sablepanel.panel.preview;

import com.klnon.sablepanel.panel.preview.resources.ModResourceStack;
import com.klnon.sablepanel.panel.preview.resources.ResourcePreparation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewSubsystemCacheTest {
    private static final int MIB = 1024 * 1024;

    @Test
    void meshCacheReplacementAndOversizedValueRespectTheByteLimit() throws Exception {
        PreviewSubsystem service = new PreviewSubsystem(uuid -> null, new ResourcePreparation(
                progress -> { throw new IllegalStateException("no resources in test"); },
                b -> new ModResourceStack(b.archive(), java.util.List.of())));
        Method cache = PreviewSubsystem.class.getDeclaredMethod("cache", String.class, byte[].class);
        cache.setAccessible(true);

        cache.invoke(service, "same", new byte[13 * MIB]);
        cache.invoke(service, "same", new byte[13 * MIB]);
        assertEquals(13L * MIB, cacheBytes(service), "覆盖同键不能把旧值再记一遍");
        assertEquals(1, cacheEntries(service));

        cache.invoke(service, "oversized", new byte[24 * MIB + 1]);
        assertEquals(13L * MIB, cacheBytes(service), "单项超过总预算时不能绕过缓存上限");
        assertEquals(1, cacheEntries(service), "超大响应可以返回给本次请求,但不能常驻缓存");
    }

    private static long cacheBytes(PreviewSubsystem service) throws Exception {
        Field field = PreviewSubsystem.class.getDeclaredField("cacheBytes");
        field.setAccessible(true);
        return field.getLong(service);
    }

    private static int cacheEntries(PreviewSubsystem service) throws Exception {
        Field field = PreviewSubsystem.class.getDeclaredField("cache");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(service)).size();
    }
}
