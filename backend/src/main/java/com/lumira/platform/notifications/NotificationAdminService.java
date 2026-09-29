package com.lumira.platform.notifications;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.AdministrationPort;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Global, in-app broadcast administration. External delivery remains in the mail/SMS queues. */
@Service
public class NotificationAdminService {
    public static final String READ = "platform:notifications:read";
    public static final String WRITE = "platform:notifications:write";
    private static final int MAX_RECIPIENTS = 10_000;
    private final NotificationStore store;
    private final JdbcClient jdbc;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final ObjectMapper mapper;

    public record Publish(String scope, List<UUID> userIds, String title, String body, String href,
                          String expiresAt) {}
    public record Published(UUID broadcastId, int recipientCount) {}

    public NotificationAdminService(NotificationStore store, JdbcClient jdbc, AdministrationPort guard,
                                     AuditPort audit, ObjectMapper mapper) {
        this.store = store;
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationStore.AdminEntry> list(String actor, PageRequest page) {
        guard.requireGlobal(actor, READ);
        return store.listAdmin(page);
    }

    @Transactional
    public Published publish(String actor, Publish input) {
        guard.require(actor, WRITE);
        guard.requireGlobal(actor, WRITE);
        if (input == null || input.scope() == null || input.title() == null || input.body() == null) {
            throw invalid();
        }
        String scope = input.scope().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "USERS").contains(scope)) throw invalid();
        String title = bounded(input.title(), 200);
        String body = bounded(input.body(), 4_000);
        String href = normalizeHref(input.href());
        OffsetDateTime expiresAt = parseExpiry(input.expiresAt());
        List<UUID> recipients = resolveRecipients(scope, input.userIds());
        if (recipients.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notificationNoRecipients");
        if (recipients.size() > MAX_RECIPIENTS) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "notificationTooManyRecipients");

        ObjectNode params = mapper.createObjectNode();
        params.put("title", title);
        params.put("body", body);
        String serialized = mapper.writeValueAsString(params);
        UUID broadcastId = UUID.randomUUID();
        var requests = recipients.stream().map(recipient -> new NotificationStore.CreateRequest(
            recipient, null, "broadcast:" + broadcastId + ":" + recipient,
            "broadcast", "notificationCustomTitle", "notificationCustomBody", serialized, href, expiresAt
        )).toList();
        int created = store.createBatch(requests);
        audit.append(actor, "notification.broadcast", broadcastId);
        return new Published(broadcastId, created);
    }

    private List<UUID> resolveRecipients(String scope, List<UUID> requested) {
        if (scope.equals("ALL")) {
            return jdbc.sql("SELECT id FROM platform_user WHERE enabled=true ORDER BY id LIMIT :limit")
                    .param("limit", MAX_RECIPIENTS + 1).query(UUID.class).list();
        }
        if (requested == null || requested.isEmpty()) return List.of();
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(requested);
        if (unique.size() != requested.size()) throw invalid();
        if (unique.size() > MAX_RECIPIENTS) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "notificationTooManyRecipients");
        var found = jdbc.sql("SELECT id FROM platform_user WHERE enabled=true AND id IN (:ids)")
                .param("ids", unique).query(UUID.class).list();
        if (found.size() != unique.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "notificationRecipientNotFound");
        return found;
    }

    private static String bounded(String value, int max) {
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > max || normalized.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return normalized;
    }

    private static String normalizeHref(String value) {
        if (value == null || value.isBlank()) return null;
        String href = value.strip();
        if (href.length() > 512 || !href.startsWith("/") || href.startsWith("//") || href.contains("\n") || href.contains("\r")) throw invalid();
        return href;
    }

    private static OffsetDateTime parseExpiry(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            if (!parsed.isAfter(OffsetDateTime.now())) throw invalid();
            return parsed;
        } catch (java.time.format.DateTimeParseException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notificationInvalidExpiry");
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "notificationInvalid");
    }
}
