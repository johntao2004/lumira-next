package com.lumira.platform.files;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.plugins.personalfiles.PersonalFileService;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;

/** Composes the real optional workflow with the real kernel adapters for existing regressions. */
final class TestFileWorkflow extends FileService {
    private final PersonalFileService personal;
    private final com.lumira.plugins.directory.users.UserFileQuotaService quotaViews;

    TestFileWorkflow(
            FileRepository files,
            AccountOwnerPort accounts,
            BlobStore blobs,
            PlatformTransactionManager manager,
            AuditPort audit,
            FileQuota quota) {
        this(files, accounts, blobs, manager, audit, quota, null);
    }

    TestFileWorkflow(
            FileRepository files,
            AccountOwnerPort accounts,
            BlobStore blobs,
            PlatformTransactionManager manager,
            AuditPort audit,
            FileQuota quota,
            AdministrationPort guard) {
        this(files, accounts, blobs, manager, audit, quota, guard, defaults(quota));
    }

    TestFileWorkflow(
            FileRepository files,
            AccountOwnerPort accounts,
            BlobStore blobs,
            PlatformTransactionManager manager,
            AuditPort audit,
            FileQuota quota,
            AdministrationPort guard,
            FileQuotaPolicyPort policies) {
        this(files, accounts, blobs, manager, audit, quota, guard, policies, null);
    }

    TestFileWorkflow(
            FileRepository files,
            AccountOwnerPort accounts,
            BlobStore blobs,
            PlatformTransactionManager manager,
            AuditPort audit,
            FileQuota quota,
            AdministrationPort guard,
            FileQuotaPolicyPort policies,
            AuthorizationContextBoundaryPort authorization) {
        this(
                files,
                accounts,
                blobs,
                manager,
                audit,
                quota,
                guard,
                policies,
                authorization,
                () -> new FileQuotaPolicyPort.Limits(quota.maximumBytes(), quota.maximumFiles()));
    }

    TestFileWorkflow(
            FileRepository files,
            AccountOwnerPort accounts,
            BlobStore blobs,
            PlatformTransactionManager manager,
            AuditPort audit,
            FileQuota quota,
            AdministrationPort guard,
            FileQuotaPolicyPort policies,
            AuthorizationContextBoundaryPort authorization,
            FileQuotaDefaultsPort quotaDefaults) {
        this(
                files,
                accounts,
                blobs,
                manager,
                audit,
                quota,
                guard,
                policies,
                authorization,
                quotaDefaults,
                null);
    }

    TestFileWorkflow(
            FileRepository files,
            AccountOwnerPort accounts,
            BlobStore blobs,
            PlatformTransactionManager manager,
            AuditPort audit,
            FileQuota quota,
            AdministrationPort guard,
            FileQuotaPolicyPort policies,
            AuthorizationContextBoundaryPort authorization,
            FileQuotaDefaultsPort quotaDefaults,
            RoleAssignmentPreviewPort rolePreview) {
        super(files, accounts, blobs, manager, audit, policies, authorization);
        personal =
                new PersonalFileService(
                        new AccountFileStorageAdapter(files, this),
                        new FileManagementStorageAdapter(files, blobs, audit, manager),
                        accounts,
                        policies,
                        audit,
                        authorization,
                        manager);
        quotaViews =
                new com.lumira.plugins.directory.users.UserFileQuotaService(
                        new AccountFileStorageAdapter(files, this),
                        policies,
                        quotaDefaults,
                        guard,
                        rolePreview);
    }

    PersonalFileService.Usage usage(String actor) {
        return personal.usage(actor);
    }

    void delete(String actor, UUID id, long version) {
        personal.delete(actor, id, version);
    }

    void restore(String actor, UUID id, long version) {
        personal.restore(actor, id, version);
    }

    void purge(String actor, UUID id, long version) {
        personal.purge(actor, id, version);
    }

    com.lumira.plugins.directory.users.UserFileQuotaService.UserQuotaView userQuota(
            String actor, UUID userId) {
        return quotaViews.userQuota(actor, userId);
    }

    com.lumira.plugins.directory.users.UserFileQuotaService.UserQuotaPreview previewUserQuota(
            String actor, UUID userId, java.util.Set<UUID> roles) {
        return quotaViews.previewUserQuota(actor, userId, roles);
    }

    private static FileQuotaPolicyPort defaults(FileQuota quota) {
        return new FileQuotaPolicyPort() {
            @Override
            public Limits forUser(UUID user) {
                return new Limits(quota.maximumBytes(), quota.maximumFiles());
            }

            @Override
            public Limits forRoles(java.util.Collection<UUID> roles) {
                return new Limits(quota.maximumBytes(), quota.maximumFiles());
            }
        };
    }
}
