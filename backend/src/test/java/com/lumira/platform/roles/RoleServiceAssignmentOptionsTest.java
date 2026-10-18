package com.lumira.platform.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
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

class RoleServiceAssignmentOptionsTest {
    private static final String ACTOR = "manager";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSIGNED_ROLE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @AfterEach
    void clearPluginPermissions() {
        PluginPermissions.replaceAll(Map.of());
    }

    @Test
    void readOnlyRoleLookupOnlyReturnsAssignedOptionsAndCannotAssign() {
        var roles = org.mockito.Mockito.mock(RoleRepository.class);
        var guard = org.mockito.Mockito.mock(AdministrationPort.class);
        var page = new PageRequest(1, 10, "");
        var option =
                new RoleRepository.AssignmentOption(
                        ASSIGNED_ROLE_ID, "qa-role", "QA role", true, null, null, false);
        doThrow(forbidden())
                .when(guard)
                .requireOperationUser(ACTOR, USER_ID, PlatformOperation.USER_ASSIGN_ROLES);
        doNothing()
                .when(guard)
                .requireOperationUser(ACTOR, USER_ID, PlatformOperation.USER_READ_ROLES);
        when(roles.userRoles(USER_ID)).thenReturn(List.of(ASSIGNED_ROLE_ID));
        when(roles.assignmentOptions(page, Set.of(ASSIGNED_ROLE_ID), Set.of(), Set.of(), false))
                .thenReturn(new PageResult<>(List.of(option), 1, 1, 10));

        var result = service(roles, guard).assignmentOptions(USER_ID, page, ACTOR);

        assertEquals(1, result.total());
        assertEquals(ASSIGNED_ROLE_ID, result.items().getFirst().id());
        assertFalse(result.items().getFirst().assignable());
        assertFalse(
                java.util.Arrays.stream(
                                RoleService.AssignmentRoleOption.class.getRecordComponents())
                        .anyMatch(component -> component.getName().equals("authorities")));
        verify(roles).assignmentOptions(page, Set.of(ASSIGNED_ROLE_ID), Set.of(), Set.of(), false);
        verify(roles, never()).actorGlobalAuthorities(ACTOR);
    }

    @Test
    void assignmentLookupFiltersToGloballyDelegableRoleGrantsAndKeepsCurrentIds() {
        var roles = org.mockito.Mockito.mock(RoleRepository.class);
        var guard = org.mockito.Mockito.mock(AdministrationPort.class);
        var page = new PageRequest(2, 20, "hr");
        var option =
                new RoleRepository.AssignmentOption(
                        ASSIGNED_ROLE_ID, "qa-role", "QA role", true, null, null, true);
        doNothing()
                .when(guard)
                .requireOperationUser(ACTOR, USER_ID, PlatformOperation.USER_ASSIGN_ROLES);
        when(roles.userRoles(USER_ID)).thenReturn(List.of(ASSIGNED_ROLE_ID));
        when(roles.actorGlobalAuthorities(ACTOR))
                .thenReturn(
                        Set.of(
                                "platform:users:access",
                                "platform:users:assign-roles",
                                "platform:users:update"));
        Set<String> delegable =
                Set.of(
                        "platform:users:access",
                        "platform:users:assign-roles",
                        "platform:users:update");
        when(roles.assignmentOptions(
                        page, Set.of(ASSIGNED_ROLE_ID), delegable, RoleService.PERMISSIONS, true))
                .thenReturn(new PageResult<>(List.of(option), 1, 2, 20));

        var result = service(roles, guard).assignmentOptions(USER_ID, page, ACTOR);

        assertEquals(2, result.page());
        assertEquals("qa-role", result.items().getFirst().code());
        assertTrue(result.items().getFirst().assignable());
        verify(roles)
                .assignmentOptions(
                        page, Set.of(ASSIGNED_ROLE_ID), delegable, RoleService.PERMISSIONS, true);
        verify(guard).requireOperationUser(ACTOR, USER_ID, PlatformOperation.USER_ASSIGN_ROLES);
    }

    private static RoleService service(RoleRepository roles, AdministrationPort guard) {
        return new RoleService(
                roles,
                org.mockito.Mockito.mock(UserDirectoryPort.class),
                guard,
                org.mockito.Mockito.mock(AuditPort.class));
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }
}
