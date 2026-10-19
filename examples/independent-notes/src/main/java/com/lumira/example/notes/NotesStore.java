package com.lumira.example.notes;

import com.lumira.platform.common.AuditPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用仅拥有 notes_* 表；业务写入与公开审计端口的本地适配同事务。 The application owns only notes_* tables; business writes
 * and its public audit-port adapter share one transaction.
 */
@Repository
class NotesStore implements AuditPort {
    private final JdbcClient jdbc;

    NotesStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static void require(String permission) {
        var actor =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (actor == null
                || !actor.isAuthenticated()
                || actor.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals(permission)))
            throw new org.springframework.security.access.AccessDeniedException(
                    "permission denied");
    }

    List<Map<String, Object>> list() {
        require("notes:read");
        return jdbc.sql("SELECT id, text FROM notes_entry ORDER BY created_at, id")
                .query()
                .listOfRows();
    }

    @Transactional
    UUID create(String text) {
        require("notes:write");
        if (text == null || text.isBlank() || text.length() > 200)
            throw new IllegalArgumentException("text must contain 1..200 characters");
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO notes_entry(id,text) VALUES (:id,:text)")
                .param("id", id)
                .param("text", text)
                .update();
        append(
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication()
                        .getName(),
                "notes.created",
                id);
        return id;
    }

    @Override
    public void append(String actor, String action, UUID targetId) {
        jdbc.sql("INSERT INTO notes_audit(actor,action,target_id) VALUES (:actor,:action,:target)")
                .param("actor", actor)
                .param("action", action)
                .param("target", targetId)
                .update();
    }
}
