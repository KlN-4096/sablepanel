package com.klnon.sablepanel.panel.data;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
