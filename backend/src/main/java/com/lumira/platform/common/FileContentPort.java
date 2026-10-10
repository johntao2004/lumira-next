package com.lumira.platform.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Minimal normalized-image contract exposed to optional content modules. */
public interface FileContentPort {
    long MAXIMUM_BRANDING_IMAGE_BYTES = 5L * 1024 * 1024;

    Image uploadInlineImageForContent(
            String actor, String filename, InputStream source, long contentLength)
            throws IOException;

    /** Uploads a sanitized image for public platform branding at the slot's display resolution. */
    Image uploadBrandingImage(
            String actor, BrandingImageSlot slot, InputStream source, long contentLength)
            throws IOException;

    Image findReadyImageForContent(String actor, UUID id);

    /**
     * Looks up a saved branding image; only images registered by the branding upload path qualify.
     */
    Image findReadyBrandingImage(UUID id);

    /** Retains an uploaded image for the current setting and its immutable history revisions. */
    void retainBrandingImage(UUID id);

    Image findManagedFeedbackImageForContent(UUID id);

    InputStream openContentImage(Image image) throws IOException;

    /** File-module validation failure expressed without exposing ImageNormalizer. */
    final class InvalidImage extends IOException {
        public InvalidImage(String code, Throwable cause) {
            super(code, cause);
        }
    }

    record Image(UUID id, long sizeBytes) {}

    enum BrandingImageSlot {
        FAVICON(256, "favicon"),
        LOGO(1024, "logo"),
        LOGIN_BACKGROUND(2048, "login-background");

        private final int outputEdge;
        private final String filename;

        BrandingImageSlot(int outputEdge, String filename) {
            this.outputEdge = outputEdge;
            this.filename = filename;
        }

        public int outputEdge() {
            return outputEdge;
        }

        public String filename() {
            return filename;
        }
    }
}
