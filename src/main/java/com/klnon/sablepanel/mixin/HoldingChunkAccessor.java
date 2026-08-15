package com.klnon.sablepanel.mixin;

import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

/**
 * 暴露 holding 区块里"待加载体"那张表。
 * <p>
 * sable 的 {@code queueDeletion} 只从 {@code getSubLevelPointers()} 摘掉指针,却把体留在这张表里;
 * 而 {@code saveAll()} 末尾会遍历它做搬家整理(pointer 的 chunkPos 对不上就 moveAndSaveSubLevel),
 * 于是刚删掉的体又被写回一个新槽位 —— 净效果是搬家不是删除。删除时必须把这条一起摘掉。
 * <p>
 * 现成接口不够用:{@code getLoadedHoldingSubLevels()} 只读,{@code snatch(uuid)} 会连带把
 * {@code dependencies()} 里的成员一起摘走(糖音气球那种组一次就是 178 个)。
 */
@Mixin(value = SubLevelHoldingChunk.class, remap = false)
public interface HoldingChunkAccessor {
    @Accessor("loadedHoldingSubLevels")
    Object2ObjectMap<UUID, HoldingSubLevel> sablepanel$loadedHoldingSubLevels();
}
