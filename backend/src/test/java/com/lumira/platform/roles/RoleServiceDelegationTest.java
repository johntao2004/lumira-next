package com.lumira.platform.roles;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.UserDirectoryPort;
import com.lumira.platform.modules.PluginPermissions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RoleServiceDelegationTest {
    private static final String ACTOR = "manager";
    private static final String ROLE_WRITE = "platform:roles:write";
    private static final String FILE_WRITE = "platform:files:write";

    @Test
    void cannotCreateRoleWithLocallyHeldButNotGloballyDelegablePermission() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        when(guard.require(ACTOR, ROLE_WRITE)).thenReturn(Set.of(ROLE_WRITE, FILE_WRITE));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal(ACTOR, FILE_WRITE);
        var service = service(roles, guard, mock(UserDirectoryPort.class));

        assertThrows(
                ResponseStatusException.class,
                () -> service.create(input(Set.of(FILE_WRITE)), ACTOR));

        verify(roles, never())
                .create(
                        any(UUID.class),
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyString(),
                        any(Long.class),
                        any(Long.class));
    }

    @Test
    void requiresGlobalDelegationForAuthoritiesRemovedFromAnExistingRole() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        UUID id = UUID.randomUUID();
        when(guard.require(ACTOR, ROLE_WRITE)).thenReturn(Set.of(ROLE_WRITE, FILE_WRITE));
        when(roles.find(id)).thenReturn(role(id, false, Set.of(FILE_WRITE)));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal(ACTOR, FILE_WRITE);
        var service = service(roles, guard, mock(UserDirectoryPort.class));

        assertThrows(
                ResponseStatusException.class, () -> service.update(id, input(Set.of()), ACTOR));

        verify(roles, never())
                .update(any(UUID.class), anyString(), anyBoolean(), anyString(), any(), any());
        verify(roles, never()).authorities(any(UUID.class), any());
    }

    @Test
    void requiresGlobalDelegationWhenDeletingDisabledRoles() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        UUID id = UUID.randomUUID();
        when(guard.require(ACTOR, ROLE_WRITE)).thenReturn(Set.of(ROLE_WRITE, FILE_WRITE));
        when(roles.find(id)).thenReturn(role(id, false, Set.of(FILE_WRITE)));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal(ACTOR, FILE_WRITE);
        var service = service(roles, guard, mock(UserDirectoryPort.class));

        assertThrows(ResponseStatusException.class, () -> service.delete(id, 7, ACTOR));

        verify(roles, never()).delete(any(UUID.class));
    }

    @Test
    void checksAuthoritiesOfRolesRemovedFromAnAssignment() {
        var roles = mock(RoleRepository.class);
        var users = mock(UserDirectoryPort.class);
        var guard = mock(AdministrationPort.class);
        UUID userId = UUID.randomUUID();
        UUID disabledRoleId = UUID.randomUUID();
        when(guard.require(ACTOR, ROLE_WRITE)).thenReturn(Set.of(ROLE_WRITE, FILE_WRITE));
        when(users.version(userId)).thenReturn(12L);
        when(roles.userRoles(userId)).thenReturn(List.of(disabledRoleId));
        when(roles.find(disabledRoleId))
                .thenReturn(role(disabledRoleId, false, Set.of(FILE_WRITE)));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal(ACTOR, FILE_WRITE);
        var service = service(roles, guard, users);

        assertThrows(
                ResponseStatusException.class,
                () -> service.assign(userId, new RoleService.Assignment(12L, Set.of()), ACTOR));

        verify(roles, never()).assign(any(UUID.class), any());
    }

    @Test
    void stillAllowsGloballyHeldDynamicPluginAuthorities() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        String dynamic = "plugin:fixture:" + UUID.randomUUID().toString().replace("-", "");
        PluginPermissions.register(Set.of(dynamic));
        when(guard.require(ACTOR, ROLE_WRITE)).thenReturn(Set.of(ROLE_WRITE, dynamic));
        var service = service(roles, guard, mock(UserDirectoryPort.class));

        service.create(input(Set.of(dynamic)), ACTOR);

        verify(guard).requireGlobal(ACTOR, dynamic);
        verify(roles)
                .create(
                        any(UUID.class),
                        eq("qa-role"),
                        eq("QA role"),
                        eq(true),
                        eq("SELF"),
                        any(),
                        any());
    }

    private static RoleService service(
            RoleRepository roles, AdministrationPort guard, UserDirectoryPort users) {
        return new RoleService(roles, users, guard, mock(AuditPort.class));
    }

    private static RoleService.RoleInput input(Set<String> authorities) {
        return new RoleService.RoleInput(
                "qa-role", "QA role", true, 7L, authorities, "SELF", null, null);
    }

    private static RoleRepository.Role role(UUID id, boolean enabled, Set<String> authorities) {
        return new RoleRepository.Role(
                id,
                "qa-existing",
                "QA existing",
                enabled,
                7L,
                "SELF",
                null,
                null,
                List.copyOf(authorities),
                false);
    }
}
