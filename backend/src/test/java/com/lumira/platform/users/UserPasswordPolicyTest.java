package com.lumira.platform.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class UserPasswordPolicyTest {
    @Test
    void sequenceRejectionCreatesNoAccountOrPasswordHash() {
        var repository = mock(UserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);
        var security = mock(SecurityPolicyPort.class);
        when(security.currentPolicy())
                .thenReturn(
                        new SecurityPolicyPort.Policy(
                                8, false, false, false, false, 30, 120, 10, 60, 600, 60, 10, 10,
                                5));
        var service = new UserService(repository, encoder, guard, audit, security);
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.create(
                                        new UserService.CreateUser(
                                                "fixture", "Fixture", "Q!r71239"),
                                        "admin"));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(repository, encoder, guard, audit);
    }
}
