package com.lumira.platform.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Minimal normalized-image contract exposed to optional content modules. */
public interface FileContentPort {
    Image uploadInlineImageForContent(String actor, String filename, InputStream source, long contentLength) throws IOException;

    Image findReadyImageForContent(String actor, UUID id);

    Image findManagedFeedbackImageForContent(UUID id);

    InputStream openContentImage(Image image) throws IOException;

    /** File-module validation failure expressed without exposing ImageNormalizer. */
    final class InvalidImage extends IOException {
        public InvalidImage(String code, Throwable cause) {
            super(code, cause);
        }
    }

    record Image(UUID id, long sizeBytes) {}
}
