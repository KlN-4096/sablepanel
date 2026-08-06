package com.klnon.sablepanel.panel.recycle;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.klnon.sablepanel.panel.storage.DiskScanner;

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
            JsonObject page = store.view("latest", cursor, 3);
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
        JsonObject page = empty.view("latest", "", 0);
        assertEquals(0, page.getAsJsonArray("groups").size());
        assertEquals("", page.get("next_cursor").getAsString());
        assertEquals(0, page.get("total_groups").getAsInt());

        writeGroup("20260101-000001-000", 1, 1);
        writeGroup("20260101-000002-000", 1, 1);
        JsonObject full = store().view("latest", "", 10);
        assertEquals(2, full.getAsJsonArray("groups").size());
        assertEquals("", full.get("next_cursor").getAsString(), "取完了就不该再给游标");

        // 游标指向最后一个组 -> 后面没有内容了
        String lastId = ids(full).get(1);
        JsonObject beyond = store().view("latest", lastId, 10);
        assertEquals(0, beyond.getAsJsonArray("groups").size());
        assertEquals("", beyond.get("next_cursor").getAsString());
    }

    @Test
    void staleOrInvalidCursorDoesNotBreakPaging() throws Exception {
        String older = writeGroup("20260101-000001-000", 1, 1);
        String newer = writeGroup("20260101-000009-000", 1, 1);
        RecycleStore store = store();

        // 游标那一组在两次请求之间被清理掉:keyset 语义下仍然只返回它之后的组
        JsonObject afterDeleted = store.view("latest", newer, 10);
        assertEquals(List.of(older), ids(afterDeleted));
        deleteTree(this.root.resolve(newer));
        assertEquals(List.of(older), ids(store.view("latest", newer, 10)), "游标组消失后翻页不能失败");

        // 格式非法的游标退回第一页,而不是抛错把整个回收站页面打空
        JsonObject garbage = store.view("latest", "../../etc/passwd", 10);
        assertEquals(List.of(older), ids(garbage));
        assertFalse(garbage.has("error"));
    }

    @Test
    void oversizedSingleGroupStillMakesProgress() throws Exception {
        // 方块构成占绝大部分:退到只发元数据就装得下,成员明细仍然要在
        String huge = writeGroup("20260101-000009-000", 30, 20_000);
        String small = writeGroup("20260101-000001-000", 1, 2);
        RecycleStore store = store();

        JsonObject first = store.view("latest", "", 100);
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
        assertEquals(2, store.view("latest", "", 100).getAsJsonArray("groups").size());

        store.purgeGroups(List.of(gone));

        JsonObject after = store.view("latest", "", 100);
        assertEquals(List.of(kept), ids(after), "彻底删除必须让缓存立刻失效");
        assertEquals(1, after.get("total_groups").getAsInt());
        assertEquals(1, after.get("latest_groups").getAsInt());
        assertFalse(Files.exists(this.root.resolve(gone)), "目录树要真的删干净");
    }

    @Test
    void capacityNeverDeletesGroupsAutomatically() throws Exception {
        // 超过上限只拒绝新的备份，不由后台淘汰任何已有回收组。
        // 现有组始终保留，彻底删除只能由人工操作。
        // 容量统计仍反映真实磁盘占用。
        PanelConfig config = new PanelConfig();
        config.recycleMaxFiles = 2;
        for (int i = 1; i <= 4; i++) {
            String id = writeGroup(String.format("20260101-00000%d-000", i), 1, 2);
            Files.writeString(this.root.resolve(id).resolve("a.nbt.gz"), "x", StandardCharsets.UTF_8);
        }
        RecycleStore store = new RecycleStore(config, this.root);
        JsonObject before = store.view("latest", "", 100);
        assertEquals(4, before.getAsJsonArray("groups").size(), "超限也不能自动删除");
        assertEquals(4, before.get("file_count").getAsInt());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> store.stage(List.of(new RecycleStore.Source(UUID.randomUUID(),
                        RecycleStore.DEFAULT_DIMENSION, new DiskScanner.EntryKey(
                                RecycleStore.DEFAULT_DIMENSION, 0, 0, 0, 0), new net.minecraft.nbt.CompoundTag())), Map.of()));
    }

    @Test
    void everyGroupIsStillReachableWhenPagingFromACursor() throws Exception {
        // 游标定位改成了二分。二分写错的典型表现是漏掉边界那一条或死循环,
        // 所以这里逐页走完并核对总数
        Set<String> written = new LinkedHashSet<>();
        for (int i = 1; i <= 9; i++) written.add(writeGroup(String.format("2026010%d-000000-000", i), 1, 2));
        RecycleStore store = store();
        Set<String> seen = new LinkedHashSet<>();
        String cursor = "";
        int pages = 0;
        do {
            JsonObject page = store.view("latest", cursor, 2);
            for (String id : ids(page)) assertTrue(seen.add(id), "同一组不能翻到两次: " + id);
            cursor = page.get("next_cursor").getAsString();
            assertTrue(++pages <= 12, "翻页必须收敛");
        } while (!cursor.isEmpty());
        assertEquals(written, seen, "二分定位不能漏掉边界上的组");

        // 游标指向一个已经不存在的 id(两次请求之间被清掉):keyset 语义下仍要继续往后翻。
        // 后缀 zzzzzzzz 排在 05 那组的真实随机后缀之后,所以 05 自己也在游标之后 —— 05..01 共 5 组
        JsonObject afterStale = store.view("latest", "20260105-000000-000-zzzzzzzz", 100);
        assertEquals(5, afterStale.getAsJsonArray("groups").size(), "失效游标要落到同一个位置");
    }

    /** 一路翻到底,收集所有翻得到的组 id */
    private static Set<String> walkAllIds(RecycleStore store) {
        Set<String> seen = new LinkedHashSet<>();
        String cursor = "";
        for (int page = 0; page < 20; page++) {
            JsonObject view = store.view("latest", cursor, 100);
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

        JsonObject first = store.view("latest", "", 100);
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
            JsonObject page = store.view("latest", cursor, 100);
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

        JsonObject first = store.view("latest", "", 100);
        assertEquals(List.of(small), ids(first), "超预算的大组必须留到下一页");
        assertFalse(first.getAsJsonArray("groups").get(0).getAsJsonObject().has("blocks_omitted"));
        // 被拒的候选不能把自己的调色板条目留在表里 —— 那就是没人引用又没记账的字节。
        // 小组自己用了 2 种方块,大组的 20000 种一条都不该留下
        assertEquals(2, first.getAsJsonArray("block_palette").size(),
                "第一页只该有小组用到的那两种方块");

        JsonObject second = store.view("latest", first.get("next_cursor").getAsString(), 100);
        assertEquals(List.of(huge), ids(second), "大组单独成页");
        assertTrue(second.getAsJsonArray("groups").get(0).getAsJsonObject()
                .get("blocks_omitted").getAsBoolean(), "单独成页才轮得到只发元数据");
        assertEquals(0, second.getAsJsonArray("block_palette").size());
    }

    @Test
    void pageLimitIsCappedAndPaletteIsPerPage() throws Exception {
        for (int i = 1; i <= 3; i++) writeGroup(String.format("20260101-00000%d-000", i), 1, 2);
        RecycleStore store = store();
        JsonObject page = store.view("latest", "", 9999);
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

    /**
     * 声明的单页预算是 2 MiB,但从前只累计候选组和新增调色板 —— 外层统计字段、两个数组的
     * 括号键名、组之间的逗号都没记账,所以真实上限是"2 MiB 加一份没人算过的外壳"。
     * 走正常准入路径(每组自己都装得下)时,整页必须落在声明的边界内。
     * <p>
     * 唯一的例外是"单组自己就超预算":那一条无条件发出,否则它永远翻不过去。
     * 那条路径由 {@code oversizedSingleGroupStillMakesProgress} 覆盖,断言的是另一个界。
     */
    @Test
    void everyOrdinaryPageStaysInsideTheDeclaredTwoMiBBudget() throws Exception {
        for (int i = 1; i <= 6; i++) writeGroup("2026010" + i + "-000000-000", 60, 1500, 200);
        RecycleStore store = store();
        String cursor = "";
        int pages = 0;
        do {
            JsonObject page = store.view("latest", cursor, 200);
            // 退让标记就是例外路径的指纹;本用例的每组自己都装得下,一个都不该出现
            for (var element : page.getAsJsonArray("groups")) {
                JsonObject group = element.getAsJsonObject();
                assertFalse(group.has("blocks_omitted") || group.has("bodies_omitted"),
                        "不该走单组强行发出那条例外路径: " + group.get("id").getAsString());
            }
            assertTrue(pageBytes(page) < 2 * 1024 * 1024,
                    "第 " + pages + " 页越过声明的 2 MiB 预算,实际 " + pageBytes(page));
            cursor = page.get("next_cursor").getAsString();
        } while (!cursor.isEmpty() && ++pages < 12);
        assertTrue(pages > 0, "用例必须真的翻了页,否则上面的断言是空的");
    }

    /**
     * 目录清单只按写入失效,不带 TTL。
     * <p>
     * 这里钉的是取消 TTL 之后唯一会坏的东西:失效点是否完整。回收目录由本模块独占,
     * 背着 store 直接铺到磁盘的目录本来就不该被看见 —— 从前靠 30 秒 TTL "碰巧"能看见,
     * 代价是一个没有任何写入的回收站每 30 秒重扫一次全盘。
     */
    @Test
    void theDirectoryIndexIsCachedUntilAWriteNotUntilATimerExpires() throws Exception {
        String first = writeGroup("20260101-000000-000", 1, 2);
        RecycleStore store = store();
        assertEquals(List.of(first), ids(store.view("latest", "", 100)));

        writeGroup("20260102-000000-000", 1, 2);
        assertEquals(List.of(first), ids(store.view("latest", "", 100)), "没有经过本模块的写入就不该重扫全盘");

        store.markRestored(first);   // 一次真正的写入
        assertEquals(2, ids(store.view("latest", "", 100)).size(), "写入之后必须立刻反映,不能等 TTL");
    }

    private static void deleteTree(Path directory) throws Exception {
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
