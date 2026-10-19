package com.lumira.platform.organizations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.UserDirectoryPort;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OrganizationServiceMembershipTest {
    @Test
    void acceptsImmutableSetOfOrganizationIds() {
        var repository = mock(OrganizationRepository.class);
        var users = mock(UserDirectoryPort.class);
        var userId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        when(users.version(userId)).thenReturn(3L);
        when(repository.find(organizationId))
                .thenReturn(
                        new OrganizationRepository.Organization(
                                organizationId, null, "qa-org", "QA organization", true, 1L));
        when(repository.memberships(userId)).thenReturn(List.of(organizationId));
        var service =
                new OrganizationService(
                        repository, mock(AdministrationPort.class), users, mock(AuditPort.class));

        var result =
                service.assign(
                        userId,
                        new OrganizationService.MembershipInput(3L, Set.of(organizationId)),
                        "admin");

        assertEquals(new OrganizationService.Membership(4L, List.of(organizationId)), result);
        verify(repository).memberships(userId, Set.of(organizationId));
    }

    @Test
    void rejectsMutableSetContainingNullBeforeWritingMemberships() {
        var repository = mock(OrganizationRepository.class);
        var users = mock(UserDirectoryPort.class);
        var userId = UUID.randomUUID();
        when(users.version(userId)).thenReturn(3L);
        var organizationIds = new HashSet<UUID>();
        organizationIds.add(null);
        var service =
                new OrganizationService(
                        repository, mock(AdministrationPort.class), users, mock(AuditPort.class));

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.assign(
                                        userId,
                                        new OrganizationService.MembershipInput(
                                                3L, organizationIds),
                                        "admin"));

        assertEquals("invalidOrganization", error.getReason());
        verify(repository, never()).memberships(eq(userId), anySet());
    }

    @Test
    void membershipDeltaContainsOnlyAdditionsAndRemovals() {
        var retained = UUID.randomUUID();
        var removed = UUID.randomUUID();
        var added = UUID.randomUUID();
        assertEquals(
                Set.of(removed, added),
                OrganizationService.membershipChanges(
                        Set.of(retained, removed), Set.of(retained, added)));
        assertEquals(
                Set.of(),
                OrganizationService.membershipChanges(Set.of(retained), Set.of(retained)));
    }
}
