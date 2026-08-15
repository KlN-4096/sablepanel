package com.klnon.sablepanel.panel.preview.structure;

/** Raised before a partial structure can be mistaken for a complete preview. */
public final class PreviewTooLargeException extends Exception {
    public PreviewTooLargeException(int limit) {
        super("preview exceeds " + limit + " non-air voxels");
    }
}
