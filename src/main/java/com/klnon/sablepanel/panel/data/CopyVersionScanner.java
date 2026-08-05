package com.klnon.sablepanel.panel.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reconstructs dependency-group versions from payloads referenced by the same holding chunk. */
public final class CopyVersionScanner {
    private static final int MAX_MEMBERS = 500;
    private static final int MAX_COPIES = 2_000;

    private CopyVersionScanner() {
    }

    public record Location(String dimension, int chunkX, int chunkZ) implements Comparable<Location> {
        @Override
        public int compareTo(Location other) {
            int dimensionOrder = this.dimension.compareTo(other.dimension);
            if (dimensionOrder != 0) return dimensionOrder;
            int xOrder = Integer.compare(this.chunkX, other.chunkX);
            return xOrder != 0 ? xOrder : Integer.compare(this.chunkZ, other.chunkZ);
        }
    }

    public record Copy(UUID uuid, DiskScanner.EntryKey key, CompoundTag tag, int blocks,
                       List<DiskScanner.LiveLocation> pointers) {
    }

    public record Version(String id, boolean complete, boolean active, List<Copy> copies,
                          List<Copy> redundant, List<Location> locations, Set<UUID> missingDependencies) {
        public int blocks() {
            return this.copies.stream().mapToInt(Copy::blocks).sum();
        }
    }

    public record Scan(UUID target, Set<UUID> members, List<Version> versions,
                       List<Copy> incomplete, String currentVersion) {
    }

    public static Scan scan(Map<String, Path> dimensions, Map<UUID, List<DiskScanner.EntryMeta>> metadata,
                            UUID target, String activeEntry, List<String> warnings) throws IOException {
        List<Set<UUID>> groups = DiskScanner.selectedDependencyComponents(metadata, List.of(target));
        Set<UUID> members = groups.isEmpty() ? Set.of(target) : groups.get(0);
        if (members.size() > MAX_MEMBERS) throw new IOException("副本依赖组超过 " + MAX_MEMBERS + " 个成员");

        Set<DiskScanner.EntryKey> keys = new LinkedHashSet<>();
        for (UUID member : members) {
            for (DiskScanner.EntryMeta entry : metadata.getOrDefault(member, List.of())) keys.add(entry.key());
        }
        if (keys.isEmpty()) throw new IOException("找不到该物理结构的磁盘条目");
        if (keys.size() > MAX_COPIES) throw new IOException("副本条目超过 " + MAX_COPIES + " 个");

        Map<DiskScanner.EntryKey, List<DiskScanner.LiveLocation>> pointers =
                DiskScanner.locatePointersStrict(dimensions, keys, warnings);
        List<Copy> copies = new ArrayList<>();
        for (UUID member : members) {
            for (DiskScanner.EntryMeta entry : metadata.getOrDefault(member, List.of())) {
                Path directory = dimensions.get(entry.key().dim());
                CompoundTag tag = directory == null ? null : DiskScanner.readEntryTag(directory, entry.key());
                if (tag == null || !member.equals(readUuid(tag))) {
                    throw new IOException("副本槽位已经变化: " + entry.key().id());
                }
                copies.add(new Copy(member, entry.key(), tag,
                        DiskScanner.countBlocks(tag.getCompound("plot"), null),
                        List.copyOf(pointers.getOrDefault(entry.key(), List.of()))));
            }
        }
        return assemble(target, members, copies, activeEntry);
    }

    static Scan assemble(UUID target, Set<UUID> members, Collection<Copy> copies, String activeEntry) {
        Map<Location, LinkedHashMap<DiskScanner.EntryKey, Copy>> byLocation = new LinkedHashMap<>();
        for (Copy copy : copies) {
            for (DiskScanner.LiveLocation pointer : copy.pointers()) {
                Location location = new Location(copy.key().dim(), pointer.chunkX(), pointer.chunkZ());
                byLocation.computeIfAbsent(location, ignored -> new LinkedHashMap<>()).put(copy.key(), copy);
            }
        }

        Map<String, MutableVersion> versions = new LinkedHashMap<>();
        for (Map.Entry<Location, LinkedHashMap<DiskScanner.EntryKey, Copy>> entry : byLocation.entrySet()) {
            Version candidate = atLocation(target, entry.getKey(), entry.getValue().values(), activeEntry);
            if (candidate == null) continue;
            MutableVersion known = versions.get(candidate.id());
            if (known == null) versions.put(candidate.id(), new MutableVersion(candidate));
            else known.locations.add(entry.getKey());
        }

        List<Version> result = versions.values().stream().map(MutableVersion::freeze)
                .sorted(Comparator.comparing((Version version) -> !version.active())
                        .thenComparing((Version version) -> !version.complete())
                        .thenComparing(Version::id)).toList();
        Set<DiskScanner.EntryKey> assigned = new LinkedHashSet<>();
        for (Version version : result) {
            if (version.complete()) {
                version.copies().forEach(copy -> assigned.add(copy.key()));
                version.redundant().forEach(copy -> assigned.add(copy.key()));
            }
        }
        List<Copy> incomplete = copies.stream().filter(copy -> !assigned.contains(copy.key()))
                .sorted(Comparator.comparing(copy -> copy.key().id())).toList();
        String current = result.stream().filter(version -> version.active() && version.complete())
                .map(Version::id).findFirst().orElse(null);
        return new Scan(target, Set.copyOf(members), result, incomplete, current);
    }

    private static Version atLocation(UUID target, Location location, Collection<Copy> locationCopies,
                                      String activeEntry) {
        Map<UUID, List<Copy>> byUuid = new LinkedHashMap<>();
        for (Copy copy : locationCopies) byUuid.computeIfAbsent(copy.uuid(), ignored -> new ArrayList<>()).add(copy);
        if (!byUuid.containsKey(target)) return null;

        Map<UUID, Set<UUID>> graph = new LinkedHashMap<>();
        for (UUID uuid : byUuid.keySet()) graph.put(uuid, new LinkedHashSet<>());
        for (Map.Entry<UUID, List<Copy>> entry : byUuid.entrySet()) {
            for (Copy copy : entry.getValue()) {
                for (UUID dependency : dependencies(copy.tag())) {
                    if (!graph.containsKey(dependency)) continue;
                    graph.get(entry.getKey()).add(dependency);
                    graph.get(dependency).add(entry.getKey());
                }
            }
        }

        Set<UUID> connected = new LinkedHashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(target);
        while (!queue.isEmpty()) {
            UUID uuid = queue.removeFirst();
            if (!connected.add(uuid)) continue;
            queue.addAll(graph.getOrDefault(uuid, Set.of()));
        }

        List<Copy> selected = new ArrayList<>();
        List<Copy> redundant = new ArrayList<>();
        Set<UUID> missing = new LinkedHashSet<>();
        boolean duplicateMember = false;
        for (UUID uuid : connected) {
            List<Copy> values = new ArrayList<>(byUuid.getOrDefault(uuid, List.of()));
            values.sort(Comparator.comparing((Copy copy) -> !copy.key().id().equals(activeEntry))
                    .thenComparing(copy -> copy.key().id()));
            if (values.size() > 1) {
                CompoundTag first = values.get(0).tag();
                if (values.stream().allMatch(copy -> copy.tag().equals(first))) {
                    selected.add(values.get(0));
                    redundant.addAll(values.subList(1, values.size()));
                } else {
                    duplicateMember = true;
                    selected.addAll(values);
                }
            } else {
                selected.addAll(values);
            }
            for (Copy copy : values) {
                for (UUID dependency : dependencies(copy.tag())) {
                    if (!byUuid.containsKey(dependency)) missing.add(dependency);
                }
            }
        }
        selected.sort(Comparator.comparing(copy -> copy.key().id()));
        redundant.sort(Comparator.comparing(copy -> copy.key().id()));
        boolean active = activeEntry != null
                && java.util.stream.Stream.concat(selected.stream(), redundant.stream())
                .anyMatch(copy -> copy.key().id().equals(activeEntry));
        return new Version(versionId(selected), !duplicateMember && missing.isEmpty(), active,
                List.copyOf(selected), List.copyOf(redundant), List.of(location), Set.copyOf(missing));
    }

    private static Set<UUID> dependencies(CompoundTag tag) {
        Set<UUID> result = new LinkedHashSet<>();
        if (!tag.contains("loading_dependencies")) return result;
        var values = tag.getList("loading_dependencies", net.minecraft.nbt.Tag.TAG_INT_ARRAY);
        for (net.minecraft.nbt.Tag value : values) result.add(net.minecraft.nbt.NbtUtils.loadUUID(value));
        return result;
    }

    private static UUID readUuid(CompoundTag tag) {
        try {
            return tag.getUUID("uuid");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String versionId(List<Copy> copies) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Copy copy : copies) {
                digest.update(copy.key().id().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream output = new DataOutputStream(bytes)) {
                    NbtIo.write(copy.tag(), output);
                }
                digest.update(bytes.toByteArray());
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static final class MutableVersion {
        private final Version version;
        private final Set<Location> locations = new LinkedHashSet<>();

        private MutableVersion(Version version) {
            this.version = version;
            this.locations.addAll(version.locations());
        }

        private Version freeze() {
            List<Location> ordered = this.locations.stream().sorted().toList();
            return new Version(this.version.id(), this.version.complete(), this.version.active(),
                    this.version.copies(), this.version.redundant(), ordered, this.version.missingDependencies());
        }
    }
}
