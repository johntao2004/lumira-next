package com.lumira.platform.files;

import com.lumira.platform.common.AuditPort;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes one already-fenced file outside its row lock, then releases its metadata by version CAS.
 */
final class FileDeleteFinalizer {
    private static final Logger log = LoggerFactory.getLogger(FileDeleteFinalizer.class);

    private final FileRepository files;
    private final BlobStore blobs;
    private final AuditPort audit;
    private final TransactionTemplate transaction;

    FileDeleteFinalizer(
            FileRepository files,
            BlobStore blobs,
            AuditPort audit,
            PlatformTransactionManager manager) {
        this.files = files;
        this.blobs = blobs;
        this.audit = audit;
        this.transaction = new TransactionTemplate(manager);
    }

    /**
     * Attempts a single cleanup. Failures stay pending and charged; the scheduled worker retries
     * them later. The pending-state/version lookup prevents stale callers from deleting bytes.
     */
    void finish(UUID id, long version) {
        try {
            if (files.findDeleting(id, version).isEmpty()) return;

            // Storage IO happens after the state-changing transaction has committed and with no
            // owner or file row lock held. BlobStore.delete is idempotent for a fenced UUID.
            blobs.delete(id);
            if (blobs.deletionPending(id)) {
                transaction.executeWithoutResult(status -> files.deferCleanup(id, version));
                return;
            }
            boolean deleted =
                    Boolean.TRUE.equals(
                            transaction.execute(
                                    status -> {
                                        if (files.finishDelete(id, version)) {
                                            audit.append("system:file-cleanup", "file.cleanup", id);
                                            return true;
                                        }
                                        return false;
                                    }));
            if (!deleted)
                transaction.executeWithoutResult(status -> files.deferCleanup(id, version));
        } catch (IOException | RuntimeException failure) {
            try {
                transaction.executeWithoutResult(status -> files.deferCleanup(id, version));
            } catch (RuntimeException retryFailure) {
                log.warn(
                        "Could not defer file cleanup {} ({})",
                        id,
                        retryFailure.getClass().getSimpleName());
            }
            log.warn("File cleanup will retry {} ({})", id, failure.getClass().getSimpleName());
        }
    }
}
