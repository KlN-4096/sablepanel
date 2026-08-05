package com.klnon.sablepanel.panel.data;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.PanelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敌意输入下的响应上限。
 * <p>
 * 前面几轮每次都是"某个具体字段忘了记账"被逐个找出来:clone_sets 的名称、组名、
 * 摘要里没截断的 dims、单个成员的调色板……逐条补测试只能证明那一条被修了。
 * 这里换个角度:把能塞进 NBT / 清单的每一类字符串都撑到极限,只断言一件事 ——
 * 序列化之后小于 32 MiB。字段名是什么无所谓,漏了哪个都会在这里响。
 * <p>
 * 32 MiB 的出处是 {@code PanelWire.MAX_FRAME_BYTES}:越过它传输层直接拒发,
 * 面板表现为列表永远打不开,而且每次刷新都重新制造一次同样的压力。
 */
class ResponseLimitAdversarialTest {

    private static final int WIRE_LIMIT = 32 * 1024 * 1024;
    /** NBT 字符串的上限就是 65535 字节 */
    private static final String HOSTILE = "N".repeat(65_000);

    @TempDir
    Path root;

    private static long bytes(JsonObject view) {
        return view.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static void assertUnderWireLimit(String what, JsonObject view) {
        long size = bytes(view);
        assertTrue(size < WIRE_LIMIT, what + " 必须落在 32 MiB 协议上限内,实际 " + size);
        // ResponseGuard 是最后一道闸,不是达标的手段:它响就说明构建阶段有字段没记账。
        // 少了这条断言,任何漏记的字段都会被兜底悄悄抹平,这个测试也就废了
        assertFalse(view.has("over_limit"),
                what + " 必须靠构建阶段的预算就落在上限内,而不是靠兜底降级");
    }

    // ---------- /api/bodies ----------

    private static JsonObject bodies(List<DiskScanner.DiskEntry> entries) {
        BodyIndex index = new BodyIndex();
        index.updateDisk(entries);
        return index.view();
    }

    private static DiskScanner.DiskEntry entry(UUID uuid, int slot, String name, List<String> blockIds) {
        return new DiskScanner.DiskEntry(
                new DiskScanner.EntryKey("minecraft:overworld", 0, 0, 0, slot), uuid, name,
                new double[]{1, 2, 3}, new double[]{4, 5, 6}, blockIds.size(), List.of(), true, 0, 0,
                blockIds, false, 0, 0);
    }

    @Test
    void hostileDisplayNamesOnEveryBody() {
        List<DiskScanner.DiskEntry> entries = new ArrayList<>();
        for (int i = 0; i < 2000; i++) entries.add(entry(UUID.randomUUID(), i, HOSTILE + i, List.of("sp:stone")));
        assertUnderWireLimit("超长体名称", bodies(entries));
    }

    @Test
    void hostileNamesThatAlsoFormCloneSets() {
        // 同名同块数同包围盒 = clone set;名称同时出现在成员、组名和 clone set 三处
        List<DiskScanner.DiskEntry> entries = new ArrayList<>();
        int slot = 0;
        for (int set = 0; set < 500; set++) {
            for (int i = 0; i < 2; i++) {
                entries.add(entry(UUID.randomUUID(), slot++, HOSTILE + set, List.of("sp:stone")));
            }
        }
        assertUnderWireLimit("超长名称的克隆集合", bodies(entries));
    }

    @Test
    void hostileBlockIdsOnASingleBody() {
        // 方块 id 直接来自 NBT 的 Name;解析不出来时 BlockNames 会把原串同时放进 en/zh,
        // 一条调色板记录就是三份。全堆在同一个体上,预算只在成员入口查一次是拦不住的
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 300; i++) ids.add("sp:" + HOSTILE + i);
        assertUnderWireLimit("单个体的超长方块 id", bodies(List.of(entry(UUID.randomUUID(), 0, "n", ids))));
    }

    @Test
    void hostileBlockIdsSpreadOverManyBodies() {
        List<DiskScanner.DiskEntry> entries = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            entries.add(entry(UUID.randomUUID(), i, "n", List.of("sp:" + HOSTILE + i)));
        }
        assertUnderWireLimit("分散在多个体上的超长方块 id", bodies(entries));
    }

    // ---------- /api/recycle ----------

    /**
     * 直接铺 manifest:字段值全部由用例控制,模拟磁盘上被写坏/被构造的清单。
     *
     * @param field    组级字段名,{@code value} 放在这里
     * @param bodyName 每个体的 display_name,单独给 —— 和组级字段一起撑大会越过
     *                 {@code MANIFEST_MAX_BYTES},那走的是"清单读不了"的分支,测不到本意
     */
    private String writeGroup(String stamp, String field, String value, int bodies, String bodyName)
            throws Exception {
        String id = stamp + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path dir = Files.createDirectories(this.root.resolve(id));
        StringBuilder manifest = new StringBuilder();
        manifest.append("{\"version\":1,\"id\":\"").append(id).append("\",\"state\":\"deleted\"")
                .append(",\"deleted_at\":1,\"").append(field).append("\":\"").append(value).append('"')
                .append(",\"bodies\":[");
        for (int b = 0; b < bodies; b++) {
            if (b > 0) manifest.append(',');
            manifest.append("{\"uuid\":\"").append(UUID.randomUUID())
                    .append("\",\"blocks\":1,\"name\":\"").append(bodyName)
                    .append("\",\"block_ids\":[],\"backups\":[\"a.nbt.gz\"]}");
        }
        manifest.append("]}");
        Files.writeString(dir.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
        return id;
    }

    @Test
    void hostileDimsFieldCannotEscapeThroughTheSummary() throws Exception {
        // 摘要从前是"复制白名单字段再截断 name",于是同样来自磁盘、同样没有长度限制的
        // dims 整份进来 —— 一个 34 MiB 的 dims 就能让「固定尺寸摘要」自己越过协议上限
        writeGroup("20260101-000009-000", "dims", "D".repeat(35_000_000), 1, "n");
        JsonObject page = new RecycleStore(new PanelConfig(), this.root).view("", 100);
        assertUnderWireLimit("超长 dims 的回收组", page);
    }

    @Test
    void hostileStateFieldCannotEscapeThroughTheSummary() throws Exception {
        writeGroup("20260101-000009-000", "state", "S".repeat(35_000_000), 1, "n");
        JsonObject page = new RecycleStore(new PanelConfig(), this.root).view("", 100);
        assertUnderWireLimit("超长 state 的回收组", page);
    }

    @Test
    void hostileBodyNamesAcrossManyRecycleGroups() throws Exception {
        for (int i = 1; i <= 9; i++) {
            writeGroup("2026010" + i + "-000000-000", "dims", "minecraft:overworld", 60, HOSTILE + i);
        }
        RecycleStore store = new RecycleStore(new PanelConfig(), this.root);
        String cursor = "";
        for (int page = 0; page < 20; page++) {
            JsonObject view = store.view(cursor, 200);
            assertUnderWireLimit("回收站第 " + page + " 页", view);
            cursor = view.get("next_cursor").getAsString();
            if (cursor.isEmpty()) return;
        }
    }
}
