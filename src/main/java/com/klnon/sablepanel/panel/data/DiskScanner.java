package com.klnon.sablepanel.panel.data;

import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.SablePanel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final int STRICT_WARNING_LIMIT = 100;
    private static final int BACKGROUND_WARNING_PATH_LIMIT = 128;

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

    /** 写操作后的验收必须绕过 mtime+size 缓存,避免同毫秒内原位更新被误判为未变化。 */
    public static void invalidateCache() {
        SLVLS_CACHE.clear();
    }

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

    /** 删除验收使用:只有目录确实不存在时才省略,访问失败或路径类型异常必须上抛。 */
    public static Map<String, Path> sublevelDirsStrict(MinecraftServer server) throws IOException {
        Map<String, Path> dirs = new HashMap<>();
        Path root = server.getWorldPath(LevelResource.ROOT);
        for (ServerLevel level : server.getAllLevels()) {
            Path dimRoot = DimensionType.getStorageFolder(level.dimension(), root);
            Path sub = dimRoot.resolve("sublevels");
            try {
                BasicFileAttributes attributes = Files.readAttributes(sub, BasicFileAttributes.class);
                if (!attributes.isDirectory()) throw new IOException("sublevels 路径不是目录: " + sub);
                dirs.put(level.dimension().location().toString(), sub);
            } catch (NoSuchFileException ignored) {
                // 该维度从未产生过 Sable 存档,不存在待验收条目。
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

    /**
     * 删除/恢复流程的严格全量元数据扫描:完整读取每个 .slvls 非空槽位,但只保留
     * uuid/槽位/依赖/plot 坐标,不持有完整 NBT —— 堆峰值与条目体量无关。
     * 唯一容忍的损坏形态是"头部截断"(<4096 字节,建文件后写头前崩溃的残留):按 sable
     * 同款可读前缀语义解析并记入 warnings。其余情况 —— 文件打不开、已声明 span 但条目
     * 读不出 —— 一律上抛:那些可能只是权限/瞬态 IO 问题,sable 自己握着句柄照样可见,
     * 跳过会让删除误判"已不存在"、让验收失去证明力。
     */
    public record EntryMeta(EntryKey key, List<UUID> deps, int plotX, int plotZ) {
    }

    public static Map<UUID, List<EntryMeta>> scanEntryMetaStrict(Map<String, Path> dims,
                                                                 List<String> warnings) throws IOException {
        Map<UUID, List<EntryMeta>> meta = new HashMap<>();
        for (Map.Entry<String, Path> dimension : dims.entrySet()) {
            String dim = dimension.getKey();
            Path dir = dimension.getValue();
            if (!Files.isDirectory(dir)) throw new IOException("sublevels 目录不存在: " + dir);
            List<Path> files;
            try (var stream = Files.list(dir)) {
                files = stream.filter(path -> SLVLS.matcher(path.getFileName().toString()).matches()).toList();
            }
            for (Path file : files) collectFileMetaStrict(dim, file, dir, meta, warnings);
        }
        return meta;
    }

    private static void collectFileMetaStrict(String dim, Path file, Path dir,
                                              Map<UUID, List<EntryMeta>> meta,
                                              List<String> warnings) throws IOException {
        Matcher matcher = SLVLS.matcher(file.getFileName().toString());
        if (!matcher.matches()) return;
        int rx = Integer.parseInt(matcher.group(1));
        int rz = Integer.parseInt(matcher.group(2));
        int storage = Integer.parseInt(matcher.group(3));
        forEachEntryStrict(file, dir, rx, rz, 4096, warnings, (index, tag) -> {
            UUID uuid;
            try {
                uuid = tag.getUUID("uuid");
            } catch (Exception error) {
                throw new IOException("NBT 条目缺少有效 UUID: " + file.getFileName() + "#" + index, error);
            }
            CompoundTag plot = tag.getCompound("plot");
            meta.computeIfAbsent(uuid, ignored -> new ArrayList<>())
                    .add(new EntryMeta(new EntryKey(dim, rx, rz, storage, index),
                            dependencies(tag), plot.getInt("plot_x"), plot.getInt("plot_z")));
        });
    }

    /** plot 槽位 → 声明它的 uuid 集(盘上条目视角;恢复前的同槽冲突守卫用) */
    public record PlotKey(String dim, int x, int z) {
    }

    public static Map<PlotKey, Set<UUID>> plotOwners(Map<UUID, List<EntryMeta>> meta) {
        Map<PlotKey, Set<UUID>> owners = new HashMap<>();
        for (Map.Entry<UUID, List<EntryMeta>> entry : meta.entrySet()) {
            for (EntryMeta copy : entry.getValue()) {
                owners.computeIfAbsent(new PlotKey(copy.key().dim(), copy.plotX(), copy.plotZ()),
                        ignored -> new HashSet<>()).add(entry.getKey());
            }
        }
        return owners;
    }

    /** 将任意选中成员扩展为完整的、无重复的依赖连通组；已丢失的依赖 UUID 不会凭空加入。 */
    public static List<Set<UUID>> selectedDependencyComponents(
            Map<UUID, List<EntryMeta>> entries, List<UUID> requested) {
        Map<UUID, UUID> parent = new HashMap<>();
        for (UUID uuid : entries.keySet()) parent.put(uuid, uuid);
        for (Map.Entry<UUID, List<EntryMeta>> entry : entries.entrySet()) {
            for (EntryMeta copy : entry.getValue()) {
                for (UUID dependency : copy.deps()) {
                    if (parent.containsKey(dependency)) union(parent, entry.getKey(), dependency);
                }
            }
        }
        Map<UUID, List<UUID>> members = new HashMap<>();
        for (UUID uuid : parent.keySet()) members.computeIfAbsent(find(parent, uuid), ignored -> new ArrayList<>()).add(uuid);
        for (List<UUID> group : members.values()) group.sort(UUID::compareTo);

        List<Set<UUID>> result = new ArrayList<>();
        Set<UUID> emittedRoots = new HashSet<>();
        Set<UUID> emittedMissing = new HashSet<>();
        for (UUID selected : requested) {
            if (!parent.containsKey(selected)) {
                if (emittedMissing.add(selected)) result.add(new LinkedHashSet<>(List.of(selected)));
                continue;
            }
            UUID root = find(parent, selected);
            if (!emittedRoots.add(root)) continue;
            LinkedHashSet<UUID> group = new LinkedHashSet<>();
            group.add(selected);
            group.addAll(members.get(root));
            result.add(group);
        }
        return result;
    }

    private static List<UUID> dependencies(CompoundTag tag) {
        List<UUID> dependencies = new ArrayList<>();
        if (!tag.contains("loading_dependencies")) return dependencies;
        ListTag list = tag.getList("loading_dependencies", Tag.TAG_INT_ARRAY);
        for (Tag dependency : list) dependencies.add(NbtUtils.loadUUID(dependency));
        return dependencies;
    }

    private static UUID find(Map<UUID, UUID> parent, UUID uuid) {
        UUID root = uuid;
        while (!parent.get(root).equals(root)) root = parent.get(root);
        while (!parent.get(uuid).equals(root)) {
            UUID next = parent.get(uuid);
            parent.put(uuid, root);
            uuid = next;
        }
        return root;
    }

    private static void union(Map<UUID, UUID> parent, UUID first, UUID second) {
        UUID firstRoot = find(parent, first);
        UUID secondRoot = find(parent, second);
        if (!firstRoot.equals(secondRoot)) parent.put(firstRoot, secondRoot);
    }

    /** 严格统计仍引用目标存储槽的 .slvlr holding 指针。 */
    public static Map<EntryKey, Integer> countPointersStrict(Map<String, Path> dims, Set<EntryKey> targets,
                                                             List<String> warnings) throws IOException {
        Map<EntryKey, Integer> counts = new HashMap<>();
        for (Map.Entry<EntryKey, List<LiveLocation>> entry
                : locatePointersStrict(dims, targets, warnings).entrySet()) {
            counts.put(entry.getKey(), entry.getValue().size());
        }
        return counts;
    }

    /** 删除准备使用:严格收集每个存储槽的全部 holding 指针,保留重复引用。 */
    public static Map<EntryKey, List<LiveLocation>> locatePointersStrict(Map<String, Path> dims,
                                                                          Set<EntryKey> targets,
                                                                          List<String> warnings)
            throws IOException {
        Map<EntryKey, List<LiveLocation>> located = new HashMap<>();
        forEachPointerStrict(dims, warnings, reference -> {
            if (targets.contains(reference.key())) {
                located.computeIfAbsent(reference.key(), ignored -> new ArrayList<>())
                        .add(new LiveLocation(reference.key(), reference.chunkX(), reference.chunkZ()));
            }
        });
        return located;
    }

    public record PointerReference(EntryKey key, int chunkX, int chunkZ) {
    }

    /** 严格读取全部 holding 指针；一致性检查据此识别目标槽为空的引用。 */
    public static List<PointerReference> scanPointersStrict(Map<String, Path> dims,
                                                             List<String> warnings) throws IOException {
        List<PointerReference> references = new ArrayList<>();
        forEachPointerStrict(dims, warnings, references::add);
        return List.copyOf(references);
    }

    /** 流式严格遍历 holding 指针；目标定位和有上限的检查不必先物化全服指针表。 */
    public static void forEachPointerStrict(Map<String, Path> dims, List<String> warnings,
                                            PointerConsumer consumer) throws IOException {
        List<Map.Entry<String, Path>> dimensions = new ArrayList<>(dims.entrySet());
        dimensions.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Path> dimension : dimensions) {
            String dim = dimension.getKey();
            Path dir = dimension.getValue();
            if (!Files.isDirectory(dir)) throw new IOException("sublevels 目录不存在: " + dir);
            List<Path> files;
            try (var stream = Files.list(dir)) {
                files = stream.filter(path -> SLVLR.matcher(path.getFileName().toString()).matches())
                        .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            }
            for (Path file : files) {
                Matcher matcher = SLVLR.matcher(file.getFileName().toString());
                if (!matcher.matches()) continue;
                int rx = Integer.parseInt(matcher.group(1));
                int rz = Integer.parseInt(matcher.group(2));
                forEachEntryStrict(file, dir, rx, rz, 128, warnings, (index, tag) -> {
                    int chunkX = rx * 32 + (index & 31);
                    int chunkZ = rz * 32 + (index >> 5);
                    for (int packed : tag.getIntArray("pointers")) {
                        EntryKey key = new EntryKey(dim, rx, rz,
                                (packed >> 16) & 0xFFFF, packed & 0xFFFF);
                        consumer.accept(new PointerReference(key, chunkX, chunkZ));
                    }
                });
            }
        }
    }

    @FunctionalInterface
    public interface PointerConsumer {
        void accept(PointerReference reference);
    }

    /**
     * 全目录扫描的存储文件遍历。唯一的容错:头部截断(<4096 字节)按 sable 的
     * SubLevelStorageFile 同款语义处理 —— 零填充 buffer 读入可读前缀,缺失槽位视为空闲,
     * 并记入 warnings(这是崩溃残留的确定形态,面板与 sable 解析逐位一致)。
     * 文件打不开、前缀读不满、已声明 span 的条目读不出 —— 全部照常上抛,由调用方整体失败:
     * 这些可能是权限/瞬态 IO/并发写,数据对 sable 依然可见,静默跳过会造成误报成功。
     */
    private static void forEachEntryStrict(Path file, Path dir, int rx, int rz, int sectorSize,
                                           List<String> warnings, EntryConsumer consumer)
            throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            ByteBuffer header = ByteBuffer.allocate(4096);
            if (fileSize < 4096) {
                addStrictWarning(warnings, "存储头截断(" + fileSize + " 字节): " + file.getFileName()
                        + ",已按可读前缀处理;建议停服备份后删除该文件");
            }
            int available = (int) Math.min(fileSize, 4096);
            if (available > 0) {
                ByteBuffer prefix = ByteBuffer.allocate(available);
                readFully(channel, prefix, 0, file);
                header.put(0, prefix.array(), 0, available);
            }
            for (int index = 0; index < 1024; index++) {
                int span = header.getInt(index * 4);
                if (span == 0) continue;
                CompoundTag tag = readEntryTagStrict(
                        channel, fileSize, file, dir, rx, rz, index, span, sectorSize);
                consumer.accept(index, tag);
            }
        }
    }

    private static CompoundTag readEntryTagStrict(FileChannel channel, long fileSize, Path file, Path dir,
                                                  int rx, int rz, int index, int span, int sectorSize)
            throws IOException {
        int start = (span >> 8) & 0xFFFFFF;
        int sectors = span & 0xFF;
        if (start <= 0 || sectors <= 0) throw new IOException("存储槽位范围无效: " + file.getFileName() + "#" + index);
        long offset = (long) start * sectorSize;
        long capacity = (long) sectors * sectorSize;
        if (offset + 5 > fileSize) throw new IOException("存储槽位超出文件: " + file.getFileName() + "#" + index);

        ByteBuffer metadata = ByteBuffer.allocate(5);
        readFully(channel, metadata, offset, file);
        metadata.flip();
        int size = metadata.getInt();
        byte type = metadata.get();
        if (size <= 0) throw new IOException("存储槽位长度无效: " + file.getFileName() + "#" + index);

        if ((type & 0x10) != 0) {
            Path external = dir.resolve("r." + rx + "." + rz + ".r").resolve(index + ".slvl");
            if (!Files.isRegularFile(external)) throw new IOException("外部条目缺失: " + external);
            try {
                return BoundedNbtIo.readCompressed(external);
            } catch (Exception error) {
                throw new IOException("NBT 条目无法解析: " + file.getFileName() + "#" + index
                        + ": " + error.getMessage(), error);
            }
        }
        long recordSize = 4L + size;
        if (recordSize > capacity || offset + recordSize > fileSize) {
            throw new IOException("存储槽位内容不完整: " + file.getFileName() + "#" + index);
        }
        byte[] payload = new byte[size - 1];
        readFully(channel, ByteBuffer.wrap(payload), offset + 5, file);

        try {
            return BoundedNbtIo.readCompressed(payload);
        } catch (Exception error) {
            throw new IOException("NBT 条目无法解析: " + file.getFileName() + "#" + index
                    + ": " + error.getMessage(), error);
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position, Path file)
            throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read <= 0) throw new IOException("文件读取不完整: " + file.getFileName());
        }
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

    /** 严格路径要能上抛 IO 错误;宽松路径的 lambda 不抛,由 forEachEntry 的 catch 兜住 */
    private interface EntryConsumer {
        void accept(int index, CompoundTag tag) throws IOException;
    }

    /** 后台扫描发现的截断文件,每个路径只告警一次,避免每轮扫描刷屏 */
    private static final Set<String> WARNED_TRUNCATED = new HashSet<>();

    private static void forEachEntry(Path file, Path dir, int rx, int rz, int sectorSize, EntryConsumer consumer) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < 4096 && rememberTruncatedPath(file.toAbsolutePath().toString())) {
                SablePanel.LOGGER.warn("sablepanel: 存储头截断({} 字节),与 sable 相同仅可读前缀有效,"
                        + "建议停服备份后删除该文件: {}", fileSize, file);
            }
            // 头部零填充:截断文件按 sable 语义取可读前缀,缺失槽位视为空
            ByteBuffer header = ByteBuffer.allocate(4096);
            while (header.hasRemaining()) {
                if (ch.read(header, header.position()) <= 0) break;
            }
            for (int idx = 0; idx < 1024; idx++) {
                CompoundTag tag = readSlot(ch, fileSize, header, dir, rx, rz, sectorSize, idx);
                if (tag != null) consumer.accept(idx, tag);
            }
        } catch (Exception e) {
            SablePanel.LOGGER.debug("sablepanel: cannot read {}", file, e);
        }
    }

    private static void addStrictWarning(List<String> warnings, String warning) {
        if (warnings.size() < STRICT_WARNING_LIMIT) {
            warnings.add(warning);
        } else if (warnings.size() == STRICT_WARNING_LIMIT) {
            warnings.add("其余截断文件警告已省略");
        }
    }

    private static boolean rememberTruncatedPath(String path) {
        synchronized (WARNED_TRUNCATED) {
            if (WARNED_TRUNCATED.contains(path) || WARNED_TRUNCATED.size() >= BACKGROUND_WARNING_PATH_LIMIT) {
                return false;
            }
            return WARNED_TRUNCATED.add(path);
        }
    }

    /**
     * 按头部索引读单个槽位。头部 1024 个 int 记的就是 {@code (起始扇区 << 8) | 扇区数},
     * 想要哪个条目直接算偏移即可 —— 不必把整个文件解压一遍。
     */
    private static CompoundTag readSlot(FileChannel ch, long fileSize, ByteBuffer header, Path dir,
                                        int rx, int rz, int sectorSize, int idx) {
        int span = header.getInt(idx * 4);
        if (span == 0) return null;
        int start = (span >> 8) & 0xFFFFFF, length = span & 0xFF;
        if (start <= 0 || length <= 0) return null;
        long off = (long) start * sectorSize;
        int cap = length * sectorSize;
        if (off + 5 > fileSize) return null;
        try {
            ByteBuffer buf = ByteBuffer.allocate(cap);
            ch.read(buf, off);
            buf.flip();
            int size = buf.getInt();
            byte dtype = buf.get();
            if ((dtype & 0x10) != 0) {
                Path ext = dir.resolve("r." + rx + "." + rz + ".r").resolve(idx + ".slvl");
                if (!Files.isRegularFile(ext)) return null;
                return BoundedNbtIo.readCompressed(ext);
            }
            if (size <= 0 || size - 1 > buf.remaining()) return null;
            byte[] payload = new byte[size - 1];
            buf.get(payload);
            return BoundedNbtIo.readCompressed(payload);
        } catch (Exception ignored) {
            return null; // 写入中的瞬态条目,跳过
        }
    }

    public record LiveLocation(EntryKey key, int chunkX, int chunkZ) {
    }

    public record LocatedEntry(EntryKey key, CompoundTag tag) {
    }

    /**
     * 实时定位某 uuid 的条目(不要求有指针——孤儿收养用)。
     * 同 uuid 多条目时取方块数最大的一份(资产安全:保最完整版本)。
     */
    public static LocatedEntry locateEntry(String dim, Path dir, UUID uuid) {
        return locateEntries(dim, dir, Set.of(uuid)).get(uuid);
    }

    /**
     * 批量版 {@link #locateEntry}:一趟扫描解出整批 uuid。
     * <p>
     * 逐个调用时每个 uuid 都要把该维度所有 .slvls 解压一遍,依赖链有几十个成员就是几十遍
     * 同样的解压 —— 生产事故的主要成本之一。同 uuid 多条目仍取方块数最大的一份。
     */
    public static Map<UUID, LocatedEntry> locateEntries(String dim, Path dir, Set<UUID> uuids) {
        Map<UUID, LocatedEntry> best = new HashMap<>();
        if (uuids.isEmpty()) return best;
        try {
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
                        UUID uuid = tag.getUUID("uuid");
                        if (!uuids.contains(uuid)) return;
                        LocatedEntry candidate = new LocatedEntry(new EntryKey(dim, rx, rz, si, idx), tag);
                        LocatedEntry current = best.get(uuid);
                        if (current == null || countBlocks(tag.getCompound("plot"), null)
                                > countBlocks(current.tag().getCompound("plot"), null)) {
                            best.put(uuid, candidate);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception e) {
            SablePanel.LOGGER.warn("sablepanel: batch locate entries failed", e);
        }
        return best;
    }

    /**
     * 一趟建指针表 + 一趟扫条目,批量解出 uuid 的存活位置。
     * <p>
     * 逐个调用时这两趟对每个 uuid 各做一次,而它无条件跑在依赖链的<b>每个</b>成员上
     * (不像 locateEntry 只在快照失配时才走),是生产上那 16 分钟的主要来源。
     */
    public static Map<UUID, LiveLocation> locateLiveAll(String dim, Path dir, Set<UUID> uuids) {
        Map<UUID, LiveLocation> found = new HashMap<>();
        if (uuids.isEmpty()) return found;
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
            for (Path p : slvlsFiles) {
                Matcher m = SLVLS.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2)), si = Integer.parseInt(m.group(3));
                forEachEntry(p, dir, rx, rz, 4096, (idx, tag) -> {
                    try {
                        UUID uuid = tag.getUUID("uuid");
                        if (!uuids.contains(uuid) || found.containsKey(uuid)) return;
                        int[] rc = refChunk.get(rx + "." + rz + "." + si + ":" + idx);
                        if (rc == null) return; // 无指针引用的条目 snatch 不到,跳过
                        found.put(uuid, new LiveLocation(new EntryKey(dim, rx, rz, si, idx), rc[0], rc[1]));
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception e) {
            SablePanel.LOGGER.warn("sablepanel: batch live locate failed", e);
        }
        return found;
    }

    /**
     * 读单个条目的完整 NBT。
     * <p>
     * 只解压目标槽位。旧实现走 {@code forEachEntry} 把整个存储文件(最多 1024 个条目)
     * 全解压一遍才挑出一条 —— 依赖链定位对每个成员都要调它一次,生产上一条 64 成员的链
     * 因此跑了十几分钟,而存档头部本来就记着每个条目的确切偏移。
     */
    public static CompoundTag readEntryTag(Path dimDir, EntryKey key) {
        if (key.index() < 0 || key.index() >= 1024) return null;
        Path file = dimDir.resolve("r." + key.rx() + "." + key.rz() + "." + key.storage() + ".slvls");
        // 条目常因 autosave 搬迁而不在原文件,缺文件是正常情况,不值得记日志
        if (!Files.isRegularFile(file)) return null;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            ByteBuffer header = ByteBuffer.allocate(4096);
            while (header.hasRemaining()) {
                if (ch.read(header, header.position()) <= 0) break;
            }
            return readSlot(ch, fileSize, header, dimDir, key.rx(), key.rz(), 4096, key.index());
        } catch (Exception e) {
            SablePanel.LOGGER.debug("sablepanel: cannot read {}", file, e);
            return null;
        }
    }

    private static DiskEntry toEntry(EntryKey key, CompoundTag tag, boolean reachable) {
        try {
            UUID uuid = tag.getUUID("uuid");
            String name = tag.contains("display_name") ? tag.getString("display_name") : null;
            CompoundTag pose = tag.getCompound("pose").getCompound("position");
            CompoundTag wb = tag.getCompound("world_bounds");
            double[] size = {wb.getDouble("maxX") - wb.getDouble("minX"),
                    wb.getDouble("maxY") - wb.getDouble("minY"),
                    wb.getDouble("maxZ") - wb.getDouble("minZ")};
            // 面板坐标统一为世界包围盒"底面中心"(与传送目标语义一致);
            // bounds 缺失或退化(空体)时退回 pose 原点
            double[] pos = size[0] > 0 || size[1] > 0 || size[2] > 0
                    ? new double[]{(wb.getDouble("minX") + wb.getDouble("maxX")) / 2, wb.getDouble("minY"),
                            (wb.getDouble("minZ") + wb.getDouble("maxZ")) / 2}
                    : new double[]{pose.getDouble("x"), pose.getDouble("y"), pose.getDouble("z")};
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

    /** 从回收站 NBT 生成与在线索引一致的静态摘要。 */
    public static DiskEntry summarize(EntryKey key, CompoundTag tag) {
        return toEntry(key, tag, false);
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
    public static int countBlocks(CompoundTag plot, Set<String> ids) {
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
