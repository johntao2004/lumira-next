package com.lumira.platform.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.UUID;

/** Writes new objects to PostgreSQL and routes legacy local objects until they are migrated. */
public final class RoutedBlobStore implements BlobStore {
    private final FileRepository files;
    private final LocalBlobStore local;
    private final PostgresBlobStore postgres;

    public RoutedBlobStore(FileRepository files, LocalBlobStore local, PostgresBlobStore postgres) {
        this.files = files;
        this.local = local;
        this.postgres = postgres;
    }

    @Override public Staged stage(UUID key, InputStream source, long maximumBytes) throws IOException {
        return postgres.stage(key, source, maximumBytes);
    }

    @Override public InputStream open(UUID key) throws IOException {
        String backend = files.storageBackend(key).orElseThrow(() -> new NoSuchFileException(key.toString()));
        if ("postgres".equals(backend)) return postgres.open(key);
        if (!"local".equals(backend)) throw new IOException("unsupportedFileStorageBackend");
        try {
            return local.open(key);
        } catch (NoSuchFileException missingLocalFile) {
            // Migration may switch the metadata row and unlink the local path between these calls.
            if (files.storageBackend(key).filter("postgres"::equals).isPresent()) return postgres.open(key);
            throw missingLocalFile;
        }
    }

    @Override public void delete(UUID key) throws IOException {
        String backend = files.storageBackend(key).orElse(null);
        if (backend == null) {
            local.delete(key);
            postgres.discard(key);
            return;
        }
        if ("local".equals(backend)) {
            local.delete(key);
            postgres.discard(key);
        } else if ("postgres".equals(backend)) {
            postgres.delete(key);
        } else {
            throw new IOException("unsupportedFileStorageBackend");
        }
        if ("postgres".equals(backend) && files.legacyLocalCleanupPending(key)) {
            local.delete(key);
            files.finishLegacyLocalCleanup(key);
        }
    }
}
