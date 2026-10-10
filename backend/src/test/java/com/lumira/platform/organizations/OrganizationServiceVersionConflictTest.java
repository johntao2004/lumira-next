package com.lumira.platform.organizations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.UserDirectoryPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OrganizationServiceVersionConflictTest {
    @Test
    void reportsStaleRecordWhenAtomicVersionedUpdateLosesRace() {
        var repository = mock(OrganizationRepository.class);
        var id = UUID.randomUUID();
        var old = new OrganizationRepository.Organization(id, null, "qa-org", "Old", true, 5, 8L);
        when(repository.find(id)).thenReturn(old);
        when(repository.update(id, null, "New", true, 5, 8L)).thenReturn(false);
        var service =
                new OrganizationService(
                        repository,
                        mock(AdministrationPort.class),
                        mock(UserDirectoryPort.class),
                        mock(AuditPort.class));

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.update(
                                        id,
                                        new OrganizationService.Input(null, null, "New", true, 8L),
                                        "admin"));

        assertEquals(409, error.getStatusCode().value());
        assertEquals("staleRecord", error.getReason());
        verify(repository).update(eq(id), eq(null), eq("New"), eq(true), eq(5), eq(8L));
    }
}
