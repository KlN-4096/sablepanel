package com.klnon.sablepanel.panel.preview.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * 解码 Create contraption 实体载荷:调色板 + 相对锚点的方块表。
 * <p>
 * 装配好的 contraption 不在物理体的 {@code .slvls} 里 —— 它是一个实体,存在世界的
 * {@code <dim>/entities/*.mca} 中。载荷形状(实盘 118 个样本核对过):
 * <pre>
 * Contraption/Anchor                            IntArray[3],装载区世界坐标
 * Contraption/Blocks/Palette[{Name, Properties}] String + Compound
 * Contraption/Blocks/BlockList[{State:Int, Pos:Long}]  Pos 是 BlockPos.asLong,相对锚点
 * </pre>
 * 方块表存的是装配那一刻的姿态,当前角度由轴承方块实体的 {@code Angle} 单独给出 ——
 * 二者相乘才是现在的样子,所以本类不碰旋转(旋转矩阵在前端逐实例施加)。
 * <p>
 * 锚点恒为「轴承方块 + 其朝向一格」(Create 的 {@code bearingPos.relative(facing)}),
 * 实盘 93/93 命中,且实体恒存放在锚点自己的区块里、{@code Pos} 与锚点分毫不差。
 * <p>
 * 本类只做载荷解析。方块状态解析需要方块注册表,留给 {@link StateStructureExtractor} ——
 * 本项目的单元测试刻意不做 Bootstrap(见 build.gradle),所以可测的部分必须独立成缝。
 */
public final class ContraptionDecoder {
    /** 单个 contraption 的方块上限。实盘最大 210 块,留两个数量级的余量纯为兜底。 */
    public static final int MAX_BLOCKS = 20_000;

    private ContraptionDecoder() {
    }

    /** 相对锚点的整数偏移 + 调色板下标。 */
    public record Placement(int x, int y, int z, int paletteIndex) {
    }

    public record Decoded(ListTag palette, List<Placement> blocks) {
    }

    /** 不是 contraption、载荷不完整或超限时返回 null(调用方按「这个轴承没东西」处理)。 */
    public static Decoded decode(CompoundTag entityTag) {
        CompoundTag contraption = entityTag.getCompound("Contraption");
        int[] anchor = contraption.getIntArray("Anchor");
        if (anchor.length != 3) return null;
        CompoundTag blocks = contraption.getCompound("Blocks");
        ListTag palette = blocks.getList("Palette", Tag.TAG_COMPOUND);
        ListTag list = blocks.getList("BlockList", Tag.TAG_COMPOUND);
        if (palette.isEmpty() || list.isEmpty() || list.size() > MAX_BLOCKS) return null;
        List<Placement> placements = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            int state = entry.getInt("State");
            if (state < 0 || state >= palette.size()) return null;
            BlockPos offset = BlockPos.of(entry.getLong("Pos"));
            placements.add(new Placement(offset.getX(), offset.getY(), offset.getZ(), state));
        }
        return new Decoded(palette, List.copyOf(placements));
    }
}
