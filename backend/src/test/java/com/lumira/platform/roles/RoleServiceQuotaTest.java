package com.lumira.platform.roles;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RoleServiceQuotaTest {
    @Test
    void acceptsRoleQuotaAboveTheSystemFallback() {
        var roles = mock(RoleRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var service =
                new RoleService(
                        roles,
                        mock(com.lumira.platform.common.UserDirectoryPort.class),
                        guard,
                        mock(com.lumira.platform.common.AuditPort.class));
        when(guard.require("admin", "platform:roles:write")).thenReturn(Set.of());

        service.create(
                new RoleService.RoleInput(
                        "qa-storage",
                        "QA storage",
                        true,
                        null,
                        Set.of(),
                        "SELF",
                        4L * 1024 * 1024 * 1024,
                        2_000L),
                "admin");

        verify(roles)
                .create(
                        any(UUID.class),
                        eq("qa-storage"),
                        eq("QA storage"),
                        eq(true),
                        eq("SELF"),
                        eq(4L * 1024 * 1024 * 1024),
                        eq(2_000L));
    }

    @Test
    void stillRejectsQuotaOutsideTheSafeIntegerRange() {
        var roles = mock(RoleRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var service =
                new RoleService(
                        roles,
                        mock(com.lumira.platform.common.UserDirectoryPort.class),
                        guard,
                        mock(com.lumira.platform.common.AuditPort.class));
        when(guard.require("admin", "platform:roles:write")).thenReturn(Set.of());

        assertThrows(
                ResponseStatusException.class,
                () ->
                        service.create(
                                new RoleService.RoleInput(
                                        "qa-storage",
                                        "QA storage",
                                        true,
                                        null,
                                        Set.of(),
                                        "SELF",
                                        9_007_199_254_740_992L,
                                        null),
                                "admin"));

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
    void stillRejectsNullAuthorities() {
        var roles = mock(RoleRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var service =
                new RoleService(
                        roles,
                        mock(com.lumira.platform.common.UserDirectoryPort.class),
                        guard,
                        mock(com.lumira.platform.common.AuditPort.class));
        when(guard.require("admin", "platform:roles:write")).thenReturn(Set.of());
        var authorities = new HashSet<String>();
        authorities.add(null);

        assertThrows(
                ResponseStatusException.class,
                () ->
                        service.create(
                                new RoleService.RoleInput(
                                        "qa-storage",
                                        "QA storage",
                                        true,
                                        null,
                                        authorities,
                                        "SELF",
                                        null,
                                        null),
                                "admin"));

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
    void stillRejectsNullAssignmentRoleIds() {
        var roles = mock(RoleRepository.class);
        var users = mock(com.lumira.platform.common.UserDirectoryPort.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var service =
                new RoleService(
                        roles, users, guard, mock(com.lumira.platform.common.AuditPort.class));
        when(guard.require("admin", "platform:roles:write")).thenReturn(Set.of());
        var roleIds = new HashSet<UUID>();
        roleIds.add(null);

        assertThrows(
                ResponseStatusException.class,
                () ->
                        service.assign(
                                UUID.randomUUID(),
                                new RoleService.Assignment(1L, roleIds),
                                "admin"));

        verify(roles, never()).assign(any(UUID.class), any());
    }
}
