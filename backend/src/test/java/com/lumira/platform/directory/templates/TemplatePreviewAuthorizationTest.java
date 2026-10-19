package com.lumira.platform.directory.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.TemplateUserDataPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class TemplatePreviewAuthorizationTest {
    @Test
    void rolePreviewCannotGeneratePdfFromAUserTemplate() {
        var repository = mock(TemplateRepository.class);
        var userData = mock(TemplateUserDataPort.class);
        var service =
                new TemplateService(
                        repository,
                        userData,
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        mock(ObjectMapper.class));
        UUID actorId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        var authorization =
                new RequestAuthorizationContext(
                        "actor",
                        actorId,
                        "session",
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        "revision",
                        "revision",
                        true,
                        new RequestAuthorizationContext.Preview(
                                "ACTIVE",
                                UUID.randomUUID(),
                                "QA role",
                                1,
                                now,
                                now.plusMinutes(5),
                                false,
                                null));

        try (var ignored =
                AuthenticationRequestContext.bind(
                        new AuthenticationRequestContext.Snapshot(
                                "actor", actorId, null, authorization))) {
            var failure =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    service.preview(
                                            UUID.randomUUID(),
                                            0,
                                            UUID.randomUUID(),
                                            Map.of(),
                                            "actor"));
            assertEquals(HttpStatus.FORBIDDEN, failure.getStatusCode());
        }

        verifyNoInteractions(repository, userData);
    }

    @Test
    void userPreviewRequiresPrintPermissionBeforeReadingUserValues() {
        var repository = mock(TemplateRepository.class);
        var userData = mock(TemplateUserDataPort.class);
        var guard = mock(AdministrationPort.class);
        var service =
                new TemplateService(
                        repository,
                        userData,
                        guard,
                        mock(AuditPort.class),
                        mock(ObjectMapper.class));
        UUID userId = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationUser("actor", userId, PlatformOperation.USER_PRINT);

        var failure =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.preview(UUID.randomUUID(), 0, userId, Map.of(), "actor"));

        assertEquals(HttpStatus.FORBIDDEN, failure.getStatusCode());
        verifyNoInteractions(repository, userData);
    }

    @Test
    void blankPreviewDoesNotRequirePrintPermission() {
        var repository = mock(TemplateRepository.class);
        var guard = mock(AdministrationPort.class);
        var service =
                new TemplateService(
                        repository,
                        mock(TemplateUserDataPort.class),
                        guard,
                        mock(AuditPort.class),
                        mock(ObjectMapper.class));

        assertThrows(
                RuntimeException.class,
                () -> service.preview(UUID.randomUUID(), 0, null, Map.of(), "actor"));

        verify(guard, never()).requireOperationUser(any(), any(), eq(PlatformOperation.USER_PRINT));
    }
}
