package com.klnon.sablepanel.panel.data;

import com.klnon.sablepanel.panel.PanelConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klnon.sablepanel.SablePanel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 删除备份的持久化回收站；中断事务会转成可见的 recovery_required 记录。 */
public final class RecycleStore {
    public static final String DEFAULT_DIMENSION = "minecraft:overworld";
    private static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Pattern SAFE_ID = Pattern.compile("[0-9A-Za-z_-]{8,96}");
    private static final String MANIFEST = "manifest.json";
    /** 单页组数上限与默认值:客户端可以要更少,但要不到更多 */
    public static final int PAGE_LIMIT_MAX = 200;
    private static final int PAGE_LIMIT_DEFAULT = 100;
    /** 单页预算:条数之外再兜一层,防止少数超大组把一页撑爆。单位见 {@link #manifestCost} */
    private static final int PAGE_BLOCK_BUDGET = 200_000;
    /** 一个体的元数据(名称/坐标/包围盒/依赖链/备份名)折算成多少个方块索引当量 */
    private static final int BODY_COST_UNITS = 128;

    public record Source(UUID uuid, String dimension, DiskScanner.EntryKey key, CompoundTag tag) {
    }

    public record RestoreBody(UUID uuid, String dimension, CompoundTag tag) {
    }

    public record RestoreGroup(String id, String state, List<RestoreBody> bodies) {
    }

    public static final class Stage {
        private final String id;
        private final Path directory;
        private final JsonObject manifest;
        private boolean committed;

        private Stage(String id, Path directory, JsonObject manifest) {
            this.id = id;
            this.directory = directory;
            this.manifest = manifest;
        }

        public String id() {
            return this.id;
        }
    }

    private record RetentionEntry(Path path, long createdAt, int files, boolean directory) {
    }

    private final PanelConfig config;
    private final Path root;
    private final Path pendingRoot;

    public RecycleStore(PanelConfig config) {
        this(config, FMLPaths.GAMEDIR.get().resolve("sablepanel-recycle"));
    }

    RecycleStore(PanelConfig config, Path root) {
        this.config = config;
        this.root = root.toAbsolutePath().normalize();
        this.pendingRoot = this.root.resolve(".pending");
        recoverInterruptedStages();
    }

    public synchronized Stage stage(List<Source> sources) throws IOException {
        if (sources.isEmpty()) throw new IllegalArgumentException("回收组没有可备份条目");
        if (sources.size() > this.config.recycleMaxFiles) {
            throw new IllegalStateException("该依赖组需要 " + sources.size()
                    + " 个备份文件，超过当前回收站上限 " + this.config.recycleMaxFiles);
        }
        int protectedFiles = protectedFileCount();
        if (sources.size() > this.config.recycleMaxFiles - protectedFiles) {
            throw new IllegalStateException("需恢复备份已占用 " + protectedFiles
                    + " 个文件，剩余容量不足以安全删除该依赖组");
        }
        Files.createDirectories(this.pendingRoot);
        String id = ID_TIME.format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = this.pendingRoot.resolve(id);
        Files.createDirectory(directory);
        try {
            JsonObject manifest = buildManifest(id, sources);
            writeBackups(directory, manifest, sources);
            writeJsonAtomic(directory.resolve(MANIFEST), manifest);
            return new Stage(id, directory, manifest);
        } catch (Exception error) {
            deleteTree(directory);
            if (error instanceof IOException io) throw io;
            throw new IOException("创建回收站事务失败", error);
        }
    }

    public synchronized String commit(Stage stage) throws IOException {
        return commit(stage, "deleted");
    }

    public synchronized String commitRecoveryRequired(Stage stage) throws IOException {
        return commit(stage, "recovery_required");
    }

    private String commit(Stage stage, String state) throws IOException {
        requirePending(stage);
        long now = System.currentTimeMillis();
        stage.manifest.addProperty("state", state);
        stage.manifest.addProperty("deleted_at", now);
        if ("recovery_required".equals(state)) stage.manifest.addProperty("recovery_required_at", now);
        writeJsonAtomic(stage.directory.resolve(MANIFEST), stage.manifest);
        Files.createDirectories(this.root);
        Path destination = this.root.resolve(stage.id);
        if (Files.exists(destination)) throw new IOException("回收组 ID 已存在: " + stage.id);
        moveAtomic(stage.directory, destination);
        stage.committed = true;
        try {
            prune();
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: recycle retention cleanup failed", error);
        }
        return stage.id;
    }

    /** 上次进程在事务完成前退出时，把完整 pending 备份转成可见的人工恢复记录。 */
    private void recoverInterruptedStages() {
        if (!Files.isDirectory(this.pendingRoot)) return;
        int recovered = 0;
        try (var stream = Files.list(this.pendingRoot)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                String id = directory.getFileName().toString();
                if (!SAFE_ID.matcher(id).matches()) continue;
                try {
                    JsonObject manifest = readManifest(directory);
                    if (!id.equals(manifest.has("id") ? manifest.get("id").getAsString() : "")) {
                        throw new IOException("事务目录与清单 ID 不一致");
                    }
                    String state = manifest.has("state") ? manifest.get("state").getAsString() : "pending";
                    if (!"pending".equals(state) && !"recovery_required".equals(state)) continue;
                    long now = System.currentTimeMillis();
                    long stagedAt = Files.getLastModifiedTime(directory).toMillis();
                    manifest.addProperty("state", "recovery_required");
                    manifest.addProperty("deleted_at", stagedAt);
                    manifest.addProperty("recovery_required_at", now);
                    writeJsonAtomic(directory.resolve(MANIFEST), manifest);
                    Path destination = this.root.resolve(id);
                    if (Files.exists(destination)) throw new IOException("同名回收组已存在");
                    moveAtomic(directory, destination);
                    recovered++;
                } catch (Exception error) {
                    SablePanel.LOGGER.error("sablepanel: pending recycle transaction {} needs manual inspection",
                            id, error);
                }
            }
            if (recovered > 0) {
                SablePanel.LOGGER.warn("sablepanel: exposed {} interrupted recycle transaction(s) for recovery",
                        recovered);
                prune();
            }
        } catch (Exception error) {
            SablePanel.LOGGER.error("sablepanel: failed to recover interrupted recycle transactions", error);
        }
    }

    public synchronized void discard(Stage stage) {
        if (stage == null || stage.committed) return;
        try {
            deleteTree(stage.directory);
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: failed to discard pending recycle transaction {}", stage.id, error);
        }
    }

    public synchronized RestoreGroup loadStage(Stage stage) throws IOException {
        if (stage == null || stage.committed) throw new IllegalArgumentException("回收站事务不可用于回滚");
        return readGroup(stage.id, stage.directory, stage.manifest);
    }

    public synchronized RestoreGroup loadGroup(String id) throws IOException {
        Path directory = groupDirectory(id);
        JsonObject manifest = readManifest(directory);
        return readGroup(id, directory, manifest);
    }

    public synchronized JsonObject mesh(String id, UUID uuid) throws IOException {
        Path directory = groupDirectory(id);
        JsonObject manifest = readManifest(directory);
        // 只读目标体自己的备份文件:大组预览不必整组解压,单个成员文件损坏也不拖累其他成员
        for (var element : manifest.getAsJsonArray("bodies")) {
            JsonObject body = element.getAsJsonObject();
            if (!uuid.toString().equals(body.get("uuid").getAsString())) continue;
            JsonArray backups = body.getAsJsonArray("backups");
            if (backups == null || backups.isEmpty()) throw new IOException("回收组缺少 NBT 文件: " + uuid);
            CompoundTag tag = readTag(safeChild(directory, backups.get(0).getAsString()));
            if (!uuid.equals(tag.getUUID("uuid"))) throw new IOException("回收组 UUID 与 NBT 不一致: " + uuid);
            return MeshExtractor.extract(tag);
        }
        throw new IOException("回收组中不存在该物理体");
    }

    /**
     * 游标分页视图。
     * <p>
     * 从前是"读全部 manifest、建全局调色板、输出全部组",一次调用的堆和响应都只随备份数增长
     * ——实测 3 个组 357 个备份文件就已经 2.6 MB,离 32 MiB 的协议上限并不远,而且是先把整个对象
     * 建出来才发现发不出去。现在读取、构建、传输三个阶段都有单页上限。
     * <p>
     * 游标是上一页最后一个组的 id。组目录名是 {@code yyyyMMdd-HHmmss-SSS-<rand>},字典序即时间序,
     * 所以定位游标不需要读任何 manifest;用的是 keyset 语义(跳过 id ≥ 游标的),游标那一组即使
     * 在两次请求之间被清理掉也不会翻页失败。格式非法的游标按"没有游标"处理,退回第一页。
     *
     * @param limit 单页组数,≤0 取默认值,上限 {@link #PAGE_LIMIT_MAX}
     */
    public synchronized JsonObject view(String cursor, int limit) {
        int pageLimit = limit <= 0 ? PAGE_LIMIT_DEFAULT : Math.min(limit, PAGE_LIMIT_MAX);
        String from = cursor != null && SAFE_ID.matcher(cursor).matches() ? cursor : "";
        JsonObject out = new JsonObject();
        out.addProperty("limit", this.config.recycleMaxFiles);
        out.addProperty("page_limit", pageLimit);
        JsonArray groups = new JsonArray();
        try {
            List<Path> directories = new ArrayList<>(committedDirectories());
            directories.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
            out.addProperty("total_groups", directories.size());
            List<JsonObject> manifests = new ArrayList<>();
            String nextCursor = "";
            boolean more = false;
            int cost = 0;
            for (Path directory : directories) {
                String id = directory.getFileName().toString();
                if (!from.isEmpty() && id.compareTo(from) >= 0) continue;
                // 单条记录可能异常大(一个组几百个体),所以除了条数还有一份方块索引预算;
                // 但每页至少出一条,否则超预算的那一组会永远翻不过去
                if (manifests.size() >= pageLimit || (cost >= PAGE_BLOCK_BUDGET && !manifests.isEmpty())) {
                    more = true;
                    break;
                }
                try {
                    JsonObject manifest = readManifest(directory);
                    manifests.add(manifest);
                    cost += manifestCost(manifest);
                    nextCursor = id;
                } catch (Exception error) {
                    SablePanel.LOGGER.warn("sablepanel: skipping unreadable recycle group {}", id, error);
                }
            }
            // 单个组自己就超过预算时(几百个体的巨型依赖组)只发它的元数据,不发方块构成 ——
            // 强行整份放进去等于分页白做,一页照样能撑爆响应
            boolean withBlocks = !(manifests.size() == 1 && cost > PAGE_BLOCK_BUDGET);
            Map<String, Integer> palette = withBlocks ? collectPalette(manifests) : Map.of();
            for (JsonObject manifest : manifests) groups.add(toView(manifest, palette, withBlocks));
            // 全盘统计要走一遍目录树,只在第一页算,翻页时前端沿用首页的值
            if (from.isEmpty()) out.addProperty("file_count", countBackupFiles(this.root, true));
            out.add("block_palette", paletteJson(palette));
            out.addProperty("next_cursor", more ? nextCursor : "");
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: recycle view failed", error);
            out.addProperty("error", String.valueOf(error.getMessage()));
            out.addProperty("file_count", 0);
            out.add("block_palette", new JsonArray());
            out.addProperty("next_cursor", "");
        }
        out.add("groups", groups);
        return out;
    }

    /**
     * 单页预算的记账当量:一个方块索引算 1。
     * <p>
     * 光算方块索引不够 —— 一个组几百个体、每个体带名称/坐标/包围盒/依赖链,这些元数据本身
     * 就能顶满响应,所以每个体再记一份固定当量。
     */
    private static int manifestCost(JsonObject manifest) {
        JsonArray bodies = manifest.getAsJsonArray("bodies");
        if (bodies == null) return 0;
        int total = 0;
        for (var bodyElement : bodies) {
            JsonArray ids = bodyElement.getAsJsonObject().getAsJsonArray("block_ids");
            total += BODY_COST_UNITS + (ids != null ? ids.size() : 0);
        }
        return total;
    }

    public synchronized void markRestored(String id) throws IOException {
        Path directory = groupDirectory(id);
        JsonObject manifest = readManifest(directory);
        manifest.addProperty("state", "restored");
        manifest.addProperty("restored_at", System.currentTimeMillis());
        writeJsonAtomic(directory.resolve(MANIFEST), manifest);
    }

    public synchronized int setLimit(int limit) throws IOException {
        if (limit < 1 || limit > 1_000_000) throw new IllegalArgumentException("回收站上限必须在 1 到 1000000 之间");
        int previous = this.config.recycleMaxFiles;
        this.config.recycleMaxFiles = limit;
        try {
            this.config.save();
        } catch (IOException error) {
            this.config.recycleMaxFiles = previous;
            throw error;
        }
        prune();
        return limit;
    }

    private JsonObject buildManifest(String id, List<Source> sources) {
        Map<UUID, List<Source>> byUuid = new LinkedHashMap<>();
        for (Source source : sources) byUuid.computeIfAbsent(source.uuid, ignored -> new ArrayList<>()).add(source);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("version", FORMAT_VERSION);
        manifest.addProperty("id", id);
        manifest.addProperty("state", "pending");
        manifest.addProperty("deleted_at", 0L);
        manifest.addProperty("file_count", sources.size());
        JsonArray bodies = new JsonArray();
        long totalBlocks = 0;
        String groupName = "";
        int namedBlocks = -1;
        Set<String> dimensions = new LinkedHashSet<>();
        for (Map.Entry<UUID, List<Source>> entry : byUuid.entrySet()) {
            Source primary = entry.getValue().get(0);
            DiskScanner.DiskEntry summary = DiskScanner.summarize(primary.key, primary.tag);
            JsonObject body = bodyJson(primary, summary);
            body.addProperty("backup_count", entry.getValue().size());
            body.add("backups", new JsonArray());
            bodies.add(body);
            dimensions.add(blankDimension(primary.dimension));
            if (summary != null) {
                totalBlocks += summary.blocks();
                if (summary.name() != null && summary.blocks() > namedBlocks) {
                    groupName = summary.name();
                    namedBlocks = summary.blocks();
                }
            }
        }
        manifest.addProperty("name", groupName);
        manifest.addProperty("members", byUuid.size());
        manifest.addProperty("blocks", totalBlocks);
        manifest.addProperty("dims", String.join(",", dimensions));
        manifest.add("bodies", bodies);
        return manifest;
    }

    private static JsonObject bodyJson(Source source, DiskScanner.DiskEntry summary) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", source.uuid.toString());
        body.addProperty("dim", blankDimension(source.dimension));
        body.addProperty("source_entry", source.key.id());
        if (summary == null) return body;
        if (summary.name() != null) body.addProperty("name", summary.name());
        body.addProperty("blocks", summary.blocks());
        body.add("pos", doubles(summary.pos()));
        body.add("size", doubles(summary.size()));
        body.addProperty("be", summary.blockEntities());
        body.addProperty("contents", summary.contents());
        JsonArray dependencies = new JsonArray();
        for (UUID dependency : summary.deps()) dependencies.add(dependency.toString());
        body.add("dependencies", dependencies);
        JsonArray blockIds = new JsonArray();
        for (String blockId : summary.blockIds()) blockIds.add(blockId);
        body.add("block_ids", blockIds);
        return body;
    }

    private static void writeBackups(Path directory, JsonObject manifest, List<Source> sources) throws IOException {
        Map<UUID, Integer> indexes = new LinkedHashMap<>();
        Map<UUID, JsonArray> files = new LinkedHashMap<>();
        for (var element : manifest.getAsJsonArray("bodies")) {
            JsonObject body = element.getAsJsonObject();
            files.put(UUID.fromString(body.get("uuid").getAsString()), body.getAsJsonArray("backups"));
        }
        for (Source source : sources) {
            int index = indexes.getOrDefault(source.uuid, 0);
            indexes.put(source.uuid, index + 1);
            String name = source.uuid + "-" + index + ".nbt.gz";
            Path file = directory.resolve(name);
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(file))) {
                NbtIo.writeCompressed(source.tag, output);
            }
            files.get(source.uuid).add(name);
        }
    }

    private RestoreGroup readGroup(String id, Path directory, JsonObject manifest) throws IOException {
        List<RestoreBody> bodies = new ArrayList<>();
        for (var element : manifest.getAsJsonArray("bodies")) {
            JsonObject body = element.getAsJsonObject();
            UUID uuid = UUID.fromString(body.get("uuid").getAsString());
            JsonArray backups = body.getAsJsonArray("backups");
            if (backups == null || backups.isEmpty()) throw new IOException("回收组缺少 NBT 文件: " + uuid);
            Path file = safeChild(directory, backups.get(0).getAsString());
            CompoundTag tag = readTag(file);
            if (!uuid.equals(tag.getUUID("uuid"))) throw new IOException("回收组 UUID 与 NBT 不一致: " + uuid);
            String dimension = body.has("dim") ? blankDimension(body.get("dim").getAsString()) : DEFAULT_DIMENSION;
            bodies.add(new RestoreBody(uuid, dimension, tag));
        }
        if (bodies.isEmpty()) throw new IOException("回收组为空");
        String state = manifest.has("state") ? manifest.get("state").getAsString() : "deleted";
        return new RestoreGroup(id, state, List.copyOf(bodies));
    }

    private void prune() throws IOException {
        if (!Files.isDirectory(this.root)) return;
        List<RetentionEntry> entries = retentionEntries();
        int total = countBackupFiles(this.root, true);
        entries.sort(Comparator.comparingLong(RetentionEntry::createdAt));
        for (RetentionEntry entry : entries) {
            if (total <= this.config.recycleMaxFiles) break;
            if (entry.directory) deleteTree(entry.path);
            else Files.deleteIfExists(entry.path);
            total -= entry.files;
        }
    }

    private List<RetentionEntry> retentionEntries() throws IOException {
        List<RetentionEntry> entries = new ArrayList<>();
        try (var stream = Files.list(this.root)) {
            for (Path path : stream.toList()) {
                if (path.equals(this.pendingRoot)) continue;
                if (Files.isDirectory(path)) {
                    int files = countBackupFiles(path, false);
                    if (files == 0) continue;
                    long createdAt;
                    try {
                        JsonObject manifest = readManifest(path);
                        if ("recovery_required".equals(manifest.has("state")
                                ? manifest.get("state").getAsString() : "")) continue;
                        createdAt = deletedAt(manifest);
                    } catch (Exception error) {
                        // 无法确认状态的备份不能自动淘汰；它仍计入总量并压缩后续删除容量。
                        continue;
                    }
                    entries.add(new RetentionEntry(path, createdAt, files, true));
                } else if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".nbt.gz")) {
                    entries.add(new RetentionEntry(path, Files.getLastModifiedTime(path).toMillis(), 1, false));
                }
            }
        }
        return entries;
    }

    private int protectedFileCount() throws IOException {
        if (!Files.isDirectory(this.root)) return 0;
        int total = 0;
        try (var stream = Files.list(this.root)) {
            for (Path path : stream.filter(Files::isDirectory).toList()) {
                if (path.equals(this.pendingRoot)) continue;
                int files = countBackupFiles(path, false);
                if (files == 0) continue;
                try {
                    JsonObject manifest = readManifest(path);
                    String state = manifest.has("state") ? manifest.get("state").getAsString() : "";
                    if ("recovery_required".equals(state)) total += files;
                } catch (Exception error) {
                    total += files;
                }
            }
        }
        return total;
    }

    private List<Path> committedDirectories() throws IOException {
        if (!Files.isDirectory(this.root)) return List.of();
        List<Path> directories = new ArrayList<>();
        try (var stream = Files.list(this.root)) {
            for (Path path : stream.toList()) {
                if (Files.isDirectory(path) && !path.equals(this.pendingRoot)
                        && Files.isRegularFile(path.resolve(MANIFEST))) directories.add(path);
            }
        }
        return directories;
    }

    private static Map<String, Integer> collectPalette(List<JsonObject> manifests) {
        Map<String, Integer> palette = new LinkedHashMap<>();
        for (JsonObject manifest : manifests) {
            for (var bodyElement : manifest.getAsJsonArray("bodies")) {
                JsonArray ids = bodyElement.getAsJsonObject().getAsJsonArray("block_ids");
                if (ids == null) continue;
                for (var id : ids) palette.putIfAbsent(id.getAsString(), palette.size());
            }
        }
        return palette;
    }

    /**
     * @param withBlocks false 时丢掉方块构成(只留计数)。单个组自己就超过整页预算时用它 ——
     *                   总得让这一页翻得过去,但也不能整份塞进来把响应撑爆;构成条前端会显示为空。
     */
    private static JsonObject toView(JsonObject manifest, Map<String, Integer> palette, boolean withBlocks) {
        // 就地改。manifest 是 view() 刚从磁盘解析出来的临时对象,除了这里没人引用它,
        // deepCopy 只是在超大组这个最不该翻倍的场景把峰值堆再翻一倍
        JsonObject view = manifest;
        for (var bodyElement : view.getAsJsonArray("bodies")) {
            JsonObject body = bodyElement.getAsJsonObject();
            JsonArray ids = body.remove("block_ids") instanceof JsonArray array ? array : new JsonArray();
            body.remove("backups");
            JsonArray indexes = new JsonArray();
            if (withBlocks) for (var id : ids) indexes.add(palette.get(id.getAsString()));
            body.add("blk", indexes);
        }
        if (!withBlocks) view.addProperty("blocks_omitted", true);
        return view;
    }

    private static JsonArray paletteJson(Map<String, Integer> palette) {
        JsonArray result = new JsonArray();
        for (String id : palette.keySet()) {
            String[] names = BlockNames.of(id);
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("en", names[0]);
            item.addProperty("zh", names[1]);
            result.add(item);
        }
        return result;
    }

    private Path groupDirectory(String id) throws IOException {
        if (id == null || !SAFE_ID.matcher(id).matches()) throw new IOException("回收组 ID 无效");
        Path directory = this.root.resolve(id).normalize();
        if (!directory.getParent().equals(this.root) || !Files.isDirectory(directory)) {
            throw new IOException("回收组不存在");
        }
        return directory;
    }

    private static Path safeChild(Path directory, String name) throws IOException {
        Path file = directory.resolve(name).normalize();
        if (!file.getParent().equals(directory) || !Files.isRegularFile(file)) throw new IOException("回收站文件无效");
        return file;
    }

    private static JsonObject readManifest(Path directory) throws IOException {
        Path file = directory.resolve(MANIFEST);
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception error) {
            throw new IOException("回收组清单无法读取: " + directory.getFileName(), error);
        }
    }

    private static CompoundTag readTag(Path file) throws IOException {
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            return NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
        }
    }

    private static void writeJsonAtomic(Path file, JsonObject value) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value), StandardCharsets.UTF_8);
        moveAtomic(temporary, file);
    }

    private static void moveAtomic(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static int countBackupFiles(Path directory, boolean excludePending) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        try (var stream = Files.walk(directory)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".nbt.gz"))
                    .filter(path -> !excludePending || !path.startsWith(directory.resolve(".pending")))
                    .count();
        }
    }

    private static long deletedAt(JsonObject manifest) {
        return manifest.has("deleted_at") ? manifest.get("deleted_at").getAsLong() : 0L;
    }

    private static JsonArray doubles(double[] values) {
        JsonArray result = new JsonArray();
        for (double value : values) result.add(value);
        return result;
    }

    private static String blankDimension(String dimension) {
        return dimension == null || dimension.isBlank() ? DEFAULT_DIMENSION : dimension;
    }

    private static void requirePending(Stage stage) {
        if (stage == null || stage.committed) throw new IllegalStateException("回收站事务已经结束");
    }
}
