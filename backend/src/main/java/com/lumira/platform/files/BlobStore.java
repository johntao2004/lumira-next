package com.lumira.platform.files;

import java.io.*;
import java.util.UUID;

/**
 * Opaque storage keys only. Authorization and original filenames belong to the metadata service.
 */
public interface BlobStore {
    record Stored(UUID key, long size, String sha256) {}

    /** Single-owner staging handle; closing discards temporary bytes, never published bytes. */
    interface Staged extends AutoCloseable {
        Stored metadata();

        void publish() throws IOException;

        @Override
        void close() throws IOException;
    }

    /** Does not close the caller's input stream; consumes at most maximumBytes + 1 bytes. */
    default Stored put(InputStream source, long maximumBytes) throws IOException {
        return put(UUID.randomUUID(), source, maximumBytes);
    }

    /** Publishes once under a pre-reserved metadata key; an existing object is never replaced. */
    default Stored put(UUID key, InputStream source, long maximumBytes) throws IOException {
        try (var staged = stage(key, source, maximumBytes)) {
            staged.publish();
            return staged.metadata();
        }
    }

    Staged stage(UUID key, InputStream source, long maximumBytes) throws IOException;

    InputStream open(UUID key) throws IOException;

    /** Removes published and temporary bytes. Caller must first fence out any publisher. */
    void delete(UUID key) throws IOException;

    /** True while asynchronous storage deletion still leaves bytes for this key. */
    default boolean deletionPending(UUID key) throws IOException {
        return false;
    }

    final class TooLarge extends IOException {
        public TooLarge() {
            super("fileTooLarge");
        }
    }
}
