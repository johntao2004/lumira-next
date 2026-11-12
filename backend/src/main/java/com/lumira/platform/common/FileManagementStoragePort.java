package com.lumira.platform.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Required storage primitives; optional administration owns authorization, metadata queries and
 * CAS.
 */
public interface FileManagementStoragePort {
    record Blob(UUID id, String originalName, String state, Long sizeBytes) {}

    record FilePreviewView(
            String text, boolean truncated, String imageDataUrl, int page, int pageCount) {}

    InputStream open(Blob blob) throws IOException;

    FilePreviewView preview(Blob blob, int page) throws IOException;

    /**
     * Finish only after the DELETING transaction commits; nested calls remain queued for the
     * worker.
     */
    void finishDeletion(UUID id, long version);
}
