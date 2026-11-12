package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AdministrationPortTest {
    private AdministrationPort guard() {
        var guard = mock(AdministrationPort.class, CALLS_REAL_METHODS);
        when(guard.require(eq("actor"), anyString()))
                .thenAnswer(call -> Set.of(call.getArgument(1, String.class)));
        return guard;
    }

    @Test
    void globalOperationChecksBothGrantsAndBothGlobalScopesExactlyOnce() {
        for (var operation : PlatformOperation.values()) {
            var guard = guard();
            var page = operation.page().accessPermission();
            var action = operation.permission();
            assertEquals(Set.of(page, action), guard.requireOperationGlobal("actor", operation));
            verify(guard).require("actor", page);
            verify(guard).require("actor", action);
            verify(guard).requireGlobal("actor", page);
            verify(guard).requireGlobal("actor", action);
        }
    }

    @Test
    void globalOperationStillRejectsEitherMissingGlobalScopeForEveryOperation() {
        for (var operation : PlatformOperation.values()) {
            for (String permission :
                    Set.of(operation.page().accessPermission(), operation.permission())) {
                var guard = guard();
                doThrow(new IllegalStateException("denied"))
                        .when(guard)
                        .requireGlobal("actor", permission);
                assertThrows(
                        IllegalStateException.class,
                        () -> guard.requireOperationGlobal("actor", operation),
                        permission);
            }
        }
    }

    @Test
    void actionGlobalRetainsScopedPageVisibilityAndChecksActionOnce() {
        for (var operation : PlatformOperation.values()) {
            var guard = guard();
            guard.requireOperationActionGlobal("actor", operation);
            verify(guard).requireGlobal("actor", operation.permission());
            verify(guard, times(operation.page().scope() == PlatformOperation.Scope.GLOBAL ? 1 : 0))
                    .requireGlobal("actor", operation.page().accessPermission());
        }
    }

    @Test
    void implementationsWithoutConcurrentSupportRetainTheStrictContract() {
        for (var operation : PlatformOperation.values()) {
            var guard = guard();
            guard.requireConcurrentOperationGlobal("actor", operation);
            verify(guard).requireOperationGlobal("actor", operation);
            verify(guard).requireGlobal("actor", operation.page().accessPermission());
            verify(guard).requireGlobal("actor", operation.permission());
        }
    }
}
