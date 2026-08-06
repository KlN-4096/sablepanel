package com.klnon.sablepanel.panel.api;

import com.klnon.sablepanel.panel.PanelConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PanelApiServiceCacheTest {
    private static final int MIB = 1024 * 1024;

    @Test
    void meshCacheReplacementAndOversizedValueRespectTheByteLimit() throws Exception {
        PanelConfig config = new PanelConfig();
        config.serverName = "cache-test";
        PanelApiService service = new PanelApiService(config, null, null, null, null);
        Method cache = PanelApiService.class.getDeclaredMethod("cache", String.class, byte[].class);
        cache.setAccessible(true);

        cache.invoke(service, "same", new byte[13 * MIB]);
        cache.invoke(service, "same", new byte[13 * MIB]);
        assertEquals(13L * MIB, cacheBytes(service), "覆盖同键不能把旧值再记一遍");
        assertEquals(1, cacheEntries(service));

        cache.invoke(service, "oversized", new byte[24 * MIB + 1]);
        assertEquals(13L * MIB, cacheBytes(service), "单项超过总预算时不能绕过缓存上限");
        assertEquals(1, cacheEntries(service), "超大响应可以返回给本次请求,但不能常驻缓存");
    }

    private static long cacheBytes(PanelApiService service) throws Exception {
        Field field = PanelApiService.class.getDeclaredField("meshCacheBytes");
        field.setAccessible(true);
        return field.getLong(service);
    }

    private static int cacheEntries(PanelApiService service) throws Exception {
        Field field = PanelApiService.class.getDeclaredField("meshCache");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(service)).size();
    }
}
