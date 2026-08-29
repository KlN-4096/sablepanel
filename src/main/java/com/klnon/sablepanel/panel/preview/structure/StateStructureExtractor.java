package com.klnon.sablepanel.panel.preview.structure;

import com.klnon.sablepanel.panel.preview.protocol.Spm2Record;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.PalettedContainer;
import com.klnon.sablepanel.panel.storage.BlockNames;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Decodes complete block states without invoking world or mod client logic. */
public final class StateStructureExtractor {
    public static final int MAX_VOXELS = 400_000;

    public PreviewStructure extract(CompoundTag entryTag, ContraptionSource contraptions)
            throws PreviewTooLargeException {
        CompoundTag plot = entryTag.getCompound("plot");
        List<Section> sections = readSections(plot.getCompound("chunks"));
        Map<BlockState, Integer> paletteIndexes = new HashMap<>();
        List<MutablePalette> palette = new ArrayList<>();
        // 绝对坐标只是中间量,存成扁平 int(每体素 4 个),不为它造一份等长的对象列表。
        // 它也不能直接用 Spm2Record ——区块 x/z 和 section y 都可能为负,而线格式记录是无符号的。
        IntArrayList voxels = new IntArrayList();
        LongSet occupied = new LongOpenHashSet();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;

        for (Section section : sections) {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = section.states().get(x, y, z);
                if (state.isAir()) continue;
                requireVoxelCapacity(voxels.size() / 4);
                int gx = section.cx() * 16 + x;
                int gy = section.sy() * 16 + y;
                int gz = section.cz() * 16 + z;
                int index = paletteIndexes.computeIfAbsent(state, ignored -> {
                    MutablePalette item = new MutablePalette(state);
                    palette.add(item);
                    return palette.size() - 1;
                });
                palette.get(index).count++;
                voxels.add(gx); voxels.add(gy); voxels.add(gz); voxels.add(index);
                occupied.add(BlockPos.asLong(gx, gy, gz));
                minX = Math.min(minX, gx);
                minY = Math.min(minY, gy);
                minZ = Math.min(minZ, gz);
            }
        }

        /* contraption 体素刻意不进 occupied:它们随轴承转,不该把静态方块判成被遮挡,
           自己也永远算外壳。数量最多两位数,少剔一点比错剔划算得多。 */
        List<PreviewStructure.Group> groups = contraptions == null ? List.of()
                : appendContraptions(plot.getCompound("chunks"), sections, contraptions,
                        voxels, palette, paletteIndexes);
        for (PreviewStructure.Group group : groups) {
            for (int i = group.first() * 4; i < (group.first() + group.count()) * 4; i += 4) {
                minX = Math.min(minX, voxels.getInt(i));
                minY = Math.min(minY, voxels.getInt(i + 1));
                minZ = Math.min(minZ, voxels.getInt(i + 2));
            }
        }

        if (voxels.isEmpty()) {
            minX = minY = minZ = 0;
        }
        Rebased rebased = rebase(voxels, occupied, minX, minY, minZ);

        List<PreviewStructure.PaletteEntry> entries = new ArrayList<>(palette.size());
        for (MutablePalette item : palette) entries.add(item.freeze());
        return new PreviewStructure(entries, rebased.voxels(), rebased.shell(), minX, minY, minZ,
                plot.getInt("plot_x"), plot.getInt("plot_z"), plot.getString("biome"),
                rebase(groups, minX, minY, minZ));
    }

    /**
     * 把每个轴承上装配着的 contraption 展开成体素,追加在体素表末尾并登记成一个旋转组。
     * <p>
     * 每组体素在表里连续,所以组只用记 {@code first/count} 两个数,线格式一个字节都不用加。
     * 存的是装配姿态,当前角度作为组的属性交给前端逐实例乘一个绕轴矩阵 —— 不在这里转,
     * 因为整数体素网格表示不了任意角度(硬取整会让方块互相重叠并打出空洞)。
     */
    private static List<PreviewStructure.Group> appendContraptions(
            CompoundTag chunks, List<Section> sections, ContraptionSource contraptions,
            IntArrayList voxels, List<MutablePalette> palette, Map<BlockState, Integer> paletteIndexes)
            throws PreviewTooLargeException {
        List<Bearing> bearings = readBearings(chunks);
        if (bearings.isEmpty()) return List.of();
        Map<Long, Section> grid = new HashMap<>();
        for (Section section : sections) grid.put(sectionKey(section.cx(), section.sy(), section.cz()), section);
        int offsetY = resolveYOffset(bearings, (x, y, z) -> blockIdAt(grid, x, y, z));
        if (offsetY == NO_OFFSET) return List.of();

        List<PreviewStructure.Group> groups = new ArrayList<>();
        for (Bearing bearing : bearings) {
            BlockState state = stateAt(grid, bearing.localX(), bearing.stagingY() + offsetY, bearing.localZ());
            Direction facing = state == null ? null : facingOf(state);
            if (facing == null) continue;
            CompoundTag entity = contraptions.at(bearing.stagingX() + facing.getStepX(),
                    bearing.stagingY() + facing.getStepY(), bearing.stagingZ() + facing.getStepZ());
            if (entity == null) continue;
            ContraptionDecoder.Decoded decoded = ContraptionDecoder.decode(entity);
            if (decoded == null) continue;
            int anchorX = bearing.localX() + facing.getStepX();
            int anchorY = bearing.stagingY() + offsetY + facing.getStepY();
            int anchorZ = bearing.localZ() + facing.getStepZ();
            int first = voxels.size() / 4, added = 0;
            for (ContraptionDecoder.Placement block : decoded.blocks()) {
                BlockState placed = paletteState(decoded.palette(), block.paletteIndex());
                if (placed == null || placed.isAir()) continue;
                requireVoxelCapacity(voxels.size() / 4);
                int index = paletteIndexes.computeIfAbsent(placed, ignored -> {
                    palette.add(new MutablePalette(placed));
                    return palette.size() - 1;
                });
                palette.get(index).count++;
                voxels.add(anchorX + block.x()); voxels.add(anchorY + block.y());
                voxels.add(anchorZ + block.z()); voxels.add(index);
                added++;
            }
            if (added == 0) continue;
            groups.add(new PreviewStructure.Group(first, added, anchorX, anchorY, anchorZ,
                    facing.getAxis().getSerializedName(), bearing.angle()));
        }
        return List.copyOf(groups);
    }

    /** 轴承候选:凡是带数值 {@code Angle} 的方块实体都算,锚点上有没有 contraption 才是最终判据。 */
    private static List<Bearing> readBearings(CompoundTag chunks) {
        List<Bearing> bearings = new ArrayList<>();
        for (String chunkKey : chunks.getAllKeys()) {
            ChunkPos position = new ChunkPos(Long.parseLong(chunkKey));
            ListTag list = chunks.getCompound(chunkKey).getList("block_entities", Tag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++) {
                CompoundTag entity = list.getCompound(index);
                if (!entity.contains("Angle", Tag.TAG_ANY_NUMERIC)) continue;
                int x = entity.getInt("x"), y = entity.getInt("y"), z = entity.getInt("z");
                bearings.add(new Bearing(entity.getString("id"), x, y, z,
                        position.x * 16 + (x & 15), position.z * 16 + (z & 15), entity.getFloat("Angle")));
            }
        }
        return bearings;
    }

    @FunctionalInterface
    interface BlockIdLookup {
        /** @return 该本地坐标上的方块 id,空气或越界返回 null。 */
        String idAt(int x, int y, int z);
    }

    static final int NO_OFFSET = Integer.MIN_VALUE;

    /**
     * 解出「装载区 y → 子关卡本地 y」的偏移量。
     * <p>
     * 方块实体的 x/z 是装载区世界坐标,但它挂在本地区块键下,所以水平方向直接
     * {@code 本地区块*16 + (世界坐标&15)} 就够了 —— 只有 y 没有这样的锚。
     * <p>
     * 子关卡按 section 对齐到父维度底部,所以偏移必是 16 的倍数(主世界 +64,下界/末地 0;
     * 实盘 522 个轴承全部落在这一条上)。判据是轴承落点上的方块 id 必须等于它自己的 id ——
     * 同一列上摞着三个同款螺旋桨轴承是实际存在的情形,单个轴承定不下来,对全体取交集才唯一。
     *
     * @return 唯一偏移;判不出来时返回 {@link #NO_OFFSET}(调用方按「这个体不显示 contraption」处理)
     */
    static int resolveYOffset(List<Bearing> bearings, BlockIdLookup lookup) {
        int found = NO_OFFSET;
        for (int offset = -384; offset <= 384; offset += 16) {
            boolean all = true;
            for (Bearing bearing : bearings) {
                if (bearing.id().equals(lookup.idAt(bearing.localX(), bearing.stagingY() + offset,
                        bearing.localZ()))) continue;
                all = false;
                break;
            }
            if (!all) continue;
            if (found != NO_OFFSET) return NO_OFFSET;
            found = offset;
        }
        return found;
    }

    record Bearing(String id, int stagingX, int stagingY, int stagingZ, int localX, int localZ, float angle) {
    }

    private static BlockState paletteState(ListTag palette, int index) {
        try {
            return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), palette.getCompound(index));
        } catch (RuntimeException unknownBlock) {
            return null;
        }
    }

    private static Direction facingOf(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (!property.getName().equals("facing")) continue;
            Object value = state.getValue(property);
            if (value instanceof Direction direction) return direction;
        }
        return null;
    }

    private static BlockState stateAt(Map<Long, Section> grid, int x, int y, int z) {
        Section section = grid.get(sectionKey(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16)));
        return section == null ? null
                : section.states().get(Math.floorMod(x, 16), Math.floorMod(y, 16), Math.floorMod(z, 16));
    }

    private static String blockIdAt(Map<Long, Section> grid, int x, int y, int z) {
        BlockState state = stateAt(grid, x, y, z);
        return state == null || state.isAir() ? null : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static long sectionKey(int cx, int sy, int cz) {
        return (long) cx & 0xfffff | ((long) cz & 0xfffff) << 20 | ((long) sy & 0xffffff) << 40;
    }

    private static List<PreviewStructure.Group> rebase(List<PreviewStructure.Group> groups,
                                                      int minX, int minY, int minZ) {
        List<PreviewStructure.Group> out = new ArrayList<>(groups.size());
        for (PreviewStructure.Group group : groups) {
            out.add(new PreviewStructure.Group(group.first(), group.count(), group.pivotX() - minX,
                    group.pivotY() - minY, group.pivotZ() - minZ, group.axis(), group.angle()));
        }
        return out;
    }

    private static List<Section> readSections(CompoundTag chunks) {
        List<String> chunkKeys = new ArrayList<>(chunks.getAllKeys());
        chunkKeys.sort(Comparator.comparingLong(Long::parseLong));
        List<Section> result = new ArrayList<>();
        for (String chunkKey : chunkKeys) {
            ChunkPos position = new ChunkPos(Long.parseLong(chunkKey));
            CompoundTag sections = chunks.getCompound(chunkKey).getCompound("sections");
            List<String> sectionKeys = new ArrayList<>(sections.getAllKeys());
            sectionKeys.sort(Comparator.comparingInt(Integer::parseInt));
            for (String sectionKey : sectionKeys) {
                CompoundTag blockStates = sections.getCompound(sectionKey).getCompound("block_states");
                if (blockStates.isEmpty()) continue;
                PalettedContainer<BlockState> states = requireDecoded(
                        codec().parse(NbtOps.INSTANCE, blockStates).result(), chunkKey + "/" + sectionKey);
                result.add(new Section(position.x, position.z, Integer.parseInt(sectionKey), states));
            }
        }
        return result;
    }

    record Rebased(List<Spm2Record> voxels, byte[] shell) {
    }

    /**
     * 把绝对坐标体素表重基成线格式记录,并按绝对坐标判定外壳位。
     * <p>
     * 单独拆出来是为了能被直接测试:完整的 {@link #extract} 需要方块注册表,而本项目的单元测试
     * 刻意不做 Bootstrap(见 build.gradle),因此非空提取路径此前一条断言都没有。
     */
    static Rebased rebase(IntArrayList voxels, LongSet occupied, int minX, int minY, int minZ) {
        int count = voxels.size() / 4;
        List<Spm2Record> relative = new ArrayList<>(count);
        byte[] shell = new byte[(count + 7) / 8];
        for (int i = 0; i < count; i++) {
            int offset = i * 4;
            int x = voxels.getInt(offset), y = voxels.getInt(offset + 1), z = voxels.getInt(offset + 2);
            relative.add(new Spm2Record(x - minX, y - minY, z - minZ, voxels.getInt(offset + 3)));
            if (!isOccluded(occupied, x, y, z)) shell[i >>> 3] |= 1 << (i & 7);
        }
        return new Rebased(relative, shell);
    }

    static <T> T requireDecoded(java.util.Optional<T> decoded, String location) {
        return decoded.orElseThrow(() -> new IllegalArgumentException("方块区段状态无法解码: " + location));
    }

    static void requireVoxelCapacity(int currentCount) throws PreviewTooLargeException {
        if (currentCount >= MAX_VOXELS) throw new PreviewTooLargeException(MAX_VOXELS);
    }

    private static boolean isOccluded(LongSet occupied, int x, int y, int z) {
        return occupied.contains(BlockPos.asLong(x + 1, y, z)) && occupied.contains(BlockPos.asLong(x - 1, y, z))
                && occupied.contains(BlockPos.asLong(x, y + 1, z)) && occupied.contains(BlockPos.asLong(x, y - 1, z))
                && occupied.contains(BlockPos.asLong(x, y, z + 1)) && occupied.contains(BlockPos.asLong(x, y, z - 1));
    }

    private static Codec<PalettedContainer<BlockState>> codec() {
        return CodecHolder.CODEC;
    }

    private static final class CodecHolder {
        private static final Codec<PalettedContainer<BlockState>> CODEC = PalettedContainer.codecRW(
                Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
                Blocks.AIR.defaultBlockState());
    }

    private record Section(int cx, int cz, int sy, PalettedContainer<BlockState> states) {
    }

    private static final class MutablePalette {
        private final String id;
        private final String stateKey;
        private final String en;
        private final String zh;
        private final int color;
        private final int lightEmission;
        private long count;

        private MutablePalette(BlockState state) {
            this.id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            this.stateKey = stateKey(state, this.id);
            String[] names = BlockNames.of(this.id);
            this.en = names[0];
            this.zh = names[1];
            int mapColor = state.getBlock().defaultMapColor().col;
            this.color = mapColor == 0 ? 0x7F7F7F : mapColor;
            this.lightEmission = state.getLightEmission();
        }

        private PreviewStructure.PaletteEntry freeze() {
            return new PreviewStructure.PaletteEntry(id, stateKey, en, zh, color, lightEmission, count);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String stateKey(BlockState state, String id) {
        List<String> properties = new ArrayList<>();
        for (Property property : state.getProperties()) {
            properties.add(property.getName() + "=" + property.getName(state.getValue(property)));
        }
        properties.sort(String::compareTo);
        return properties.isEmpty() ? id : id + "[" + String.join(",", properties) + "]";
    }
}
