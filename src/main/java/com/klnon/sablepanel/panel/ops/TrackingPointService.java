package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.panel.storage.DiskScanner;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.tracking_points.SubLevelTrackingPointSavedData;
import dev.ryanhcode.sable.sublevel.tracking_points.TrackingPoint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.joml.Vector3d;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Sable tracking point validation shared by explicit consistency repair and deletion verification. */
public final class TrackingPointService {
    private TrackingPointService() {
    }

    public record Snapshot(UUID id, UUID body, String dimension, boolean inSubLevel,
                           int chunkX, int chunkZ, int storage, int index) {
    }

    public record Issue(String id, UUID trackingId, UUID body, DiskScanner.EntryKey key,
                        int chunkX, int chunkZ) {
    }

    public static List<Issue> stale(List<Snapshot> points, Set<DiskScanner.EntryKey> occupied) {
        List<Issue> issues = new ArrayList<>();
        for (Snapshot point : points) {
            if (!point.inSubLevel) continue;
            DiskScanner.EntryKey key = new DiskScanner.EntryKey(point.dimension,
                    Math.floorDiv(point.chunkX, 32), Math.floorDiv(point.chunkZ, 32),
                    point.storage, point.index);
            if (!occupied.contains(key)) issues.add(new Issue(issueId(point.id, key), point.id,
                    point.body, key, point.chunkX, point.chunkZ));
        }
        issues.sort(Comparator.comparing(Issue::id));
        return issues;
    }

    public static List<Snapshot> snapshotsOnMain(MinecraftServer server) {
        List<Snapshot> points = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            for (Map.Entry<UUID, TrackingPoint> entry
                    : SubLevelTrackingPointSavedData.getOrLoad(level).getAllTrackingPoints()) {
                TrackingPoint point = entry.getValue();
                GlobalSavedSubLevelPointer pointer = point.lastSavedSubLevelPointer();
                if (pointer == null) continue;
                points.add(new Snapshot(entry.getKey(), point.subLevelID(), dimension, point.inSubLevel(),
                        pointer.chunkPos().x, pointer.chunkPos().z,
                        pointer.local().storageIndex(), pointer.local().subLevelIndex()));
            }
        }
        return points;
    }

    public static Set<String> removeOnMain(MinecraftServer server, Collection<Issue> selected,
                                           Set<DiskScanner.EntryKey> occupied, Set<String> skipped) {
        Map<String, Map<String, Issue>> byDimension = new LinkedHashMap<>();
        Set<String> changedDimensions = new LinkedHashSet<>();
        for (Issue issue : selected) {
            byDimension.computeIfAbsent(issue.key.dim(), ignored -> new LinkedHashMap<>())
                    .put(issue.id, issue);
        }
        for (ServerLevel level : server.getAllLevels()) {
            Map<String, Issue> wanted = byDimension.remove(level.dimension().location().toString());
            if (wanted == null) continue;
            SubLevelTrackingPointSavedData data = SubLevelTrackingPointSavedData.getOrLoad(level);
            for (Issue issue : wanted.values()) {
                TrackingPoint current = data.getTrackingPoint(issue.trackingId);
                Issue now = current == null ? null : issueOf(issue.key.dim(), issue.trackingId, current);
                if (!issue.equals(now) || occupied.contains(issue.key)) {
                    skipped.add("tracking:" + issue.id);
                } else {
                    data.removeTrackingPoint(issue.trackingId);
                    changedDimensions.add(issue.key.dim());
                }
            }
        }
        for (Map<String, Issue> missing : byDimension.values()) {
            for (Issue issue : missing.values()) skipped.add("tracking:" + issue.id);
        }
        return Set.copyOf(changedDimensions);
    }

    static Map<UUID, String> detachDeletedOnMain(
            MinecraftServer server, Map<UUID, Map<DiskScanner.EntryKey, CompoundTag>> deleted) {
        List<Update> updates = new ArrayList<>();
        Map<UUID, String> errors = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            SubLevelTrackingPointSavedData data = SubLevelTrackingPointSavedData.getOrLoad(level);
            for (Map.Entry<UUID, TrackingPoint> entry : data.getAllTrackingPoints()) {
                TrackingPoint point = entry.getValue();
                UUID body = point.subLevelID();
                if (!point.inSubLevel() || body == null || !deleted.containsKey(body)) continue;
                GlobalSavedSubLevelPointer pointer = point.lastSavedSubLevelPointer();
                CompoundTag tag = pointer == null ? null : deleted.get(body).get(key(dimension, pointer));
                SubLevelData source = tag == null ? null : SubLevelSerializer.fromData(tag);
                if (source == null) {
                    errors.put(body, "仍有追踪点引用未纳入删除的存储槽: " + entry.getKey());
                    continue;
                }
                Vector3d global = source.pose().transformPosition(new Vector3d(point.point()));
                updates.add(new Update(data, entry.getKey(), new TrackingPoint(
                        false, null, null, global, null)));
            }
        }
        if (!errors.isEmpty()) return errors;
        for (Update update : updates) update.data.setTrackingPoint(update.id, update.point);
        return Map.of();
    }

    public static Path dataFile(MinecraftServer server, ServerLevel level) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(level.dimension(), root).resolve("data")
                .resolve(SubLevelTrackingPointSavedData.FILE_ID + ".dat");
    }

    private static Issue issueOf(String dimension, UUID id, TrackingPoint point) {
        if (!point.inSubLevel() || point.lastSavedSubLevelPointer() == null) return null;
        GlobalSavedSubLevelPointer pointer = point.lastSavedSubLevelPointer();
        DiskScanner.EntryKey key = key(dimension, pointer);
        return new Issue(issueId(id, key), id, point.subLevelID(), key,
                pointer.chunkPos().x, pointer.chunkPos().z);
    }

    private static DiskScanner.EntryKey key(String dimension, GlobalSavedSubLevelPointer pointer) {
        return new DiskScanner.EntryKey(dimension,
                Math.floorDiv(pointer.chunkPos().x, 32), Math.floorDiv(pointer.chunkPos().z, 32),
                pointer.local().storageIndex(), pointer.local().subLevelIndex());
    }

    private static String issueId(UUID id, DiskScanner.EntryKey key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((id + "@" + key.id()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private record Update(SubLevelTrackingPointSavedData data, UUID id, TrackingPoint point) {
    }
}
