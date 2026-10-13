package com.lumira.platform.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.UserDirectoryPort;
import com.lumira.platform.modules.PluginPermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RoleServiceDelegationTest {
    private static final String ACTOR = "manager";
    private static final String ROLE_CREATE = "platform:roles:create";
    private static final String ROLE_UPDATE = "platform:roles:update";
    private static final String ROLE_DELETE = "platform:roles:delete";
    private static final String ROLE_ASSIGN = "platform:roles:assign";
    private static final String FILE_DELETE = PlatformOperation.FILE_DELETE.permission();

    @AfterEach
    void clearPluginPermissions() {
        PluginPermissions.replaceAll(Map.of());
    }

    @Test
    void cannotCreateRoleWithLocallyHeldButNotGloballyDelegablePermission() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        when(guard.require(ACTOR, ROLE_CREATE)).thenReturn(Set.of(ROLE_CREATE, FILE_DELETE));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal(ACTOR, FILE_DELETE);
        var service = service(roles, guard, mock(UserDirectoryPort.class));

        assertThrows(
                ResponseStatusException.class,
                () -> service.create(input(Set.of(FILE_DELETE)), ACTOR));

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
        when(guard.require(ACTOR, ROLE_UPDATE)).thenReturn(Set.of(ROLE_UPDATE, FILE_DELETE));
        when(roles.find(id)).thenReturn(role(id, true, Set.of(FILE_DELETE)));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal(ACTOR, FILE_DELETE);
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
        when(guard.require(ACTOR, ROLE_DELETE)).thenReturn(Set.of(ROLE_DELETE, FILE_DELETE));
        when(roles.find(id)).thenReturn(role(id, false, Set.of(FILE_DELETE)));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal(ACTOR, FILE_DELETE);
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
        when(guard.require(ACTOR, ROLE_ASSIGN)).thenReturn(Set.of(ROLE_ASSIGN, FILE_DELETE));
        when(users.version(userId)).thenReturn(12L);
        when(roles.userRoles(userId)).thenReturn(List.of(disabledRoleId));
        when(roles.find(disabledRoleId))
                .thenReturn(role(disabledRoleId, false, Set.of(FILE_DELETE)));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal(ACTOR, FILE_DELETE);
        var service = service(roles, guard, users);

        assertThrows(
                ResponseStatusException.class,
                () -> service.assign(userId, new RoleService.Assignment(12L, Set.of()), ACTOR));

        verify(roles, never()).assign(any(UUID.class), any());
    }

    @Test
    void rejectsNonEmptyLegacyProjectionMetadataBeforeRoleWrites() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        var service = service(roles, guard, mock(UserDirectoryPort.class));
        var staleClientInput =
                new RoleService.RoleInput(
                        "qa-role",
                        "QA role",
                        true,
                        7L,
                        Set.of(),
                        "SELF",
                        null,
                        null,
                        Set.of("platform:users:assign-roles"));

        ResponseStatusException error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.create(staleClientInput, ACTOR));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(roles, guard);
    }

    @Test
    void rejectsNonEmptyLegacyProjectionMetadataBeforeRoleUpdates() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        var service = service(roles, guard, mock(UserDirectoryPort.class));
        var staleClientInput =
                new RoleService.RoleInput(
                        "qa-role",
                        "QA role",
                        true,
                        7L,
                        Set.of(),
                        "SELF",
                        null,
                        null,
                        Set.of("platform:__v50:retired"));

        ResponseStatusException error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.update(UUID.randomUUID(), staleClientInput, ACTOR));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(roles, guard);
    }

    @Test
    void acceptsEmptyLegacyProjectionMetadataAsCompatibilitySentinel() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        when(guard.require(ACTOR, ROLE_CREATE)).thenReturn(Set.of(ROLE_CREATE));
        var service = service(roles, guard, mock(UserDirectoryPort.class));
        var input =
                new RoleService.RoleInput(
                        "qa-role", "QA role", true, 7L, Set.of(), "SELF", null, null, Set.of());

        service.create(input, ACTOR);

        verify(roles).authorities(any(UUID.class), eq(Set.of()));
    }

    @Test
    void stillAllowsGloballyHeldDynamicPluginAuthorities() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        String dynamic = "platform:fixture:read";
        PluginPermissions.replaceAll(
                Map.of(
                        "fixture",
                        List.of(
                                new PluginPermissions.CatalogGroup(
                                        "fixture",
                                        "fixture",
                                        List.of(),
                                        List.of(
                                                new PluginPermissions.Permission(
                                                        dynamic, "read"))))));
        when(guard.require(ACTOR, ROLE_CREATE)).thenReturn(Set.of(ROLE_CREATE, dynamic));
        when(guard.hasGlobal(ACTOR, dynamic)).thenReturn(true);
        var service = service(roles, guard, mock(UserDirectoryPort.class));

        service.create(input(Set.of(dynamic)), ACTOR);

        verify(guard).hasGlobal(ACTOR, dynamic);
        verify(guard, never()).requireGlobal(ACTOR, dynamic);
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

    @Test
    void installerCanBootstrapOnlyAnActivePluginPermissionAndAuditsTheRole() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);
        String dynamic = "platform:fixture:read";
        PluginPermissions.replaceAll(
                Map.of(
                        "fixture",
                        List.of(
                                new PluginPermissions.CatalogGroup(
                                        "fixture",
                                        "fixture",
                                        List.of(),
                                        List.of(
                                                new PluginPermissions.Permission(
                                                        dynamic, "read"))))));
        when(guard.require(ACTOR, ROLE_CREATE)).thenReturn(Set.of(ROLE_CREATE));
        when(guard.hasGlobal(ACTOR, dynamic)).thenReturn(false);
        when(guard.hasGlobal(ACTOR, "platform:modules:install")).thenReturn(true);
        var service = new RoleService(roles, mock(UserDirectoryPort.class), guard, audit);

        service.create(input(Set.of(dynamic)), ACTOR);

        verify(audit).append(eq(ACTOR), eq("role.pluginPermission.bootstrap:" + dynamic), any());
        verify(roles).authorities(any(UUID.class), eq(Set.of(dynamic)));
    }

    @Test
    void cannotGrantAnUninstalledPluginPermissionEvenAsInstaller() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        when(guard.require(ACTOR, ROLE_CREATE)).thenReturn(Set.of(ROLE_CREATE));
        when(guard.hasGlobal(ACTOR, "platform:modules:install")).thenReturn(true);
        var service = service(roles, guard, mock(UserDirectoryPort.class));

        assertThrows(
                ResponseStatusException.class,
                () -> service.create(input(Set.of("platform:retired:read")), ACTOR));

        verify(roles, never()).authorities(any(UUID.class), any());
    }

    @Test
    void keepsAnExistingRetiredGrantWhenEditingOtherRoleFields() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        UUID id = UUID.randomUUID();
        String retired = "platform:retired:read";
        when(guard.require(ACTOR, ROLE_UPDATE)).thenReturn(Set.of(ROLE_UPDATE));
        when(roles.find(id)).thenReturn(role(id, true, Set.of(retired)));
        when(roles.hasAdministrators()).thenReturn(true);
        var service = service(roles, guard, mock(UserDirectoryPort.class));
        var input =
                new RoleService.RoleInput(
                        "qa-existing",
                        "Renamed role",
                        true,
                        7L,
                        Set.of(retired),
                        "SELF",
                        null,
                        null);

        var updated = service.update(id, input, ACTOR);

        assertEquals(List.of(retired), updated.authorities());
        verify(roles, never()).authorities(any(UUID.class), any());
    }

    @Test
    void cannotAddAnUnknownOrRetiredGrantThatWasNotAlreadyStored() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        UUID id = UUID.randomUUID();
        when(guard.require(ACTOR, ROLE_UPDATE)).thenReturn(Set.of(ROLE_UPDATE));
        when(roles.find(id)).thenReturn(role(id, true, Set.of()));
        var service = service(roles, guard, mock(UserDirectoryPort.class));

        assertThrows(
                ResponseStatusException.class,
                () -> service.update(id, input(Set.of("platform:retired:read")), ACTOR));

        verify(roles, never())
                .update(any(UUID.class), anyString(), anyBoolean(), anyString(), any(), any());
        verify(roles, never()).authorities(any(UUID.class), any());
    }

    @Test
    void pluginMetadataCannotMasqueradeAsAHostPermissionForBootstrap() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        String hostPermission = "platform:users:delete";
        PluginPermissions.replaceAll(
                Map.of(
                        "malicious-fixture",
                        List.of(
                                new PluginPermissions.CatalogGroup(
                                        "malicious-fixture",
                                        "fixture",
                                        List.of(),
                                        List.of(
                                                new PluginPermissions.Permission(
                                                        hostPermission, "read"))))));
        when(guard.require(ACTOR, ROLE_CREATE)).thenReturn(Set.of(ROLE_CREATE));
        when(guard.hasGlobal(ACTOR, hostPermission)).thenReturn(false);
        when(guard.hasGlobal(ACTOR, "platform:modules:install")).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal(ACTOR, hostPermission);
        var service = service(roles, guard, mock(UserDirectoryPort.class));

        assertThrows(
                ResponseStatusException.class,
                () -> service.create(input(Set.of(hostPermission)), ACTOR));

        verify(roles, never()).authorities(any(UUID.class), any());
    }

    @Test
    void cannotReenableRoleWithUnheldActiveAuthorities() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        UUID id = UUID.randomUUID();
        String authority = "platform:users:delete";
        when(guard.require(ACTOR, ROLE_UPDATE)).thenReturn(Set.of(ROLE_UPDATE));
        when(roles.find(id)).thenReturn(role(id, false, Set.of(authority)));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal(ACTOR, authority);
        var service = service(roles, guard, mock(UserDirectoryPort.class));

        assertThrows(
                ResponseStatusException.class,
                () -> service.update(id, input(Set.of(authority)), ACTOR));

        verify(roles, never())
                .update(any(UUID.class), anyString(), anyBoolean(), anyString(), any(), any());
    }

    @Test
    void cannotWidenRoleScopeWithUnheldActiveAuthorities() {
        var roles = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        UUID id = UUID.randomUUID();
        String authority = "platform:users:delete";
        when(guard.require(ACTOR, ROLE_UPDATE)).thenReturn(Set.of(ROLE_UPDATE));
        when(roles.find(id)).thenReturn(role(id, true, Set.of(authority)));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal(ACTOR, authority);
        var service = service(roles, guard, mock(UserDirectoryPort.class));
        var widened =
                new RoleService.RoleInput(
                        "qa-existing",
                        "QA existing",
                        true,
                        7L,
                        Set.of(authority),
                        "ALL",
                        null,
                        null);

        assertThrows(ResponseStatusException.class, () -> service.update(id, widened, ACTOR));

        verify(roles, never())
                .update(any(UUID.class), anyString(), anyBoolean(), anyString(), any(), any());
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
