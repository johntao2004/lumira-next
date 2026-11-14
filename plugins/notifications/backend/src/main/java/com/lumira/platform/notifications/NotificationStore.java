package com.lumira.platform.notifications;

import com.lumira.platform.common.JdbcPageQuery;
import com.lumira.platform.common.PageFilterSql;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Account-scoped in-app notifications. Producers supply a stable dedupe key and localized message
 * keys.
 */
@Repository
public class NotificationStore {
    public record Entry(
            UUID id,
            String kind,
            String titleKey,
            String bodyKey,
            String params,
            String href,
            boolean read,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {}

    public record AdminEntry(
            UUID id,
            UUID recipientId,
            String ownerUsername,
            String ownerDisplayName,
            String kind,
            String titleKey,
            String bodyKey,
            String params,
            String href,
            boolean read,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {}

    /** The minimal account data needed to select a notification recipient. */
    public record RecipientOption(UUID id, String username, String displayName, boolean enabled) {}

    public record CreateRequest(
            UUID recipientId,
            UUID eventId,
            String dedupeKey,
            String kind,
            String titleKey,
            String bodyKey,
            String params,
            String href,
            OffsetDateTime expiresAt) {}

    public record BatchResult(
            int expectedCount,
            int inboxCreated,
            Map<String, Integer> skippedByReason,
            String resultCode) {
        public BatchResult {
            skippedByReason = skippedByReason == null ? Map.of() : Map.copyOf(skippedByReason);
        }

        public int inboxSkipped() {
            return skippedByReason.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private record RecipientKey(UUID recipientId, String dedupeKey) {}

    private record RecipientState(boolean enabled, boolean deleted) {}

    private final JdbcClient jdbc;
    private final JdbcTemplate batchJdbc;
    private final ObjectMapper mapper;

    public NotificationStore(JdbcClient jdbc, JdbcTemplate batchJdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.batchJdbc = batchJdbc;
        this.mapper = mapper;
    }

    @Transactional
    public boolean create(
            UUID recipientId,
            UUID eventId,
            String dedupeKey,
            String kind,
            String titleKey,
            String bodyKey,
            String params,
            String href) {
        return create(recipientId, eventId, dedupeKey, kind, titleKey, bodyKey, params, href, null);
    }

    @Transactional
    public boolean create(
            UUID recipientId,
            UUID eventId,
            String dedupeKey,
            String kind,
            String titleKey,
            String bodyKey,
            String params,
            String href,
            OffsetDateTime expiresAt) {
        Objects.requireNonNull(recipientId);
        validateKey(dedupeKey, 191, "dedupe");
        validateKey(kind, 80, "kind");
        validateKey(titleKey, 128, "title");
        validateKey(bodyKey, 128, "body");
        String normalized = normalizeParams(params);
        String normalizedHref = NotificationHref.normalize(href);
        return jdbc.sql(
                                """
                                INSERT INTO platform_notification(id,recipient_id,event_id,dedupe_key,kind,title_key,body_key,params,href,expires_at)
                                VALUES(:id,:recipient,:event,:dedupe,:kind,:title,:body,CAST(:params AS jsonb),:href,:expiresAt)
                                ON CONFLICT (recipient_id,dedupe_key) DO NOTHING
                                """)
                        .param("id", UUID.randomUUID())
                        .param("recipient", recipientId)
                        .param("event", eventId)
                        .param("dedupe", dedupeKey)
                        .param("kind", kind)
                        .param("title", titleKey)
                        .param("body", bodyKey)
                        .param("params", normalized)
                        .param("href", normalizedHref)
                        .param("expiresAt", expiresAt)
                        .update()
                == 1;
    }

    /** Insert a bounded batch while preserving per-recipient deduplication. */
    @Transactional
    public int createBatch(List<CreateRequest> requests) {
        return createBatchWithResult(requests).inboxCreated();
    }

    /** Insert a bounded batch and return safe, transactionally accurate skip aggregates. */
    @Transactional
    public BatchResult createBatchWithResult(List<CreateRequest> requests) {
        if (requests == null || requests.isEmpty())
            return new BatchResult(0, 0, Map.of(), "DELIVERED");
        List<String> normalizedParams = new ArrayList<>(requests.size());
        List<String> normalizedHrefs = new ArrayList<>(requests.size());
        LinkedHashSet<UUID> recipientIds = new LinkedHashSet<>();
        for (CreateRequest request : requests) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(request.recipientId());
            validateKey(request.dedupeKey(), 191, "dedupe");
            validateKey(request.kind(), 80, "kind");
            validateKey(request.titleKey(), 128, "title");
            validateKey(request.bodyKey(), 128, "body");
            normalizedParams.add(normalizeParams(request.params()));
            normalizedHrefs.add(NotificationHref.normalize(request.href()));
            recipientIds.add(request.recipientId());
        }
        Map<UUID, RecipientState> recipients =
                jdbc
                        .sql(
                                "SELECT id,enabled,(deleted_at IS NOT NULL) AS deleted FROM"
                                        + " platform_user WHERE id IN (:ids) FOR SHARE")
                        .param("ids", recipientIds)
                        .query(
                                (rs, n) ->
                                        Map.entry(
                                                rs.getObject("id", UUID.class),
                                                new RecipientState(
                                                        rs.getBoolean("enabled"),
                                                        rs.getBoolean("deleted"))))
                        .list()
                        .stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey, Map.Entry::getValue));
        Set<RecipientKey> existing = existingDedupeKeys(requests);
        Map<String, Integer> skipped = new TreeMap<>();
        List<CreateRequest> eligible = new ArrayList<>();
        List<Integer> eligibleRequestIndexes = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (int index = 0; index < requests.size(); index++) {
            CreateRequest request = requests.get(index);
            String reason = null;
            if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
                reason = "EXPIRED";
            } else {
                RecipientState recipient = recipients.get(request.recipientId());
                if (recipient == null || recipient.deleted()) reason = "RECIPIENT_DELETED";
                else if (!recipient.enabled()) reason = "RECIPIENT_INACTIVE";
                else if (existing.contains(
                        new RecipientKey(request.recipientId(), request.dedupeKey())))
                    reason = "DUPLICATE";
            }
            if (reason == null) {
                eligible.add(request);
                eligibleRequestIndexes.add(index);
            } else {
                skipped.merge(reason, 1, Integer::sum);
            }
        }
        int created = 0;
        for (int offset = 0; offset < eligible.size(); offset += 500) {
            int chunkOffset = offset;
            List<CreateRequest> chunk =
                    eligible.subList(chunkOffset, Math.min(chunkOffset + 500, eligible.size()));
            int[] results =
                    batchJdbc.batchUpdate(
                            """
                            INSERT INTO platform_notification(id,recipient_id,event_id,dedupe_key,kind,title_key,body_key,params,href,expires_at)
                            SELECT ?,?,?,?,?,?, ?,CAST(? AS jsonb),?,?
                            WHERE EXISTS (
                              SELECT 1 FROM platform_user u
                              WHERE u.id=? AND u.enabled=true AND u.deleted_at IS NULL
                              FOR SHARE OF u
                            )
                            AND (CAST(? AS timestamptz) IS NULL OR CAST(? AS timestamptz)>now())
                            ON CONFLICT (recipient_id,dedupe_key) DO NOTHING
                            """,
                            new BatchPreparedStatementSetter() {
                                @Override
                                public void setValues(
                                        java.sql.PreparedStatement statement, int index)
                                        throws java.sql.SQLException {
                                    CreateRequest request = chunk.get(index);
                                    statement.setObject(1, UUID.randomUUID());
                                    statement.setObject(2, request.recipientId());
                                    statement.setObject(3, request.eventId());
                                    statement.setString(4, request.dedupeKey());
                                    statement.setString(5, request.kind());
                                    statement.setString(6, request.titleKey());
                                    statement.setString(7, request.bodyKey());
                                    int requestIndex =
                                            eligibleRequestIndexes.get(chunkOffset + index);
                                    statement.setString(8, normalizedParams.get(requestIndex));
                                    statement.setString(9, normalizedHrefs.get(requestIndex));
                                    statement.setObject(10, request.expiresAt());
                                    statement.setObject(11, request.recipientId());
                                    statement.setObject(12, request.expiresAt());
                                    statement.setObject(13, request.expiresAt());
                                }

                                @Override
                                public int getBatchSize() {
                                    return chunk.size();
                                }
                            });
            for (int index = 0; index < results.length; index++) {
                int result = results[index];
                if (result == 1 || result == java.sql.Statement.SUCCESS_NO_INFO) {
                    created++;
                } else {
                    CreateRequest request = chunk.get(index);
                    String reason =
                            request.expiresAt() != null
                                            && !request.expiresAt().isAfter(OffsetDateTime.now())
                                    ? "EXPIRED"
                                    : "DUPLICATE";
                    skipped.merge(reason, 1, Integer::sum);
                }
            }
        }
        String resultCode =
                created > 0 && !skipped.isEmpty()
                        ? "PARTIAL"
                        : created > 0
                                ? "DELIVERED"
                                : skipped.keySet().equals(Set.of("EXPIRED"))
                                        ? "EXPIRED_ALL"
                                        : skipped.keySet().equals(Set.of("DUPLICATE"))
                                                ? "DUPLICATE_ONLY"
                                                : "ALL_SKIPPED";
        return new BatchResult(requests.size(), created, skipped, resultCode);
    }

    private Set<RecipientKey> existingDedupeKeys(List<CreateRequest> requests) {
        Set<RecipientKey> existing = new HashSet<>();
        for (int offset = 0; offset < requests.size(); offset += 500) {
            List<CreateRequest> chunk =
                    requests.subList(offset, Math.min(offset + 500, requests.size()));
            StringBuilder values = new StringBuilder();
            Map<String, Object> params = new HashMap<>();
            for (int index = 0; index < chunk.size(); index++) {
                if (index > 0) values.append(',');
                values.append("(CAST(:recipient")
                        .append(index)
                        .append(" AS uuid),:dedupe")
                        .append(index)
                        .append(')');
                params.put("recipient" + index, chunk.get(index).recipientId());
                params.put("dedupe" + index, chunk.get(index).dedupeKey());
            }
            jdbc.sql(
                            "WITH requested(recipient_id,dedupe_key) AS (VALUES "
                                    + values
                                    + ") SELECT n.recipient_id,n.dedupe_key FROM"
                                    + " platform_notification n JOIN requested q"
                                    + " USING(recipient_id,dedupe_key)")
                    .params(params)
                    .query(
                            (rs, n) ->
                                    new RecipientKey(
                                            rs.getObject("recipient_id", UUID.class),
                                            rs.getString("dedupe_key")))
                    .list()
                    .forEach(existing::add);
        }
        return existing;
    }

    @Transactional(readOnly = true)
    public PageResult<Entry> list(UUID recipientId, PageRequest page, boolean unreadOnly) {
        return list(recipientId, page, unreadOnly, null);
    }

    /** Explicit read state takes precedence; legacy unread=false continues to return all rows. */
    @Transactional(readOnly = true)
    public PageResult<Entry> list(
            UUID recipientId, PageRequest page, boolean unreadOnly, Boolean read) {
        Objects.requireNonNull(recipientId);
        Boolean readState = read != null ? read : unreadOnly ? Boolean.FALSE : null;
        String filter =
                " WHERE recipient_id=:recipient AND (expires_at IS NULL OR expires_at>now())";
        if (readState != null)
            filter += readState ? " AND read_at IS NOT NULL" : " AND read_at IS NULL";
        return JdbcPageQuery.queryWindowed(
                jdbc,
                page,
                "id,kind,title_key,body_key,params::text,href,(read_at IS NOT NULL) AS"
                        + " read,created_at,expires_at",
                " FROM platform_notification" + filter,
                "created_at DESC,id DESC",
                Map.of("recipient", recipientId),
                (rs, n) ->
                        new Entry(
                                rs.getObject("id", UUID.class),
                                rs.getString("kind"),
                                rs.getString("title_key"),
                                rs.getString("body_key"),
                                rs.getString("params"),
                                NotificationHref.safeOrNull(rs.getString("href")),
                                rs.getBoolean("read"),
                                rs.getObject("created_at", OffsetDateTime.class),
                                rs.getObject("expires_at", OffsetDateTime.class)));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID recipientId) {
        return jdbc.sql(
                        "SELECT count(*) FROM platform_notification WHERE recipient_id=:recipient"
                                + " AND read_at IS NULL AND (expires_at IS NULL OR expires_at>now())")
                .param("recipient", recipientId)
                .query(Long.class)
                .single();
    }

    @Transactional
    public boolean markRead(UUID recipientId, UUID id) {
        return jdbc.sql(
                                "UPDATE platform_notification SET read_at=COALESCE(read_at,now())"
                                        + " WHERE id=:id AND recipient_id=:recipient AND read_at IS"
                                        + " NULL")
                        .param("id", id)
                        .param("recipient", recipientId)
                        .update()
                == 1;
    }

    @Transactional
    public int markAllRead(UUID recipientId) {
        return jdbc.sql(
                        "UPDATE platform_notification SET read_at=now() WHERE"
                                + " recipient_id=:recipient AND read_at IS NULL AND (expires_at IS NULL"
                                + " OR expires_at>now())")
                .param("recipient", recipientId)
                .update();
    }

    @Transactional(readOnly = true)
    public PageResult<AdminEntry> listAdmin(PageRequest page) {
        String filter =
                " WHERE (:search='' OR strpos(lower(u.username),lower(:search))>0 OR"
                        + " strpos(lower(u.display_name),lower(:search))>0 OR"
                        + " strpos(lower(n.title_key),lower(:search))>0 OR"
                        + " strpos(lower(n.body_key),lower(:search))>0)";
        var fieldFilter =
                PageFilterSql.build(
                        page,
                        Map.of(
                                "ownerUsername",
                                "CASE WHEN u.username IS NULL THEN '—' ELSE"
                                        + " coalesce(u.display_name,u.username) || ' (' || u.username"
                                        + " || ')' END",
                                "title",
                                "coalesce(n.params->>'title',n.title_key)",
                                "body",
                                "coalesce(n.params->>'body',n.body_key)",
                                "read",
                                "(n.read_at IS NOT NULL)",
                                "createdAt",
                                "n.created_at"),
                        java.util.Set.of("createdAt"));
        return JdbcPageQuery.query(
                jdbc,
                page,
                "platform_notification n JOIN platform_user u ON u.id=n.recipient_id",
                "n.id,n.recipient_id,u.username,u.display_name,n.kind,n.title_key,n.body_key,"
                        + "n.params::text,n.href,(n.read_at IS NOT NULL) AS"
                        + " read,n.created_at,n.expires_at",
                filter,
                fieldFilter,
                Map.of(),
                "n.created_at DESC,n.id DESC",
                (rs, n) ->
                        new AdminEntry(
                                rs.getObject("id", UUID.class),
                                rs.getObject("recipient_id", UUID.class),
                                rs.getString("username"),
                                rs.getString("display_name"),
                                rs.getString("kind"),
                                rs.getString("title_key"),
                                rs.getString("body_key"),
                                rs.getString("params"),
                                NotificationHref.safeOrNull(rs.getString("href")),
                                rs.getBoolean("read"),
                                rs.getObject("created_at", OffsetDateTime.class),
                                rs.getObject("expires_at", OffsetDateTime.class)));
    }

    @Transactional(readOnly = true)
    public PageResult<RecipientOption> listRecipientOptions(PageRequest page) {
        String fromAndFilter =
                " FROM platform_user u WHERE u.enabled=true AND u.deleted_at IS NULL AND"
                        + " (:search='' OR strpos(lower(coalesce(u.username,'')),lower(:search))>0 OR"
                        + " strpos(lower(coalesce(u.display_name,'')),lower(:search))>0)";
        return JdbcPageQuery.queryWindowed(
                jdbc,
                page,
                "u.id,u.username,u.display_name,u.enabled",
                fromAndFilter,
                "lower(coalesce(u.display_name,'')),lower(u.username),u.id",
                Map.of("search", page.search()),
                this::mapRecipientOption);
    }

    @Transactional(readOnly = true)
    public Optional<RecipientOption> recipientOption(UUID id) {
        Objects.requireNonNull(id);
        return jdbc.sql(
                        "SELECT id,username,display_name,enabled FROM platform_user"
                                + " WHERE id=:id AND enabled=true AND deleted_at IS NULL")
                .param("id", id)
                .query(this::mapRecipientOption)
                .optional();
    }

    private RecipientOption mapRecipientOption(java.sql.ResultSet rs, int row)
            throws java.sql.SQLException {
        return new RecipientOption(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getBoolean("enabled"));
    }

    String normalizeParams(String value) {
        if (value == null) throw new IllegalArgumentException("Invalid notification params");
        final tools.jackson.databind.JsonNode node;
        try {
            node = mapper.readTree(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid notification params");
        }
        if (node == null || node.isNull())
            throw new IllegalArgumentException("Invalid notification params");
        byte[] bytes = mapper.writeValueAsBytes(node);
        try {
            if (bytes.length < 2 || bytes.length > 8192)
                throw new IllegalArgumentException("Notification params too large");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void validateKey(String value, int max, String label) {
        if (!isValidKey(value, max))
            throw new IllegalArgumentException("Invalid notification " + label);
    }

    private static boolean isValidKey(String value, int max) {
        if (value == null || value.isEmpty() || value.length() > max) {
            return false;
        }
        char first = value.charAt(0);
        if (!((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z'))) {
            return false;
        }
        for (int i = 1, len = value.length(); i < len; i++) {
            char ch = value.charAt(i);
            boolean ok =
                    (ch >= 'A' && ch <= 'Z')
                            || (ch >= 'a' && ch <= 'z')
                            || (ch >= '0' && ch <= '9')
                            || ch == '.'
                            || ch == '_'
                            || ch == ':'
                            || ch == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
