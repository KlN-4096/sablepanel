package com.klnon.sablepanel.panel.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 计数器必须和真正发出去的字节完全相等 —— 它是整个响应上限的地基,
 * 差一点就意味着上限差一点,而这正是从前那套估算常量的毛病。
 */
class JsonSizeTest {

    private static final Gson GSON = new Gson();

    private static void assertExact(com.google.gson.JsonElement element) {
        long expected = GSON.toJson(element).getBytes(StandardCharsets.UTF_8).length;
        assertEquals(expected, JsonSize.of(element), () -> "实际输出: " + GSON.toJson(element));
    }

    @Test
    void countsAsciiObjectsExactly() {
        JsonObject o = new JsonObject();
        o.addProperty("id", "minecraft:stone");
        o.addProperty("blocks", 1234);
        o.addProperty("loaded", true);
        assertExact(o);
    }

    @Test
    void countsMultibyteAndSurrogatesExactly() {
        JsonObject o = new JsonObject();
        o.addProperty("zh", "石头方块");            // 3 字节/字
        o.addProperty("latin", "Grüße");            // 2 字节
        o.addProperty("emoji", "船\uD83D\uDEA2x");  // 代理对 = 4 字节
        assertExact(o);
    }

    @Test
    void countsEscapedCharactersExactly() {
        // JSON 转义会把 1 个字符撑成 6 个 —— 按字符数估的做法就是在这里失真的
        JsonObject o = new JsonObject();
        o.addProperty("ctrl", "a\u0001\u0002\u001fb");
        o.addProperty("quote", "他说\"你好\"\\结束\n");
        assertExact(o);
    }

    @Test
    void countsNestedArraysAndEmptyValuesExactly() {
        JsonObject o = new JsonObject();
        JsonArray blk = new JsonArray();
        for (int i = 0; i < 500; i++) blk.add(i * 137);
        o.add("blk", blk);
        o.add("empty", new JsonArray());
        o.add("nested", new JsonObject());
        o.addProperty("neg", -0.5);
        assertExact(o);
    }
}
