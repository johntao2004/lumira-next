package com.lumira.example.notes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 直接调用业务层也须拒绝无权用户，避免只保护 HTTP 入口。 Direct service calls must reject unauthorized users, not only HTTP
 * entry points.
 */
class NotesAuthorizationTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousCannotReadOrWrite() {
        var database = mock(JdbcClient.class);
        var store = new NotesStore(database);
        assertThrows(AccessDeniedException.class, store::list);
        assertThrows(AccessDeniedException.class, () -> store.create("text"));
        verifyNoInteractions(database);
    }

    @Test
    void readerCannotWriteThroughDirectServiceCalls() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "reader", "", AuthorityUtils.createAuthorityList("notes:read")));
        var database = mock(JdbcClient.class);
        assertThrows(AccessDeniedException.class, () -> new NotesStore(database).create("text"));
        verifyNoInteractions(database);
    }

    @Test
    void editorInvalidInputDoesNotReachDatabase() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "editor",
                                "",
                                AuthorityUtils.createAuthorityList("notes:read", "notes:write")));
        var database = mock(JdbcClient.class);
        assertThrows(IllegalArgumentException.class, () -> new NotesStore(database).create(""));
        verifyNoInteractions(database);
    }
}
