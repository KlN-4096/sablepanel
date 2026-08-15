package com.klnon.sablepanel.panel.preview;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Resolves the immutable structure tag used by the preview subsystem. */
@FunctionalInterface
public interface PreviewSource {
    Loaded load(UUID uuid) throws Exception;

    final class Ambiguous extends Exception {
        public Ambiguous(String message) { super(message); }
    }

    /**
     * @param contraptions 轴承上装配着的 Create contraption 从哪儿读;null 表示不显示。
     *                     回收站与历史副本预览必须是 null —— 那些体的 contraption 早已不在世界里,
     *                     拿当下世界的实体去凑只会画出一个不存在的姿态。
     */
    record Loaded(String cacheKey, CompoundTag tag,
                  com.klnon.sablepanel.panel.preview.structure.ContraptionSource contraptions) {
        public Loaded {
            if (cacheKey == null || cacheKey.isBlank()) {
                throw new IllegalArgumentException("preview cache key is blank");
            }
            if (tag == null) throw new IllegalArgumentException("preview tag is null");
        }

        public Loaded(String cacheKey, CompoundTag tag) {
            this(cacheKey, tag, null);
        }
    }
}
