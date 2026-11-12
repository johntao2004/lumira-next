package com.lumira.platform.notifications;

import com.lumira.platform.common.*;
import com.lumira.platform.notifications.api.NotificationProducerPort;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class NotificationService implements NotificationProducerPort {
    static final String FEEDBACK_SOURCE = "feedback";
    static final String FEEDBACK_EVENT = "feedback.notification.requested";
    private final NotificationStore store;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AuthorizationContextBoundaryPort authorizationBoundary;
    private final DurableEventPublisherPort eventPublisher;
    private final FeedbackNotificationAudience feedbackAudience;

    public NotificationService(
            NotificationStore store,
            JdbcClient jdbc,
            ObjectMapper mapper,
            AuthorizationContextBoundaryPort authorizationBoundary,
            DurableEventPublisherPort eventPublisher,
            FeedbackNotificationAudience feedbackAudience) {
        this.store = store;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.authorizationBoundary = authorizationBoundary;
        this.eventPublisher = eventPublisher;
        this.feedbackAudience = feedbackAudience;
    }

    private UUID owner(String actor) {
        return jdbc.sql("SELECT id FROM platform_user WHERE username=:actor AND enabled=true")
                .param("actor", actor)
                .query(UUID.class)
                .optional()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized"));
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationStore.Entry> list(
            String actor, PageRequest page, boolean unreadOnly) {
        return store.list(owner(actor), page, unreadOnly);
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationStore.Entry> list(
            String actor, PageRequest page, boolean unreadOnly, Boolean read) {
        return store.list(owner(actor), page, unreadOnly, read);
    }

    @Transactional(readOnly = true)
    public long unreadCount(String actor) {
        return store.unreadCount(owner(actor));
    }

    @Transactional
    public boolean markRead(String actor, UUID id) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        return store.markRead(owner(actor), id);
    }

    @Transactional
    public int markAllRead(String actor) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        return store.markAllRead(owner(actor));
    }

    /** Shared feedback producer hook; duplicate status versions are ignored by the dedupe key. */
    @Override
    @Transactional
    public void publishFeedback(
            UUID recipientId,
            UUID feedbackId,
            long version,
            String title,
            String status,
            boolean handled) {
        publishFeedbackTracked(
                UUID.randomUUID(), null, recipientId, feedbackId, version, title, status, handled);
    }

    @Override
    @Transactional
    public void publishFeedback(
            UUID operationId,
            UUID auditOperationId,
            UUID recipientId,
            UUID feedbackId,
            long version,
            String title,
            String status,
            boolean handled) {
        publishFeedbackTracked(
                operationId,
                auditOperationId,
                recipientId,
                feedbackId,
                version,
                title,
                status,
                handled);
    }

    private void publishFeedbackTracked(
            UUID operationId,
            UUID auditOperationId,
            UUID recipientId,
            UUID feedbackId,
            long version,
            String title,
            String status,
            boolean handled) {
        if (operationId == null || feedbackId == null || version < 1)
            throw new IllegalArgumentException("Invalid feedback notification operation");
        if (eventPublisher == null)
            throw new IllegalStateException(
                    "Durable feedback notification publisher is unavailable");
        boolean globalManager =
                recipientId != null && feedbackAudience.isGlobalManager(recipientId);
        String operationType = handled ? "feedback.handle" : "feedback.create";
        String outcome =
                recipientId == null
                        ? "NO_RECIPIENT"
                        : globalManager ? "SUPPRESSED_GLOBAL_MANAGER" : "EVENTS_PENDING";
        eventPublisher.recordOperation(
                new DurableEventPublisherPort.Operation(
                        operationId,
                        operationType,
                        "FEEDBACK",
                        feedbackId,
                        version,
                        auditOperationId,
                        recipientId == null || globalManager ? 0 : 1,
                        recipientId == null ? 0 : 1,
                        outcome));
        if (recipientId == null) {
            eventPublisher.completeOperationWithoutEvents(
                    operationId, 0, 0, java.util.Map.of(), "NO_RECIPIENT");
            return;
        }
        // A manager processing their own fixture/content does not need an inbox copy. Keep an
        // explicit operation result so an empty event list is not mistaken for lost correlation.
        if (globalManager) {
            eventPublisher.completeOperationWithoutEvents(
                    operationId,
                    0,
                    1,
                    java.util.Map.of("GLOBAL_FEEDBACK_MANAGER", 1),
                    "SUPPRESSED_GLOBAL_MANAGER");
            return;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("recipientId", recipientId.toString());
        payload.put("feedbackId", feedbackId.toString());
        payload.put("version", version);
        payload.put("action", handled ? "handled" : "created");
        payload.put("title", title == null ? "" : title);
        if (handled) payload.put("status", status == null ? "" : status);
        ObjectNode params = mapper.createObjectNode();
        params.put("title", title == null ? "" : title);
        if (handled) params.put("status", status == null ? "" : status);
        store.normalizeParams(mapper.writeValueAsString(params));
        String key = "feedback:" + feedbackId + ":" + version;
        eventPublisher.publish(
                UUID.randomUUID(),
                FEEDBACK_SOURCE,
                FEEDBACK_EVENT,
                key,
                mapper.writeValueAsString(payload),
                operationId);
    }
}
