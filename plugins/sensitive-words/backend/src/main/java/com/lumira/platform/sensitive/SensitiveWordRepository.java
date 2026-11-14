package com.lumira.platform.sensitive;

import com.lumira.platform.common.PageRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class SensitiveWordRepository {
    public record Word(
            UUID id,
            String word,
            String normalizedWord,
            String category,
            String severity,
            String action,
            boolean enabled,
            long version,
            String createdBy,
            String updatedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record ActiveEntry(String word, String normalizedWord, String severity, String action) {}

    public record MatcherSnapshot(long revision, List<ActiveEntry> entries) {}

    private static final String SELECT =
            "SELECT id,word,normalized_word,category,severity,action,enabled,version,created_by,updated_by,created_at,updated_at FROM platform_sensitive_word";
    private final JdbcClient jdbc;
    private final TransactionTemplate matcherSnapshotTransaction;

    public SensitiveWordRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.matcherSnapshotTransaction = new TransactionTemplate(transactionManager);
        this.matcherSnapshotTransaction.setReadOnly(true);
        this.matcherSnapshotTransaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.matcherSnapshotTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private Word row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Word(
                rs.getObject("id", UUID.class),
                rs.getString("word"),
                rs.getString("normalized_word"),
                rs.getString("category"),
                rs.getString("severity"),
                rs.getString("action"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                rs.getString("created_by"),
                rs.getString("updated_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static String filter(
            PageRequest page,
            String word,
            String category,
            String severity,
            String action,
            Boolean enabled) {
        var conditions = new ArrayList<String>();
        conditions.add("deleted=false");
        if (!page.search().isEmpty())
            conditions.add(
                    "(strpos(lower(word),lower(:search))>0 OR strpos(lower(category),lower(:search))>0)");
        if (word != null) conditions.add("strpos(lower(word),lower(:word))>0");
        if (category != null) conditions.add("category=:category");
        if (severity != null) conditions.add("severity=:severity");
        if (action != null) conditions.add("action=:action");
        if (enabled != null) conditions.add("enabled=:enabled");
        return " WHERE " + String.join(" AND ", conditions);
    }

    public long count(
            PageRequest page,
            String word,
            String category,
            String severity,
            String action,
            Boolean enabled) {
        var query =
                jdbc.sql(
                        "SELECT count(*) FROM platform_sensitive_word"
                                + filter(page, word, category, severity, action, enabled));
        if (!page.search().isEmpty()) query = query.param("search", page.search());
        if (word != null) query = query.param("word", word);
        if (category != null) query = query.param("category", category);
        if (severity != null) query = query.param("severity", severity);
        if (action != null) query = query.param("action", action);
        if (enabled != null) query = query.param("enabled", enabled);
        return query.query(Long.class).single();
    }

    public List<Word> list(
            PageRequest page,
            String word,
            String category,
            String severity,
            String action,
            Boolean enabled) {
        var query =
                jdbc.sql(
                                SELECT
                                        + filter(page, word, category, severity, action, enabled)
                                        + " ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset")
                        .param("limit", page.pageSize())
                        .param("offset", page.offset());
        if (!page.search().isEmpty()) query = query.param("search", page.search());
        if (word != null) query = query.param("word", word);
        if (category != null) query = query.param("category", category);
        if (severity != null) query = query.param("severity", severity);
        if (action != null) query = query.param("action", action);
        if (enabled != null) query = query.param("enabled", enabled);
        return query.query((rs, n) -> row(rs)).list();
    }

    public Word find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id=:id AND deleted=false")
                .param("id", id)
                .query((rs, n) -> row(rs))
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "sensitiveWordNotFound"));
    }

    public List<ActiveEntry> activeEntries() {
        return jdbc.sql(
                        "SELECT word,normalized_word,severity,action FROM platform_sensitive_word WHERE deleted=false AND enabled=true ORDER BY id")
                .query(
                        (rs, n) ->
                                new ActiveEntry(
                                        rs.getString("word"),
                                        rs.getString("normalized_word"),
                                        rs.getString("severity"),
                                        rs.getString("action")))
                .list();
    }

    /**
     * Returns only the revision when the local matcher is current, otherwise one consistent rule
     * snapshot.
     */
    public MatcherSnapshot matcherSnapshot(long knownRevision) {
        long currentRevision = revision();
        if (currentRevision == knownRevision)
            return new MatcherSnapshot(currentRevision, List.of());
        return matcherSnapshotTransaction.execute(
                status -> {
                    long snapshotRevision = revision();
                    if (snapshotRevision == knownRevision)
                        return new MatcherSnapshot(snapshotRevision, List.of());
                    return new MatcherSnapshot(snapshotRevision, activeEntries());
                });
    }

    private long revision() {
        return jdbc.sql(
                        "SELECT revision FROM platform_sensitive_word_revision WHERE singleton=true")
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("sensitiveWordRevisionMissing"));
    }

    public void create(
            UUID id,
            String word,
            String normalizedWord,
            String category,
            String severity,
            String action,
            boolean enabled,
            String actor) {
        jdbc.sql(
                        "INSERT INTO platform_sensitive_word(id,word,normalized_word,category,severity,action,enabled,created_by,updated_by) VALUES(:id,:word,:normalized,:category,:severity,:action,:enabled,:actor,:actor)")
                .param("id", id)
                .param("word", word)
                .param("normalized", normalizedWord)
                .param("category", category)
                .param("severity", severity)
                .param("action", action)
                .param("enabled", enabled)
                .param("actor", actor)
                .update();
    }

    public int update(
            UUID id,
            long version,
            String word,
            String normalizedWord,
            String category,
            String severity,
            String action,
            boolean enabled,
            String actor) {
        return jdbc.sql(
                        "UPDATE platform_sensitive_word SET word=:word,normalized_word=:normalized,category=:category,severity=:severity,action=:action,enabled=:enabled,updated_by=:actor,updated_at=now(),version=version+1 WHERE id=:id AND version=:version AND deleted=false")
                .param("id", id)
                .param("version", version)
                .param("word", word)
                .param("normalized", normalizedWord)
                .param("category", category)
                .param("severity", severity)
                .param("action", action)
                .param("enabled", enabled)
                .param("actor", actor)
                .update();
    }

    public int setEnabled(UUID id, long version, boolean enabled, String actor) {
        return jdbc.sql(
                        "UPDATE platform_sensitive_word SET enabled=:enabled,updated_by=:actor,updated_at=now(),version=version+1 WHERE id=:id AND version=:version AND deleted=false")
                .param("id", id)
                .param("version", version)
                .param("enabled", enabled)
                .param("actor", actor)
                .update();
    }

    public int softDelete(UUID id, long version, String actor) {
        return jdbc.sql(
                        "UPDATE platform_sensitive_word SET deleted=true,enabled=false,updated_by=:actor,updated_at=now(),version=version+1 WHERE id=:id AND version=:version AND deleted=false")
                .param("id", id)
                .param("version", version)
                .param("actor", actor)
                .update();
    }

    public boolean exists(String normalizedWord, UUID exceptId) {
        if (exceptId == null) {
            return jdbc.sql(
                            "SELECT EXISTS(SELECT 1 FROM platform_sensitive_word WHERE normalized_word=:normalized AND deleted=false)")
                    .param("normalized", normalizedWord)
                    .query(Boolean.class)
                    .single();
        }
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_sensitive_word WHERE normalized_word=:normalized AND deleted=false AND id<>:exceptId)")
                .param("normalized", normalizedWord)
                .param("exceptId", exceptId)
                .query(Boolean.class)
                .single();
    }

    /** Import uses the unique partial index as the final concurrency fence. */
    public int insertIfAbsent(
            UUID id,
            String word,
            String normalizedWord,
            String category,
            String severity,
            String action,
            String actor) {
        return jdbc.sql(
                        "INSERT INTO platform_sensitive_word(id,word,normalized_word,category,severity,action,enabled,created_by,updated_by) VALUES(:id,:word,:normalized,:category,:severity,:action,true,:actor,:actor) ON CONFLICT DO NOTHING")
                .param("id", id)
                .param("word", word)
                .param("normalized", normalizedWord)
                .param("category", category)
                .param("severity", severity)
                .param("action", action)
                .param("actor", actor)
                .update();
    }

    /** Bulk import in multi-row chunks to avoid per-word round trips on large dictionaries. */
    public int insertBatchIfAbsent(
            java.util.Map<String, String> normalizedToWord,
            String category,
            String severity,
            String action,
            String actor) {
        if (normalizedToWord == null || normalizedToWord.isEmpty()) return 0;
        var entries = new ArrayList<>(normalizedToWord.entrySet());
        int inserted = 0;
        for (int offset = 0; offset < entries.size(); offset += 500) {
            var chunk = entries.subList(offset, Math.min(offset + 500, entries.size()));
            var values = new StringBuilder(chunk.size() * 64);
            var params = new java.util.HashMap<String, Object>(chunk.size() * 3 + 4);
            params.put("category", category);
            params.put("severity", severity);
            params.put("action", action);
            params.put("actor", actor);
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) values.append(',');
                values.append("(:id")
                        .append(i)
                        .append(",:word")
                        .append(i)
                        .append(",:norm")
                        .append(i)
                        .append(",:category,:severity,:action,true,:actor,:actor)");
                var entry = chunk.get(i);
                params.put("id" + i, UUID.randomUUID());
                params.put("word" + i, entry.getValue());
                params.put("norm" + i, entry.getKey());
            }
            inserted +=
                    jdbc.sql(
                                    "INSERT INTO platform_sensitive_word(id,word,normalized_word,category,severity,action,enabled,created_by,updated_by) VALUES "
                                            + values
                                            + " ON CONFLICT DO NOTHING")
                            .params(params)
                            .update();
        }
        return inserted;
    }
}
