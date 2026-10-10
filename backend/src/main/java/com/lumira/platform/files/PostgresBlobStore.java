package com.lumira.platform.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Stores file bytes in PostgreSQL without buffering a whole upload in application memory. */
public final class PostgresBlobStore implements BlobStore {
    private static final int CHUNK_BYTES = 64 * 1024;
    private static final int BATCH_CHUNKS = 16;

    private final JdbcTemplate jdbc;
    private final FileRepository files;
    private final TransactionTemplate transaction;

    public PostgresBlobStore(
            JdbcTemplate jdbc,
            FileRepository files,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.files = files;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public Staged stage(UUID key, InputStream source, long maximumBytes) throws IOException {
        validateLimit(maximumBytes);
        try {
            Stored stored =
                    writeChunks(key, source, maximumBytes, "postgres", "UPLOADING", 0, true);
            long expectedChunks = chunkCount(stored.size());
            return new Staged() {
                private boolean closed;
                private boolean published;

                @Override
                public Stored metadata() {
                    return stored;
                }

                @Override
                public synchronized void publish() throws IOException {
                    if (closed || published)
                        throw new IllegalStateException("Staged upload already finalized");
                    if (!files.lockBlobWrite(key, "postgres", "UPLOADING", 0, true)
                            || !matches(stats(key), expectedChunks, stored.size())) {
                        throw new IOException("fileUploadNotReady");
                    }
                    published = true;
                }

                @Override
                public synchronized void close() throws IOException {
                    if (closed) return;
                    if (!published) discardChunks(key);
                    closed = true;
                }
            };
        } catch (IOException | RuntimeException | Error failure) {
            discardAfterFailedStage(key, failure);
            throw failure;
        }
    }

    /**
     * Copies one existing local file, validates its stored size and digest, then switches its DB
     * row.
     */
    public Stored migrateLocal(FileRepository.Entry entry, InputStream source) throws IOException {
        if (entry == null
                || !"local".equals(entry.storageBackend())
                || !"READY".equals(entry.state())
                || entry.sizeBytes() == null
                || entry.sha256() == null) {
            throw new IOException("legacyFileNotReady");
        }
        UUID key = entry.id();
        clearChunks(key);
        try {
            Stored stored =
                    writeChunks(
                            key,
                            source,
                            entry.sizeBytes(),
                            "local",
                            "READY",
                            entry.version(),
                            false);
            if (stored.size() != entry.sizeBytes() || !stored.sha256().equals(entry.sha256())) {
                clearChunks(key);
                throw new IOException("legacyFileIntegrityCheckFailed");
            }
            boolean migrated =
                    Boolean.TRUE.equals(
                            transaction.execute(
                                    status -> {
                                        if (!files.lockBlobWrite(
                                                key, "local", "READY", entry.version(), false))
                                            return false;
                                        if (!matches(
                                                stats(key),
                                                chunkCount(stored.size()),
                                                stored.size())) return false;
                                        return files.markLegacyMigrated(
                                                key,
                                                entry.version(),
                                                stored.size(),
                                                stored.sha256());
                                    }));
            if (!migrated) {
                clearChunks(key);
                throw new IOException("legacyFileChangedDuringMigration");
            }
            return stored;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                if (files.storageBackend(key).filter("local"::equals).isPresent()) clearChunks(key);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private Stored writeChunks(
            UUID key,
            InputStream source,
            long maximumBytes,
            String backend,
            String state,
            long version,
            boolean requireUnexpiredUpload)
            throws IOException {
        if (source == null) throw new NullPointerException("source");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }

        long size = 0;
        int nextIndex = 0;
        byte[] buffer = new byte[CHUNK_BYTES];
        List<Chunk> batch = new ArrayList<>(BATCH_CHUNKS);
        while (true) {
            int target = (int) Math.min(CHUNK_BYTES, maximumBytes - size);
            if (target == 0) {
                if (source.read() >= 0) throw new BlobStore.TooLarge();
                break;
            }

            // InputStream.read(byte[], ...) may return fewer bytes than requested. Fill each
            // database chunk before persisting it so chunk indexes and size stay canonical.
            int count = 0;
            while (count < target) {
                int read = source.read(buffer, count, target - count);
                if (read < 0) break;
                if (read == 0) {
                    int single = source.read();
                    if (single < 0) break;
                    buffer[count++] = (byte) single;
                } else {
                    count += read;
                }
            }
            if (count == 0) break;

            byte[] payload = Arrays.copyOf(buffer, count);
            digest.update(payload);
            batch.add(new Chunk(nextIndex++, payload));
            size += count;
            if (batch.size() == BATCH_CHUNKS) {
                persistBatch(key, batch, backend, state, version, requireUnexpiredUpload);
                batch.clear();
            }
            if (count < target) break;
        }
        if (!batch.isEmpty())
            persistBatch(key, batch, backend, state, version, requireUnexpiredUpload);
        return new Stored(key, size, HexFormat.of().formatHex(digest.digest()));
    }

    private void persistBatch(
            UUID key,
            List<Chunk> chunks,
            String backend,
            String state,
            long version,
            boolean requireUnexpiredUpload) {
        List<Chunk> batch = List.copyOf(chunks);
        transaction.executeWithoutResult(
                status -> {
                    if (!files.lockBlobWrite(
                            key, backend, state, version, requireUnexpiredUpload)) {
                        throw new IllegalStateException("fileStorageFenceLost");
                    }
                    jdbc.batchUpdate(
                            "INSERT INTO platform_file_blob_chunk(file_id,chunk_index,payload) VALUES(?,?,?)",
                            new BatchPreparedStatementSetter() {
                                @Override
                                public void setValues(PreparedStatement statement, int index)
                                        throws SQLException {
                                    Chunk chunk = batch.get(index);
                                    statement.setObject(1, key);
                                    statement.setInt(2, chunk.index());
                                    statement.setBytes(3, chunk.payload());
                                }

                                @Override
                                public int getBatchSize() {
                                    return batch.size();
                                }
                            });
                });
    }

    private record Chunk(int index, byte[] payload) {}

    private record ChunkStats(long count, long bytes, Integer minimum, Integer maximum) {}

    private ChunkStats stats(UUID key) {
        return jdbc.queryForObject(
                """
            SELECT count(*) AS chunks, coalesce(sum(octet_length(payload)), 0)::bigint AS bytes,
                   min(chunk_index) AS minimum, max(chunk_index) AS maximum
              FROM platform_file_blob_chunk WHERE file_id=?
            """,
                (rs, row) ->
                        new ChunkStats(
                                rs.getLong("chunks"),
                                rs.getLong("bytes"),
                                (Integer) rs.getObject("minimum"),
                                (Integer) rs.getObject("maximum")),
                key);
    }

    private static boolean matches(ChunkStats stats, long expectedChunks, long expectedBytes) {
        if (stats.count() != expectedChunks || stats.bytes() != expectedBytes) return false;
        return expectedChunks == 0
                ? stats.minimum() == null && stats.maximum() == null
                : Integer.valueOf(0).equals(stats.minimum())
                        && Integer.valueOf((int) (expectedChunks - 1)).equals(stats.maximum());
    }

    private static long chunkCount(long size) {
        return size == 0 ? 0 : (size + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    private void clearChunks(UUID key) {
        jdbc.update("DELETE FROM platform_file_blob_chunk WHERE file_id=?", key);
    }

    public void discard(UUID key) {
        clearChunks(key);
    }

    private void discardChunks(UUID key) throws IOException {
        try {
            clearChunks(key);
        } catch (RuntimeException failure) {
            throw new IOException("fileStagingCleanupFailed", failure);
        }
    }

    private void discardAfterFailedStage(UUID key, Throwable original) {
        try {
            clearChunks(key);
        } catch (RuntimeException cleanup) {
            original.addSuppressed(cleanup);
        }
    }

    private static void validateLimit(long maximumBytes) {
        if (maximumBytes < 0 || maximumBytes == Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid upload limit");
    }

    @Override
    public InputStream open(UUID key) throws IOException {
        List<FileMetadata> files =
                jdbc.query(
                        """
            SELECT size_bytes,sha256 FROM platform_file
             WHERE id=? AND storage_backend='postgres'
            """,
                        (rs, row) ->
                                new FileMetadata(rs.getLong("size_bytes"), rs.getString("sha256")),
                        key);
        if (files.isEmpty()) throw new NoSuchFileException(key.toString());
        FileMetadata metadata = files.get(0);
        if (metadata.size() < 0 || metadata.digest() == null)
            throw new IOException("fileIntegrityCheckFailed");
        if (metadata.size() == 0) {
            if (!loadChunks(key, 0).isEmpty() || !emptyDigest().equals(metadata.digest())) {
                throw new IOException("fileIntegrityCheckFailed");
            }
            return InputStream.nullInputStream();
        }
        return new ChunkInputStream(this, key, metadata.size(), metadata.digest());
    }

    private List<Chunk> loadChunks(UUID key, int firstIndex) {
        return jdbc.query(
                """
            SELECT chunk_index,payload FROM platform_file_blob_chunk
             WHERE file_id=? AND chunk_index>=?
             ORDER BY chunk_index LIMIT ?
            """,
                (rs, row) -> new Chunk(rs.getInt("chunk_index"), rs.getBytes("payload")),
                key,
                firstIndex,
                BATCH_CHUNKS);
    }

    private static String emptyDigest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void delete(UUID key) {
        jdbc.update(
                """
            UPDATE platform_file_blob_chunk c
               SET retire_after = CASE
                   WHEN EXISTS (
                       SELECT 1 FROM platform_file f
                        WHERE f.id=c.file_id AND f.storage_backend='postgres' AND f.size_bytes IS NOT NULL
                   ) THEN coalesce(c.retire_after,clock_timestamp()+interval '24 hours')
                   ELSE clock_timestamp()
               END
             WHERE c.file_id=?
               AND (c.retire_after IS NULL OR NOT EXISTS (
                   SELECT 1 FROM platform_file f
                    WHERE f.id=c.file_id AND f.storage_backend='postgres' AND f.size_bytes IS NOT NULL
               ))
            """,
                key);
        // Incomplete uploads and orphan chunks have no published metadata or downloadable object.
        jdbc.update(
                "DELETE FROM platform_file_blob_chunk c WHERE c.file_id=? AND c.retire_after<=clock_timestamp()",
                key);
    }

    @Override
    public boolean deletionPending(UUID key) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM platform_file_blob_chunk WHERE file_id=?)",
                        Boolean.class,
                        key));
    }

    // At most 16,000 chunks per run. Stop starting batches after five seconds; an individual
    // database statement keeps its normal timeout.
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void purgeRetiredChunks() {
        long started = System.nanoTime();
        long budget = java.time.Duration.ofSeconds(5).toNanos();
        for (int batch = 0; batch < 16 && System.nanoTime() - started < budget; batch++) {
            int removed =
                    jdbc.update(
                            """
            WITH retired AS (
                SELECT file_id,chunk_index FROM platform_file_blob_chunk
                 WHERE retire_after<=clock_timestamp()
                 ORDER BY retire_after,file_id,chunk_index
                 LIMIT 1000 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM platform_file_blob_chunk c USING retired r
             WHERE c.file_id=r.file_id AND c.chunk_index=r.chunk_index
            """);
            if (removed < 1000) break;
        }
    }

    private static final class ChunkInputStream extends InputStream {
        private final PostgresBlobStore store;
        private final UUID key;
        private final long expectedSize;
        private final String expectedDigest;
        private final MessageDigest digest;
        private List<Chunk> batch = List.of();
        private byte[] current;
        private int currentOffset;
        private int batchOffset;
        private int nextChunkIndex;
        private long bytesRead;
        private boolean closed;
        private boolean eof;

        private ChunkInputStream(
                PostgresBlobStore store, UUID key, long expectedSize, String expectedDigest) {
            this.store = store;
            this.key = key;
            this.expectedSize = expectedSize;
            this.expectedDigest = expectedDigest;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (eof) return -1;
            if (closed) throw new IOException("Stream closed");
            if (buffer == null) throw new NullPointerException("buffer");
            if (offset < 0 || length < 0 || length > buffer.length - offset)
                throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            try {
                while (true) {
                    if (current != null && currentOffset < current.length) {
                        int count = Math.min(length, current.length - currentOffset);
                        System.arraycopy(current, currentOffset, buffer, offset, count);
                        digest.update(buffer, offset, count);
                        currentOffset += count;
                        bytesRead += count;
                        return count;
                    }
                    current = null;
                    if (batchOffset >= batch.size()) {
                        batch = store.loadChunks(key, nextChunkIndex);
                        batchOffset = 0;
                    }
                    if (batch.isEmpty()) {
                        verifyEnd();
                        return -1;
                    }
                    Chunk chunk = batch.get(batchOffset++);
                    if (chunk.index() != nextChunkIndex++
                            || chunk.payload() == null
                            || chunk.payload().length == 0
                            || chunk.payload().length > CHUNK_BYTES) {
                        throw new IOException("fileChunkSequenceInvalid");
                    }
                    current = chunk.payload();
                    currentOffset = 0;
                }
            } catch (RuntimeException failure) {
                throw fail(new IOException("fileDatabaseReadFailed", failure));
            } catch (IOException failure) {
                throw fail(failure);
            }
        }

        private void verifyEnd() throws IOException {
            String actualDigest = HexFormat.of().formatHex(digest.digest());
            if (bytesRead != expectedSize || !actualDigest.equals(expectedDigest)) {
                throw new IOException("fileIntegrityCheckFailed");
            }
            eof = true;
            current = null;
            batch = List.of();
        }

        private IOException fail(IOException failure) {
            close();
            return failure;
        }

        @Override
        public void close() {
            closed = true;
            current = null;
            batch = List.of();
        }
    }

    private record FileMetadata(long size, String digest) {}
}
