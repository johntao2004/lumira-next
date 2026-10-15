package com.lumira.platform.files;

import com.lumira.platform.common.AuthorizationContextBoundaryPort;
import com.lumira.platform.common.FileContentPort;
import com.lumira.platform.common.FileQuotaDefaultsPort;
import com.lumira.platform.common.FileQuotaPolicyPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.identity.api.AccountOwnerPort;
import java.io.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FileService implements FileContentPort {
    public static final long MAXIMUM_BYTES = 20L * 1024 * 1024;
    private final FileRepository files;
    private final AccountOwnerPort accounts;
    private final BlobStore blobs;
    private final com.lumira.platform.common.AuditPort audit;
    private final com.lumira.platform.common.AdministrationPort guard;
    private final TransactionTemplate transaction;
    private final FileDeleteFinalizer deleteFinalizer;
    private final FileQuotaPolicyPort quotaPolicies;
    private final FileQuotaDefaultsPort quotaDefaults;
    private final AuthorizationContextBoundaryPort authorizationBoundary;

    public FileService(
            FileRepository files,
            AccountOwnerPort accounts,
            BlobStore blobs,
            PlatformTransactionManager manager,
            com.lumira.platform.common.AuditPort audit,
            FileQuota quota) {
        this(files, accounts, blobs, manager, audit, quota, null, defaultQuotaPolicies(quota));
    }

    public FileService(
            FileRepository files,
            AccountOwnerPort accounts,
            BlobStore blobs,
            PlatformTransactionManager manager,
            com.lumira.platform.common.AuditPort audit,
            FileQuota quota,
            com.lumira.platform.common.AdministrationPort guard) {
        this(files, accounts, blobs, manager, audit, quota, guard, defaultQuotaPolicies(quota));
    }

    public FileService(
            FileRepository files,
            AccountOwnerPort accounts,
            BlobStore blobs,
            PlatformTransactionManager manager,
            com.lumira.platform.common.AuditPort audit,
            FileQuota quota,
            com.lumira.platform.common.AdministrationPort guard,
            FileQuotaPolicyPort quotaPolicies) {
        this(files, accounts, blobs, manager, audit, quota, guard, quotaPolicies, null);
    }

    public FileService(
            FileRepository files,
            AccountOwnerPort accounts,
            BlobStore blobs,
            PlatformTransactionManager manager,
            com.lumira.platform.common.AuditPort audit,
            FileQuota quota,
            com.lumira.platform.common.AdministrationPort guard,
            FileQuotaPolicyPort quotaPolicies,
            AuthorizationContextBoundaryPort authorizationBoundary) {
        this(
                files,
                accounts,
                blobs,
                manager,
                audit,
                quota,
                guard,
                quotaPolicies,
                authorizationBoundary,
                () -> new FileQuotaPolicyPort.Limits(quota.maximumBytes(), quota.maximumFiles()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FileService(
            FileRepository files,
            AccountOwnerPort accounts,
            BlobStore blobs,
            PlatformTransactionManager manager,
            com.lumira.platform.common.AuditPort audit,
            FileQuota quota,
            com.lumira.platform.common.AdministrationPort guard,
            FileQuotaPolicyPort quotaPolicies,
            AuthorizationContextBoundaryPort authorizationBoundary,
            FileQuotaDefaultsPort quotaDefaults) {
        this.files = files;
        this.accounts = accounts;
        this.blobs = blobs;
        this.transaction = new TransactionTemplate(manager);
        this.deleteFinalizer = new FileDeleteFinalizer(files, blobs, audit, manager);
        this.audit = audit;
        this.guard = guard;
        this.quotaPolicies = quotaPolicies;
        this.authorizationBoundary = authorizationBoundary;
        this.quotaDefaults = quotaDefaults;
    }

    private static FileQuotaPolicyPort defaultQuotaPolicies(FileQuota quota) {
        return new FileQuotaPolicyPort() {
            @Override
            public Limits forUser(UUID userId) {
                return new Limits(quota.maximumBytes(), quota.maximumFiles());
            }

            @Override
            public Limits forRoles(java.util.Collection<UUID> roleIds) {
                return new Limits(quota.maximumBytes(), quota.maximumFiles());
            }
        };
    }

    public static String filename(String value) {
        if (value == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFilename");
        String name = value.strip();
        if (name.isEmpty()
                || name.length() > 255
                || name.equals(".")
                || name.equals("..")
                || name.codePoints()
                        .anyMatch(c -> Character.isISOControl(c) || c == '/' || c == '\\'))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFilename");
        return name;
    }

    public FileRepository.Entry upload(String actor, String filename, InputStream source)
            throws IOException {
        return upload(actor, filename, source, -1);
    }

    public FileRepository.Entry upload(
            String actor, String filename, InputStream source, long contentLength)
            throws IOException {
        return upload(actor, filename, source, contentLength, false, () -> {}, entry -> {});
    }

    /**
     * Only normalized pixels may enter the inline image path. Completion participates in
     * publication's transaction.
     */
    FileRepository.Entry uploadImage(
            String actor,
            ImageNormalizer.Normalized image,
            Runnable beforePublication,
            java.util.function.Consumer<FileRepository.Entry> completion)
            throws IOException {
        byte[] bytes = image.png();
        return upload(
                actor,
                "avatar.png",
                new ByteArrayInputStream(bytes),
                bytes.length,
                true,
                beforePublication,
                completion);
    }

    /** Store a normalized image while retaining the caller's safe display name. */
    public FileRepository.Entry uploadNormalizedImage(
            String actor, String filename, ImageNormalizer.Normalized image) throws IOException {
        byte[] bytes = image.png();
        return upload(
                actor,
                filename,
                new ByteArrayInputStream(bytes),
                bytes.length,
                true,
                () -> {},
                entry -> {});
    }

    /** Generic inline images share the same normalization, timeout and quota path as avatars. */
    public FileRepository.Entry uploadInlineImage(
            String actor, String filename, InputStream source, long contentLength)
            throws IOException {
        if (contentLength > FileContentPort.MAXIMUM_BRANDING_IMAGE_BYTES)
            throw new ImageNormalizer.InvalidImage("imageTooLarge");
        var normalized =
                ImageNormalizer.normalize(
                        new com.lumira.platform.common.DeadlineInputStream(
                                source, java.time.Duration.ofMinutes(15)));
        return uploadNormalizedImage(actor, filename, normalized);
    }

    @Override
    public FileContentPort.Image uploadInlineImageForContent(
            String actor, String filename, InputStream source, long contentLength)
            throws IOException {
        try {
            var entry = uploadInlineImage(actor, filename, source, contentLength);
            return new FileContentPort.Image(entry.id(), entry.sizeBytes());
        } catch (ImageNormalizer.InvalidImage error) {
            throw new FileContentPort.InvalidImage(error.getMessage(), error);
        }
    }

    @Override
    public FileContentPort.Image uploadBrandingImage(
            String actor,
            FileContentPort.BrandingImageSlot slot,
            InputStream source,
            long contentLength)
            throws IOException {
        if (slot == null) throw new ImageNormalizer.InvalidImage("invalidImage");
        if (contentLength > ImageNormalizer.MAXIMUM_BYTES)
            throw new ImageNormalizer.InvalidImage("imageTooLarge");
        try {
            var normalized =
                    ImageNormalizer.normalize(
                            new com.lumira.platform.common.DeadlineInputStream(
                                    source, java.time.Duration.ofMinutes(15)),
                            slot.outputEdge());
            byte[] bytes = normalized.png();
            var entry =
                    upload(
                            actor,
                            "branding-" + slot.filename() + ".png",
                            new ByteArrayInputStream(bytes),
                            bytes.length,
                            true,
                            () -> {},
                            uploaded -> files.reserveBrandingImage(uploaded.id()));
            return new FileContentPort.Image(entry.id(), entry.sizeBytes());
        } catch (ImageNormalizer.InvalidImage error) {
            throw new FileContentPort.InvalidImage(error.getMessage(), error);
        }
    }

    @Override
    public FileContentPort.Image findReadyImageForContent(String actor, UUID id) {
        var entry = findReadyImage(actor, id);
        return new FileContentPort.Image(entry.id(), entry.sizeBytes());
    }

    @Override
    public FileContentPort.Image findManagedFeedbackImageForContent(UUID id) {
        var entry = findManagedFeedbackImage(id);
        return new FileContentPort.Image(entry.id(), entry.sizeBytes());
    }

    @Override
    public FileContentPort.Image findReadyBrandingImage(UUID id) {
        var entry =
                files.findReadyBrandingImage(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "fileNotFound"));
        return new FileContentPort.Image(entry.id(), entry.sizeBytes());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void retainBrandingImage(UUID id) {
        if (!files.retainBrandingImage(id))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidBranding");
    }

    @Override
    public InputStream openContentImage(FileContentPort.Image image) throws IOException {
        if (image == null || image.id() == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound");
        return blobs.open(image.id());
    }

    private FileRepository.Entry upload(
            String actor,
            String filename,
            InputStream source,
            long contentLength,
            boolean image,
            Runnable beforePublication,
            java.util.function.Consumer<FileRepository.Entry> completion)
            throws IOException {
        String name = filename(filename);
        UUID id = UUID.randomUUID();
        if (contentLength > MAXIMUM_BYTES) throw new BlobStore.TooLarge();
        long reservation = contentLength < 0 ? MAXIMUM_BYTES : contentLength;
        var boundedSource =
                new com.lumira.platform.common.DeadlineInputStream(
                        source, java.time.Duration.ofMinutes(15));
        UUID owner =
                transaction.execute(
                        status -> {
                            validateAuthorizationBoundary();
                            UUID ownerId = accounts.id(actor, true);
                            // Serialize reservations with quota changes on the same user row. This
                            // makes
                            // a quota reduction and a concurrent upload observe one another
                            // atomically.
                            files.quotaOwner(ownerId, true);
                            var usage = files.usage(ownerId);
                            effectiveQuota(ownerId)
                                    .requireCapacity(
                                            usage.chargedBytes(), usage.fileCount(), reservation);
                            files.reserve(id, ownerId, "postgres", name, reservation);
                            return ownerId;
                        });
        try (var staged = blobs.stage(id, boundedSource, reservation)) {
            var stored = staged.metadata();
            return transaction.execute(
                    status -> {
                        validateAuthorizationBoundary();
                        beforePublication.run();
                        // Keep account -> file lock order, including callbacks which add account
                        // references.
                        accounts.id(actor, true);
                        if (!files.lockUpload(id, owner))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "fileStateConflict");
                        try {
                            staged.publish();
                        } catch (IOException error) {
                            throw new UncheckedIOException(error);
                        }
                        if (!files.complete(id, owner, 0, stored.size(), stored.sha256()))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "fileStateConflict");
                        if (image) files.markNormalizedImage(id);
                        var entry = files.findOwned(id, owner).orElseThrow();
                        completion.accept(entry);
                        audit.append(actor, "file.upload", id);
                        return entry;
                    });
        } catch (IOException | RuntimeException failure) {
            // A commit may have succeeded even if its acknowledgement failed. Never delete READY
            // bytes.
            try {
                if (Boolean.TRUE.equals(transaction.execute(status -> files.abandonUpload(id)))) {
                    blobs.delete(id);
                    if (blobs.deletionPending(id))
                        transaction.executeWithoutResult(status -> files.deferCleanup(id, 1));
                    else transaction.executeWithoutResult(status -> files.finishDelete(id, 1));
                }
            } catch (IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof UncheckedIOException error) throw error.getCause();
            throw failure;
        }
    }

    public FileRepository.Entry find(String actor, UUID id) {
        return files.findOwned(id, accounts.id(actor, false))
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound"));
    }

    public FileRepository.Entry findReadyImage(String actor, UUID id) {
        return files.findReadyImage(id, accounts.id(actor, false))
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound"));
    }

    public FileRepository.Entry findManagedFeedbackImage(UUID id) {
        return files.findManagedFeedbackImage(id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<FileRepository.Entry> list(String actor, PageRequest page) {
        return files.listOwned(accounts.id(actor, false), page);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<FileRepository.ManagedEntry> listManaged(String actor, PageRequest page) {
        guard.requirePageGlobal(actor, PlatformPageId.FILES_ALL);
        return files.listManaged(page);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<FileRepository.Entry> listTrash(String actor, PageRequest page) {
        return files.listTrashOwned(accounts.id(actor, false), page);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<FileRepository.ManagedEntry> listManagedTrash(
            String actor, PageRequest page) {
        guard.requirePageGlobal(actor, PlatformPageId.FILES_ALL);
        return files.listManagedTrash(page);
    }

    public FileRepository.ManagedEntry findManaged(String actor, UUID id) {
        guard.requireOperationGlobal(actor, PlatformOperation.FILE_DOWNLOAD);
        return managedEntry(id);
    }

    private FileRepository.ManagedEntry managedEntry(UUID id) {
        return files.findManaged(id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound"));
    }

    public InputStream openManaged(String actor, UUID id) throws IOException {
        guard.requireOperationGlobal(actor, PlatformOperation.FILE_DOWNLOAD);
        var entry = managedEntry(id);
        return open(
                new FileRepository.Entry(
                        entry.id(),
                        entry.ownerId(),
                        entry.storageBackend(),
                        entry.originalName(),
                        entry.state(),
                        entry.sizeBytes(),
                        entry.sha256(),
                        entry.version(),
                        entry.createdAt(),
                        entry.updatedAt(),
                        entry.trashedAt()));
    }

    public void deleteManaged(String actor, UUID id, long version) {
        guard.requireOperationGlobal(actor, PlatformOperation.FILE_DELETE);
        transaction.executeWithoutResult(
                status -> {
                    validateAuthorizationBoundary();
                    var entry =
                            files.findManaged(id)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND, "fileNotFound"));
                    if (entry.version() != version)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
                    if (!files.requestManagedTrash(id, version))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
                    audit.append(actor, "file.trash", id);
                });
    }

    public void restoreManaged(String actor, UUID id, long version) {
        requireManagedWrite(actor, PlatformOperation.FILE_RESTORE);
        transaction.executeWithoutResult(
                status -> {
                    validateAuthorizationBoundary();
                    var entry =
                            files.findManaged(id)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND, "fileNotFound"));
                    if (entry.version() != version || !files.restoreManaged(id, version))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
                    audit.append(actor, "file.restore", id);
                });
    }

    public void purgeManaged(String actor, UUID id, long version) {
        requireManagedWrite(actor, PlatformOperation.FILE_PURGE);
        transaction.executeWithoutResult(
                status -> {
                    validateAuthorizationBoundary();
                    var entry =
                            files.findManaged(id)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND, "fileNotFound"));
                    if (entry.version() != version || !files.purgeManaged(id, version))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
                    audit.append(actor, "file.purgeRequested", id);
                });
        deleteFinalizer.finish(id, version + 1);
    }

    private void requireManagedWrite(String actor, PlatformOperation operation) {
        guard.requireOperationGlobal(actor, operation);
    }

    public record Usage(
            long chargedBytes,
            long fileCount,
            long maximumBytes,
            long maximumFiles,
            long pendingDeletingCount,
            long pendingDeletingBytes) {
        public Usage(long chargedBytes, long fileCount, long maximumBytes, long maximumFiles) {
            this(chargedBytes, fileCount, maximumBytes, maximumFiles, 0, 0);
        }
    }

    public record UserQuotaView(
            UUID userId,
            String username,
            String displayName,
            long chargedBytes,
            long fileCount,
            long maximumBytes,
            long maximumFiles,
            long defaultMaximumBytes,
            long defaultMaximumFiles,
            long version) {}

    public record UserQuotaPreview(
            long currentMaximumBytes,
            long currentMaximumFiles,
            long proposedMaximumBytes,
            long proposedMaximumFiles,
            long chargedBytes,
            long fileCount,
            long defaultMaximumBytes,
            long defaultMaximumFiles,
            long version) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Usage usage(String actor) {
        UUID owner = accounts.id(actor, false);
        var used = files.usage(owner);
        var effective = effectiveQuota(owner);
        return new Usage(
                used.chargedBytes(),
                used.fileCount(),
                effective.maximumBytes(),
                effective.maximumFiles(),
                used.pendingDeletingCount(),
                used.pendingDeletingBytes());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserQuotaView userQuota(String actor, UUID userId) {
        guard.requirePage(actor, PlatformPageId.USERS);
        guard.requireUser(actor, userId, PlatformPageId.USERS.accessPermission());
        var owner = files.quotaOwner(userId, false);
        var used = files.usage(userId);
        var effective = effectiveQuota(userId);
        return quotaView(owner, used, effective);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserQuotaPreview previewUserQuota(
            String actor, UUID userId, java.util.Set<UUID> proposedRoleIds) {
        guard.requireOperationUser(actor, userId, PlatformOperation.USER_ASSIGN_ROLES);
        var owner = files.quotaOwner(userId, false);
        var used = files.usage(userId);
        var current = effectiveQuota(userId);
        var proposedLimits = quotaPolicies.forRoles(proposedRoleIds);
        var defaults = quotaDefaults.current();
        return new UserQuotaPreview(
                current.maximumBytes(),
                current.maximumFiles(),
                proposedLimits.maximumBytes(),
                proposedLimits.maximumFiles(),
                used.chargedBytes(),
                used.fileCount(),
                defaults.maximumBytes(),
                defaults.maximumFiles(),
                owner.version());
    }

    private FileQuota effectiveQuota(UUID userId) {
        var limits = quotaPolicies.forUser(userId);
        return new FileQuota(limits.maximumBytes(), limits.maximumFiles());
    }

    private UserQuotaView quotaView(
            FileRepository.QuotaOwner owner, FileRepository.Usage used, FileQuota effective) {
        var defaults = quotaDefaults.current();
        return new UserQuotaView(
                owner.userId(),
                owner.username(),
                owner.displayName(),
                used.chargedBytes(),
                used.fileCount(),
                effective.maximumBytes(),
                effective.maximumFiles(),
                defaults.maximumBytes(),
                defaults.maximumFiles(),
                owner.version());
    }

    public InputStream open(FileRepository.Entry entry) throws IOException {
        if (!entry.state().equals("READY"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "fileNotReady");
        return blobs.open(entry.id());
    }

    public FilePreview preview(String actor, UUID id) throws IOException {
        return preview(actor, id, 1);
    }

    public FilePreview preview(String actor, UUID id, int page) throws IOException {
        return preview(find(actor, id), page);
    }

    public FilePreview previewManaged(String actor, UUID id, int page) throws IOException {
        guard.requireOperationGlobal(actor, PlatformOperation.FILE_PREVIEW);
        var entry = managedEntry(id);
        return preview(
                new FileRepository.Entry(
                        entry.id(),
                        entry.ownerId(),
                        entry.storageBackend(),
                        entry.originalName(),
                        entry.state(),
                        entry.sizeBytes(),
                        entry.sha256(),
                        entry.version(),
                        entry.createdAt(),
                        entry.updatedAt(),
                        entry.trashedAt()),
                page);
    }

    private FilePreview preview(FileRepository.Entry entry, int page) throws IOException {
        if (!entry.state().equals("READY"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "fileNotReady");
        FilePreviewReader.requireSupported(entry.originalName());
        if (entry.sizeBytes() != null && entry.sizeBytes() > MAXIMUM_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "filePreviewTooLarge");
        try (var input = open(entry)) {
            byte[] content = input.readNBytes((int) MAXIMUM_BYTES + 1);
            if (content.length > MAXIMUM_BYTES)
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "filePreviewTooLarge");
            return FilePreviewReader.read(entry.originalName(), content, page);
        }
    }

    public void delete(String actor, UUID id, long version) {
        transaction.executeWithoutResult(
                status -> {
                    validateAuthorizationBoundary();
                    var entry = find(actor, id);
                    if (entry.version() != version)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
                    if (!files.requestTrash(id, entry.ownerId(), version))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
                    audit.append(actor, "file.trash", id);
                });
    }

    public void restore(String actor, UUID id, long version) {
        transaction.executeWithoutResult(
                status -> {
                    validateAuthorizationBoundary();
                    var owner = accounts.id(actor, true);
                    var entry =
                            files.findOwned(id, owner)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND, "fileNotFound"));
                    if (entry.version() != version || !files.restoreOwned(id, owner, version))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
                    audit.append(actor, "file.restore", id);
                });
    }

    public void purge(String actor, UUID id, long version) {
        transaction.executeWithoutResult(
                status -> {
                    validateAuthorizationBoundary();
                    var owner = accounts.id(actor, true);
                    var entry =
                            files.findOwned(id, owner)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND, "fileNotFound"));
                    if (entry.version() != version || !files.purgeOwned(id, owner, version))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
                    audit.append(actor, "file.purgeRequested", id);
                });
        deleteFinalizer.finish(id, version + 1);
    }

    private void validateAuthorizationBoundary() {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
    }
}
