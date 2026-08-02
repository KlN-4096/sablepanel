package com.klnon.sablepanel.panel;

import com.klnon.sablepanel.SablePanel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 异步只读磁盘扫描:解析各维度 sublevels 的 .slvlr/.slvls,产出全量体条目快照。
 * 与 sable 并发写共存:只读打开、条目级容错(写入中的瞬态解析失败仅跳过)。
 * .slvls 按文件 mtime+size 增量缓存(重扫只解析变化过的文件);.slvlr 指针每轮全量重读(小文件)。
 */
public final class DiskScanner {
    private static final Pattern SLVLS = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.(\\d+)\\.slvls");
    private static final Pattern SLVLR = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.slvlr");

    public record EntryKey(String dim, int rx, int rz, int storage, int index) {
        public String id() {
            return dim + "/" + rx + "." + rz + "." + storage + ":" + index;
        }
    }

    /**
     * @param blockEntities 方块实体数量(机械/家具/容器的密度信号)
     * @param contents      任一方块实体里有物品或告示牌文字 —— 玩家资产的硬证据
     */
    public record DiskEntry(EntryKey key, UUID uuid, String name, double[] pos, double[] size,
                            int blocks, List<UUID> deps, boolean reachable, int plotX, int plotZ,
                            List<String> blockIds, boolean userData, int blockEntities, int contents) {
        DiskEntry withReachable(boolean r) {
            return r == this.reachable ? this
                    : new DiskEntry(this.key, this.uuid, this.name, this.pos, this.size, this.blocks,
                    this.deps, r, this.plotX, this.plotZ, this.blockIds, this.userData,
                    this.blockEntities, this.contents);
        }
    }

    private record FileCache(long mtime, long size, List<DiskEntry> entries) {
    }

    /** .slvls 解析缓存,key = 文件绝对路径 */
    private static final ConcurrentHashMap<String, FileCache> SLVLS_CACHE = new ConcurrentHashMap<>();

    /** dim -> sublevels 目录 */
    public static Map<String, Path> sublevelDirs(MinecraftServer server) {
        Map<String, Path> dirs = new ConcurrentHashMap<>();
        Path root = server.getWorldPath(LevelResource.ROOT);
        for (ServerLevel level : server.getAllLevels()) {
            Path dimRoot = DimensionType.getStorageFolder(level.dimension(), root);
            Path sub = dimRoot.resolve("sublevels");
            if (Files.isDirectory(sub)) {
                dirs.put(level.dimension().location().toString(), sub);
            }
        }
        return dirs;
    }

    /** 全量扫描,返回条目列表(reachable 由 .slvlr 指针决定) */
    public static List<DiskEntry> scan(Map<String, Path> dims) {
        List<DiskEntry> out = new ArrayList<>();
        Set<String> seenFiles = new HashSet<>();
        for (Map.Entry<String, Path> dimEntry : dims.entrySet()) {
            String dim = dimEntry.getKey();
            Path dir = dimEntry.getValue();
            try {
                Set<String> pointerIds = new HashSet<>();
                List<Path> slvlsFiles = new ArrayList<>();
                try (var stream = Files.list(dir)) {
                    for (Path p : stream.toList()) {
                        String fn = p.getFileName().toString();
                        Matcher mr = SLVLR.matcher(fn);
                        if (mr.matches()) {
                            int rx = Integer.parseInt(mr.group(1)), rz = Integer.parseInt(mr.group(2));
                            collectPointers(p, dir, rx, rz, pointerIds);
                            continue;
                        }
                        if (SLVLS.matcher(fn).matches()) {
                            slvlsFiles.add(p);
                        }
                    }
                }
                for (Path p : slvlsFiles) {
                    Matcher m = SLVLS.matcher(p.getFileName().toString());
                    if (!m.matches()) continue;
                    int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2)), si = Integer.parseInt(m.group(3));
                    String cacheKey = p.toAbsolutePath().toString();
                    seenFiles.add(cacheKey);
                    List<DiskEntry> parsed = null;
                    long mtime = 0, size = 0;
                    try {
                        mtime = Files.getLastModifiedTime(p).toMillis();
                        size = Files.size(p);
                        FileCache fc = SLVLS_CACHE.get(cacheKey);
                        if (fc != null && fc.mtime() == mtime && fc.size() == size) {
                            parsed = fc.entries();
                        }
                    } catch (Exception ignored) {
                    }
                    if (parsed == null) {
                        List<DiskEntry> fresh = new ArrayList<>();
                        forEachEntry(p, dir, rx, rz, 4096, (idx, tag) -> {
                            DiskEntry e = toEntry(new EntryKey(dim, rx, rz, si, idx), tag, false);
                            if (e != null) fresh.add(e);
                        });
                        parsed = fresh;
                        if (mtime > 0) SLVLS_CACHE.put(cacheKey, new FileCache(mtime, size, fresh));
                    }
                    for (DiskEntry e : parsed) {
                        out.add(e.withReachable(pointerIds.contains(
                                e.key().rx() + "." + e.key().rz() + "." + e.key().storage() + ":" + e.key().index())));
                    }
                }
            } catch (Exception e) {
                SablePanel.LOGGER.warn("sablepanel: disk scan failed for {}", dim, e);
            }
        }
        SLVLS_CACHE.keySet().retainAll(seenFiles);
        return out;
    }

    private static void collectPointers(Path file, Path dir, int rx, int rz, Set<String> pointerIds) {
        forEachEntry(file, dir, rx, rz, 128, (idx, tag) -> {
            int[] ptrs = tag.getIntArray("pointers");
            for (int pk : ptrs) {
                int si = (pk >> 16) & 0xFFFF, li = pk & 0xFFFF;
                pointerIds.add(rx + "." + rz + "." + si + ":" + li);
            }
        });
    }

    private interface EntryConsumer {
        void accept(int index, CompoundTag tag);
    }

    private static void forEachEntry(Path file, Path dir, int rx, int rz, int sectorSize, EntryConsumer consumer) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < 4096) return;
            ByteBuffer header = ByteBuffer.allocate(4096);
            ch.read(header, 0);
            header.flip();
            for (int idx = 0; idx < 1024; idx++) {
                int span = header.getInt(idx * 4);
                if (span == 0) continue;
                int start = (span >> 8) & 0xFFFFFF, length = span & 0xFF;
                if (start <= 0 || length <= 0) continue;
                long off = (long) start * sectorSize;
                int cap = length * sectorSize;
                if (off + 5 > fileSize) continue;
                try {
                    ByteBuffer buf = ByteBuffer.allocate(cap);
                    ch.read(buf, off);
                    buf.flip();
                    int size = buf.getInt();
                    byte dtype = buf.get();
                    byte[] payload;
                    if ((dtype & 0x10) != 0) {
                        Path ext = dir.resolve("r." + rx + "." + rz + ".r").resolve(idx + ".slvl");
                        if (!Files.isRegularFile(ext)) continue;
                        payload = Files.readAllBytes(ext);
                    } else {
                        if (size <= 0 || size - 1 > buf.remaining()) continue;
                        payload = new byte[size - 1];
                        buf.get(payload);
                    }
                    CompoundTag tag = NbtIo.readCompressed(
                            new DataInputStream(new ByteArrayInputStream(payload)), NbtAccounter.unlimitedHeap());
                    consumer.accept(idx, tag);
                } catch (Exception ignored) {
                    // 写入中的瞬态条目,跳过
                }
            }
        } catch (Exception e) {
            SablePanel.LOGGER.debug("sablepanel: cannot read {}", file, e);
        }
    }

    public record LiveLocation(EntryKey key, int chunkX, int chunkZ) {
    }

    /**
     * 实时定位某 uuid 在盘上的最新条目及引用它的 holding chunk(强制加载用,不依赖快照)。
     * 引用 chunk 必须来自 .slvlr 实际指针,snatchAndLoad 才能命中。
     */
    public static LiveLocation locateLive(String dim, Path dir, UUID uuid) {
        try {
            Map<String, int[]> refChunk = new HashMap<>();
            List<Path> slvlsFiles = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                for (Path p : stream.toList()) {
                    String fn = p.getFileName().toString();
                    Matcher mr = SLVLR.matcher(fn);
                    if (mr.matches()) {
                        int rx = Integer.parseInt(mr.group(1)), rz = Integer.parseInt(mr.group(2));
                        forEachEntry(p, dir, rx, rz, 128, (idx, tag) -> {
                            int cx = rx * 32 + (idx & 31), cz = rz * 32 + (idx >> 5);
                            for (int pk : tag.getIntArray("pointers")) {
                                refChunk.put(rx + "." + rz + "." + ((pk >> 16) & 0xFFFF) + ":" + (pk & 0xFFFF),
                                        new int[]{cx, cz});
                            }
                        });
                    } else if (SLVLS.matcher(fn).matches()) {
                        slvlsFiles.add(p);
                    }
                }
            }
            LiveLocation[] found = new LiveLocation[1];
            for (Path p : slvlsFiles) {
                Matcher m = SLVLS.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2)), si = Integer.parseInt(m.group(3));
                forEachEntry(p, dir, rx, rz, 4096, (idx, tag) -> {
                    try {
                        if (!uuid.equals(tag.getUUID("uuid"))) return;
                        int[] rc = refChunk.get(rx + "." + rz + "." + si + ":" + idx);
                        if (rc == null) return; // 无指针引用的条目 snatch 不到,跳过
                        found[0] = new LiveLocation(new EntryKey(dim, rx, rz, si, idx), rc[0], rc[1]);
                    } catch (Exception ignored) {
                    }
                });
                if (found[0] != null) return found[0];
            }
            return found[0];
        } catch (Exception e) {
            SablePanel.LOGGER.warn("sablepanel: live locate failed for {}", uuid, e);
            return null;
        }
    }

    public record LocatedEntry(EntryKey key, CompoundTag tag) {
    }

    /**
     * 实时定位某 uuid 的条目(不要求有指针——孤儿收养用)。
     * 同 uuid 多条目时取方块数最大的一份(资产安全:保最完整版本)。
     */
    public static LocatedEntry locateEntry(String dim, Path dir, UUID uuid) {
        try {
            List<LocatedEntry> hits = new ArrayList<>();
            List<Path> slvlsFiles = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                for (Path p : stream.toList()) {
                    if (SLVLS.matcher(p.getFileName().toString()).matches()) slvlsFiles.add(p);
                }
            }
            for (Path p : slvlsFiles) {
                Matcher m = SLVLS.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2)), si = Integer.parseInt(m.group(3));
                forEachEntry(p, dir, rx, rz, 4096, (idx, tag) -> {
                    try {
                        if (uuid.equals(tag.getUUID("uuid"))) {
                            hits.add(new LocatedEntry(new EntryKey(dim, rx, rz, si, idx), tag));
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
            if (hits.isEmpty()) return null;
            hits.sort((a, b) -> Integer.compare(
                    countBlocks(b.tag().getCompound("plot"), null), countBlocks(a.tag().getCompound("plot"), null)));
            return hits.get(0);
        } catch (Exception e) {
            SablePanel.LOGGER.warn("sablepanel: locate entry failed for {}", uuid, e);
            return null;
        }
    }

    /** 读单个条目的完整 NBT(mesh 预览用) */
    public static CompoundTag readEntryTag(Path dimDir, EntryKey key) {
        CompoundTag[] holder = new CompoundTag[1];
        Path file = dimDir.resolve("r." + key.rx() + "." + key.rz() + "." + key.storage() + ".slvls");
        forEachEntry(file, dimDir, key.rx(), key.rz(), 4096, (idx, tag) -> {
            if (idx == key.index()) holder[0] = tag;
        });
        return holder[0];
    }

    private static DiskEntry toEntry(EntryKey key, CompoundTag tag, boolean reachable) {
        try {
            UUID uuid = tag.getUUID("uuid");
            String name = tag.contains("display_name") ? tag.getString("display_name") : null;
            CompoundTag pose = tag.getCompound("pose").getCompound("position");
            double[] pos = {pose.getDouble("x"), pose.getDouble("y"), pose.getDouble("z")};
            CompoundTag wb = tag.getCompound("world_bounds");
            double[] size = {wb.getDouble("maxX") - wb.getDouble("minX"),
                    wb.getDouble("maxY") - wb.getDouble("minY"),
                    wb.getDouble("maxZ") - wb.getDouble("minZ")};
            List<UUID> deps = new ArrayList<>();
            if (tag.contains("loading_dependencies")) {
                ListTag list = tag.getList("loading_dependencies", Tag.TAG_INT_ARRAY);
                for (Tag t : list) deps.add(NbtUtils.loadUUID(t));
            }
            CompoundTag plot = tag.getCompound("plot");
            Set<String> ids = new LinkedHashSet<>();
            int blocks = countBlocks(plot, ids);
            boolean userData = tag.contains("user_data") && !tag.getCompound("user_data").isEmpty();
            int[] be = countBlockEntities(plot);
            return new DiskEntry(key, uuid, name, pos, size, blocks, deps, reachable,
                    plot.getInt("plot_x"), plot.getInt("plot_z"), List.copyOf(ids), userData, be[0], be[1]);
        } catch (Exception e) {
            return null;
        }
    }

    /** @return [方块实体数, 其中有内容(物品/告示牌文字)的个数] */
    private static int[] countBlockEntities(CompoundTag plot) {
        int count = 0, withContent = 0;
        CompoundTag chunks = plot.getCompound("chunks");
        for (String ck : chunks.getAllKeys()) {
            ListTag list = chunks.getCompound(ck).getList("block_entities", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                count++;
                if (hasContent(list.getCompound(i), 0)) withContent++;
            }
        }
        return new int[]{count, withContent};
    }

    /** 方块实体里是否有玩家放进去的东西:非空物品栏,或写过字的告示牌。深度有限,避免病态 NBT 拖慢扫描 */
    private static boolean hasContent(CompoundTag tag, int depth) {
        if (depth > 5) return false;
        for (String k : tag.getAllKeys()) {
            Tag t = tag.get(k);
            if (t == null) continue;
            if (t.getId() == Tag.TAG_LIST) {
                ListTag l = (ListTag) t;
                if (l.isEmpty()) continue;
                if (isItemKey(k)) return true;
                // 告示牌:messages 是四行 JSON 文本,空行为 '""'
                if (k.equals("messages")) {
                    for (int i = 0; i < l.size(); i++) {
                        String s = l.getString(i);
                        if (!s.isEmpty() && !s.equals("\"\"") && !s.equals("''")) return true;
                    }
                    continue;
                }
                if (l.getElementType() == Tag.TAG_COMPOUND) {
                    for (int i = 0; i < l.size() && i < 64; i++) {
                        if (hasContent(l.getCompound(i), depth + 1)) return true;
                    }
                }
            } else if (t.getId() == Tag.TAG_COMPOUND) {
                CompoundTag c = (CompoundTag) t;
                // 单格容器写成 {id:"...",count:n};但 Create 的 copycat 方块用 Item 存伪装材料
                // (与 Material 同时出现),那是外观不是玩家物品栏,不能算资产
                if (isItemKey(k) && !tag.contains("Material")
                        && c.contains("id") && !c.getString("id").equals("minecraft:air")) {
                    return true;
                }
                if (hasContent(c, depth + 1)) return true;
            }
        }
        return false;
    }

    private static boolean isItemKey(String k) {
        return k.equals("Items") || k.equals("Item") || k.equals("Inventory")
                || k.equals("inventory") || k.equals("items");
    }

    /** palette+data 快速非 air 计数(无需完整 BlockState 解码);ids 非空时同时收集出现过的方块 id */
    static int countBlocks(CompoundTag plot, Set<String> ids) {
        int total = 0;
        CompoundTag chunks = plot.getCompound("chunks");
        for (String ck : chunks.getAllKeys()) {
            CompoundTag sections = chunks.getCompound(ck).getCompound("sections");
            for (String sk : sections.getAllKeys()) {
                CompoundTag bs = sections.getCompound(sk).getCompound("block_states");
                ListTag palette = bs.getList("palette", Tag.TAG_COMPOUND);
                if (palette.isEmpty()) continue;
                boolean[] air = new boolean[palette.size()];
                for (int i = 0; i < palette.size(); i++) {
                    String n = palette.getCompound(i).getString("Name");
                    air[i] = n.equals("minecraft:air") || n.equals("minecraft:cave_air") || n.equals("minecraft:void_air");
                    if (!air[i] && ids != null) ids.add(n);
                }
                if (!bs.contains("data") || palette.size() == 1) {
                    if (!air[0]) total += 4096;
                    continue;
                }
                long[] data = bs.getLongArray("data");
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                int per = 64 / bits;
                long mask = (1L << bits) - 1;
                int got = 0;
                outer:
                for (long lv : data) {
                    for (int k = 0; k < per; k++) {
                        int pi = (int) ((lv >>> (k * bits)) & mask);
                        if (pi < air.length && !air[pi]) total++;
                        if (++got == 4096) break outer;
                    }
                }
            }
        }
        return total;
    }
}
