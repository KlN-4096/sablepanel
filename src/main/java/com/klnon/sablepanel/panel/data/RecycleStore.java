package com.klnon.sablepanel.panel.data;

import com.klnon.sablepanel.panel.PanelConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klnon.sablepanel.SablePanel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
    private static final int FORMAT_VERSION = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    static final Pattern SAFE_ID = Pattern.compile("[0-9A-Za-z_-]{8,96}");
    private static final String MANIFEST = "manifest.json";
    /** 单页组数上限与默认值:客户端可以要更少,但要不到更多 */
    public static final int PAGE_LIMIT_MAX = 200;
    private static final int PAGE_LIMIT_DEFAULT = 100;
    /**
     * 单页字节预算,量的是候选组序列化之后的真实字节。
     * <p>
     * 从前按"体数 × 固定当量 + block_ids 条数"估:名称、维度、依赖链一个字节都没算,
     * 而 display_name 单条就能有 65535 字节。清单本身允许到 64 MiB,估算看不见的部分
     * 足够让一页越过 32 MiB 的协议上限。
     */
    private static final long PAGE_BYTE_BUDGET = 2L << 20;
    /**
     * 循环跑完之后才追加的字段(file_count / disk_bytes / next_cursor / error)和两个数组的
     * 括号键名。都是定长的小字段,与其逐个数标点,不如留一档余量 —— 那种"每个逗号几字节"的
     * 账本正是前几轮反复出错的地方。
     */
    private static final long PAGE_SHELL_RESERVE = 512;
    /** 单份清单读进堆的字节上限。取值远高于任何正常组(每体几百字节),只为兜住坏文件 */
    private static final long MANIFEST_MAX_BYTES = 64L << 20;
    /** 全盘统计缓存的存活时间,见 {@link #storageStatsCached} */
    private static final long STATS_TTL_MS = 30_000;

    public record Source(UUID uuid, String dimension, DiskScanner.EntryKey key, CompoundTag tag) {
    }

    public record OperationalState(boolean paused, boolean forced) {
    }

    public record RestoreBody(UUID uuid, String dimension, String sourceEntry, CompoundTag tag,
                              boolean paused, boolean forced) {
    }

    public record RestoreGroup(String id, String state, boolean oldVersion, List<RestoreBody> bodies) {
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

        public boolean committed() {
            return this.committed;
        }
    }

    private record StorageStats(int files, long bytes) {
    }

    private final PanelConfig config;
    private final Path root;
    private final Path pendingRoot;
    /** 全盘统计缓存,见 {@link #storageStatsCached} */
    private StorageStats stats;
    private long statsAt;
    /** 分页目录清单缓存,见 {@link #pageIndex} */
    private RecyclePages.PageIndex index;
    private final RecycleVersions versions;

    public RecycleStore(PanelConfig config) {
        this(config, FMLPaths.GAMEDIR.get().resolve("sablepanel-recycle"));
    }

    RecycleStore(PanelConfig config, Path root) {
        this.config = config;
        if (config.recycleMaxFiles < 1 || config.recycleMaxFiles > PanelConfig.MAX_RECYCLE_FILES) {
            config.recycleMaxFiles = PanelConfig.DEFAULT_RECYCLE_MAX_FILES;
        }
        this.root = root.toAbsolutePath().normalize();
        this.pendingRoot = this.root.resolve(".pending");
        this.versions = new RecycleVersions(this.root);
        this.versions.recoverVersionTransactions();
        this.versions.rebuildLatestIndex();
        recoverInterruptedStages();
        this.versions.rebuildLatestIndex();
    }

    public synchronized Stage stage(List<Source> sources, Map<UUID, OperationalState> states) throws IOException {
        return stageInternal(sources, states, null);
    }

    private Stage stageInternal(List<Source> sources, Map<UUID, OperationalState> states,
                                String archivedState) throws IOException {
        if (sources.isEmpty()) throw new IllegalArgumentException("回收组没有可备份条目");
        if (sources.size() > this.config.recycleMaxFiles) {
            throw new IllegalStateException("该依赖组需要 " + sources.size()
                    + " 个备份文件，超过当前回收站上限 " + this.config.recycleMaxFiles);
        }
        int storedFiles = Files.isDirectory(this.root) ? countBackupFiles(this.root, true) : 0;
        int pendingFiles = Files.isDirectory(this.pendingRoot)
                ? countBackupFiles(this.pendingRoot, false) : 0;
        if (sources.size() > this.config.recycleMaxFiles - storedFiles - pendingFiles) {
            throw new IllegalStateException("回收站已占用 " + storedFiles
                    + " 个文件，待提交事务已占用 " + pendingFiles + " 个文件，剩余容量不足；请先人工彻底删除旧组");
        }
        Files.createDirectories(this.pendingRoot);
        String id = ID_TIME.format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = this.pendingRoot.resolve(id);
        Files.createDirectory(directory);
        try {
            if (archivedState != null) {
                Files.writeString(directory.resolve(RecycleVersions.OLD_VERSION_MARKER),
                        archivedState + "\n", StandardCharsets.UTF_8);
            }
            JsonObject manifest = buildManifest(id, sources, states);
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

    public synchronized Stage stageArchived(List<Source> sources, Map<UUID, OperationalState> states,
                                            String state) throws IOException {
        if (!"deleted".equals(state) && !"incomplete".equals(state)) {
            throw new IllegalArgumentException("旧版本状态无效");
        }
        return stageInternal(sources, states, state);
    }

    public synchronized String commitOld(Stage stage) throws IOException {
        return commitArchived(stage, "deleted");
    }

    public synchronized String commitIncomplete(Stage stage) throws IOException {
        return commitArchived(stage, "incomplete");
    }

    private String commit(Stage stage, String state) throws IOException {
        requirePending(stage);
        if (RecycleVersions.hasOldMarker(stage.directory)) throw new IOException("旧版本事务不能注册为最新版本");
        long now = System.currentTimeMillis();
        stage.manifest.addProperty("state", state);
        stage.manifest.addProperty("deleted_at", now);
        if ("recovery_required".equals(state)) stage.manifest.addProperty("recovery_required_at", now);
        writeJsonAtomic(stage.directory.resolve(MANIFEST), stage.manifest);
        Files.createDirectories(this.root);
        Path destination = this.root.resolve(stage.id);
        if (Files.exists(destination)) throw new IOException("回收组 ID 已存在: " + stage.id);
        Set<String> previous = this.versions.prepareVersionTransaction(stage.directory, stage.manifest);
        AtomicIo.move(stage.directory, destination);
        stage.committed = true;
        this.versions.registerLatest(stage.id, RecycleVersions.bodyUuids(stage.manifest), previous);
        try {
            this.versions.completeVersionTransaction(destination);
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: recycle version transaction remains pending for {}", stage.id, error);
        }
        invalidateCaches();
        return stage.id;
    }

    private String commitArchived(Stage stage, String state) throws IOException {
        requirePending(stage);
        if (!state.equals(RecycleVersions.archivedState(stage.directory))) {
            throw new IOException("旧版本事务标记与提交状态不一致");
        }
        long now = System.currentTimeMillis();
        stage.manifest.addProperty("state", state);
        stage.manifest.addProperty("deleted_at", now);
        writeJsonAtomic(stage.directory.resolve(MANIFEST), stage.manifest);
        Files.createDirectories(this.root);
        Path destination = this.root.resolve(stage.id);
        if (Files.exists(destination)) throw new IOException("回收组 ID 已存在: " + stage.id);
        AtomicIo.move(stage.directory, destination);
        stage.committed = true;
        invalidateCaches();
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
                    String archived = RecycleVersions.archivedState(directory);
                    if (archived != null) {
                        manifest.addProperty("state", archived);
                        manifest.addProperty("deleted_at", System.currentTimeMillis());
                        writeJsonAtomic(directory.resolve(MANIFEST), manifest);
                        Path destination = this.root.resolve(id);
                        if (Files.exists(destination)) throw new IOException("同名回收组已存在");
                        AtomicIo.move(directory, destination);
                        recovered++;
                        continue;
                    }
                    String state = manifest.has("state") ? manifest.get("state").getAsString() : "pending";
                    if (!"pending".equals(state) && !"deleted".equals(state)
                            && !"recovery_required".equals(state)) continue;
                    long now = System.currentTimeMillis();
                    manifest.addProperty("state", "recovery_required");
                    // pending 直到这里才对用户可见；恢复时刻才是它进入回收站的顺序。
                    manifest.addProperty("deleted_at", now);
                    manifest.addProperty("recovery_required_at", now);
                    writeJsonAtomic(directory.resolve(MANIFEST), manifest);
                    Path destination = this.root.resolve(id);
                    if (Files.exists(destination)) throw new IOException("同名回收组已存在");
                    Set<String> previous = this.versions.prepareVersionTransaction(directory, manifest);
                    AtomicIo.move(directory, destination);
                    this.versions.registerLatest(id, RecycleVersions.bodyUuids(manifest), previous);
                    try {
                        this.versions.completeVersionTransaction(destination);
                    } catch (Exception error) {
                        SablePanel.LOGGER.warn("sablepanel: recovered recycle version transaction remains pending for {}",
                                id, error);
                    }
                    recovered++;
                } catch (Exception error) {
                    SablePanel.LOGGER.error("sablepanel: pending recycle transaction {} needs manual inspection",
                            id, error);
                }
            }
            if (recovered > 0) {
                SablePanel.LOGGER.warn("sablepanel: exposed {} interrupted recycle transaction(s) for recovery",
                        recovered);
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
        Path directory = groupDirectory(this.root, id);
        JsonObject manifest = readManifest(directory);
        return readGroup(id, directory, manifest);
    }

    public synchronized JsonObject mesh(String id, UUID uuid) throws IOException {
        Path directory = groupDirectory(this.root, id);
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
    public synchronized JsonObject view(String version, String cursor, int limit) {
        if (!"latest".equals(version) && !"old".equals(version)) {
            throw new IllegalArgumentException("回收站版本必须是 latest 或 old");
        }
        boolean oldVersion = "old".equals(version);
        int pageLimit = limit <= 0 ? PAGE_LIMIT_DEFAULT : Math.min(limit, PAGE_LIMIT_MAX);
        String from = cursor != null && SAFE_ID.matcher(cursor).matches() ? cursor : "";
        JsonObject out = new JsonObject();
        out.addProperty("limit", this.config.recycleMaxFiles);
        out.addProperty("page_limit", pageLimit);
        out.addProperty("latest_groups", 0);
        out.addProperty("old_groups", 0);
        out.addProperty("total_groups", 0);
        JsonArray groups = new JsonArray();
        try {
            RecyclePages.PageIndex index = pageIndex();
            out.addProperty("latest_groups", index.latest().size());
            out.addProperty("old_groups", index.old().size());
            List<Path> directories = index.of(oldVersion);
            out.addProperty("total_groups", directories.size());
            RecyclePages.PagePalette palette = new RecyclePages.PagePalette();
            String nextCursor = "";
            boolean more = false;
            // 字节账本统一走 ByteBudget(与 BodyIndex.view 同一套):外壳也占字节,
            // 上面那几个统计字段是真的会发出去的
            ByteBudget budget = new ByteBudget(PAGE_BYTE_BUDGET);
            budget.charge(JsonSize.of(out) + PAGE_SHELL_RESERVE);
            // 清单已按 id 降序排好,游标位置直接二分 —— 从头线性扫到游标的话,
            // 翻到第 N 页就要白扫前 N 页的全部条目
            for (Path directory : directories.subList(RecyclePages.cursorOffset(directories, from), directories.size())) {
                String id = directory.getFileName().toString();
                if (groups.size() >= pageLimit) {
                    more = true;
                    break;
                }
                try {
                    JsonObject manifest = readManifest(directory);
                    manifest.addProperty("version_state", version);
                    // 量的是这一条真正会发出去的字节,连它新增的调色板条目一起算。
                    // 只看余额不记账的话,小组先占了位,紧跟着的超大组照样整条进来
                    JsonObject candidate = RecyclePages.toView(manifest, palette, true);
                    if (!budget.offerBytes(JsonSize.of(candidate) + palette.pendingBytes() + 1)) {   // +1 是数组分隔符
                        palette.rollback();
                        // 前面已经有组了就把这条留到下一页 —— 它自己一页装得下
                        if (!groups.isEmpty()) {
                            more = true;
                            break;
                        }
                        // 单组自己就超预算:先退到只发元数据,还是装不下就发固定尺寸摘要,
                        // 否则这一组永远翻不过去。与整页预算比的是"单组超页"判定;
                        // 选中的那份必须发出去,走 charge 无条件记账
                        candidate = RecyclePages.toView(manifest, palette, false);
                        long size = JsonSize.of(candidate);
                        if (size > PAGE_BYTE_BUDGET) {
                            candidate = RecyclePages.summaryView(id, manifest);
                            size = JsonSize.of(candidate);
                            // 摘要是按固定尺寸构造的,到这儿还超说明构造本身出了问题。
                            // 跳过这一组但推进游标,后面的组照样翻得到
                            if (size > PAGE_BYTE_BUDGET) {
                                SablePanel.LOGGER.error(
                                        "sablepanel: 回收组 {} 的固定摘要仍有 {} 字节,已跳过;这是需要修的 bug",
                                        id, size);
                                nextCursor = id;
                                continue;
                            }
                        }
                        budget.charge(size);
                    }
                    palette.commit();
                    groups.add(candidate);
                    nextCursor = id;
                } catch (Exception error) {
                    SablePanel.LOGGER.warn("sablepanel: skipping unreadable recycle group {}", id, error);
                }
            }
            // 全盘统计要走一遍目录树,只在第一页算,翻页时前端沿用首页的值
            if (from.isEmpty()) {
                StorageStats current = storageStatsCached();
                out.addProperty("file_count", current.files);
                out.addProperty("disk_bytes", current.bytes);
            }
            out.add("block_palette", palette.committedArr);
            out.addProperty("next_cursor", more ? nextCursor : "");
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: recycle view failed", error);
            out.addProperty("error", String.valueOf(error.getMessage()));
            out.addProperty("file_count", 0);
            out.addProperty("disk_bytes", 0);
            out.add("block_palette", new JsonArray());
            out.addProperty("next_cursor", "");
        }
        out.add("groups", groups);
        // 同 BodyIndex.view():PAGE_BYTE_BUDGET 是内容目标,最终上限在 PanelWire.response()
        return out;
    }

    public synchronized JsonObject purgeGroups(List<String> groupIds) {
        JsonArray results = new JsonArray();
        int removed = 0;
        int removedFiles = 0;
        long removedBytes = 0;
        for (String id : new LinkedHashSet<>(groupIds)) {
            JsonObject result = new JsonObject();
            result.addProperty("id", id);
            try {
                Path directory = groupDirectory(this.root, id);
                JsonObject manifest = readManifest(directory);
                if (!id.equals(manifest.has("id") ? manifest.get("id").getAsString() : "")) {
                    throw new IOException("回收组目录与清单 ID 不一致");
                }
                int files = countBackupFiles(directory, false);
                long bytes = directoryBytes(directory);
                int members = manifest.has("members") ? manifest.get("members").getAsInt()
                        : manifest.getAsJsonArray("bodies").size();
                // 该组可能仍携带“把前一版标旧”的事务；事务不能随组一起丢失。
                this.versions.completeVersionTransaction(directory);
                deleteCommittedGroup(directory);
                this.versions.forgetGroup(id);
                result.addProperty("ok", true);
                result.addProperty("members", members);
                result.addProperty("files", files);
                result.addProperty("bytes", bytes);
                removed++;
                removedFiles += files;
                removedBytes += bytes;
            } catch (Exception error) {
                result.addProperty("ok", false);
                result.addProperty("error", com.klnon.sablepanel.panel.api.PanelResponse.messageOf(error));
            }
            results.add(result);
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", removed);
        out.addProperty("total", results.size());
        out.addProperty("files", removedFiles);
        out.addProperty("bytes", removedBytes);
        out.add("results", results);
        invalidateCaches();
        return out;
    }

    /** 恢复不改组集合,但 state 变了,列表要立刻反映 */
    public synchronized void markRestored(String id) throws IOException {
        invalidateCaches();
        Path directory = groupDirectory(this.root, id);
        JsonObject manifest = readManifest(directory);
        manifest.addProperty("state", "restored");
        manifest.addProperty("restored_at", System.currentTimeMillis());
        writeJsonAtomic(directory.resolve(MANIFEST), manifest);
    }

    public synchronized int setLimit(int limit) throws IOException {
        if (limit < 1 || limit > PanelConfig.MAX_RECYCLE_FILES) {
            throw new IllegalArgumentException("回收站上限必须在 1 到 1000000 之间");
        }
        int previous = this.config.recycleMaxFiles;
        this.config.recycleMaxFiles = limit;
        try {
            this.config.save();
        } catch (IOException error) {
            this.config.recycleMaxFiles = previous;
            throw error;
        }
        return limit;
    }

    private JsonObject buildManifest(String id, List<Source> sources, Map<UUID, OperationalState> states) {
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
            JsonObject body = bodyJson(primary, summary,
                    states.getOrDefault(primary.uuid, new OperationalState(false, false)));
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

    private static JsonObject bodyJson(Source source, DiskScanner.DiskEntry summary, OperationalState state) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", source.uuid.toString());
        body.addProperty("dim", blankDimension(source.dimension));
        body.addProperty("source_entry", source.key.id());
        body.addProperty("paused", state.paused());
        body.addProperty("forced", state.forced());
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
            // 删除尚未发生;超限备份必须在事务阶段失败,不能写得出却恢复时读不回来。
            BoundedNbtIo.requireCompressedSize(file);
            files.get(source.uuid).add(name);
        }
    }

    private RestoreGroup readGroup(String id, Path directory, JsonObject manifest) throws IOException {
        if (!manifest.has("version") || manifest.get("version").getAsInt() != FORMAT_VERSION) {
            throw new IOException("回收组格式不受当前版本支持: " + id);
        }
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
            String sourceEntry = body.get("source_entry").getAsString();
            boolean paused = body.get("paused").getAsBoolean();
            boolean forced = body.get("forced").getAsBoolean();
            bodies.add(new RestoreBody(uuid, dimension, sourceEntry, tag, paused, forced));
        }
        if (bodies.isEmpty()) throw new IOException("回收组为空");
        String state = manifest.has("state") ? manifest.get("state").getAsString() : "deleted";
        return new RestoreGroup(id, state, this.versions.isOldVersion(directory), List.copyOf(bodies));
    }

    static List<Path> committedDirectories(Path root) throws IOException {
        Path pending = root.resolve(".pending");
        if (!Files.isDirectory(root)) return List.of();
        List<Path> directories = new ArrayList<>();
        // 不 toList():只在迭代里判定,省一份全量路径
        try (var stream = Files.list(root)) {
            for (var iterator = stream.iterator(); iterator.hasNext(); ) {
                Path path = iterator.next();
                String id = path.getFileName().toString();
                if (Files.isDirectory(path) && !path.equals(pending) && SAFE_ID.matcher(id).matches()
                        && Files.isRegularFile(path.resolve(MANIFEST))) directories.add(path);
            }
        }
        return directories;
    }

    /**
     * 分页用的目录清单:已按 id 降序(即时间序)排好,并按版本分好组。
     * <p>
     * 每次翻页都重新 {@code Files.list} + 每组 4 次 stat + 全量排序,是 {@code O(总组数)} 的活,
     * 而翻页本身只需要一页。
     * <p>
     * 只按写入失效,不带 TTL。目录集合只有本类会改,而且每个改动点都在 synchronized 里紧跟一次
     * {@link #invalidateCaches}:提交(含版本事务里的 markOld)、彻底删除、恢复;两处恢复流程
     * 只在构造函数里跑,那时缓存还没建。绑 30 秒 TTL 的话,一个没有任何写入的回收站也会因为
     * "两次请求隔得久"就重扫全盘 —— 百万文件规模下 {@code view()} 是同步的,查看、恢复、清理
     * 会一起卡住。全盘统计({@link #storageStatsCached})另算:它数的是磁盘文件,服主手动删目录时
     * TTL 是唯一的自愈路径,而它过期只是显示数字偏一点。
     * <p>
     * ponytail: 内存缓存而不是持久索引。持久索引要跟每个写入点对账,漏一个就是永久错数据;
     * 缓存最坏只是重扫一次。等到"进程内首次全扫"本身都嫌慢,再上索引。
     */
    private RecyclePages.PageIndex pageIndex() throws IOException {
        if (this.index != null) return this.index;
        List<Path> latest = new ArrayList<>();
        List<Path> old = new ArrayList<>();
        for (Path directory : committedDirectories(this.root)) (this.versions.isOldVersion(directory) ? old : latest).add(directory);
        Comparator<Path> newestFirst = Comparator.comparing((Path path) -> path.getFileName().toString()).reversed();
        latest.sort(newestFirst);
        old.sort(newestFirst);
        this.index = new RecyclePages.PageIndex(List.copyOf(latest), List.copyOf(old));
        return this.index;
    }

    private void invalidateIndex() {
        this.index = null;
    }

    /**
     * 先流式删掉备份文件,最后才删事务标记 / 清单 / 根目录 —— 中途崩了仍能被恢复流程认出来。
     * <p>
     * 和 {@link #deleteTree} 一样不再 {@code Files.walk().sorted().toList()}:
     * purge 和容量淘汰走的是这个方法,一个组允许到百万个备份文件,排序就要先把百万个 Path
     * 全排进内存。{@link FileVisitor} 天然后序,不需要排序也不需要列表。
     */
    private static void deleteCommittedGroup(Path directory) throws IOException {
        Path manifest = directory.resolve(MANIFEST);
        Path marker = directory.resolve(RecycleVersions.OLD_VERSION_MARKER);
        Path transaction = directory.resolve(RecycleVersions.VERSION_TRANSACTION);
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!file.equals(manifest) && !file.equals(marker) && !file.equals(transaction)) {
                    Files.deleteIfExists(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path visited, IOException failure) throws IOException {
                if (failure != null) throw failure;
                if (!visited.equals(directory)) Files.deleteIfExists(visited);
                return FileVisitResult.CONTINUE;
            }
        });
        Files.deleteIfExists(transaction);
        Files.deleteIfExists(marker);
        Files.deleteIfExists(manifest);
        Files.deleteIfExists(directory);
    }

    /**
     * 全盘统计只用于显示"已用 N / 上限 M 个备份文件 · 磁盘 X"。它要走一遍整棵树,
     * 而回收站上限允许到 1,000,000 个文件 —— 每次刷新、每次切筛选都重走一遍是纯浪费。
     * <p>
     * 写入路径(提交、清理)会主动作废缓存;再兜一层 TTL,防止漏掉某个改动路径导致数字长期不动。
     * <p>
     * ponytail: 缓存而非增量计数器。真要做增量就得给每个写入点都记账,漏一个就是永久错数;
     * 显示用的数字慢 30 秒没有代价。等到 TTL 内的一次全走都嫌慢再上目录索引。
     */
    private StorageStats storageStatsCached() throws IOException {
        long now = System.currentTimeMillis();
        if (this.stats != null && now - this.statsAt < STATS_TTL_MS) return this.stats;
        this.stats = storageStats();
        this.statsAt = now;
        return this.stats;
    }

    /** 组集合变了就把两份缓存一起丢掉:全盘统计和分页目录清单的失效时机完全一致 */
    private void invalidateCaches() {
        this.stats = null;
        invalidateIndex();
    }

    private StorageStats storageStats() throws IOException {
        if (!Files.isDirectory(this.root)) return new StorageStats(0, 0);
        int files = 0;
        long bytes = 0;
        // 不要 toList():这里的元素数就是回收站的全部文件数,物化一遍等于白占一份堆
        try (var stream = Files.walk(this.root)) {
            for (var iterator = stream.filter(Files::isRegularFile).iterator(); iterator.hasNext(); ) {
                Path path = iterator.next();
                bytes += Files.size(path);
                if (path.getFileName().toString().endsWith(".nbt.gz") && !path.startsWith(this.pendingRoot)) files++;
            }
        }
        return new StorageStats(files, bytes);
    }

    private static long directoryBytes(Path directory) throws IOException {
        // 流式累加,不物化路径列表:一个组允许到百万个备份文件
        try (var stream = Files.walk(directory)) {
            long bytes = 0;
            for (var iterator = stream.filter(Files::isRegularFile).iterator(); iterator.hasNext(); ) {
                bytes += Files.size(iterator.next());
            }
            return bytes;
        }
    }

    static Path groupDirectory(Path root, String id) throws IOException {
        if (id == null || !SAFE_ID.matcher(id).matches()) throw new IOException("回收组 ID 无效");
        Path directory = root.resolve(id).normalize();
        if (!directory.getParent().equals(root) || !Files.isDirectory(directory)) {
            throw new IOException("回收组不存在");
        }
        return directory;
    }

    private static Path safeChild(Path directory, String name) throws IOException {
        Path file = directory.resolve(name).normalize();
        if (!file.getParent().equals(directory) || !Files.isRegularFile(file)) throw new IOException("回收站文件无效");
        return file;
    }

    static JsonObject readManifest(Path directory) throws IOException {
        Path file = directory.resolve(MANIFEST);
        try {
            // 整份读进堆里,大小没有上限就等于把堆峰值交给磁盘上的文件说了算。
            // 正常清单是每体几百字节 × 组成员数,撞到这条线的只可能是坏文件
            long size = Files.size(file);
            if (size > MANIFEST_MAX_BYTES) throw new IOException("回收组清单过大: " + size + " 字节");
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception error) {
            throw new IOException("回收组清单无法读取: " + directory.getFileName(), error);
        }
    }

    private static CompoundTag readTag(Path file) throws IOException {
        return BoundedNbtIo.readCompressed(file);
    }

    static void writeJsonAtomic(Path file, JsonObject value) throws IOException {
        AtomicIo.writeString(file, GSON.toJson(value));
    }

    /**
     * 后序遍历删除。从前是 {@code Files.walk().sorted(reverseOrder()).toList()} ——
     * 为了拿到"子在前父在后"的顺序,先把整棵树的路径全排进内存排一遍;一个组允许到百万个
     * 备份文件,那就是百万个 Path。{@link FileVisitor} 天然就是后序,不需要排序也不需要列表。
     */
    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path visited, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(visited);
                return FileVisitResult.CONTINUE;
            }
        });
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
