package com.klnon.sablepanel.panel.copies;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
import com.klnon.sablepanel.panel.storage.DiskScanner;
import com.klnon.sablepanel.panel.storage.UnionFind;

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

    public enum CurrentState {
        KNOWN, UNKNOWN, MIXED
    }

    public record Version(String id, boolean complete, int activeMembers, List<Copy> copies,
                          List<Copy> redundant, List<Location> locations, Set<UUID> missingDependencies) {
        public boolean active() {
            return this.activeMembers > 0;
        }

        public int blocks() {
            return this.copies.stream().mapToInt(Copy::blocks).sum();
        }
    }

    public record Scan(UUID target, Set<UUID> members, List<Version> versions,
                       List<Copy> incomplete, String currentVersion, CurrentState currentState, int activeMembers) {
    }

    public static Scan scan(Map<String, Path> dimensions, Map<UUID, List<DiskScanner.EntryMeta>> metadata,
                            UUID target, Map<UUID, String> activeEntries, List<String> warnings) throws IOException {
        Set<UUID> members = members(metadata, target);

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
        return assemble(target, members, copies, activeEntries);
    }

    public static Set<UUID> members(Map<UUID, List<DiskScanner.EntryMeta>> metadata, UUID target)
            throws IOException {
        List<Set<UUID>> groups = DiskScanner.selectedDependencyComponents(metadata, List.of(target));
        Set<UUID> members = groups.isEmpty() ? Set.of(target) : groups.get(0);
        if (members.size() > MAX_MEMBERS) throw new IOException("副本依赖组超过 " + MAX_MEMBERS + " 个成员");
        return Set.copyOf(members);
    }

    public static Scan assemble(UUID target, Set<UUID> members, Collection<Copy> copies,
                                Map<UUID, String> activeEntries) {
        Map<Location, LinkedHashMap<DiskScanner.EntryKey, Copy>> byLocation = new LinkedHashMap<>();
        for (Copy copy : copies) {
            for (DiskScanner.LiveLocation pointer : copy.pointers()) {
                Location location = new Location(copy.key().dim(), pointer.chunkX(), pointer.chunkZ());
                byLocation.computeIfAbsent(location, ignored -> new LinkedHashMap<>()).put(copy.key(), copy);
            }
        }

        Map<String, MutableVersion> versions = new LinkedHashMap<>();
        for (Map.Entry<Location, LinkedHashMap<DiskScanner.EntryKey, Copy>> entry : byLocation.entrySet()) {
            Version candidate = atLocation(target, entry.getKey(), entry.getValue().values(), activeEntries);
            if (candidate == null) continue;
            MutableVersion known = versions.get(candidate.id());
            if (known == null) versions.put(candidate.id(), new MutableVersion(candidate));
            else known.merge(candidate, entry.getKey());
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
        Current current = resolveCurrent(result, activeEntries);
        return new Scan(target, Set.copyOf(members), result, incomplete, current.version(), current.state(),
                activeEntries.size());
    }

    private static Version atLocation(UUID target, Location location, Collection<Copy> locationCopies,
                                      Map<UUID, String> activeEntries) {
        Map<UUID, List<Copy>> byUuid = new LinkedHashMap<>();
        for (Copy copy : locationCopies) byUuid.computeIfAbsent(copy.uuid(), ignored -> new ArrayList<>()).add(copy);
        if (!byUuid.containsKey(target)) return null;

        UnionFind linked = new UnionFind();
        for (UUID uuid : byUuid.keySet()) linked.add(uuid);
        for (Map.Entry<UUID, List<Copy>> entry : byUuid.entrySet()) {
            for (Copy copy : entry.getValue()) {
                for (UUID dependency : DiskScanner.dependencies(copy.tag())) {
                    if (linked.contains(dependency)) linked.union(entry.getKey(), dependency);
                }
            }
        }
        // 顺序无关:selected/redundant 输出前按条目 id 重排,missing 是集合
        UUID targetRoot = linked.find(target);
        Set<UUID> connected = new LinkedHashSet<>();
        for (UUID uuid : byUuid.keySet()) {
            if (linked.find(uuid).equals(targetRoot)) connected.add(uuid);
        }

        List<Copy> selected = new ArrayList<>();
        List<Copy> redundant = new ArrayList<>();
        Set<UUID> missing = new LinkedHashSet<>();
        boolean duplicateMember = false;
        int activeMembers = 0;
        for (UUID uuid : connected) {
            List<Copy> values = new ArrayList<>(byUuid.getOrDefault(uuid, List.of()));
            String activeEntry = activeEntries.get(uuid);
            values.sort(Comparator.comparing(copy -> copy.key().id()));
            if (activeEntry != null && values.stream().anyMatch(copy -> copy.key().id().equals(activeEntry))) {
                activeMembers++;
            }
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
                for (UUID dependency : DiskScanner.dependencies(copy.tag())) {
                    if (!byUuid.containsKey(dependency)) missing.add(dependency);
                }
            }
        }
        selected.sort(Comparator.comparing(copy -> copy.key().id()));
        redundant.sort(Comparator.comparing(copy -> copy.key().id()));
        return new Version(versionId(selected), !duplicateMember && missing.isEmpty(), activeMembers,
                List.copyOf(selected), List.copyOf(redundant), List.of(location), Set.copyOf(missing));
    }

    private static Current resolveCurrent(List<Version> versions, Map<UUID, String> activeEntries) {
        if (activeEntries.isEmpty()) return new Current(null, CurrentState.UNKNOWN);
        List<Version> complete = versions.stream().filter(Version::complete).toList();
        Set<String> compatible = null;
        boolean unmatched = false;
        for (Map.Entry<UUID, String> evidence : activeEntries.entrySet()) {
            Set<String> matching = complete.stream()
                    .filter(version -> containsEntry(version, evidence.getKey(), evidence.getValue()))
                    .map(Version::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (matching.isEmpty()) {
                unmatched = true;
                continue;
            }
            if (compatible == null) compatible = matching;
            else compatible.retainAll(matching);
        }
        if (compatible != null && compatible.isEmpty()) return new Current(null, CurrentState.MIXED);
        if (!unmatched && compatible != null && compatible.size() == 1) {
            return new Current(compatible.iterator().next(), CurrentState.KNOWN);
        }
        return new Current(null, CurrentState.UNKNOWN);
    }

    private static boolean containsEntry(Version version, UUID uuid, String entryId) {
        return java.util.stream.Stream.concat(version.copies().stream(), version.redundant().stream())
                .anyMatch(copy -> copy.uuid().equals(uuid) && copy.key().id().equals(entryId));
    }

    private record Current(String version, CurrentState state) {
    }

    private static UUID readUuid(CompoundTag tag) {
        try {
            return tag.getUUID("uuid");
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 随物理每 tick 变化、并被 {@code saveAll} 落盘的顶层键(键名取自实盘 349 个体的清点)。
     * <p>
     * 它们是运行态,不是版本身份。算进 id 会让两条路径对同一个已加载副本得出不同的 id ——
     * 展示走 {@code CopyOps.inspectCopies} 不 flush,确认走 {@code prepareCopyResolution}
     * 却先无条件 {@code flushLoadedTargets},saveAll 把当前 pose/速度重写下去 ——
     * 于是常驻+加载中的体永远处理不了副本,重扫也没用(下次确认又 flush 一遍)。
     * <p>
     * 只做减法:未列出的键(含模组自定义的 user_data)一律仍参与哈希,宁可多报一次变化。
     * 条目 key 本身也仍在哈希里,所以不同槽位的副本 id 依旧不同,{@code assemble} 的合并语义不变。
     */
    private static final Set<String> RUNTIME_STATE_KEYS =
            Set.of("pose", "world_bounds", "linear_velocity", "angular_velocity");

    /** 浅挑键:子标签直接复用引用,不深拷整份 plot(大体几十 MB)。 */
    private static CompoundTag versionIdentity(CompoundTag tag) {
        CompoundTag identity = new CompoundTag();
        for (String key : tag.getAllKeys()) {
            if (!RUNTIME_STATE_KEYS.contains(key)) identity.put(key, tag.get(key));
        }
        return identity;
    }

    private static String versionId(List<Copy> copies) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Copy copy : copies) {
                digest.update(copy.key().id().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream output = new DataOutputStream(bytes)) {
                    NbtIo.write(versionIdentity(copy.tag()), output);
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
        private int activeMembers;

        private MutableVersion(Version version) {
            this.version = version;
            this.locations.addAll(version.locations());
            this.activeMembers = version.activeMembers();
        }

        private void merge(Version candidate, Location location) {
            this.locations.add(location);
            this.activeMembers = Math.max(this.activeMembers, candidate.activeMembers());
        }

        private Version freeze() {
            List<Location> ordered = this.locations.stream().sorted().toList();
            return new Version(this.version.id(), this.version.complete(), this.activeMembers,
                    this.version.copies(), this.version.redundant(), ordered, this.version.missingDependencies());
        }
    }
}
