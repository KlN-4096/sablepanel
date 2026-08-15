package com.klnon.sablepanel.mixin;

import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

/**
 * 暴露已加载的 holding 区块表,用来定位要摘除的记录(见 {@link HoldingChunkAccessor})。
 * <p>
 * 只读取已加载的区块,不走 {@code getOrLoadHoldingChunk} —— 那个会按需从盘上读区块,
 * 删除路径上不该有这种副作用。
 */
@Mixin(value = SubLevelHoldingChunkMap.class, remap = false)
public interface HoldingChunkMapAccessor {
    @Accessor("loadedHoldingChunks")
    Long2ObjectMap<SubLevelHoldingChunk> sablepanel$loadedHoldingChunks();

    /**
     * uuid → holding 记录的全局索引({@code getHoldingSubLevel} 读的就是它)。
     * 区块那张表摘干净之后条目确实删掉了,但这张索引还留着,体依旧被判定为 holding。
     */
    @Accessor("allHoldingSubLevels")
    Object2ObjectMap<UUID, HoldingSubLevel> sablepanel$allHoldingSubLevels();
}
