package com.lumira.platform.common;

import java.util.UUID;

/** Owned, bounded attachment content supplied by the file module to AI adapters. */
public interface AiAttachmentPort {
    int MAX_BYTES = 5 * 1024 * 1024;
    int MAX_TEXT = 20_000;
    int MAX_PER_MESSAGE = 4;

    Content read(String actor, UUID id);

    record Content(
            UUID id,
            String name,
            long sizeBytes,
            String text,
            String imageDataUrl,
            boolean truncated) {
        public boolean image() {
            return imageDataUrl != null;
        }
    }
}
