package com.lumira.plugins.directory.organizations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthorizationContextProjectionPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.UserDirectoryPort;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
        when(repository.coveredOrganization(eq("admin"), any(), eq(organizationId)))
                .thenReturn(true);
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
    void rejectsOrganizationOutsideActorScopeBeforeWritingMemberships() {
        var repository = mock(OrganizationRepository.class);
        var users = mock(UserDirectoryPort.class);
        var userId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        when(users.version(userId)).thenReturn(3L);
        when(repository.coveredOrganization(eq("clerk"), any(), eq(organizationId)))
                .thenReturn(false);
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
                                                3L, Set.of(organizationId)),
                                        "clerk"));

        assertEquals(404, error.getStatusCode().value());
        verify(repository, never()).memberships(eq(userId), anySet());
    }

    @Test
    void keepsMembershipsOutsideActorScopeWhenReplacingVisibleOnes() {
        var repository = mock(OrganizationRepository.class);
        var users = mock(UserDirectoryPort.class);
        var userId = UUID.randomUUID();
        var visibleId = UUID.randomUUID();
        var hiddenId = UUID.randomUUID();
        when(users.version(userId)).thenReturn(3L);
        when(repository.coveredOrganization(eq("clerk"), any(), eq(visibleId))).thenReturn(true);
        when(repository.coveredOrganization(eq("clerk"), any(), eq(hiddenId))).thenReturn(false);
        when(repository.find(visibleId))
                .thenReturn(
                        new OrganizationRepository.Organization(
                                visibleId, null, "visible", "Visible", true, 1L));
        when(repository.memberships(userId)).thenReturn(List.of(visibleId, hiddenId));
        var service =
                new OrganizationService(
                        repository, mock(AdministrationPort.class), users, mock(AuditPort.class));

        var result =
                service.assign(
                        userId,
                        new OrganizationService.MembershipInput(3L, Set.of(visibleId)),
                        "clerk");

        assertEquals(new OrganizationService.Membership(4L, List.of(visibleId)), result);
        verify(repository).memberships(userId, Set.of(visibleId, hiddenId));
    }

    @Test
    void listsOnlyOrganizationsTheActorCanAssign() {
        var repository = mock(OrganizationRepository.class);
        var guard = mock(AdministrationPort.class);
        var users = mock(UserDirectoryPort.class);
        var userId = UUID.randomUUID();
        var visibleId = UUID.randomUUID();
        when(users.versionSnapshot(userId)).thenReturn(3L);
        when(repository.visibleMemberships(
                        userId,
                        "clerk",
                        PlatformOperation.USER_ASSIGN_ORGANIZATIONS.permission(),
                        null))
                .thenReturn(List.of(visibleId));
        var service = new OrganizationService(repository, guard, users, mock(AuditPort.class));

        var result = service.memberships(userId, "clerk");

        assertEquals(new OrganizationService.Membership(3L, List.of(visibleId)), result);
    }

    @Test
    void listsUserScopeOrganizationsWhenActorCannotAssign() {
        var repository = mock(OrganizationRepository.class);
        var guard = mock(AdministrationPort.class);
        var users = mock(UserDirectoryPort.class);
        var userId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        when(users.versionSnapshot(userId)).thenReturn(3L);
        when(guard.requireOperation(eq("reader"), eq(PlatformOperation.USER_ASSIGN_ORGANIZATIONS)))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"));
        when(repository.visibleMemberships(userId, "reader", "platform:users:access", null))
                .thenReturn(List.of(organizationId));
        var service = new OrganizationService(repository, guard, users, mock(AuditPort.class));

        var result = service.memberships(userId, "reader");

        assertEquals(new OrganizationService.Membership(3L, List.of(organizationId)), result);
    }

    @Test
    void listsMembershipsUsingThePreviewRoleSnapshot() {
        var repository = mock(OrganizationRepository.class);
        var guard = mock(AdministrationPort.class);
        var users = mock(UserDirectoryPort.class);
        var projection = mock(AuthorizationContextProjectionPort.class);
        var userId = UUID.randomUUID();
        var snapshot =
                new AuthorizationContextProjectionPort.Snapshot(
                        Set.of("platform:users:access"), Set.of(), true, UUID.randomUUID(), 7);
        when(projection.current("admin")).thenReturn(Optional.of(snapshot));
        when(guard.requireOperation("admin", PlatformOperation.USER_ASSIGN_ORGANIZATIONS))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"));
        when(repository.visibleMemberships(userId, "admin", "platform:users:access", snapshot))
                .thenReturn(List.of());
        var service =
                new OrganizationService(
                        repository, guard, users, mock(AuditPort.class), null, projection);

        service.memberships(userId, "admin");

        verify(repository).visibleMemberships(userId, "admin", "platform:users:access", snapshot);
    }
}
