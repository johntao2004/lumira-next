package com.lumira.platform.feedback;

import com.lumira.platform.common.*;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FeedbackService {
    public static final String ACCESS = "platform:feedback:access",
            READ = "platform:feedback:read",
            CREATE = "platform:feedback:create",
            UPDATE = "platform:feedback:update",
            REPLY = "platform:feedback:reply";

    public record Create(String title, String detailHtml, String priority, String category) {}

    public record Handle(Long version, String status, String adminReply) {}

    public record ImageUpload(UUID id, String url, String mediaType, long sizeBytes) {}

    private final FeedbackStore store;
    private final FeedbackCatalog catalog;
    private final AdministrationPort guard;
    private final JdbcClient jdbc;
    private final FileContentPort files;

    public FeedbackService(
            FeedbackStore store,
            FeedbackCatalog catalog,
            AdministrationPort guard,
            JdbcClient jdbc,
            FileContentPort files) {
        this.store = store;
        this.catalog = catalog;
        this.guard = guard;
        this.jdbc = jdbc;
        this.files = files;
    }

    private UUID owner(String actor) {
        return jdbc.sql("SELECT id FROM platform_user WHERE username=:actor AND enabled")
                .param("actor", actor)
                .query(UUID.class)
                .optional()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized"));
    }

    private UUID read(String actor) {
        requireRead(actor);
        UUID id = owner(actor);
        return id;
    }

    private String filter(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,31}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFeedback");
        return normalized;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<FeedbackStore.Summary> mine(
            String actor, PageRequest page, String status, String priority, String category) {
        return store.listOwned(
                read(actor), page, filter(status), filter(priority), filter(category));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeedbackStore.Detail mine(String actor, UUID id) {
        return store.findOwned(read(actor), id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeedbackCatalog.Catalog options(String actor) {
        guard.require(actor, ACCESS);
        try {
            guard.require(actor, READ);
        } catch (ResponseStatusException deniedRead) {
            if (deniedRead.getStatusCode() != HttpStatus.FORBIDDEN) throw deniedRead;
            guard.require(actor, CREATE);
        }
        return catalog.load();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeedbackCatalog.Catalog managementOptions(String actor) {
        requireRead(actor);
        return catalog.load();
    }

    @Transactional
    public FeedbackStore.Detail create(String actor, Create input) {
        requireOperation(actor, CREATE);
        UUID id = owner(actor);
        if (input == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFeedback");
        var content = FeedbackContent.validate(input.title(), input.detailHtml());
        return store.create(
                id,
                actor,
                content.title(),
                content.detailHtml(),
                input.priority(),
                input.category(),
                catalog.load().policy());
    }

    public ImageUpload uploadImage(
            String actor, String filename, java.io.InputStream source, long contentLength)
            throws IOException {
        requireOperation(actor, CREATE);
        owner(actor);
        var entry = files.uploadInlineImageForContent(actor, filename, source, contentLength);
        return new ImageUpload(
                entry.id(),
                FeedbackContent.IMAGE_URL_PREFIX + entry.id(),
                "image/png",
                entry.sizeBytes());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FileContentPort.Image image(String actor, UUID id) {
        owner(actor);
        requireRead(actor);
        try {
            return files.findReadyImageForContent(actor, id);
        } catch (ResponseStatusException missing) {
            if (missing.getStatusCode() != HttpStatus.NOT_FOUND) throw missing;
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FileContentPort.Image managementImage(String actor, UUID id) {
        requireRead(actor);
        boolean visible =
                store.findManagementImageOwners(id).stream()
                        .anyMatch(owner -> canSeeOwner(actor, owner, ACCESS, READ));
        if (!visible) throw notFound();
        return files.findManagedFeedbackImageForContent(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<FeedbackStore.Summary> managed(
            String actor, PageRequest page, String status, String priority, String category) {
        requireRead(actor);
        RequestAuthorizationContext context = currentAuthorization(actor);
        boolean includeDeletedOwners =
                guard.hasGlobal(actor, ACCESS) && guard.hasGlobal(actor, READ);
        return store.listForManagement(
                page,
                filter(status),
                filter(priority),
                filter(category),
                actor,
                context,
                includeDeletedOwners);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeedbackStore.Detail managed(String actor, UUID id) {
        requireRead(actor);
        var detail = store.findForManagement(id);
        requireVisibleOwner(actor, detail.ownerId(), ACCESS, READ);
        return withCapabilities(
                detail,
                canOperateOnOwner(actor, detail.ownerId(), UPDATE),
                canOperateOnOwner(actor, detail.ownerId(), REPLY));
    }

    @Transactional
    public FeedbackStore.Detail handle(String actor, UUID id, Handle input) {
        if (input == null
                || input.version() == null
                || input.status() == null && input.adminReply() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFeedback");
        UUID handler = owner(actor);
        requireRead(actor);
        var policy = catalog.load().policy();
        boolean statusRequested = input.status() != null;
        boolean replyRequested = input.adminReply() != null;
        if (statusRequested) guard.require(actor, UPDATE);
        if (replyRequested) guard.require(actor, REPLY);
        String requestedStatus = statusRequested ? filter(input.status()) : null;
        String requestedReply = replyRequested ? FeedbackContent.reply(input.adminReply()) : null;
        if (input.version() < 1
                || statusRequested && !policy.statuses().containsKey(requestedStatus))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFeedback");

        var current = store.findForManagementForUpdate(id);
        if (statusRequested) requireVisibleOwner(actor, current.ownerId(), ACCESS, READ, UPDATE);
        if (replyRequested) requireVisibleOwner(actor, current.ownerId(), ACCESS, READ, REPLY);
        if (current.version() != input.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        String status = statusRequested ? requestedStatus : current.status();
        String reply =
                replyRequested ? requestedReply : FeedbackContent.reply(current.adminReply());
        boolean statusChanged = !current.status().equals(status);
        boolean replyChanged =
                !java.util.Objects.equals(FeedbackContent.reply(current.adminReply()), reply);
        if (!statusChanged && !replyChanged)
            return withCapabilities(
                    current,
                    canOperateOnOwner(actor, current.ownerId(), UPDATE),
                    canOperateOnOwner(actor, current.ownerId(), REPLY));
        var updated = store.handle(handler, actor, id, input.version(), status, reply, policy);
        return withCapabilities(
                updated,
                canOperateOnOwner(actor, updated.ownerId(), UPDATE),
                canOperateOnOwner(actor, updated.ownerId(), REPLY));
    }

    private static String visibleText(String html) {
        return com.lumira.platform.content.RichText.visibleText(html);
    }

    private void requireOperation(String actor, String operation) {
        guard.require(actor, ACCESS);
        guard.require(actor, operation);
    }

    private void requireRead(String actor) {
        guard.require(actor, ACCESS);
        guard.require(actor, READ);
    }

    private void requireVisibleOwner(String actor, UUID ownerId, String... permissions) {
        if (!canSeeOwner(actor, ownerId, permissions)) throw notFound();
    }

    private boolean canSeeOwner(String actor, UUID ownerId, String... permissions) {
        if (ownerId == null || store.isDeletedOwner(ownerId))
            return Arrays.stream(permissions)
                    .allMatch(permission -> guard.hasGlobal(actor, permission));
        try {
            for (String permission : permissions) guard.requireUser(actor, ownerId, permission);
            return true;
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode() == HttpStatus.FORBIDDEN) return false;
            throw denied;
        }
    }

    private boolean canOperateOnOwner(String actor, UUID ownerId, String action) {
        try {
            guard.require(actor, action);
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode() == HttpStatus.FORBIDDEN) return false;
            throw denied;
        }
        return canSeeOwner(actor, ownerId, ACCESS, READ, action);
    }

    private FeedbackStore.Detail withCapabilities(
            FeedbackStore.Detail detail, boolean canUpdateStatus, boolean canReply) {
        return new FeedbackStore.Detail(
                detail.id(),
                detail.ownerId(),
                detail.title(),
                detail.detailHtml(),
                detail.priority(),
                detail.category(),
                detail.status(),
                detail.adminReply(),
                detail.handledBy(),
                detail.handledAt(),
                detail.version(),
                detail.createdAt(),
                detail.updatedAt(),
                canUpdateStatus,
                canReply);
    }

    private RequestAuthorizationContext currentAuthorization(String actor) {
        var snapshot = AuthenticationRequestContext.current();
        return snapshot != null && actor.equals(snapshot.principalName())
                ? snapshot.authorization()
                : null;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "feedbackNotFound");
    }
}
