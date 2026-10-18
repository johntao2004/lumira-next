package com.lumira.platform.ai;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Stores the published messages of a user-shared AI conversation. */
@Service
public class AiConversationShareService {
    public record Message(String role, String content) {}

    public record CreateResult(UUID id, String token, String path, OffsetDateTime createdAt) {}

    public record ShareSummary(UUID id, String title, OffsetDateTime createdAt) {}

    public record SharedConversation(
            String title, List<Message> messages, OffsetDateTime createdAt) {
        public SharedConversation {
            messages = List.copyOf(messages);
        }
    }

    private static final int MAX_TITLE_CODE_POINTS = 200;
    private static final int MAX_MESSAGES = 200;
    private static final int MAX_MESSAGE_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_CONTENT_BYTES = 512 * 1024;
    private static final Duration SHARE_TTL = Duration.ofDays(30);
    private static final String TOKEN_PATTERN = "[A-Za-z0-9_-]{43}";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<PlatformOperation> CHAT_OPERATIONS =
            List.of(
                    PlatformOperation.AI_CONSOLE_READ_USERS,
                    PlatformOperation.AI_CONSOLE_CREATE_USER,
                    PlatformOperation.AI_CONSOLE_UPDATE_USER,
                    PlatformOperation.AI_CONSOLE_DELETE_USER);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AdministrationPort guard;
    private final AuditPort audit;

    public AiConversationShareService(
            JdbcClient jdbc, ObjectMapper mapper, AdministrationPort guard, AuditPort audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.guard = guard;
        this.audit = audit;
    }

    @Transactional
    public CreateResult create(JsonNode input, String actor) {
        requireChatPrivilege(actor);

        Snapshot snapshot = validate(input);
        UUID id = UUID.randomUUID();
        String token = newToken();
        String tokenHash = hash(token);
        OffsetDateTime createdAt =
                jdbc.sql(
                                """
                                INSERT INTO platform_ai_conversation_share(
                                    id,token_hash,title,messages,created_by)
                                VALUES(:id,:tokenHash,:title,CAST(:messages AS jsonb),:createdBy)
                                RETURNING created_at
                                """)
                        .param("id", id)
                        .param("tokenHash", tokenHash)
                        .param("title", snapshot.title())
                        .param("messages", mapper.writeValueAsString(snapshot.messages()))
                        .param("createdBy", actor)
                        .query((row, index) -> row.getObject("created_at", OffsetDateTime.class))
                        .single();
        audit.append(actor, "ai.conversation.shared", id);
        return new CreateResult(id, token, "/share/ai/" + token, createdAt);
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<ShareSummary> list(PageRequest page, String actor) {
        requireChatPrivilege(actor);
        List<ShareSummary> items =
                jdbc.sql(
                                """
                                SELECT share.id,share.title,share.created_at
                                  FROM platform_ai_conversation_share share
                                  JOIN platform_user creator
                                    ON creator.username=share.created_by
                                   AND creator.enabled=true
                                   AND creator.deleted_at IS NULL
                                 WHERE share.created_by=:actor
                                   AND share.created_at>:notBefore
                                 ORDER BY share.created_at DESC,share.id DESC
                                 LIMIT :pageSize OFFSET :offset
                                """)
                        .param("actor", actor)
                        .param("notBefore", shareNotBefore())
                        .param("pageSize", page.pageSize())
                        .param("offset", page.offset())
                        .query(
                                (row, index) ->
                                        new ShareSummary(
                                                row.getObject("id", UUID.class),
                                                row.getString("title"),
                                                row.getObject("created_at", OffsetDateTime.class)))
                        .list();
        long total =
                jdbc.sql(
                                """
                                SELECT count(*)
                                  FROM platform_ai_conversation_share share
                                  JOIN platform_user creator
                                    ON creator.username=share.created_by
                                   AND creator.enabled=true
                                   AND creator.deleted_at IS NULL
                                 WHERE share.created_by=:actor
                                   AND share.created_at>:notBefore
                                """)
                        .param("actor", actor)
                        .param("notBefore", shareNotBefore())
                        .query(Long.class)
                        .single();
        return new PageResult<>(items, total, page.page(), page.pageSize());
    }

    @Transactional
    public void update(UUID id, JsonNode input, String actor) {
        requireChatPrivilege(actor);
        Snapshot snapshot = validate(input);
        // Updating only the existing owner row can never recreate a revoked link.
        var updated =
                jdbc.sql(
                                """
                                UPDATE platform_ai_conversation_share AS share
                                   SET title=:title,messages=CAST(:messages AS jsonb)
                                 WHERE share.id=:id
                                   AND share.created_by=:actor
                                   AND share.created_at>:notBefore
                                   AND EXISTS (
                                        SELECT 1 FROM platform_user creator
                                         WHERE creator.username=share.created_by
                                           AND creator.enabled=true
                                           AND creator.deleted_at IS NULL)
                                RETURNING share.id
                                """)
                        .param("title", snapshot.title())
                        .param("messages", mapper.writeValueAsString(snapshot.messages()))
                        .param("id", id)
                        .param("actor", actor)
                        .param("notBefore", shareNotBefore())
                        .query((row, index) -> row.getObject("id", UUID.class))
                        .optional()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "aiShareNotFound"));
        audit.append(actor, "ai.conversation.share-updated", updated);
    }

    @Transactional
    public void revoke(UUID id, String actor) {
        requireChatPrivilege(actor);
        UUID deletedId =
                jdbc.sql(
                                """
                                DELETE FROM platform_ai_conversation_share AS share
                                 WHERE share.id=:id
                                   AND share.created_by=:actor
                                   AND share.created_at>:notBefore
                                   AND EXISTS (
                                        SELECT 1 FROM platform_user creator
                                         WHERE creator.username=share.created_by
                                           AND creator.enabled=true
                                           AND creator.deleted_at IS NULL)
                                RETURNING share.id
                                """)
                        .param("id", id)
                        .param("actor", actor)
                        .param("notBefore", shareNotBefore())
                        .query((row, index) -> row.getObject("id", UUID.class))
                        .optional()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "aiShareNotFound"));
        audit.append(actor, "ai.conversation.share-revoked", deletedId);
    }

    @Transactional(readOnly = true)
    public SharedConversation read(String token) {
        if (token == null || !token.matches(TOKEN_PATTERN)) notFound();
        return jdbc.sql(
                        """
                        SELECT share.title,share.messages::text AS messages_json,share.created_at
                          FROM platform_ai_conversation_share share
                          JOIN platform_user creator
                            ON creator.username=share.created_by
                           AND creator.enabled=true
                           AND creator.deleted_at IS NULL
                         WHERE share.token_hash=:tokenHash
                           AND share.created_at>:notBefore
                        """)
                .param("tokenHash", hash(token))
                .param("notBefore", shareNotBefore())
                .query(
                        (row, index) ->
                                new SharedConversation(
                                        row.getString("title"),
                                        readMessages(row.getString("messages_json")),
                                        row.getObject("created_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "aiShareNotFound"));
    }

    /**
     * A share is an explicit client snapshot. It stays readable for {@link #SHARE_TTL} only while
     * its creator account is enabled and not deleted.
     */
    static boolean shareVisible(
            OffsetDateTime createdAt, Boolean enabled, OffsetDateTime deletedAt, OffsetDateTime now) {
        return createdAt != null
                && Boolean.TRUE.equals(enabled)
                && deletedAt == null
                && now != null
                && createdAt.isAfter(now.minus(SHARE_TTL));
    }

    private static OffsetDateTime shareNotBefore() {
        return OffsetDateTime.now(ZoneOffset.UTC).minus(SHARE_TTL);
    }

    private boolean hasOperation(String actor, PlatformOperation operation) {
        try {
            guard.requireOperation(actor, operation);
            return true;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.FORBIDDEN) return false;
            throw exception;
        }
    }

    private void requireChatPrivilege(String actor) {
        guard.requirePage(actor, PlatformPageId.AI_CONSOLE);
        if (CHAT_OPERATIONS.stream().noneMatch(operation -> hasOperation(actor, operation)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }

    private Snapshot validate(JsonNode input) {
        if (input == null
                || !input.isObject()
                || input.size() != 2
                || !input.has("title")
                || !input.has("messages")
                || !input.get("title").isString()
                || !input.get("messages").isArray()) invalid();

        String title = input.get("title").textValue();
        if (title == null
                || title.isBlank()
                || title.codePointCount(0, title.length()) > MAX_TITLE_CODE_POINTS) invalid();

        JsonNode messagesNode = input.get("messages");
        if (messagesNode.isEmpty() || messagesNode.size() > MAX_MESSAGES) invalid();
        List<Message> messages = new ArrayList<>(messagesNode.size());
        long totalBytes = 0;
        for (JsonNode message : messagesNode) {
            if (message == null
                    || !message.isObject()
                    || message.size() != 2
                    || !message.has("role")
                    || !message.has("content")
                    || !message.get("role").isString()
                    || !message.get("content").isString()) invalid();
            String role = message.get("role").textValue();
            String content = message.get("content").textValue();
            if (!("user".equals(role) || "assistant".equals(role)) || content == null) invalid();
            int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (contentBytes > MAX_MESSAGE_BYTES) tooLarge();
            totalBytes += contentBytes;
            if (totalBytes > MAX_TOTAL_CONTENT_BYTES) tooLarge();
            messages.add(new Message(role, content));
        }
        return new Snapshot(title, List.copyOf(messages));
    }

    private List<Message> readMessages(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isArray())
                throw new IllegalStateException("Stored AI share messages are invalid");
            var messages = new ArrayList<Message>(node.size());
            for (JsonNode message : node) {
                if (!message.isObject()
                        || message.size() != 2
                        || !message.has("role")
                        || !message.has("content")
                        || !message.get("role").isString()
                        || !message.get("content").isString())
                    throw new IllegalStateException("Stored AI share message is invalid");
                String role = message.get("role").textValue();
                String content = message.get("content").textValue();
                if (!("user".equals(role) || "assistant".equals(role)) || content == null)
                    throw new IllegalStateException("Stored AI share message is invalid");
                messages.add(new Message(role, content));
            }
            return List.copyOf(messages);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException) throw exception;
            throw new IllegalStateException("Stored AI share messages are invalid", exception);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidAiShare");
    }

    private static void tooLarge() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "aiShareTooLarge");
    }

    private static void notFound() {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "aiShareNotFound");
    }

    private record Snapshot(String title, List<Message> messages) {}
}
