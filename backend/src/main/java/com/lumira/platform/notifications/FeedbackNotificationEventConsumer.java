package com.lumira.platform.notifications;

import com.lumira.platform.common.DurableEventConsumerPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

/** Writes the notification requested by a committed feedback create or handling operation. */
public final class FeedbackNotificationEventConsumer implements DurableEventConsumerPort {
    private final NotificationStore store;
    private final ObjectMapper mapper;
    private final FeedbackNotificationAudience feedbackAudience;

    public FeedbackNotificationEventConsumer(
            NotificationStore store,
            ObjectMapper mapper,
            FeedbackNotificationAudience feedbackAudience) {
        this.store = store;
        this.mapper = mapper;
        this.feedbackAudience = feedbackAudience;
    }

    @Override
    public String sourceType() {
        return NotificationService.FEEDBACK_SOURCE;
    }

    @Override
    public String eventType() {
        return NotificationService.FEEDBACK_EVENT;
    }

    @Override
    public void consume(Event event) {
        consumeWithResult(event);
    }

    @Override
    public ConsumptionResult consumeWithResult(Event event) {
        JsonNode payload = parse(event);
        UUID recipientId = uuid(payload, "recipientId");
        UUID feedbackId = uuid(payload, "feedbackId");
        long version = positiveLong(payload, "version");
        String action = requiredText(payload.path("action"));
        boolean handled =
                switch (action) {
                    case "created" -> false;
                    case "handled" -> true;
                    default ->
                            throw new IllegalArgumentException(
                                    "Invalid feedback notification action");
                };
        if (!event.eventKey().equals("feedback:" + feedbackId + ":" + version))
            throw new IllegalArgumentException("Invalid feedback notification key");

        // A recipient who has since become a feedback manager should not receive a personal copy.
        if (feedbackAudience.isGlobalManager(recipientId))
            return ConsumptionResult.of(
                    1,
                    0,
                    java.util.Map.of("GLOBAL_FEEDBACK_MANAGER", 1),
                    "SUPPRESSED_GLOBAL_MANAGER");

        ObjectNode params = mapper.createObjectNode();
        params.put("title", requiredText(payload.path("title")));
        if (handled) params.put("status", requiredText(payload.path("status")));
        String key = "feedback:" + feedbackId + ":" + (handled ? "handled:" + version : "created");
        NotificationStore.BatchResult result =
                store.createBatchWithResult(
                        List.of(
                                new NotificationStore.CreateRequest(
                                        recipientId,
                                        event.id(),
                                        key,
                                        "feedback",
                                        handled
                                                ? "notificationFeedbackUpdated"
                                                : "notificationFeedbackSubmitted",
                                        handled
                                                ? "notificationFeedbackUpdatedBody"
                                                : "notificationFeedbackCreatedBody",
                                        mapper.writeValueAsString(params),
                                        "/dashboard/feedback",
                                        null)));
        return ConsumptionResult.of(
                result.expectedCount(),
                result.inboxCreated(),
                result.skippedByReason(),
                result.resultCode());
    }

    private JsonNode parse(Event event) {
        if (event == null
                || !sourceType().equals(event.sourceType())
                || !eventType().equals(event.eventType())
                || event.id() == null)
            throw new IllegalArgumentException("Invalid feedback notification event");
        JsonNode payload = mapper.readTree(event.payload());
        if (payload == null || !payload.isObject())
            throw new IllegalArgumentException("Invalid feedback notification payload");
        return payload;
    }

    private static UUID uuid(JsonNode payload, String name) {
        return UUID.fromString(requiredText(payload.path(name)));
    }

    private static long positiveLong(JsonNode payload, String name) {
        JsonNode value = payload.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1)
            throw new IllegalArgumentException("Invalid feedback notification payload");
        return value.longValue();
    }

    private static String requiredText(JsonNode value) {
        if (value == null || !value.isTextual())
            throw new IllegalArgumentException("Invalid feedback notification payload");
        return value.textValue();
    }
}
