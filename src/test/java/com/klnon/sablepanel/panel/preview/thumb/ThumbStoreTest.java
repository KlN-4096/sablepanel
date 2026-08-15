package com.klnon.sablepanel.panel.preview.thumb;

import com.klnon.sablepanel.panel.storage.DiskScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 磁盘缓存(签名/LRU/重启重载) + 前端上传收图校验 + 增量签名纯函数。 */
class ThumbStoreTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    @TempDir
    Path dir;

    @Test
    void putReadAndReloadAcrossRestart() throws Exception {
        UUID ok = UUID.randomUUID();
        ThumbStore store = new ThumbStore(dir, ThumbStore.DEFAULT_MAX_BYTES);
        assertNull(store.sig(ok));
        store.put(ok, "sig-1", new byte[]{1, 2, 3});

        assertArrayEquals(new byte[]{1, 2, 3}, store.read(ok));
        assertEquals("sig-1", store.sig(ok));

        // 重启:索引与文件都从盘上回来
        ThumbStore reloaded = new ThumbStore(dir, ThumbStore.DEFAULT_MAX_BYTES);
        assertArrayEquals(new byte[]{1, 2, 3}, reloaded.read(ok));
        assertEquals("sig-1", reloaded.sig(ok));

        // 文件被外力删掉:条目作废等重渲,而不是永远 404
        Files.delete(dir.resolve(ok + ".png"));
        assertNull(reloaded.read(ok));
        assertNull(reloaded.sig(ok));
    }

    @Test
    void overwriteUpdatesTheIncrementalByteBudget() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        ThumbStore store = new ThumbStore(dir, 100);
        store.put(first, "large", new byte[60]);
        store.put(first, "small", new byte[20]);
        store.put(second, "second", new byte[70]);

        assertNotNull(store.read(first), "覆盖后应扣除旧文件尺寸");
        assertNotNull(store.read(second));
    }

    @Test
    void appendIndexReplaysLatestRecordAndCompactsOnRestart() throws Exception {
        UUID uuid = UUID.randomUUID();
        ThumbStore store = new ThumbStore(dir, ThumbStore.DEFAULT_MAX_BYTES);
        store.put(uuid, "old", new byte[]{1});
        store.put(uuid, "new", new byte[]{2});
        assertEquals(2, Files.readAllLines(dir.resolve("index.tsv")).size(), "运行期只追加变更记录");

        ThumbStore reloaded = new ThumbStore(dir, ThumbStore.DEFAULT_MAX_BYTES);
        assertEquals("new", reloaded.sig(uuid));
        assertArrayEquals(new byte[]{2}, reloaded.read(uuid));
        assertEquals(1, Files.readAllLines(dir.resolve("index.tsv")).size(), "启动时压缩追加日志");
    }

    @Test
    void lruEvictsOldestWhenOverBudget() throws Exception {
        ThumbStore store = new ThumbStore(dir, 100);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        store.put(first, "a", new byte[60]);
        Files.setLastModifiedTime(dir.resolve(first + ".png"),
                java.nio.file.attribute.FileTime.fromMillis(1_000));
        store.put(second, "b", new byte[60]);
        Files.setLastModifiedTime(dir.resolve(second + ".png"),
                java.nio.file.attribute.FileTime.fromMillis(2_000));
        store.put(third, "c", new byte[60]);

        assertNull(store.read(first), "最旧的应被淘汰");
        assertNull(store.sig(first), "淘汰的条目索引也要摘掉,下轮才会重渲");
        assertNotNull(store.read(third));

        ThumbStore reloaded = new ThumbStore(dir, 100);
        assertNull(reloaded.sig(first), "淘汰 tombstone 必须跨重启生效");
        assertNotNull(reloaded.read(third));
    }

    @Test
    void legacyThreeColumnIndexLinesAreDropped() throws Exception {
        // 服务端渲染时代的索引是三列(uuid\tsig\tok|skip):升级后当没渲过,f1 签名反正全体失配
        UUID legacy = UUID.randomUUID(), current = UUID.randomUUID();
        Files.writeString(dir.resolve("index.tsv"),
                legacy + "\tr3|x\tok\n" + current + "\tf1|y\n垃圾行\nnot-a-uuid\ts\n");
        Files.write(dir.resolve(current + ".png"), PNG);
        ThumbStore store = new ThumbStore(dir, ThumbStore.DEFAULT_MAX_BYTES);
        assertNull(store.sig(legacy));
        assertEquals("f1|y", store.sig(current));
    }

    /* ===== 前端上传收图(ThumbService) ===== */

    @Test
    void acceptValidatesMagicAndSignature() throws Exception {
        UUID uuid = UUID.randomUUID();
        DiskScanner.DiskEntry entry = entry(uuid, "青鸢", new double[]{1, 2, 3}, 100, 5, 2, List.of("minecraft:stone"));
        ThumbService service = new ThumbService(dir,
                target -> target.equals(uuid) ? ThumbService.signature(List.of(entry)) : null);
        String sig = service.currentSig(uuid);
        assertNotNull(sig);

        assertEquals("thumb_invalid", service.accept(uuid, sig, new byte[]{1, 2, 3, 4, 5}), "不是 PNG");
        assertEquals("thumb_invalid", service.accept(uuid, sig, new byte[600 * 1024]), "超过尺寸上限");
        assertEquals("thumb_stale", service.accept(uuid, "f1|旧签名", PNG), "渲染期间体已变化");
        assertEquals("thumb_stale", service.accept(UUID.randomUUID(), sig, PNG), "体不在盘上");

        assertNull(service.accept(uuid, sig, PNG), "合法上传应收下");
        assertArrayEquals(PNG, service.read(uuid));
        assertEquals(sig, service.cachedSig(uuid));
    }

    /* ===== 增量签名纯函数 ===== */

    @Test
    void signatureIgnoresPositionAndNameButTracksContent() {
        UUID uuid = UUID.randomUUID();
        DiskScanner.DiskEntry base = entry(uuid, "青鸢", new double[]{1, 2, 3}, 100, 5, 2, List.of("minecraft:stone"));
        DiskScanner.DiskEntry moved = entry(uuid, "青鸢V2", new double[]{900, 80, -40}, 100, 5, 2, List.of("minecraft:stone"));
        DiskScanner.DiskEntry edited = entry(uuid, "青鸢", new double[]{1, 2, 3}, 101, 5, 2, List.of("minecraft:stone"));
        DiskScanner.DiskEntry repainted = entry(uuid, "青鸢", new double[]{1, 2, 3}, 100, 5, 2, List.of("minecraft:oak_planks"));

        assertEquals(ThumbService.signature(List.of(base)), ThumbService.signature(List.of(moved)),
                "移动/改名不得触发重渲");
        assertNotEquals(ThumbService.signature(List.of(base)), ThumbService.signature(List.of(edited)));
        assertNotEquals(ThumbService.signature(List.of(base)), ThumbService.signature(List.of(repainted)));
        assertNotEquals(ThumbService.signature(List.of(base)), ThumbService.signature(List.of(base, base)),
                "副本增减要触发重判");
    }

    @Test
    void signatureTracksShapeRearrangement() {
        // 凝灰岩矿场实测复现(2026-08-14):同一批方块重排(阶梯→竖塔),块数/方块实体/内容物/
        // 方块表全部不变,只有包围盒变了 —— 签名必须跟着变,否则缩略图永远停在旧形态
        UUID uuid = UUID.randomUUID();
        List<String> ids = List.of("create:andesite_casing", "minecraft:torch");
        DiskScanner.DiskEntry stairs = entry(uuid, "凝灰岩矿场", new double[]{4243, 66, -1323},
                new double[]{2, 3, 1}, 3, 1, 0, ids);
        DiskScanner.DiskEntry tower = entry(uuid, "凝灰岩矿场", new double[]{4243, 66, -1323},
                new double[]{1, 3, 1}, 3, 1, 0, ids);
        assertNotEquals(ThumbService.signature(List.of(stairs)), ThumbService.signature(List.of(tower)),
                "同料重排改变包围盒必须触发重渲");
        // 只动位置(包围盒不变)仍不得触发:飞船每次存档坐标都在变
        DiskScanner.DiskEntry flown = entry(uuid, "凝灰岩矿场", new double[]{9000, 120, 500},
                new double[]{1, 3, 1}, 3, 1, 0, ids);
        assertEquals(ThumbService.signature(List.of(tower)), ThumbService.signature(List.of(flown)));
    }

    private static DiskScanner.DiskEntry entry(UUID uuid, String name, double[] pos, int blocks,
                                               int blockEntities, int contents, List<String> blockIds) {
        return entry(uuid, name, pos, new double[]{4, 3, 4}, blocks, blockEntities, contents, blockIds);
    }

    private static DiskScanner.DiskEntry entry(UUID uuid, String name, double[] pos, double[] size, int blocks,
                                               int blockEntities, int contents, List<String> blockIds) {
        return new DiskScanner.DiskEntry(
                new DiskScanner.EntryKey("minecraft:overworld", 0, 0, 0, 7),
                uuid, name, pos, size, blocks, List.of(), true, 0, 0,
                blockIds, false, blockEntities, contents);
    }
}
