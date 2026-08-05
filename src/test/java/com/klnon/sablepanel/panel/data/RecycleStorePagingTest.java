package com.klnon.sablepanel.panel.data;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.PanelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收站视图从前一次读全部 manifest、建全局调色板、输出全部组:堆和响应都只随备份数增长,
 * 而且是先把整个对象建出来才可能撞上 32 MiB 的协议上限。这里固定的是分页契约本身 ——
 * 读取、构建、传输三个阶段都有单页上限,游标是 keyset 语义(跳过 id ≥ 游标的),所以游标那一组
 * 即使在两次请求之间被清掉也不会翻页失败。
 */
class RecycleStorePagingTest {

    @TempDir
    Path root;

    private RecycleStore store() {
        return new RecycleStore(new PanelConfig(), this.root);
    }

    /** 直接铺 manifest 文件:committedDirectories 只认「目录 + manifest.json」 */
    private String writeGroup(String stamp, int bodies, int blockTypes) throws Exception {
        return writeGroup(stamp, bodies, blockTypes, 0);
    }

    /**
     * @param nameChars 每个体的 display_name 长度。它来自 NBT,上限 65535 字节 ——
     *                  从前按"体数 × 固定当量 + block_ids 条数"估预算时,这一段是完全隐形的
     */
    private String writeGroup(String stamp, int bodies, int blockTypes, int nameChars) throws Exception {
        String id = stamp + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path dir = this.root.resolve(id);
        Files.createDirectories(dir);
        String name = "N".repeat(nameChars);
        StringBuilder manifest = new StringBuilder();
        manifest.append("{\"version\":1,\"id\":\"").append(id).append("\",\"state\":\"deleted\"")
                .append(",\"deleted_at\":").append(System.currentTimeMillis()).append(",\"bodies\":[");
        for (int b = 0; b < bodies; b++) {
            if (b > 0) manifest.append(',');
            manifest.append("{\"uuid\":\"").append(UUID.randomUUID()).append("\",\"blocks\":1");
            if (nameChars > 0) manifest.append(",\"name\":\"").append(name).append('"');
            manifest.append(",\"block_ids\":[");
            for (int k = 0; k < blockTypes; k++) {
                if (k > 0) manifest.append(',');
                manifest.append("\"sp:t_").append(k).append('"');
            }
            manifest.append("],\"backups\":[\"a.nbt.gz\"]}");
        }
        manifest.append("]}");
        Files.writeString(dir.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
        return id;
    }

    /** 一页发出去的真实字节数 —— 预算管的就是它 */
    private static long pageBytes(JsonObject page) {
        return page.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static List<String> ids(JsonObject page) {
        List<String> result = new ArrayList<>();
        for (var element : page.getAsJsonArray("groups")) {
            result.add(element.getAsJsonObject().get("id").getAsString());
        }
        return result;
    }

    @Test
    void pagesWalkEveryGroupExactlyOnceNewestFirst() throws Exception {
        Set<String> written = new LinkedHashSet<>();
        for (int i = 1; i <= 7; i++) written.add(writeGroup(String.format("20260101-00000%d-000", i), 1, 2));
        RecycleStore store = store();

        Set<String> seen = new LinkedHashSet<>();
        String cursor = "";
        int pages = 0;
        do {
            JsonObject page = store.view(cursor, 3);
            List<String> pageIds = ids(page);
            assertTrue(pageIds.size() <= 3, "单页不得超过请求的上限");
            for (String id : pageIds) assertTrue(seen.add(id), "同一个组不能出现在两页里: " + id);
            cursor = page.get("next_cursor").getAsString();
            pages++;
            assertTrue(pages <= 10, "翻页必须收敛");
        } while (!cursor.isEmpty());

        assertEquals(written.size(), seen.size(), "所有组都要被翻到");
        assertEquals(3, pages, "7 个组按每页 3 个应当是 3 页");
        List<String> descending = new ArrayList<>(seen);
        List<String> expected = new ArrayList<>(seen);
        expected.sort(java.util.Comparator.reverseOrder());
        assertEquals(expected, descending, "新的在前");
    }

    @Test
    void lastPageReportsNoCursorAndEmptyStoreReturnsEmptyPage() throws Exception {
        RecycleStore empty = store();
        JsonObject page = empty.view("", 0);
        assertEquals(0, page.getAsJsonArray("groups").size());
        assertEquals("", page.get("next_cursor").getAsString());
        assertEquals(0, page.get("total_groups").getAsInt());

        writeGroup("20260101-000001-000", 1, 1);
        writeGroup("20260101-000002-000", 1, 1);
        JsonObject full = store().view("", 10);
        assertEquals(2, full.getAsJsonArray("groups").size());
        assertEquals("", full.get("next_cursor").getAsString(), "取完了就不该再给游标");

        // 游标指向最后一个组 -> 后面没有内容了
        String lastId = ids(full).get(1);
        JsonObject beyond = store().view(lastId, 10);
        assertEquals(0, beyond.getAsJsonArray("groups").size());
        assertEquals("", beyond.get("next_cursor").getAsString());
    }

    @Test
    void staleOrInvalidCursorDoesNotBreakPaging() throws Exception {
        String older = writeGroup("20260101-000001-000", 1, 1);
        String newer = writeGroup("20260101-000009-000", 1, 1);
        RecycleStore store = store();

        // 游标那一组在两次请求之间被清理掉:keyset 语义下仍然只返回它之后的组
        JsonObject afterDeleted = store.view(newer, 10);
        assertEquals(List.of(older), ids(afterDeleted));
        deleteTree(this.root.resolve(newer));
        assertEquals(List.of(older), ids(store.view(newer, 10)), "游标组消失后翻页不能失败");

        // 格式非法的游标退回第一页,而不是抛错把整个回收站页面打空
        JsonObject garbage = store.view("../../etc/passwd", 10);
        assertEquals(List.of(older), ids(garbage));
        assertFalse(garbage.has("error"));
    }

    @Test
    void oversizedSingleGroupStillMakesProgress() throws Exception {
        // 方块构成占绝大部分:退到只发元数据就装得下,成员明细仍然要在
        String huge = writeGroup("20260101-000009-000", 30, 20_000);
        String small = writeGroup("20260101-000001-000", 1, 2);
        RecycleStore store = store();

        JsonObject first = store.view("", 100);
        assertTrue(ids(first).contains(huge), "超预算的组必须发得出去,不能被挤到永远翻不到");
        assertTrue(pageBytes(first) < 4 * 1024 * 1024, "退让之后整页仍要受控,实际 " + pageBytes(first));

        // 超预算的那组只发元数据,而且必须显式说明 —— 前端据此提示,否则构成条空着像是「没有方块」
        JsonObject group = first.getAsJsonArray("groups").get(0).getAsJsonObject();
        assertEquals(huge, group.get("id").getAsString());
        assertTrue(group.get("blocks_omitted").getAsBoolean());
        assertEquals(30, group.getAsJsonArray("bodies").size(), "元数据装得下时成员明细不该丢");
        // 退成元数据之后它就不大了,同一页还装得下别的组;正常组不该被顺带打上省略标记
        assertTrue(walkAllIds(store).contains(small), "后面的组仍要翻得到");
        for (var element : first.getAsJsonArray("groups")) {
            JsonObject each = element.getAsJsonObject();
            if (!huge.equals(each.get("id").getAsString())) assertFalse(each.has("blocks_omitted"));
        }
    }

    @Test
    void theCachedPageIndexIsDroppedWhenGroupsAreRemoved() throws Exception {
        // 分页目录清单现在带缓存(每页都重扫全库是 O(总组数) 的活)。缓存最容易出的问题是
        // 删了组还照旧列出来 —— 用户点进去只会拿到"回收组不存在"
        String kept = writeGroup("20260101-000009-000", 1, 2);
        String gone = writeGroup("20260101-000001-000", 1, 2);
        RecycleStore store = store();
        assertEquals(2, store.view("", 100).getAsJsonArray("groups").size());

        store.purgeGroups(List.of(gone));

        JsonObject after = store.view("", 100);
        assertEquals(List.of(kept), ids(after), "彻底删除必须让缓存立刻失效");
        assertEquals(1, after.get("total_groups").getAsInt());
        assertEquals(1, after.get("latest_groups").getAsInt());
        assertFalse(Files.exists(this.root.resolve(gone)), "目录树要真的删干净");
    }

    /** 一路翻到底,收集所有翻得到的组 id */
    private static Set<String> walkAllIds(RecycleStore store) {
        Set<String> seen = new LinkedHashSet<>();
        String cursor = "";
        for (int page = 0; page < 20; page++) {
            JsonObject view = store.view(cursor, 100);
            seen.addAll(ids(view));
            cursor = view.get("next_cursor").getAsString();
            if (cursor.isEmpty()) break;
        }
        return seen;
    }

    @Test
    void aGroupTooBigEvenWithoutBlocksFallsBackToAFixedSummary() throws Exception {
        // 元数据自己就超预算:200 个体、每体 65000 字节的 display_name ≈ 13 MB。
        // 「至少让这一组翻得过去」这条规则不能变成新的无界出口,所以退到固定尺寸摘要
        String huge = writeGroup("20260101-000009-000", 200, 0, 65_000);
        String small = writeGroup("20260101-000001-000", 1, 2);
        RecycleStore store = store();

        JsonObject first = store.view("", 100);
        JsonObject group = first.getAsJsonArray("groups").get(0).getAsJsonObject();
        assertEquals(huge, group.get("id").getAsString());
        assertTrue(group.get("bodies_omitted").getAsBoolean(), "连元数据都装不下就只发摘要");
        assertEquals(0, group.getAsJsonArray("bodies").size());
        assertTrue(pageBytes(first) < 64 * 1024, "摘要必须是固定尺寸,实际 " + pageBytes(first));
        assertTrue(walkAllIds(store).contains(small), "摘要之后仍要翻得到别的组");
    }

    @Test
    void bodyMetadataCountsTowardsThePageBudgetEvenWithoutBlockIds() throws Exception {
        // 零个方块索引,全靠名称占字节。按"体数 × 固定当量 + block_ids 条数"估的话
        // 这三个组的预算完全一样且与名称无关,会被一次性塞进同一页
        for (int i = 1; i <= 3; i++) {
            writeGroup(String.format("20260101-00000%d-000", i), 15, 0, 65_000);
        }
        RecycleStore store = store();

        int pages = 0;
        int seen = 0;
        String cursor = "";
        do {
            JsonObject page = store.view(cursor, 100);
            seen += page.getAsJsonArray("groups").size();
            assertTrue(pageBytes(page) < 4 * 1024 * 1024,
                    "单页字节必须受控,实际 " + pageBytes(page));
            cursor = page.get("next_cursor").getAsString();
            assertTrue(++pages <= 5, "翻页必须收敛");
        } while (!cursor.isEmpty());
        assertEquals(3, seen, "所有组都要翻得到");
        assertTrue(pages > 1, "名称记进预算之后这 3 个组装不进同一页");
    }

    @Test
    void anOversizedGroupAfterASmallOneIsPushedToTheNextPage() throws Exception {
        // 先一个小组占位,紧接着一个自己就超预算的大组。只检查读取前的旧 cost 时,大组会被整条
        // 加进来 —— 而且此时页面已有两条,blocks_omitted 的判断不成立,完整调色板照发
        String small = writeGroup("20260101-000009-000", 1, 2);
        String huge = writeGroup("20260101-000001-000", 30, 20_000);
        RecycleStore store = store();

        JsonObject first = store.view("", 100);
        assertEquals(List.of(small), ids(first), "超预算的大组必须留到下一页");
        assertFalse(first.getAsJsonArray("groups").get(0).getAsJsonObject().has("blocks_omitted"));
        // 被拒的候选不能把自己的调色板条目留在表里 —— 那就是没人引用又没记账的字节。
        // 小组自己用了 2 种方块,大组的 20000 种一条都不该留下
        assertEquals(2, first.getAsJsonArray("block_palette").size(),
                "第一页只该有小组用到的那两种方块");

        JsonObject second = store.view(first.get("next_cursor").getAsString(), 100);
        assertEquals(List.of(huge), ids(second), "大组单独成页");
        assertTrue(second.getAsJsonArray("groups").get(0).getAsJsonObject()
                .get("blocks_omitted").getAsBoolean(), "单独成页才轮得到只发元数据");
        assertEquals(0, second.getAsJsonArray("block_palette").size());
    }

    @Test
    void pageLimitIsCappedAndPaletteIsPerPage() throws Exception {
        for (int i = 1; i <= 3; i++) writeGroup(String.format("20260101-00000%d-000", i), 1, 2);
        RecycleStore store = store();
        JsonObject page = store.view("", 9999);
        assertEquals(RecycleStore.PAGE_LIMIT_MAX, page.get("page_limit").getAsInt(), "客户端要不到超过硬上限的页");
        // 调色板只覆盖这一页出现过的方块,blk 索引指向的就是本页的表
        assertEquals(2, page.getAsJsonArray("block_palette").size());
        for (var group : page.getAsJsonArray("groups")) {
            for (var body : group.getAsJsonObject().getAsJsonArray("bodies")) {
                JsonObject value = body.getAsJsonObject();
                assertFalse(value.has("block_ids"), "原始 block_ids 不该发给前端");
                for (var index : value.getAsJsonArray("blk")) {
                    assertTrue(index.getAsInt() < 2, "blk 索引必须落在本页调色板内");
                }
            }
        }
    }

    private static void deleteTree(Path directory) throws Exception {
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
