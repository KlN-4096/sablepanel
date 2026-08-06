package com.klnon.sablepanel.panel.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 方块名缓存的键上界。
 * <p>
 * {@code of(blockId)} 的入参直接来自 NBT 的方块调色板 —— 长度上限是 NBT 字符串的 65,535 字节,
 * 取值完全由存档决定。从前它无条件 {@code computeIfAbsent},注册表里查不到的 id 也照样进缓存,
 * 于是一张 {@code static} 的 Map 就有了无界入口:一份构造过或损坏的存档能把大量超长键
 * 连同两份显示名副本永久钉在堆里,而且没有任何一处会清它。
 * <p>
 * 注册表里真实存在的 id 天然有上界(就是注册表大小),那些才值得缓存。
 */
class BlockNamesCacheTest {

    @Test
    void unregisteredBlockIdsAreResolvedButNeverCached() {
        int before = BlockNames.cachedCount();
        String hostile = "N".repeat(65_000);
        for (int i = 0; i < 1000; i++) {
            String[] names = BlockNames.of("sp:" + hostile + i);
            // 仍然要给出可用的显示名,只是不留在缓存里
            assertEquals(2, names.length);
            assertArrayEquals(new String[]{names[0], names[0]}, names, "未注册的 id 回落成同一个 path");
        }
        assertEquals(before, BlockNames.cachedCount(),
                "未注册的方块 id 不得进缓存:它的取值由存档决定,没有上界");
    }
}
