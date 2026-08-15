package com.klnon.sablepanel.panel.preview.structure;

import net.minecraft.nbt.CompoundTag;

/** 按装载区世界坐标取锚点上的 Create contraption 实体标签;没有则 null。 */
@FunctionalInterface
public interface ContraptionSource {
    CompoundTag at(int x, int y, int z);
}
