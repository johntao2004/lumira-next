package com.lumira.platform.notifications;

import com.lumira.platform.common.DurableEventConsumerPort;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Writes an administrator broadcast snapshot into the account inbox. */
public final class NotificationBroadcastEventConsumer implements DurableEventConsumerPort {
    private static final int MAX_RECIPIENTS_PER_EVENT = 500;

    private final NotificationStore store;
    private final ObjectMapper mapper;

    public NotificationBroadcastEventConsumer(NotificationStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override
    public String sourceType() {
        return NotificationAdminService.BROADCAST_SOURCE;
    }

    @Override
    public String eventType() {
        return NotificationAdminService.BROADCAST_EVENT;
    }

    @Override
    public void consume(Event event) {
        JsonNode payload = parse(event);
        UUID broadcastId = uuid(payload, "broadcastId");
        int chunk = integer(payload, "chunk");
        if (chunk < 0 || !event.eventKey().equals("broadcast:" + broadcastId + ":" + chunk))
            throw new IllegalArgumentException("Invalid notification broadcast key");

        JsonNode recipientNodes = payload.path("recipients");
        if (!recipientNodes.isArray()
                || recipientNodes.isEmpty()
                || recipientNodes.size() > MAX_RECIPIENTS_PER_EVENT)
            throw new IllegalArgumentException("Invalid notification recipients");
        LinkedHashSet<UUID> recipients = new LinkedHashSet<>();
        for (JsonNode node : recipientNodes) {
            UUID recipient = UUID.fromString(requiredText(node));
            if (!recipients.add(recipient))
                throw new IllegalArgumentException("Duplicate notification recipient");
        }

        String title = boundedText(payload, "title", 200);
        String body = boundedText(payload, "body", 4_000);
        String href = nullableText(payload, "href");
        if (href != null) href = NotificationHref.normalize(href);
        OffsetDateTime expiresAt = nullableExpiry(payload);
        if (expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now())) return;

        ObjectNode params = mapper.createObjectNode();
        params.put("title", title);
        params.put("body", body);
        String serialized = mapper.writeValueAsString(params);
        String normalizedHref = href;
        List<NotificationStore.CreateRequest> requests =
                recipients.stream()
                        .map(
                                recipient ->
                                        new NotificationStore.CreateRequest(
                                                recipient,
                                                event.id(),
                                                "broadcast:" + broadcastId + ":" + recipient,
                                                "broadcast",
                                                "notificationCustomTitle",
                                                "notificationCustomBody",
                                                serialized,
                                                normalizedHref,
                                                expiresAt))
                        .toList();
        store.createBatch(requests);
    }

    private JsonNode parse(Event event) {
        if (event == null
                || !sourceType().equals(event.sourceType())
                || !eventType().equals(event.eventType())
                || event.id() == null)
            throw new IllegalArgumentException("Invalid notification broadcast event");
        JsonNode payload = mapper.readTree(event.payload());
        if (payload == null || !payload.isObject())
            throw new IllegalArgumentException("Invalid notification broadcast payload");
        return payload;
    }

    private static UUID uuid(JsonNode payload, String name) {
        return UUID.fromString(requiredText(payload.path(name)));
    }

    private static int integer(JsonNode payload, String name) {
        JsonNode value = payload.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt())
            throw new IllegalArgumentException("Invalid notification broadcast payload");
        return value.intValue();
    }

    private static String boundedText(JsonNode payload, String name, int max) {
        String value = requiredText(payload.path(name));
        String normalized = value.strip();
        if (normalized.isEmpty()
                || normalized.length() > max
                || normalized.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid notification broadcast payload");
        return normalized;
    }

    private static String nullableText(JsonNode payload, String name) {
        JsonNode value = payload.path(name);
        if (value.isMissingNode() || value.isNull()) return null;
        return requiredText(value);
    }

    private static OffsetDateTime nullableExpiry(JsonNode payload) {
        String value = nullableText(payload, "expiresAt");
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException invalid) {
            throw new IllegalArgumentException("Invalid notification expiry", invalid);
        }
    }

    private static String requiredText(JsonNode value) {
        if (value == null || !value.isTextual())
            throw new IllegalArgumentException("Invalid notification broadcast payload");
        return value.textValue();
    }
}
