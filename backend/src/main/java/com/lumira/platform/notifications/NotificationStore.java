package com.lumira.platform.notifications;

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
        if (requests == null || requests.isEmpty()) return 0;
        List<String> normalizedParams = new ArrayList<>(requests.size());
        List<String> normalizedHrefs = new ArrayList<>(requests.size());
        for (CreateRequest request : requests) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(request.recipientId());
            validateKey(request.dedupeKey(), 191, "dedupe");
            validateKey(request.kind(), 80, "kind");
            validateKey(request.titleKey(), 128, "title");
            validateKey(request.bodyKey(), 128, "body");
            normalizedParams.add(normalizeParams(request.params()));
            normalizedHrefs.add(NotificationHref.normalize(request.href()));
        }
        int created = 0;
        for (int offset = 0; offset < requests.size(); offset += 500) {
            int chunkOffset = offset;
            List<CreateRequest> chunk =
                    requests.subList(chunkOffset, Math.min(chunkOffset + 500, requests.size()));
            int[] results =
                    batchJdbc.batchUpdate(
                            """
                INSERT INTO platform_notification(id,recipient_id,event_id,dedupe_key,kind,title_key,body_key,params,href,expires_at)
                VALUES(?,?,?,?,?,?,?,CAST(? AS jsonb),?,?)
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
                                    statement.setString(
                                            8, normalizedParams.get(chunkOffset + index));
                                    statement.setString(
                                            9, normalizedHrefs.get(chunkOffset + index));
                                    statement.setObject(10, request.expiresAt());
                                }

                                @Override
                                public int getBatchSize() {
                                    return chunk.size();
                                }
                            });
            for (int result : results) created += result;
        }
        return created;
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
        var rows =
                jdbc.sql(
                                "SELECT id,kind,title_key,body_key,params::text,href,(read_at IS NOT NULL) AS read,created_at,expires_at FROM platform_notification"
                                        + filter
                                        + " ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset")
                        .param("recipient", recipientId)
                        .param("limit", page.pageSize())
                        .param("offset", page.offset())
                        .query(
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
                                                rs.getObject("expires_at", OffsetDateTime.class)))
                        .list();
        var total =
                jdbc.sql("SELECT count(*) FROM platform_notification" + filter)
                        .param("recipient", recipientId)
                        .query(Long.class)
                        .single();
        return new PageResult<>(rows, total, page.page(), page.pageSize());
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID recipientId) {
        return jdbc.sql(
                        "SELECT count(*) FROM platform_notification WHERE recipient_id=:recipient AND read_at IS NULL AND (expires_at IS NULL OR expires_at>now())")
                .param("recipient", recipientId)
                .query(Long.class)
                .single();
    }

    @Transactional
    public boolean markRead(UUID recipientId, UUID id) {
        return jdbc.sql(
                                "UPDATE platform_notification SET read_at=COALESCE(read_at,now()) WHERE id=:id AND recipient_id=:recipient AND read_at IS NULL")
                        .param("id", id)
                        .param("recipient", recipientId)
                        .update()
                == 1;
    }

    @Transactional
    public int markAllRead(UUID recipientId) {
        return jdbc.sql(
                        "UPDATE platform_notification SET read_at=now() WHERE recipient_id=:recipient AND read_at IS NULL AND (expires_at IS NULL OR expires_at>now())")
                .param("recipient", recipientId)
                .update();
    }

    @Transactional(readOnly = true)
    public PageResult<AdminEntry> listAdmin(PageRequest page) {
        String filter =
                " WHERE (:search='' OR strpos(lower(u.username),lower(:search))>0 OR strpos(lower(u.display_name),lower(:search))>0 OR strpos(lower(n.title_key),lower(:search))>0 OR strpos(lower(n.body_key),lower(:search))>0)";
        var fieldFilter =
                PageFilterSql.build(
                        page,
                        Map.of(
                                "ownerUsername",
                                "CASE WHEN u.username IS NULL THEN '—' ELSE coalesce(u.display_name,u.username) || ' (' || u.username || ')' END",
                                "title",
                                "coalesce(n.params->>'title',n.title_key)",
                                "body",
                                "coalesce(n.params->>'body',n.body_key)",
                                "read",
                                "(n.read_at IS NOT NULL)",
                                "createdAt",
                                "n.created_at"));
        var countParams = new HashMap<String, Object>();
        countParams.put("search", page.search());
        countParams.putAll(fieldFilter.parameters());
        var rowParams = new HashMap<>(countParams);
        rowParams.put("limit", page.pageSize());
        rowParams.put("offset", page.offset());
        var rows =
                jdbc.sql(
                                "SELECT n.id,n.recipient_id,u.username,u.display_name,n.kind,n.title_key,n.body_key,n.params::text,n.href,(n.read_at IS NOT NULL) AS read,n.created_at,n.expires_at FROM platform_notification n JOIN platform_user u ON u.id=n.recipient_id"
                                        + filter
                                        + fieldFilter.sql()
                                        + " ORDER BY n.created_at DESC,n.id DESC LIMIT :limit OFFSET :offset")
                        .params(rowParams)
                        .query(
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
                                                rs.getObject("expires_at", OffsetDateTime.class)))
                        .list();
        var total =
                jdbc.sql(
                                "SELECT count(*) FROM platform_notification n JOIN platform_user u ON u.id=n.recipient_id"
                                        + filter
                                        + fieldFilter.sql())
                        .params(countParams)
                        .query(Long.class)
                        .single();
        return new PageResult<>(rows, total, page.page(), page.pageSize());
    }

    @Transactional(readOnly = true)
    public PageResult<RecipientOption> listRecipientOptions(PageRequest page) {
        String fromAndFilter =
                " FROM platform_user u WHERE u.enabled=true AND u.deleted_at IS NULL"
                        + " AND (:search='' OR strpos(lower(coalesce(u.username,'')),lower(:search))>0"
                        + " OR strpos(lower(coalesce(u.display_name,'')),lower(:search))>0)";
        var parameters =
                Map.<String, Object>of(
                        "search", page.search(), "limit", page.pageSize(), "offset", page.offset());
        var rows =
                jdbc.sql(
                                "SELECT u.id,u.username,u.display_name,u.enabled"
                                        + fromAndFilter
                                        + " ORDER BY lower(coalesce(u.display_name,'')),lower(u.username),u.id"
                                        + " LIMIT :limit OFFSET :offset")
                        .params(parameters)
                        .query(this::mapRecipientOption)
                        .list();
        long total =
                jdbc.sql("SELECT count(*)" + fromAndFilter)
                        .param("search", page.search())
                        .query(Long.class)
                        .single();
        return new PageResult<>(rows, total, page.page(), page.pageSize());
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

    private String normalizeParams(String value) {
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
        if (value == null
                || value.length() > max
                || !value.matches("[A-Za-z][A-Za-z0-9._:-]{0," + (max - 1) + "}"))
            throw new IllegalArgumentException("Invalid notification " + label);
    }
}
