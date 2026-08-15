package com.klnon.sablepanel.panel.preview.protocol;

/** One logical SPM2 voxel record: relative x/y/z and palette state index. */
public record Spm2Record(int x, int y, int z, int stateIndex) {
    public Spm2Record {
        if (x < 0 || y < 0 || z < 0 || stateIndex < 0) {
            throw new IllegalArgumentException("SPM2 record values must be unsigned");
        }
    }
}
