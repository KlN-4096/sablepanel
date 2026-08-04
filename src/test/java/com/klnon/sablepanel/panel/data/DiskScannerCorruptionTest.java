package com.klnon.sablepanel.panel.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现三方用户现场:sublevels 目录里存在头部截断的区域文件(服务器创建文件后
 * 写入前崩溃的残留)。容错边界:仅"头部截断的缺失部分视为空"(sable 同款前缀语义,
 * 解析逐位一致)降级为警告;文件打不开、已声明 span 但条目读不出等情况保持严格上抛
 * —— 那些可能只是权限/瞬态 IO,数据对 sable 依然可见,跳过会让删除验收误报成功。
 */
class DiskScannerCorruptionTest {

    @TempDir
    Path root;

    private static final String DIM = "minecraft:overworld";

    // ---- .slvlr / .slvls 夹具:4096 字节头(1024 个 span=(startSector<<8)|sectors)+ 扇区数据 ----

    private static byte[] gzipNbt(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    /** 按 sable 布局写存储文件:条目从头部之后的首个扇区起顺排。 */
    private static void writeStorageFile(Path file, int sectorSize, Map<Integer, byte[]> payloads)
            throws IOException {
        int headerSectors = 4096 / sectorSize;
        int nextSector = headerSectors;
        ByteBuffer header = ByteBuffer.allocate(4096);
        List<byte[]> records = new ArrayList<>();
        List<Integer> sectorStarts = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> entry : payloads.entrySet()) {
            byte[] payload = entry.getValue();
            int recordSize = 4 + 1 + payload.length;
            int sectors = Math.max(1, (recordSize + sectorSize - 1) / sectorSize);
            header.putInt(entry.getKey() * 4, (nextSector << 8) | sectors);
            ByteBuffer record = ByteBuffer.allocate(sectors * sectorSize);
            record.putInt(payload.length + 1);
            record.put((byte) 0);
            record.put(payload);
            records.add(record.array());
            sectorStarts.add(nextSector);
            nextSector += sectors;
        }
        ByteBuffer out = ByteBuffer.allocate(nextSector * sectorSize);
        out.put(header.array());
        for (int i = 0; i < records.size(); i++) {
            out.position(sectorStarts.get(i) * sectorSize);
            out.put(records.get(i));
        }
        Files.write(file, out.array());
    }

    private static CompoundTag bodyTag(UUID uuid, int plotX, int plotZ) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", uuid);
        CompoundTag plot = new CompoundTag();
        plot.putInt("plot_x", plotX);
        plot.putInt("plot_z", plotZ);
        tag.put("plot", plot);
        return tag;
    }

    private static CompoundTag pointerTag(int... packed) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("pointers", packed.clone());
        return tag;
    }

    private Path dimDir() throws IOException {
        Path dir = root.resolve("sublevels");
        Files.createDirectories(dir);
        return dir;
    }

    // ---- 现场复现:一个截断的 .slvlr 不得让指针普查整体失败 ----

    @Test
    void truncatedPointerFileYieldsWarningNotFailure() throws Exception {
        Path dir = dimDir();
        DiskScanner.EntryKey target = new DiskScanner.EntryKey(DIM, 0, 0, 0, 5);
        writeStorageFile(dir.resolve("r.0.0.slvlr"), 128,
                Map.of(7, gzipNbt(pointerTag(5))));           // storage 0, index 5
        Files.write(dir.resolve("r.-3.-6.slvlr"), new byte[100]); // 崩溃残留:头部截断

        List<String> warnings = new ArrayList<>();
        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> located =
                DiskScanner.locatePointersStrict(Map.of(DIM, dir), Set.of(target), warnings);

        assertEquals(1, located.getOrDefault(target, List.of()).size(),
                "健康文件里的指针必须照常返回");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("r.-3.-6.slvlr")),
                "截断文件必须产生警告: " + warnings);
    }

    @Test
    void zeroByteStorageFileYieldsWarningNotFailure() throws Exception {
        Path dir = dimDir();
        UUID alive = UUID.randomUUID();
        writeStorageFile(dir.resolve("r.0.0.0.slvls"), 4096,
                Map.of(0, gzipNbt(bodyTag(alive, 1, 2))));
        Files.createFile(dir.resolve("r.1.1.0.slvls")); // CREATE 后写入前崩溃的 0 字节残留

        List<String> warnings = new ArrayList<>();
        Map<UUID, List<DiskScanner.EntryMeta>> meta =
                DiskScanner.scanEntryMetaStrict(Map.of(DIM, dir), warnings);

        assertEquals(1, meta.getOrDefault(alive, List.of()).size(), "健康条目必须照常返回");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("r.1.1.0.slvls")),
                "0 字节文件必须产生警告: " + warnings);
    }

    @Test
    void corruptEntryFailsScanStrictly() throws Exception {
        Path dir = dimDir();
        UUID alive = UUID.randomUUID();
        Path file = dir.resolve("r.0.0.0.slvls");
        writeStorageFile(file, 4096, Map.of(0, gzipNbt(bodyTag(alive, 1, 2))));
        // 手工把槽位 3 的 span 指向文件末尾之外:已声明却读不出的条目可能只是瞬态 IO/并发写,
        // sable 侧仍可见 —— 必须整体失败,不得降级为"该条目不存在"
        byte[] raw = Files.readAllBytes(file);
        ByteBuffer.wrap(raw).putInt(3 * 4, (999 << 8) | 1);
        Files.write(file, raw);

        List<String> warnings = new ArrayList<>();
        IOException error = assertThrows(IOException.class,
                () -> DiskScanner.scanEntryMetaStrict(Map.of(DIM, dir), warnings));
        assertTrue(error.getMessage().contains("#3"), "错误必须指认槽位: " + error.getMessage());
    }

    /** .slvlr 指针普查同样严格:声明了 span 的记录读不出 → 验收不得当作"无残留指针"。 */
    @Test
    void corruptPointerRecordFailsCensusStrictly() throws Exception {
        Path dir = dimDir();
        DiskScanner.EntryKey target = new DiskScanner.EntryKey(DIM, 0, 0, 0, 5);
        Path file = dir.resolve("r.0.0.slvlr");
        writeStorageFile(file, 128, Map.of(7, gzipNbt(pointerTag(5))));
        byte[] raw = Files.readAllBytes(file);
        ByteBuffer.wrap(raw).putInt(2 * 4, (999999 << 8) | 1);
        Files.write(file, raw);

        List<String> warnings = new ArrayList<>();
        assertThrows(IOException.class,
                () -> DiskScanner.locatePointersStrict(Map.of(DIM, dir), Set.of(target), warnings));
    }

    /** 截断头的可读前缀里若有 span 而条目读不出,同样必须失败(容忍仅限"缺失部分视为空")。 */
    @Test
    void spanInTruncatedPrefixWithUnreadableEntryFails() throws Exception {
        Path dir = dimDir();
        UUID alive = UUID.randomUUID();
        Path file = dir.resolve("r.0.0.0.slvls");
        writeStorageFile(file, 4096, Map.of(0, gzipNbt(bodyTag(alive, 1, 2))));
        byte[] raw = Files.readAllBytes(file);
        // 只保留头部前 2048 字节:文件 2048 < 4096 真正进入截断分支,
        // 槽位 0 的 span 落在可读前缀内,但它声明的数据(offset 4096)已在文件之外
        Files.write(file, java.util.Arrays.copyOf(raw, 2048));

        List<String> warnings = new ArrayList<>();
        IOException error = assertThrows(IOException.class,
                () -> DiskScanner.scanEntryMetaStrict(Map.of(DIM, dir), warnings));
        assertTrue(error.getMessage().contains("#0"), "错误必须指认槽位: " + error.getMessage());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("存储头截断(2048 字节)")),
                "抛错前应已记录截断警告: " + warnings);
    }

    /** 健康目录:结果完整且零警告(基线,防止容错逻辑误伤正常文件)。 */
    @Test
    void healthyFilesProduceNoWarnings() throws Exception {
        Path dir = dimDir();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<Integer, byte[]> entries = new LinkedHashMap<>();
        entries.put(0, gzipNbt(bodyTag(first, 1, 2)));
        entries.put(9, gzipNbt(bodyTag(second, 3, 4)));
        writeStorageFile(dir.resolve("r.0.0.0.slvls"), 4096, entries);
        writeStorageFile(dir.resolve("r.0.0.slvlr"), 128,
                Map.of(1, gzipNbt(pointerTag(0, 9))));

        List<String> scanWarnings = new ArrayList<>();
        Map<UUID, List<DiskScanner.EntryMeta>> meta =
                DiskScanner.scanEntryMetaStrict(Map.of(DIM, dir), scanWarnings);
        assertEquals(1, meta.get(first).size());
        assertEquals(1, meta.get(second).size());
        assertEquals(List.of(), scanWarnings);

        List<String> pointerWarnings = new ArrayList<>();
        DiskScanner.EntryKey firstKey = new DiskScanner.EntryKey(DIM, 0, 0, 0, 0);
        DiskScanner.EntryKey secondKey = new DiskScanner.EntryKey(DIM, 0, 0, 0, 9);
        Map<DiskScanner.EntryKey, Integer> counts = DiskScanner.countPointersStrict(
                Map.of(DIM, dir), Set.of(firstKey, secondKey), pointerWarnings);
        assertEquals(1, counts.get(firstKey));
        assertEquals(1, counts.get(secondKey));
        assertEquals(List.of(), pointerWarnings);
    }

    // ---- readEntryTag 改成按头部偏移直读单槽后的回归:必须仍然只取到目标那一条 ----

    @Test
    void readEntryTagSeeksTheRequestedSlotOnly() throws Exception {
        Path dir = dimDir();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        writeStorageFile(dir.resolve("r.0.0.0.slvls"), 4096, Map.of(
                0, gzipNbt(bodyTag(a, 1, 1)),
                7, gzipNbt(bodyTag(b, 2, 2)),
                900, gzipNbt(bodyTag(c, 3, 3))));

        assertEquals(a, DiskScanner.readEntryTag(dir, new DiskScanner.EntryKey(DIM, 0, 0, 0, 0)).getUUID("uuid"));
        assertEquals(b, DiskScanner.readEntryTag(dir, new DiskScanner.EntryKey(DIM, 0, 0, 0, 7)).getUUID("uuid"));
        assertEquals(c, DiskScanner.readEntryTag(dir, new DiskScanner.EntryKey(DIM, 0, 0, 0, 900)).getUUID("uuid"));
        assertNull(DiskScanner.readEntryTag(dir, new DiskScanner.EntryKey(DIM, 0, 0, 0, 5)),
                "空槽位必须返回 null,不能串到邻近条目");
        assertNull(DiskScanner.readEntryTag(dir, new DiskScanner.EntryKey(DIM, 0, 0, 0, 5000)),
                "越界索引必须返回 null 而不是越过头部读脏数据");
        assertNull(DiskScanner.readEntryTag(dir, new DiskScanner.EntryKey(DIM, 9, 9, 0, 0)),
                "文件不存在必须返回 null");
    }

    // ---- 批量定位必须与逐个定位等价(这是把 N 趟全盘扫描压成 1 趟的前提) ----

    @Test
    void batchLocateMatchesSingleLocate() throws Exception {
        Path dir = dimDir();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), missing = UUID.randomUUID();
        writeStorageFile(dir.resolve("r.0.0.0.slvls"), 4096,
                Map.of(0, gzipNbt(bodyTag(a, 1, 1)), 3, gzipNbt(bodyTag(b, 2, 2))));
        // 指针:chunk 索引 1 引用 storage 0 的 index 0 与 3
        writeStorageFile(dir.resolve("r.0.0.slvlr"), 128, Map.of(1, gzipNbt(pointerTag(0, 3))));

        Map<UUID, DiskScanner.LocatedEntry> batch =
                DiskScanner.locateEntries(DIM, dir, Set.of(a, b, missing));
        assertEquals(DiskScanner.locateEntry(DIM, dir, a).key(), batch.get(a).key());
        assertEquals(DiskScanner.locateEntry(DIM, dir, b).key(), batch.get(b).key());
        assertNull(batch.get(missing), "不存在的 uuid 不得出现在批量结果里");

        Map<UUID, DiskScanner.LiveLocation> live =
                DiskScanner.locateLiveAll(DIM, dir, Set.of(a, b, missing));
        assertEquals(DiskScanner.locateLive(DIM, dir, a), live.get(a));
        assertEquals(DiskScanner.locateLive(DIM, dir, b), live.get(b));
        assertNull(live.get(missing));
    }
}
