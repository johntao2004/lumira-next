package com.lumira.platform.files;

import com.lumira.platform.common.AuthorizationContextBoundaryPort;
import com.lumira.platform.common.FileContentPort;
import com.lumira.platform.common.FileQuotaPolicyPort;
import com.lumira.platform.identity.api.AccountOwnerPort;
import java.io.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FileService implements FileContentPort {
    public static final long MAXIMUM_BYTES = FilePayloadReader.MAXIMUM_BYTES;
    private final FileRepository files;
    private final AccountOwnerPort accounts;
    private final BlobStore blobs;
    private final FilePayloadReader payloads;
    private final com.lumira.platform.common.AuditPort audit;
    private final TransactionTemplate transaction;
    private final FileQuotaPolicyPort quotaPolicies;
    private final AuthorizationContextBoundaryPort authorizationBoundary;

    public FileService(
            FileRepository files,
            AccountOwnerPort accounts,
            BlobStore blobs,
            PlatformTransactionManager manager,
            com.lumira.platform.common.AuditPort audit,
            FileQuotaPolicyPort quotaPolicies,
            AuthorizationContextBoundaryPort authorizationBoundary) {
        this.files = files;
        this.accounts = accounts;
        this.blobs = blobs;
        this.payloads = new FilePayloadReader(blobs);
        this.transaction = new TransactionTemplate(manager);
        this.audit = audit;
        this.quotaPolicies = quotaPolicies;
        this.authorizationBoundary = authorizationBoundary;
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

    private FileQuota effectiveQuota(UUID userId) {
        var limits = quotaPolicies.forUser(userId);
        return new FileQuota(limits.maximumBytes(), limits.maximumFiles());
    }

    public InputStream open(FileRepository.Entry entry) throws IOException {
        return payloads.open(blob(entry));
    }

    public FilePreview preview(String actor, UUID id) throws IOException {
        return preview(actor, id, 1);
    }

    public FilePreview preview(String actor, UUID id, int page) throws IOException {
        return preview(find(actor, id), page);
    }

    private FilePreview preview(FileRepository.Entry entry, int page) throws IOException {
        return payloads.preview(blob(entry), page);
    }

    private static com.lumira.platform.common.FileManagementStoragePort.Blob blob(
            FileRepository.Entry entry) {
        return new com.lumira.platform.common.FileManagementStoragePort.Blob(
                entry.id(), entry.originalName(), entry.state(), entry.sizeBytes());
    }

    private void validateAuthorizationBoundary() {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
    }
}
