package com.lumira.platform.identity.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthenticationRequestContextTest {
    @Test
    void normalScopeExitClearsTheSnapshot() {
        try (var ignored = AuthenticationRequestContext.bind(snapshot("normal"))) {
            assertEquals("normal", AuthenticationRequestContext.current().principalName());
        }
        assertNull(AuthenticationRequestContext.current());
    }

    @Test
    void exceptionalScopeExitClearsTheSnapshot() {
        assertThrows(
                IllegalStateException.class,
                () -> {
                    try (var ignored = AuthenticationRequestContext.bind(snapshot("exception"))) {
                        assertEquals(
                                "exception",
                                AuthenticationRequestContext.current().principalName());
                        throw new IllegalStateException("handler failure");
                    }
                });
        assertNull(AuthenticationRequestContext.current());
    }

    @Test
    void nestedScopeRestoresPreviousSnapshotThenClearsIt() {
        var outer = snapshot("outer");
        try (var outerScope = AuthenticationRequestContext.bind(outer)) {
            assertEquals(outer, AuthenticationRequestContext.current());
            try (var innerScope = AuthenticationRequestContext.bind(snapshot("inner"))) {
                assertEquals("inner", AuthenticationRequestContext.current().principalName());
            }
            assertEquals(outer, AuthenticationRequestContext.current());
        }
        assertNull(AuthenticationRequestContext.current());
    }

    private static AuthenticationRequestContext.Snapshot snapshot(String principal) {
        return new AuthenticationRequestContext.Snapshot(principal, UUID.randomUUID(), null, null);
    }
}
