package com.lumira.platform.files;

import com.lumira.platform.common.*;
import java.io.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class FileManagementStorageAdapter implements FileManagementStoragePort {
    private final FilePayloadReader payloads;
    private final FileDeleteFinalizer deletion;

    public FileManagementStorageAdapter(
            FileRepository files,
            BlobStore blobs,
            AuditPort audit,
            PlatformTransactionManager manager) {
        payloads = new FilePayloadReader(blobs);
        deletion = new FileDeleteFinalizer(files, blobs, audit, manager);
    }

    @Override
    public InputStream open(Blob blob) throws IOException {
        return payloads.open(blob);
    }

    @Override
    public FilePreviewView preview(Blob blob, int page) throws IOException {
        var value = payloads.preview(blob, page);
        return new FilePreviewView(
                value.text(),
                value.truncated(),
                value.imageDataUrl(),
                value.page(),
                value.pageCount());
    }

    @Override
    public void finishDeletion(UUID id, long version) {
        deletion.finish(id, version);
    }
}
