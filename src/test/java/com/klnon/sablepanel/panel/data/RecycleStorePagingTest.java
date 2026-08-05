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
        String id = stamp + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path dir = this.root.resolve(id);
        Files.createDirectories(dir);
        StringBuilder manifest = new StringBuilder();
        manifest.append("{\"version\":1,\"id\":\"").append(id).append("\",\"state\":\"deleted\"")
                .append(",\"deleted_at\":").append(System.currentTimeMillis()).append(",\"bodies\":[");
        for (int b = 0; b < bodies; b++) {
            if (b > 0) manifest.append(',');
            manifest.append("{\"uuid\":\"").append(UUID.randomUUID()).append("\",\"blocks\":1,\"block_ids\":[");
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
        // 一个组自己就超过整页的方块预算(20 万条索引),后面还有正常组
        String huge = writeGroup("20260101-000009-000", 105, 2000);
        String small = writeGroup("20260101-000001-000", 1, 2);
        RecycleStore store = store();

        JsonObject first = store.view("", 100);
        assertEquals(List.of(huge), ids(first), "超预算的组必须单独成页,不能被挤到永远翻不到");
        String cursor = first.get("next_cursor").getAsString();
        assertEquals(huge, cursor);

        JsonObject second = store.view(cursor, 100);
        assertEquals(List.of(small), ids(second));
        assertEquals("", second.get("next_cursor").getAsString());
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
