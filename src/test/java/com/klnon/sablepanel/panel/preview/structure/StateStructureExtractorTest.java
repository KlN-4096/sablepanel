package com.klnon.sablepanel.panel.preview.structure;

import com.google.gson.JsonParser;
import com.klnon.sablepanel.panel.preview.protocol.Spm2Record;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class StateStructureExtractorTest {
    @Test
    void previewMetadataCarriesBothBlockNames() {
        PreviewStructure structure = new PreviewStructure(
                List.of(new PreviewStructure.PaletteEntry(
                        "minecraft:stone", "minecraft:stone", "Stone", "石头", 0x777777, 0, 1)),
                List.of(new Spm2Record(0, 0, 0, 0)), new byte[]{1},
                0, 0, 0, 0, 0, "minecraft:plains", List.of());

        var state = JsonParser.parseString(structure.toSpm2(null).metadata())
                .getAsJsonObject().getAsJsonArray("states").get(0).getAsJsonObject();

        assertEquals("Stone", state.get("en").getAsString());
        assertEquals("石头", state.get("zh").getAsString());
    }

    @Test
    void emptyPlotProducesEmptyCompleteStructure() throws Exception {
        CompoundTag entry = new CompoundTag();
        entry.put("plot", new CompoundTag());

        PreviewStructure structure = new StateStructureExtractor().extract(entry, null);

        assertEquals(0, structure.voxels().size());
        assertEquals(0, structure.palette().size());
        assertEquals(0, structure.shellBitmap().length);
        assertEquals(0, structure.width());
    }

    /**
     * 同一列上摞着三个同款螺旋桨轴承 —— 实盘就有(装载区 y=200/210/220 同 x/z)。
     * 单看任何一个都有三个偏移说得通,只有对全体取交集才唯一。这条红了说明退回了逐个解析。
     */
    @Test
    void stackedIdenticalBearingsStillPinDownOneYOffset() {
        String id = "aeronautics:propeller_bearing";
        var bearings = java.util.List.of(
                new StateStructureExtractor.Bearing(id, 20483139, 200, 20497405, 1091, 1021, 155.78f),
                new StateStructureExtractor.Bearing(id, 20483139, 210, 20497405, 1091, 1021, -155.78f),
                new StateStructureExtractor.Bearing(id, 20483139, 220, 20497405, 1091, 1021, -155.78f));
        var column = java.util.Set.of(264, 274, 284);

        assertEquals(64, StateStructureExtractor.resolveYOffset(bearings,
                (x, y, z) -> x == 1091 && z == 1021 && column.contains(y) ? id : null));
    }

    /** 偏移是子关卡相对父维度底部的对齐量:主世界 +64,下界/末地 0。非 16 倍数永远不该被选中。 */
    @Test
    void netherBodiesResolveToZeroOffset() {
        String id = "create:mechanical_bearing";
        var bearings = java.util.List.of(
                new StateStructureExtractor.Bearing(id, 100, 37, 200, 4, 8, 0f));

        assertEquals(0, StateStructureExtractor.resolveYOffset(bearings,
                (x, y, z) -> x == 4 && z == 8 && y == 37 ? id : null));
    }

    /**
     * 同一列上隔六格摞两个同款轴承(船上很常见)会给出 64 和 70 两个说得通的偏移。
     * 子关卡按 section 对齐父维度底部,偏移必是 16 的倍数,70 因此出局 —— 去掉这条对齐约束
     * 就会退化成「判不出来,整体放弃」。
     */
    @Test
    void nonSectionAlignedCandidatesAreRuledOut() {
        String id = "create:mechanical_bearing";
        var one = java.util.List.of(new StateStructureExtractor.Bearing(id, 0, 100, 0, 0, 0, 0f));

        assertEquals(64, StateStructureExtractor.resolveYOffset(one,
                (x, y, z) -> y == 164 || y == 170 ? id : null));
    }

    /** 判不出唯一解时必须放弃整个体的 contraption,而不是挑一个凑合 —— 挑错会把方块摆到几十格外。 */
    @Test
    void ambiguousOrAbsentColumnYieldsNoOffset() {
        String id = "create:mechanical_bearing";
        var one = java.util.List.of(new StateStructureExtractor.Bearing(id, 0, 100, 0, 0, 0, 0f));

        assertEquals(StateStructureExtractor.NO_OFFSET, StateStructureExtractor.resolveYOffset(one,
                (x, y, z) -> y == 164 || y == 180 ? id : null), "两个偏移都说得通时必须放弃");
        assertEquals(StateStructureExtractor.NO_OFFSET, StateStructureExtractor.resolveYOffset(one,
                (x, y, z) -> null), "列上根本没有这个方块时也必须放弃");
    }

    @Test
    void invalidNonEmptySectionRejectsTheWholePreview() {
        assertThrows(IllegalArgumentException.class,
                () -> StateStructureExtractor.requireDecoded(java.util.Optional.empty(), "0/0"));
    }

    @Test
    void voxelLimitAcceptsExactlyFourHundredThousandAndRejectsTheNext() {
        assertDoesNotThrow(() -> StateStructureExtractor.requireVoxelCapacity(399_999));
        assertThrows(PreviewTooLargeException.class,
                () -> StateStructureExtractor.requireVoxelCapacity(400_000));
    }

    /**
     * 绝对坐标可以是负的(区块 x/z、section y),重基后必须全部落到无符号线格式坐标;
     * 外壳位则必须按 <em>绝对</em> 坐标判定,否则相邻关系会被重基整体打乱。
     */
    @Test
    void rebaseShiftsNegativeAbsoluteCoordinatesAndMarksShellByAbsolutePosition() {
        // 记录 0 是被六面包住的中心,记录 1..6 是它的六个邻居 —— 只有中心不属于外壳。
        // 全部用负的绝对坐标:重基必须把它们搬到无符号区间,而占用查询必须仍用绝对坐标,
        // 否则 occupied 全部落空,中心会被错判成露在外面。
        int[][] positions = {{-32, -64, -17},
                {-33, -64, -17}, {-31, -64, -17},
                {-32, -65, -17}, {-32, -63, -17},
                {-32, -64, -18}, {-32, -64, -16}};
        it.unimi.dsi.fastutil.ints.IntArrayList voxels = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet occupied = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int i = 0; i < positions.length; i++) {
            voxels.add(positions[i][0]); voxels.add(positions[i][1]); voxels.add(positions[i][2]); voxels.add(i);
            occupied.add(net.minecraft.core.BlockPos.asLong(positions[i][0], positions[i][1], positions[i][2]));
        }

        var rebased = StateStructureExtractor.rebase(voxels, occupied, -33, -65, -18);

        assertEquals(7, rebased.voxels().size());
        for (int i = 0; i < positions.length; i++) {
            assertEquals(positions[i][0] + 33, rebased.voxels().get(i).x());
            assertEquals(positions[i][1] + 65, rebased.voxels().get(i).y());
            assertEquals(positions[i][2] + 18, rebased.voxels().get(i).z());
            assertEquals(i, rebased.voxels().get(i).stateIndex(), "记录顺序必须与写入顺序一致");
        }
        assertEquals(1, rebased.shell().length);
        assertEquals(0b1111110, rebased.shell()[0] & 0xFF,
                "被六面包住的中心不在外壳,六个邻居都在");
    }

    /** 每体素占 4 个 int,上限判定必须按体素数而不是 int 数。 */
    @Test
    void voxelCapacityCountsVoxelsNotInts() {
        it.unimi.dsi.fastutil.ints.IntArrayList voxels = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int i = 0; i < 5; i++) { voxels.add(i); voxels.add(0); voxels.add(0); voxels.add(0); }
        assertEquals(5, voxels.size() / 4);
        assertEquals(5, StateStructureExtractor.rebase(voxels,
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet(), 0, 0, 0).voxels().size());
    }
}
