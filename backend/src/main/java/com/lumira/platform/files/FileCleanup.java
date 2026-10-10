package com.lumira.platform.files;

import com.lumira.platform.common.AuditPort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fences expired uploads, then retires terminal deletions without holding locks during storage IO.
 */
@Component
public class FileCleanup {
    private final FileRepository files;
    private final AuditPort audit;
    private final TransactionTemplate transaction;
    private final FileRetentionPolicy retention;
    private final FileDeleteFinalizer deleteFinalizer;

    public FileCleanup(
            FileRepository files,
            BlobStore blobs,
            AuditPort audit,
            PlatformTransactionManager manager) {
        this(files, blobs, audit, manager, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FileCleanup(
            FileRepository files,
            BlobStore blobs,
            AuditPort audit,
            PlatformTransactionManager manager,
            FileRetentionPolicy retention) {
        this.files = files;
        this.audit = audit;
        this.transaction = new TransactionTemplate(manager);
        this.retention = retention;
        this.deleteFinalizer = new FileDeleteFinalizer(files, blobs, audit, manager);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void sweep() {
        transaction.executeWithoutResult(
                status -> {
                    for (var id : files.expireUploads())
                        audit.append("system:file-cleanup", "file.uploadExpired", id);
                });
        transaction.executeWithoutResult(
                status -> {
                    for (var id : files.expirePendingBrandingImages())
                        audit.append("system:file-cleanup", "file.orphanExpired", id);
                });
        transaction.executeWithoutResult(
                status -> {
                    for (var id : files.expireOrphanedFiles())
                        audit.append("system:file-cleanup", "file.orphanExpired", id);
                });
        if (retention != null) {
            var policy = retention.current();
            if (policy.mode() == FileRetentionPolicy.Mode.AUTOMATIC) {
                transaction.executeWithoutResult(
                        status -> {
                            for (var id : files.expireTrashed(policy.retentionDays()))
                                audit.append("system:file-cleanup", "file.retentionExpired", id);
                        });
            }
        }
        for (var entry : files.pendingCleanup()) {
            deleteFinalizer.finish(entry.id(), entry.version());
        }
    }
}
