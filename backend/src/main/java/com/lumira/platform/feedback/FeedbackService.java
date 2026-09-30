package com.lumira.platform.feedback;

import com.lumira.platform.common.*;
import com.lumira.platform.common.AdministrationPort;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FeedbackService {
    public static final String READ = "platform:feedback:read",
            CREATE = "platform:feedback:create",
            MANAGE = "platform:feedback:manage";

    public record Create(String title, String detailHtml, String priority, String category) {}

    public record Handle(Long version, String status, String adminReply) {}

    public record ImageUpload(UUID id, String url, String mediaType, long sizeBytes) {}

    private final FeedbackStore store;
    private final FeedbackCatalog catalog;
    private final AdministrationPort guard;
    private final JdbcClient jdbc;
    private final FileContentPort files;
    private final ObjectProvider<ContentPolicyPort> contentPolicies;

    public FeedbackService(
            FeedbackStore store,
            FeedbackCatalog catalog,
            AdministrationPort guard,
            JdbcClient jdbc,
            FileContentPort files,
            ObjectProvider<ContentPolicyPort> contentPolicies) {
        this.store = store;
        this.catalog = catalog;
        this.guard = guard;
        this.jdbc = jdbc;
        this.files = files;
        this.contentPolicies = contentPolicies;
    }

    private UUID owner(String actor) {
        return jdbc.sql("SELECT id FROM platform_user WHERE username=:actor AND enabled")
                .param("actor", actor)
                .query(UUID.class)
                .optional()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized"));
    }

    private boolean has(UUID id, String permission) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_effective_authority WHERE user_id=:id AND authority=:permission)")
                .param("id", id)
                .param("permission", permission)
                .query(Boolean.class)
                .single();
    }

    private UUID read(String actor) {
        UUID id = owner(actor);
        if (!has(id, READ)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
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
        read(actor);
        return catalog.load();
    }

    @Transactional
    public FeedbackStore.Detail create(String actor, Create input) {
        guard.require(actor, CREATE);
        UUID id = owner(actor);
        if (input == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFeedback");
        var content = FeedbackContent.validate(input.title(), input.detailHtml());
        var policy = contentPolicies.orderedStream().findFirst().orElse(null);
        var moderation =
                policy == null
                        ? new ContentPolicyPort.ScanResult(false)
                        : policy.evaluate(
                                content.title() + "\n" + visibleText(content.detailHtml()));
        if (moderation.blocked())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sensitiveContent");
        return store.create(
                id,
                actor,
                content.title(),
                content.detailHtml(),
                input.priority(),
                input.category(),
                catalog.load().policy());
    }

    @Transactional
    public ImageUpload uploadImage(
            String actor, String filename, java.io.InputStream source, long contentLength)
            throws IOException {
        guard.require(actor, CREATE);
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
        UUID ownerId = owner(actor);
        if (has(ownerId, READ)) {
            try {
                return files.findReadyImageForContent(actor, id);
            } catch (ResponseStatusException missing) {
                if (missing.getStatusCode() != HttpStatus.NOT_FOUND) throw missing;
            }
        }
        if (guard.hasGlobal(actor, MANAGE)) return files.findManagedFeedbackImageForContent(id);
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<FeedbackStore.Summary> managed(
            String actor, PageRequest page, String status, String priority, String category) {
        guard.requireGlobal(actor, MANAGE);
        return store.listForManagement(page, filter(status), filter(priority), filter(category));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeedbackStore.Detail managed(String actor, UUID id) {
        guard.requireGlobal(actor, MANAGE);
        return store.findForManagement(id);
    }

    @Transactional
    public FeedbackStore.Detail handle(String actor, UUID id, Handle input) {
        guard.require(actor, MANAGE);
        guard.requireGlobal(actor, MANAGE);
        if (input == null || input.version() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFeedback");
        return store.handle(
                owner(actor),
                actor,
                id,
                input.version(),
                filter(input.status()),
                input.adminReply(),
                catalog.load().policy());
    }

    private static String visibleText(String html) {
        return com.lumira.platform.content.RichText.visibleText(html);
    }
}
