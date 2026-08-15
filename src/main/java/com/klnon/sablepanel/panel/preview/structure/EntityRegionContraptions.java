package com.klnon.sablepanel.panel.preview.structure;

import com.klnon.sablepanel.SablePanel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按锚点从原版 {@code <dim>/entities/*.mca} 里取 contraption 实体。
 * <p>
 * 用原版 {@link RegionFile} 而不是自己拆扇区表:外置 {@code .mcc} 大区块、压缩类型分支这些
 * 边角原版都已经处理好了,自己写一遍只会多出一份要维护的坑。
 * <p>
 * 按区块缓存已解出的 contraption。查询的是「凡是带 Angle 的方块实体」,里面混着大量根本
 * 装不了 contraption 的轴承(实盘 522 个候选里 429 个是空的),不缓存就要为每个空候选
 * 重开一次区域文件。缓存里只留 contraption 实体本身,一个体的量级是个位数。
 * 实例活一次预览,不跨请求存活。
 */
public final class EntityRegionContraptions implements ContraptionSource {
    /** 单个实体区块的 NBT 上限,防病态存档把提取线程拖爆。 */
    private static final long MAX_CHUNK_BYTES = 32L * 1024 * 1024;

    private final Path directory;
    private final RegionStorageInfo info;
    private final Map<Long, List<CompoundTag>> cache = new HashMap<>();

    public EntityRegionContraptions(Path directory, String levelName, ResourceKey<Level> dimension) {
        this.directory = directory;
        this.info = new RegionStorageInfo(levelName, dimension, "entities");
    }

    /** @return 目录不存在时返回 null,调用方按「这个维度没有实体存档」处理。 */
    public static EntityRegionContraptions of(Path entitiesDirectory, String levelName,
                                              ResourceKey<Level> dimension) {
        return entitiesDirectory != null && Files.isDirectory(entitiesDirectory)
                ? new EntityRegionContraptions(entitiesDirectory, levelName, dimension) : null;
    }

    /** 实体恒存放在锚点自己的区块里,{@code Pos} 与锚点分毫不差(实盘 118/118),不必扫邻居。 */
    @Override
    public CompoundTag at(int x, int y, int z) {
        ChunkPos chunk = new ChunkPos(x >> 4, z >> 4);
        for (CompoundTag entity : this.cache.computeIfAbsent(chunk.toLong(), key -> read(chunk))) {
            int[] anchor = entity.getCompound("Contraption").getIntArray("Anchor");
            if (anchor.length == 3 && anchor[0] == x && anchor[1] == y && anchor[2] == z) return entity;
        }
        return null;
    }

    private List<CompoundTag> read(ChunkPos chunk) {
        Path file = this.directory.resolve("r." + chunk.getRegionX() + "." + chunk.getRegionZ() + ".mca");
        if (!Files.isRegularFile(file)) return List.of();
        try (RegionFile region = new RegionFile(this.info, file, this.directory, true)) {
            if (!region.doesChunkExist(chunk)) return List.of();
            CompoundTag tag;
            try (DataInputStream input = region.getChunkDataInputStream(chunk)) {
                if (input == null) return List.of();
                tag = NbtIo.read(input, NbtAccounter.create(MAX_CHUNK_BYTES));
            }
            ListTag entities = tag.getList("Entities", Tag.TAG_COMPOUND);
            List<CompoundTag> found = new ArrayList<>();
            for (int index = 0; index < entities.size(); index++) {
                CompoundTag entity = entities.getCompound(index);
                if (!entity.getCompound("Contraption").isEmpty()) found.add(entity);
            }
            return found;
        } catch (IOException | RuntimeException unreadable) {
            // info 而非 debug:读失败会让 contraption 静默消失且结果可能进缓存,必须在常规日志可见
            SablePanel.LOGGER.debug("sablepanel: preview entity region unreadable {}", file, unreadable);
            return List.of();
        }
    }
}
