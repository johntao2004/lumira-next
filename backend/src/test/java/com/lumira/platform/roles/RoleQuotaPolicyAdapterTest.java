package com.lumira.platform.roles;

import com.lumira.platform.files.FileQuota;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleQuotaPolicyAdapterTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void roleLimitsCanExceedSystemFallback() {
        var roles = mock(RoleRepository.class);
        var roleId = UUID.randomUUID();
        when(roles.quotaLimits(Set.of(roleId))).thenReturn(List.of(
                new RoleRepository.QuotaLimit(roleId, true, 4 * GIB, 2_000L)));

        var resolved = new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000))
                .forRoles(Set.of(roleId));

        assertEquals(4 * GIB, resolved.maximumBytes());
        assertEquals(2_000, resolved.maximumFiles());
    }

    @Test
    void minimumIsAppliedIndependentlyForEachDimension() {
        var roles = mock(RoleRepository.class);
        var storageRole = UUID.randomUUID();
        var countRole = UUID.randomUUID();
        when(roles.quotaLimits(Set.of(storageRole, countRole))).thenReturn(List.of(
                new RoleRepository.QuotaLimit(storageRole, true, 4 * GIB, null),
                new RoleRepository.QuotaLimit(countRole, true, 8 * GIB, 250L)));

        var resolved = new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000))
                .forRoles(Set.of(storageRole, countRole));

        assertEquals(4 * GIB, resolved.maximumBytes());
        assertEquals(250, resolved.maximumFiles());
    }

    @Test
    void usesSystemDefaultOnlyForDimensionsWithNoEnabledRoleLimit() {
        var roles = mock(RoleRepository.class);
        var roleId = UUID.randomUUID();
        when(roles.quotaLimits(Set.of(roleId))).thenReturn(List.of(
                new RoleRepository.QuotaLimit(roleId, true, null, 40L)));

        var resolved = new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000))
                .forRoles(Set.of(roleId));

        assertEquals(GIB, resolved.maximumBytes());
        assertEquals(40, resolved.maximumFiles());
    }

    @Test
    void disabledAndUnassignedRolesDoNotConstrainTheFallback() {
        var roles = mock(RoleRepository.class);
        var userId = UUID.randomUUID();
        var disabledRole = UUID.randomUUID();
        when(roles.quotaLimits(Set.of(disabledRole))).thenReturn(List.of(
                new RoleRepository.QuotaLimit(disabledRole, false, 1L, 1L)));
        when(roles.assignedQuotaLimits(userId)).thenReturn(List.of());
        var adapter = new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000));

        var disabled = adapter.forRoles(Set.of(disabledRole));
        var unassigned = adapter.forUser(userId);

        assertEquals(GIB, disabled.maximumBytes());
        assertEquals(1_000, disabled.maximumFiles());
        assertEquals(GIB, unassigned.maximumBytes());
        assertEquals(1_000, unassigned.maximumFiles());
    }

    @Test
    void stillRejectsNullRoleIds() {
        var roles = mock(RoleRepository.class);
        var roleIds = new HashSet<UUID>();
        roleIds.add(null);

        assertThrows(ResponseStatusException.class,
                () -> new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000)).forRoles(roleIds));
    }
}
