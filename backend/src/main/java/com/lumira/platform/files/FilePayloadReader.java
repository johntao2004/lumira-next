package com.lumira.platform.files;

import com.lumira.platform.common.FileManagementStoragePort;
import java.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Bounded raw content reader shared by personal files and optional file administration. */
final class FilePayloadReader {
    static final long MAXIMUM_BYTES =
            com.lumira.platform.common.AccountFileStoragePort.MAXIMUM_BYTES;
    private final BlobStore blobs;

    FilePayloadReader(BlobStore blobs) {
        this.blobs = blobs;
    }

    InputStream open(FileManagementStoragePort.Blob entry) throws IOException {
        if (!entry.state().equals("READY"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "fileNotReady");
        return blobs.open(entry.id());
    }

    FilePreview preview(FileManagementStoragePort.Blob entry, int page) throws IOException {
        if (!entry.state().equals("READY"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "fileNotReady");
        FilePreviewReader.requireSupported(entry.originalName());
        if (entry.sizeBytes() != null && entry.sizeBytes() > MAXIMUM_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "filePreviewTooLarge");
        try (var admission = FilePreviewReader.admit();
                var input = open(entry)) {
            byte[] content = input.readNBytes((int) MAXIMUM_BYTES + 1);
            if (content.length > MAXIMUM_BYTES)
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "filePreviewTooLarge");
            return FilePreviewReader.read(entry.originalName(), content, page);
        }
    }
}
