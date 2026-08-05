package com.klnon.sablepanel.panel.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /api/bodies} 的单次响应边界。
 * <p>
 * 从前是无条件全量构建:响应大小只随存档增长，32 MiB 的协议上限拦不住「先把整个对象建到堆里」。
 * 只按组数截断也不够 —— 少数巨型组照样能撑爆，所以组数之外还有一份字节预算，两条先到先生效。
 */
class BodyIndexViewBudgetTest {

    private static DiskScanner.DiskEntry entry(UUID uuid, int slot, int blockTypes) {
        return entry(uuid, slot, blockTypes, List.of());
    }

    /** 同一份方块 id 列表在体之间共享:DiskEntry 只存引用,每体重建会让大用例直接 OOM */
    private static final Map<Integer, List<String>> BLOCK_IDS = new HashMap<>();

    private static DiskScanner.DiskEntry entry(UUID uuid, int slot, int blockTypes, List<UUID> deps) {
        List<String> blockIds = BLOCK_IDS.computeIfAbsent(blockTypes, n -> {
            List<String> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++) ids.add("sp:t_" + i);
            return List.copyOf(ids);
        });
        return new DiskScanner.DiskEntry(
                new DiskScanner.EntryKey("minecraft:overworld", 0, 0, 0, slot), uuid, "n",
                new double[]{1, 2, 3}, new double[]{4, 5, 6}, blockTypes, deps, true, 0, 0,
                blockIds, false, 0, 0);
    }

    private static JsonObject view(List<DiskScanner.DiskEntry> entries) {
        BodyIndex index = new BodyIndex();
        index.updateDisk(entries);
        return index.view();
    }

    @Test
    void manySmallGroupsAreCappedByGroupCountAndReportTruncation() {
        List<DiskScanner.DiskEntry> entries = new ArrayList<>();
        for (int i = 0; i < 3200; i++) entries.add(entry(UUID.randomUUID(), i, 1));
        JsonObject view = view(entries);

        assertEquals(3200, view.get("total_groups").getAsInt(), "总数必须是真值");
        assertEquals(3000, view.getAsJsonArray("groups").size(), "超过组数上限要截断");
        assertEquals(3000, view.get("shown_groups").getAsInt());
        assertTrue(view.get("truncated").getAsBoolean(), "截断了就必须告诉前端");
    }

    @Test
    void fewHugeGroupsAreCappedByByteBudgetBeforeHittingTheGroupCount() {
        // 每个组一个体、每体 3 万个方块索引 ≈ 210 KB;组数远没到 3000,字节预算先到
        List<DiskScanner.DiskEntry> entries = new ArrayList<>();
        for (int i = 0; i < 200; i++) entries.add(entry(UUID.randomUUID(), i, 30_000));
        JsonObject view = view(entries);

        int shown = view.getAsJsonArray("groups").size();
        assertTrue(shown < 200, "字节预算必须在组数上限之前生效,实际输出 " + shown + " 组");
        assertTrue(shown > 0, "至少要出一组");
        assertTrue(view.get("truncated").getAsBoolean());
        assertBounded(view);
    }

    @Test
    void oneGroupThatAloneExceedsTheBudgetIsStillEmitted() {
        // 单个组自己就超预算(一个 60 万索引的体):不能因此输出空列表
        JsonObject view = view(List.of(entry(UUID.randomUUID(), 0, 600_000)));
        assertEquals(1, view.getAsJsonArray("groups").size(), "至少出一组,否则这个体永远看不见");
        assertBounded(view);
    }

    @Test
    void oneHugeDependencyGroupIsClampedByMembersNotJustByGroupCount() {
        // 一条 4000 成员、每体 500 种方块的依赖链 = 一个组,估算 15 MB 已越过 12 MiB 预算。
        // 只在组入口查预算的话,它是"第一组"所以无条件整份发出去,
        // 3000 组上限和字节预算都拦不住 —— 组内成员数没有任何上限
        UUID head = UUID.randomUUID();
        List<UUID> all = new ArrayList<>(List.of(head));
        for (int i = 1; i < 4000; i++) all.add(UUID.randomUUID());
        List<DiskScanner.DiskEntry> entries = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            // 全都依赖 head:并查集把它们并成同一个组
            entries.add(entry(all.get(i), i, 500, i == 0 ? List.of() : List.of(head)));
        }
        JsonObject view = view(entries);

        JsonArray groups = view.getAsJsonArray("groups");
        assertEquals(1, groups.size(), "确实只有一个依赖组");
        JsonObject group = groups.get(0).getAsJsonObject();
        assertEquals(4000, group.get("members").getAsInt(), "组聚合计数必须是真值");
        int shown = group.getAsJsonArray("bodies").size();
        assertTrue(shown < 4000, "组内成员也要受预算约束,实际输出 " + shown + " 个");
        assertEquals(4000 - shown, group.get("members_omitted").getAsInt());
        assertTrue(view.get("truncated").getAsBoolean(), "组内截断同样要告诉前端");
        assertBounded(view);
    }

    @Test
    void freshGroupsCountTowardsTheGroupCapAndTotalStaysTruthful() {
        // 运行时有 3200 个盘上还没有条目的体(刚生成),磁盘条目 0 个。
        // fresh 和磁盘组分开计数时两者相加会越过 3000
        BodyIndex index = new BodyIndex();
        index.updateDisk(List.of());
        for (int i = 0; i < 3200; i++) {
            index.updateRuntimePosition(UUID.randomUUID(), "minecraft:overworld", new double[]{1, 2, 3});
        }
        JsonObject view = index.view();

        assertEquals(3000, view.getAsJsonArray("groups").size(), "fresh 组也占组数名额");
        assertEquals(3200, view.get("total_bodies").getAsInt(), "总数是真值,不是显示数");
        assertEquals(3200, view.get("total_groups").getAsInt());
        assertTrue(view.get("truncated").getAsBoolean());
    }

    /** 真正要防的是「先把整个对象建出来才发现发不出去」:序列化后必须落在 32 MiB 协议上限内 */
    private static void assertBounded(JsonObject view) {
        int bytes = view.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(bytes < 32 * 1024 * 1024, "响应必须落在协议上限之内,实际 " + bytes);
    }

    @Test
    void paletteOnlyContainsBlocksOfEmittedMembers() {
        // 3200 个组各用一种独有方块;截断到 3000 组后,调色板不该还留着被砍掉那批的条目
        List<DiskScanner.DiskEntry> entries = new ArrayList<>();
        for (int i = 0; i < 3200; i++) {
            UUID uuid = UUID.randomUUID();
            entries.add(new DiskScanner.DiskEntry(
                    new DiskScanner.EntryKey("minecraft:overworld", 0, 0, 0, i), uuid, "n",
                    new double[]{1, 2, 3}, new double[]{4, 5, 6}, 1, List.of(), true, 0, 0,
                    List.of("sp:only_" + i), false, 0, 0));
        }
        JsonObject view = view(entries);
        assertEquals(3000, view.getAsJsonArray("block_palette").size(),
                "调色板只收真正输出出去的成员用到的方块");
    }
}
