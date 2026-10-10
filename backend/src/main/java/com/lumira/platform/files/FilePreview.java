package com.lumira.platform.files;

/**
 * Bounded text or raster preview. PDF pages are rendered locally without executing file content.
 */
public record FilePreview(
        String text, boolean truncated, String imageDataUrl, int page, int pageCount) {
    public FilePreview(String text, boolean truncated) {
        this(text, truncated, null, 1, 1);
    }
}
