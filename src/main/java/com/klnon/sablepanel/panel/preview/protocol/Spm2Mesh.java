package com.klnon.sablepanel.panel.preview.protocol;

import java.util.List;
import java.util.Objects;

/** Immutable logical SPM2 payload before binary encoding.
 *  注意:record 生成的 equals/hashCode 对 shellBitmap 按数组身份比——别当 Set/Map 键,比较请逐字段。 */
public record Spm2Mesh(String metadata, List<Spm2Record> records, byte[] shellBitmap) {
    public Spm2Mesh {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(shellBitmap, "shellBitmap");
        records = List.copyOf(records);
        shellBitmap = shellBitmap.clone();
        int expected = (records.size() + 7) / 8;
        if (shellBitmap.length != expected) {
            throw new IllegalArgumentException("shell bitmap length does not match voxel count");
        }
    }

    @Override
    public byte[] shellBitmap() {
        return shellBitmap.clone();
    }
}
