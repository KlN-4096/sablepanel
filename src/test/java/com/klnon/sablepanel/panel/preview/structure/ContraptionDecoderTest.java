package com.klnon.sablepanel.panel.preview.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContraptionDecoderTest {
    /**
     * 打包坐标必须按原版 {@code BlockPos.asLong} 解。
     * <p>
     * 这三个值是从实盘 {@code aeronautics:propeller_bearing_contraption} 的
     * BlockList 里原样抄出来的,不是构造的:错的位宽/位序会静默把螺旋桨摆到几百万格外。
     */
    @Test
    void decodesRealWorldPackedPositions() {
        ListTag palette = new ListTag();
        palette.add(named("minecraft:oak_slab"));
        palette.add(named("create:sail_frame"));
        ListTag blocks = new ListTag();
        blocks.add(block(0, 0L));
        blocks.add(block(1, 4096L));
        blocks.add(block(0, 274877906943L));
        blocks.add(block(1, 274877915131L));
        blocks.add(block(0, -274877898757L));

        ContraptionDecoder.Decoded decoded = ContraptionDecoder.decode(entity(palette, blocks, 20480980, 196, 20491258));

        assertNotNull(decoded);
        assertEquals(2, decoded.palette().size());
        assertEquals(List.of(
                        new ContraptionDecoder.Placement(0, 0, 0, 0),
                        new ContraptionDecoder.Placement(0, 0, 1, 1),
                        new ContraptionDecoder.Placement(0, -1, -1, 0),
                        new ContraptionDecoder.Placement(1, -5, 1, 1),
                        new ContraptionDecoder.Placement(-1, -5, 1, 0)),
                decoded.blocks());
    }

    @Test
    void rejectsMalformedOrOversizedPayloads() {
        ListTag palette = new ListTag();
        palette.add(named("minecraft:stone"));
        ListTag outOfRange = new ListTag();
        outOfRange.add(block(1, 0L));
        assertNull(ContraptionDecoder.decode(entity(palette, outOfRange, 0, 0, 0)),
                "State 下标越界必须整体拒绝,不能静默丢方块");

        assertNull(ContraptionDecoder.decode(entity(palette, new ListTag(), 0, 0, 0)), "空方块表没有可渲染内容");
        assertNull(ContraptionDecoder.decode(entity(new ListTag(), listOf(block(0, 0L)), 0, 0, 0)), "空调色板无法解状态");

        ListTag oversized = new ListTag();
        for (int index = 0; index <= ContraptionDecoder.MAX_BLOCKS; index++) oversized.add(block(0, index));
        assertNull(ContraptionDecoder.decode(entity(palette, oversized, 0, 0, 0)));

        CompoundTag notAContraption = new CompoundTag();
        notAContraption.putString("id", "minecraft:pig");
        assertNull(ContraptionDecoder.decode(notAContraption));
    }

    private static ListTag listOf(CompoundTag value) {
        ListTag list = new ListTag();
        list.add(value);
        return list;
    }

    private static CompoundTag named(String name) {
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", name);
        entry.put("Properties", new CompoundTag());
        return entry;
    }

    private static CompoundTag block(int state, long packed) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("State", state);
        entry.putLong("Pos", packed);
        return entry;
    }

    private static CompoundTag entity(ListTag palette, ListTag blockList, int x, int y, int z) {
        CompoundTag blocks = new CompoundTag();
        blocks.put("Palette", palette);
        blocks.put("BlockList", blockList);
        CompoundTag contraption = new CompoundTag();
        contraption.put("Blocks", blocks);
        contraption.putIntArray("Anchor", new int[]{x, y, z});
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "create:stationary_contraption");
        entity.put("Contraption", contraption);
        return entity;
    }
}
