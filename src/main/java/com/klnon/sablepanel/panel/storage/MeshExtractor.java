package com.klnon.sablepanel.panel.storage;

import com.klnon.sablepanel.panel.PanelConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从存档条目提取预览体素:vanilla PalettedContainer 解码 → 外壳剔除(六邻有空才保留)
 * → 按方块聚合调色板(mapColor 上色 + 中英文名 + 成分计数)。
 * voxels 平铺 [x,y,z,paletteIdx,...],悬停可反查方块。
 */
public final class MeshExtractor {
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
            Blocks.AIR.defaultBlockState());
    private static final int MAX_VOXELS = 400_000;

    public static JsonObject extract(CompoundTag entryTag) {
        CompoundTag chunks = entryTag.getCompound("plot").getCompound("chunks");
        record Sec(int cx, int cz, int sy, PalettedContainer<BlockState> states) {
        }
        List<Sec> secs = new ArrayList<>();
        for (String ck : chunks.getAllKeys()) {
            ChunkPos cp = new ChunkPos(Long.parseLong(ck));
            CompoundTag sections = chunks.getCompound(ck).getCompound("sections");
            for (String sk : sections.getAllKeys()) {
                CompoundTag bs = sections.getCompound(sk).getCompound("block_states");
                if (bs.isEmpty()) continue;
                var result = BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, bs).result();
                if (result.isEmpty()) continue;
                secs.add(new Sec(cp.x, cp.z, Integer.parseInt(sk), result.get()));
            }
        }

        // 收集非 air 体素,调色板按 Block 聚合
        LongSet solid = new LongOpenHashSet();
        Map<Block, Integer> paletteIdx = new HashMap<>();
        List<Block> paletteBlocks = new ArrayList<>();
        List<Long> paletteCounts = new ArrayList<>();
        record Vox(int x, int y, int z, int p) {
        }
        List<Vox> voxels = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        boolean truncated = false;

        outer:
        for (Sec s : secs) {
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState st = s.states.get(x, y, z);
                        if (st.isAir()) continue;
                        if (voxels.size() >= MAX_VOXELS) {
                            truncated = true;
                            break outer;
                        }
                        int gx = s.cx * 16 + x, gy = s.sy * 16 + y, gz = s.cz * 16 + z;
                        Block block = st.getBlock();
                        Integer pi = paletteIdx.get(block);
                        if (pi == null) {
                            pi = paletteBlocks.size();
                            paletteIdx.put(block, pi);
                            paletteBlocks.add(block);
                            paletteCounts.add(0L);
                        }
                        paletteCounts.set(pi, paletteCounts.get(pi) + 1);
                        voxels.add(new Vox(gx, gy, gz, pi));
                        solid.add(pack(gx, gy, gz));
                        minX = Math.min(minX, gx);
                        minY = Math.min(minY, gy);
                        minZ = Math.min(minZ, gz);
                    }
        }

        // 外壳剔除
        JsonArray flat = new JsonArray();
        int kept = 0;
        for (Vox v : voxels) {
            if (solid.contains(pack(v.x + 1, v.y, v.z)) && solid.contains(pack(v.x - 1, v.y, v.z))
                    && solid.contains(pack(v.x, v.y + 1, v.z)) && solid.contains(pack(v.x, v.y - 1, v.z))
                    && solid.contains(pack(v.x, v.y, v.z + 1)) && solid.contains(pack(v.x, v.y, v.z - 1))) {
                continue;
            }
            flat.add(v.x - minX);
            flat.add(v.y - minY);
            flat.add(v.z - minZ);
            flat.add(v.p);
            kept++;
        }

        JsonArray paletteArr = new JsonArray();
        for (int i = 0; i < paletteBlocks.size(); i++) {
            Block b = paletteBlocks.get(i);
            String id = BuiltInRegistries.BLOCK.getKey(b).toString();
            String[] names = BlockNames.of(id);
            int col = b.defaultMapColor().col;
            if (col == 0) col = 0x7F7F7F;
            JsonObject p = new JsonObject();
            p.addProperty("id", id);
            p.addProperty("en", names[0]);
            p.addProperty("zh", names[1]);
            p.addProperty("color", col);
            p.addProperty("count", paletteCounts.get(i));
            paletteArr.add(p);
        }

        JsonObject out = new JsonObject();
        out.addProperty("total", voxels.size());
        out.addProperty("shell", kept);
        out.addProperty("truncated", truncated);
        out.add("palette", paletteArr);
        out.add("voxels", flat);
        return out;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x + 1048576) << 42) | ((long) (y + 1048576) << 21) | (z + 1048576);
    }
}
