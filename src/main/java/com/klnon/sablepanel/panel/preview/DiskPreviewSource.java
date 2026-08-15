package com.klnon.sablepanel.panel.preview;

import com.klnon.sablepanel.panel.bodies.BodyIndex;
import com.klnon.sablepanel.panel.preview.structure.ContraptionSource;
import com.klnon.sablepanel.panel.preview.structure.EntityRegionContraptions;
import com.klnon.sablepanel.panel.storage.Digests;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Resolves an online body without making the API router know disk layout details. */
public final class DiskPreviewSource implements PreviewSource {
    private final MinecraftServer server;
    private final BodyIndex index;

    public DiskPreviewSource(MinecraftServer server, BodyIndex index) {
        this.server = server;
        this.index = index;
    }

    @Override
    public Loaded load(UUID uuid) throws Exception {
        Map<String, Path> dimensions = DiskScanner.sublevelDirs(this.server);
        BodyIndex.PreviewSelection selection = this.index.previewSelection(uuid);
        if (selection.ambiguous()) throw new PreviewSource.Ambiguous("副本版本不明确，请显式选择版本");
        DiskScanner.DiskEntry entry = selection.entry();
        Path directory = entry != null ? dimensions.get(entry.key().dim()) : null;
        Loaded loaded = entry != null && directory != null
                ? readLoaded(uuid, directory, entry.key(), contraptions(entry.key().dim())) : null;
        if (loaded != null) return loaded;
        Located located = locateTag(dimensions, uuid);
        if (located == null) {
            if (entry != null) throw new java.io.IOException("预览槽位正在变化");
            return null;
        }
        Loaded relocated = readLoaded(uuid, located.directory(), located.key(), contraptions(located.dim()));
        if (relocated == null) throw new java.io.IOException("预览槽位正在变化");
        return relocated;
    }

    /** contraption 实体在原版世界存档里,是 sublevels 的兄弟目录 {@code <dim>/entities}。 */
    private ContraptionSource contraptions(String dimension) {
        for (ServerLevel level : this.server.getAllLevels()) {
            if (!level.dimension().location().toString().equals(dimension)) continue;
            Path root = this.server.getWorldPath(LevelResource.ROOT);
            Path entities = DimensionType.getStorageFolder(level.dimension(), root).resolve("entities");
            return EntityRegionContraptions.of(entities,
                    this.server.getWorldData().getLevelName(), level.dimension());
        }
        return null;
    }

    private static Located locateTag(Map<String, Path> dimensions, UUID uuid) throws Exception {
        for (var entry : dimensions.entrySet()) {
            DiskScanner.LocatedEntry located = DiskScanner.locateEntries(
                    entry.getKey(), entry.getValue(), Set.of(uuid)).get(uuid);
            if (located != null) return new Located(entry.getValue(), located.key(), entry.getKey());
        }
        return null;
    }

    private static Loaded readLoaded(UUID uuid, Path directory, DiskScanner.EntryKey key,
                                     ContraptionSource contraptions) throws Exception {
        DiskScanner.EntryPayload snapshot = DiskScanner.readEntryPayload(directory, key);
        if (snapshot == null || !uuid.equals(safeUuid(snapshot.tag()))) return null;
        return new Loaded(uuid + "@" + Digests.sha256Hex(snapshot.compressed()), snapshot.tag(), contraptions);
    }

    private record Located(Path directory, DiskScanner.EntryKey key, String dim) {
    }

    private static UUID safeUuid(net.minecraft.nbt.CompoundTag tag) {
        try {
            return tag.getUUID("uuid");
        } catch (Exception ignored) {
            return null;
        }
    }

}
