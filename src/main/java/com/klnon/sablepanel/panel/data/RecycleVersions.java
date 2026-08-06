package com.klnon.sablepanel.panel.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klnon.sablepanel.SablePanel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 回收站的版本语义:同 UUID 依赖组的"最新/旧版"划分。
 * 内存里维护 latest 索引(uuid → 最新组);新组提交时把被顶掉的组用 .supersedes
 * 事务文件标旧 —— 事务先于目录移动落盘,进程中断后启动时可续作,标旧不会半途丢失。
 */
final class RecycleVersions {
    static final String OLD_VERSION_MARKER = ".old-version";
    static final String VERSION_TRANSACTION = ".supersedes";

    private final Path root;
    private final Map<UUID, String> latestByUuid = new LinkedHashMap<>();
    private final Map<String, Set<UUID>> latestMembers = new LinkedHashMap<>();
    private final Set<String> pendingOldGroups = new LinkedHashSet<>();

    RecycleVersions(Path root) {
        this.root = root;
    }

    /** 彻底删除一个组后,把它从 latest 索引与待标旧集合里一并抹掉 */
    void forgetGroup(String id) {
        removeLatestGroup(id);
        this.pendingOldGroups.remove(id);
    }

    void rebuildLatestIndex() {
        this.latestByUuid.clear();
        this.latestMembers.clear();
        try {
            List<Path> directories = new ArrayList<>(RecycleStore.committedDirectories(this.root));
            directories.sort(Comparator.comparing((Path path) -> path.getFileName().toString()));
            for (Path directory : directories) {
                if (isOldVersion(directory)) continue;
                try {
                    String id = directory.getFileName().toString();
                    Set<UUID> members = bodyUuids(RecycleStore.readManifest(directory));
                    this.latestMembers.put(id, members);
                    for (UUID uuid : members) this.latestByUuid.put(uuid, id);
                } catch (Exception error) {
                    SablePanel.LOGGER.warn("sablepanel: unreadable recycle group {} was not indexed",
                            directory.getFileName(), error);
                }
            }
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: recycle latest-version index rebuild failed", error);
        }
    }

    Set<String> prepareVersionTransaction(Path stageDirectory, JsonObject manifest) throws IOException {
        String newId = manifest.get("id").getAsString();
        Set<UUID> members = bodyUuids(manifest);
        Set<String> previous = new LinkedHashSet<>();
        for (UUID uuid : members) {
            String id = this.latestByUuid.get(uuid);
            if (id != null && !id.equals(newId)) previous.add(id);
        }
        Path transaction = stageDirectory.resolve(VERSION_TRANSACTION);
        if (previous.isEmpty()) {
            Files.deleteIfExists(transaction);
            return previous;
        }
        JsonObject value = new JsonObject();
        JsonArray ids = new JsonArray();
        for (String id : previous) ids.add(id);
        value.add("supersedes", ids);
        RecycleStore.writeJsonAtomic(transaction, value);
        return previous;
    }

    void registerLatest(String newId, Set<UUID> members, Set<String> previous) {
        this.pendingOldGroups.addAll(previous);
        for (String id : previous) removeLatestGroup(id);
        this.latestMembers.put(newId, members);
        for (UUID uuid : members) this.latestByUuid.put(uuid, newId);
    }

    void recoverVersionTransactions() {
        try {
            for (Path directory : RecycleStore.committedDirectories(this.root)) {
                if (!Files.isRegularFile(directory.resolve(VERSION_TRANSACTION))) continue;
                try {
                    Set<String> previous = readVersionTransaction(directory);
                    this.pendingOldGroups.addAll(previous);
                    completeVersionTransaction(directory);
                } catch (Exception error) {
                    SablePanel.LOGGER.warn("sablepanel: recycle version transaction {} remains pending",
                            directory.getFileName(), error);
                }
            }
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: recycle version transaction recovery failed", error);
        }
    }

    void completeVersionTransaction(Path directory) throws IOException {
        Path transaction = directory.resolve(VERSION_TRANSACTION);
        if (!Files.isRegularFile(transaction)) return;
        Set<String> previous = readVersionTransaction(directory);
        this.pendingOldGroups.addAll(previous);
        for (String id : previous) {
            Path previousDirectory = this.root.resolve(id).normalize();
            if (!previousDirectory.getParent().equals(this.root)) throw new IOException("旧版本回收组 ID 无效");
            if (!Files.exists(previousDirectory)) continue;
            markOld(RecycleStore.groupDirectory(this.root, id));
        }
        Files.delete(transaction);
        this.pendingOldGroups.removeAll(previous);
    }

    Set<String> readVersionTransaction(Path directory) throws IOException {
        JsonObject value;
        try {
            value = JsonParser.parseString(Files.readString(directory.resolve(VERSION_TRANSACTION),
                    StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("回收站版本事务损坏", error);
        }
        JsonArray ids = value.getAsJsonArray("supersedes");
        if (ids == null) throw new IOException("回收站版本事务缺少 supersedes");
        Set<String> result = new LinkedHashSet<>();
        for (var element : ids) {
            String id = element.getAsString();
            if (!RecycleStore.SAFE_ID.matcher(id).matches()) throw new IOException("回收站版本事务 ID 无效");
            result.add(id);
        }
        return result;
    }

    void removeLatestGroup(String id) {
        Set<UUID> members = this.latestMembers.remove(id);
        if (members == null) return;
        for (UUID uuid : members) this.latestByUuid.remove(uuid, id);
    }

    static Set<UUID> bodyUuids(JsonObject manifest) {
        Set<UUID> result = new LinkedHashSet<>();
        JsonArray bodies = manifest.getAsJsonArray("bodies");
        if (bodies == null) return result;
        for (var element : bodies) {
            JsonObject body = element.getAsJsonObject();
            if (body.has("uuid")) result.add(UUID.fromString(body.get("uuid").getAsString()));
        }
        return result;
    }

    boolean isOldVersion(Path directory) {
        return hasOldMarker(directory) || this.pendingOldGroups.contains(directory.getFileName().toString());
    }

    static boolean hasOldMarker(Path directory) {
        return Files.isRegularFile(directory.resolve(OLD_VERSION_MARKER));
    }

    static String archivedState(Path directory) throws IOException {
        Path marker = directory.resolve(OLD_VERSION_MARKER);
        if (!Files.isRegularFile(marker)) return null;
        String value = Files.readString(marker, StandardCharsets.UTF_8).trim();
        if (value.isEmpty() || "old".equals(value) || "deleted".equals(value)) return "deleted";
        if ("incomplete".equals(value)) return value;
        throw new IOException("旧版本事务标记无效");
    }

    static void markOld(Path directory) throws IOException {
        if (!hasOldMarker(directory)) {
            Files.writeString(directory.resolve(OLD_VERSION_MARKER), "old\n", StandardCharsets.UTF_8);
        }
    }
}
