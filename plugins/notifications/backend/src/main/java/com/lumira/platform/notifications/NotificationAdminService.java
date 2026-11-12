package com.lumira.platform.notifications;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.DurableEventPublisherPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Global, in-app notification administration. External delivery remains in the mail/SMS queues. */
@Service
public class NotificationAdminService {
    public static final String READ = PlatformPageId.NOTIFICATIONS.accessPermission();
    public static final String CREATE = PlatformOperation.NOTIFICATION_CREATE.permission();
    private static final int MAX_RECIPIENTS = 10_000;
    private static final int RECIPIENTS_PER_EVENT = 500;
    static final String BROADCAST_SOURCE = "notifications";
    static final String BROADCAST_EVENT = "notification.broadcast.requested";
    private final NotificationStore store;
    private final JdbcClient jdbc;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final ObjectMapper mapper;
    private final DurableEventPublisherPort eventPublisher;

    public record RoleOption(UUID id, String code, String name) {}

    public record Publish(
            String scope,
            List<UUID> userIds,
            List<UUID> roleIds,
            String title,
            String body,
            String href,
            String expiresAt) {}

    public record Published(
            UUID broadcastId,
            int recipientCount,
            UUID operationId,
            UUID auditOperationId,
            List<UUID> eventIds) {
        public Published {
            eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
        }

        public Published(UUID broadcastId, int recipientCount) {
            this(broadcastId, recipientCount, broadcastId, null, List.of());
        }
    }

    private record Enqueued(int recipientCount, List<UUID> eventIds) {}

    public NotificationAdminService(
            NotificationStore store,
            JdbcClient jdbc,
            AdministrationPort guard,
            AuditPort audit,
            ObjectMapper mapper,
            DurableEventPublisherPort eventPublisher) {
        this.store = store;
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationStore.AdminEntry> list(String actor, PageRequest page) {
        guard.requirePageGlobal(actor, PlatformPageId.NOTIFICATIONS);
        return store.listAdmin(page);
    }

    @Transactional(readOnly = true)
    public List<RoleOption> enabledRoles(String actor) {
        guard.requireOperationGlobal(actor, PlatformOperation.NOTIFICATION_CREATE);
        return jdbc.sql(
                        "SELECT id,code,name FROM platform_role WHERE enabled=true ORDER BY"
                                + " code,id")
                .query(RoleOption.class)
                .list();
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationStore.RecipientOption> recipientOptions(
            String actor, PageRequest page) {
        requireGlobalWriter(actor);
        return store.listRecipientOptions(page);
    }

    @Transactional(readOnly = true)
    public NotificationStore.RecipientOption recipientOption(String actor, UUID id) {
        requireGlobalWriter(actor);
        return store.recipientOption(id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "notificationRecipientNotFound"));
    }

    private void requireGlobalWriter(String actor) {
        guard.requireOperationGlobal(actor, PlatformOperation.NOTIFICATION_CREATE);
    }

    @Transactional
    public Published publish(String actor, Publish input) {
        guard.requireOperationGlobal(actor, PlatformOperation.NOTIFICATION_CREATE);
        if (input == null
                || input.scope() == null
                || input.title() == null
                || input.body() == null) {
            throw invalid();
        }
        String scope = input.scope().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "USERS", "ROLES").contains(scope)) throw invalid();
        String title = bounded(input.title(), 200);
        String body = bounded(input.body(), 4_000);
        ObjectNode notificationParams = mapper.createObjectNode();
        notificationParams.put("title", title);
        notificationParams.put("body", body);
        try {
            store.normalizeParams(mapper.writeValueAsString(notificationParams));
        } catch (IllegalArgumentException invalidParams) {
            throw invalid();
        }
        String href;
        try {
            href = NotificationHref.normalize(input.href());
        } catch (IllegalArgumentException invalidHref) {
            throw invalid();
        }
        OffsetDateTime expiresAt = parseExpiry(input.expiresAt());
        List<UUID> recipients = resolveRecipients(scope, input.userIds(), input.roleIds());
        if (recipients.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notificationNoRecipients");
        if (recipients.size() > MAX_RECIPIENTS)
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "notificationTooManyRecipients");
        if (eventPublisher == null)
            throw new IllegalStateException("Durable notification event publisher is unavailable");

        UUID broadcastId = UUID.randomUUID();
        UUID auditOperationId = UUID.randomUUID();
        int expectedEvents = (recipients.size() + RECIPIENTS_PER_EVENT - 1) / RECIPIENTS_PER_EVENT;
        eventPublisher.recordOperation(
                new DurableEventPublisherPort.Operation(
                        broadcastId,
                        "notification.broadcast",
                        "BROADCAST",
                        broadcastId,
                        null,
                        auditOperationId,
                        expectedEvents,
                        recipients.size(),
                        "EVENTS_PENDING"));
        Enqueued queued = enqueueBroadcast(broadcastId, recipients, title, body, href, expiresAt);
        audit.append(auditOperationId, actor, "notification.broadcast", broadcastId);
        return new Published(
                broadcastId,
                queued.recipientCount(),
                broadcastId,
                auditOperationId,
                queued.eventIds());
    }

    private Enqueued enqueueBroadcast(
            UUID broadcastId,
            List<UUID> recipients,
            String title,
            String body,
            String href,
            OffsetDateTime expiresAt) {
        if (eventPublisher == null)
            throw new IllegalStateException("Durable notification event publisher is unavailable");
        int queued = 0;
        List<UUID> eventIds = new ArrayList<>();
        for (int offset = 0, chunk = 0;
                offset < recipients.size();
                offset += RECIPIENTS_PER_EVENT, chunk++) {
            List<UUID> recipientChunk =
                    recipients.subList(
                            offset, Math.min(offset + RECIPIENTS_PER_EVENT, recipients.size()));
            UUID eventId = UUID.randomUUID();
            eventIds.add(eventId);
            ObjectNode payload = mapper.createObjectNode();
            payload.put("broadcastId", broadcastId.toString());
            payload.put("chunk", chunk);
            var recipientNodes = payload.putArray("recipients");
            recipientChunk.forEach(id -> recipientNodes.add(id.toString()));
            payload.put("title", title);
            payload.put("body", body);
            if (href == null) payload.putNull("href");
            else payload.put("href", href);
            if (expiresAt == null) payload.putNull("expiresAt");
            else payload.put("expiresAt", expiresAt.toString());
            byte[] bytes = mapper.writeValueAsBytes(payload);
            try {
                if (bytes.length > 262_144)
                    throw new IllegalArgumentException("Notification event payload too large");
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
            boolean inserted =
                    eventPublisher.publish(
                            eventId,
                            BROADCAST_SOURCE,
                            BROADCAST_EVENT,
                            "broadcast:" + broadcastId + ":" + chunk,
                            mapper.writeValueAsString(payload),
                            broadcastId);
            if (!inserted)
                throw new IllegalStateException("Notification broadcast event already exists");
            queued += recipientChunk.size();
        }
        return new Enqueued(queued, eventIds);
    }

    private List<UUID> resolveRecipients(
            String scope, List<UUID> requested, List<UUID> requestedRoles) {
        if (scope.equals("ALL")) {
            return jdbc.sql(
                            "SELECT id FROM platform_user WHERE enabled=true AND deleted_at IS NULL"
                                    + " ORDER BY id LIMIT :limit")
                    .param("limit", MAX_RECIPIENTS + 1)
                    .query(UUID.class)
                    .list();
        }
        if (scope.equals("ROLES")) return resolveRoleRecipients(requestedRoles);
        if (requested == null || requested.isEmpty()) return List.of();
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(requested);
        if (unique.size() != requested.size()) throw invalid();
        if (unique.size() > MAX_RECIPIENTS)
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "notificationTooManyRecipients");
        var found =
                jdbc.sql(
                                "SELECT id FROM platform_user WHERE enabled=true AND deleted_at IS"
                                        + " NULL AND id IN (:ids)")
                        .param("ids", unique)
                        .query(UUID.class)
                        .list();
        if (found.size() != unique.size())
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "notificationRecipientNotFound");
        return found;
    }

    private List<UUID> resolveRoleRecipients(List<UUID> requested) {
        if (requested == null || requested.isEmpty()) return List.of();
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(requested);
        if (unique.size() != requested.size()) throw invalid();
        if (unique.size() > MAX_RECIPIENTS) throw tooManyRecipients();
        int enabledRoles =
                jdbc.sql("SELECT count(*) FROM platform_role WHERE enabled=true AND id IN (:ids)")
                        .param("ids", unique)
                        .query(Integer.class)
                        .single();
        if (enabledRoles != unique.size())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "notificationRoleNotFound");
        return jdbc.sql(
                        """
                        SELECT DISTINCT u.id
                        FROM platform_user_role ur
                        JOIN platform_role r ON r.id=ur.role_id AND r.enabled=true
                        JOIN platform_user u ON u.id=ur.user_id AND u.enabled=true AND u.deleted_at IS NULL
                        WHERE ur.role_id IN (:ids)
                        ORDER BY u.id
                        LIMIT :limit
                        """)
                .param("ids", unique)
                .param("limit", MAX_RECIPIENTS + 1)
                .query(UUID.class)
                .list();
    }

    private static String bounded(String value, int max) {
        String normalized = value.strip();
        if (normalized.isEmpty()
                || normalized.length() > max
                || normalized.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return normalized;
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

    private static ResponseStatusException tooManyRecipients() {
        return new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE, "notificationTooManyRecipients");
    }
}
