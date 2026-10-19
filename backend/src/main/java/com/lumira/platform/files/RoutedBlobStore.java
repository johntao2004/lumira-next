/**
 * 新文件写入 PostgreSQL，旧文件按元数据路由至本地存储，并复核迁移切换竞态以免误报文件丢失。 Writes new files to PostgreSQL and routes
 * legacy files by metadata, rechecking migration races to avoid falsely reporting missing content.
 */
package com.lumira.platform.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.UUID;

/**
 * 新对象写入 PostgreSQL；旧本地对象在迁移完成前继续路由至原存储。 Writes new objects to PostgreSQL and routes legacy local
 * objects until they are migrated.
 */
public final class RoutedBlobStore implements BlobStore {
    private final FileRepository files;
    private final LocalBlobStore local;
    private final PostgresBlobStore postgres;

    public RoutedBlobStore(FileRepository files, LocalBlobStore local, PostgresBlobStore postgres) {
        this.files = files;
        this.local = local;
        this.postgres = postgres;
    }

    @Override
    public Staged stage(UUID key, InputStream source, long maximumBytes) throws IOException {
        return postgres.stage(key, source, maximumBytes);
    }

    @Override
    public InputStream open(UUID key) throws IOException {
        String backend =
                files.storageBackend(key)
                        .orElseThrow(() -> new NoSuchFileException(key.toString()));
        if ("postgres".equals(backend)) return postgres.open(key);
        if (!"local".equals(backend)) throw new IOException("unsupportedFileStorageBackend");
        try {
            return local.open(key);
        } catch (NoSuchFileException missingLocalFile) {
            // 迁移可能在这两次调用之间切换元数据行并移除本地路径。
            // Migration may switch the metadata row and unlink the local path between these calls.
            if (files.storageBackend(key).filter("postgres"::equals).isPresent())
                return postgres.open(key);
            throw missingLocalFile;
        }
    }

    @Override
    public void delete(UUID key) throws IOException {
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

    @Override
    public boolean deletionPending(UUID key) throws IOException {
        return files.legacyLocalCleanupPending(key) || postgres.deletionPending(key);
    }
}
