package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.FileQuotaPolicyPort;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RoleQuotaPolicyAdapterTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void roleLimitsCanExceedSystemFallback() {
        var roles = mock(FileRoleQuotaRepository.class);
        var roleId = UUID.randomUUID();
        when(roles.quotaLimits(Set.of(roleId)))
                .thenReturn(
                        List.of(
                                new FileRoleQuotaRepository.QuotaLimit(
                                        roleId, true, 4 * GIB, 2_000L)));

        var resolved =
                new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000))
                        .forRoles(Set.of(roleId));

        assertEquals(4 * GIB, resolved.maximumBytes());
        assertEquals(2_000, resolved.maximumFiles());
    }

    @Test
    void minimumIsAppliedIndependentlyForEachDimension() {
        var roles = mock(FileRoleQuotaRepository.class);
        var storageRole = UUID.randomUUID();
        var countRole = UUID.randomUUID();
        when(roles.quotaLimits(Set.of(storageRole, countRole)))
                .thenReturn(
                        List.of(
                                new FileRoleQuotaRepository.QuotaLimit(
                                        storageRole, true, 4 * GIB, null),
                                new FileRoleQuotaRepository.QuotaLimit(
                                        countRole, true, 8 * GIB, 250L)));

        var resolved =
                new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000))
                        .forRoles(Set.of(storageRole, countRole));

        assertEquals(4 * GIB, resolved.maximumBytes());
        assertEquals(250, resolved.maximumFiles());
    }

    @Test
    void usesSystemDefaultOnlyForDimensionsWithNoEnabledRoleLimit() {
        var roles = mock(FileRoleQuotaRepository.class);
        var roleId = UUID.randomUUID();
        when(roles.quotaLimits(Set.of(roleId)))
                .thenReturn(
                        List.of(new FileRoleQuotaRepository.QuotaLimit(roleId, true, null, 40L)));

        var resolved =
                new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000))
                        .forRoles(Set.of(roleId));

        assertEquals(GIB, resolved.maximumBytes());
        assertEquals(40, resolved.maximumFiles());
    }

    @Test
    void zeroByteRoleMeansUnlimitedWhenNoFiniteByteRoleExists() {
        var roles = mock(FileRoleQuotaRepository.class);
        var unlimitedRole = UUID.randomUUID();
        var nullRole = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var assignedRows =
                List.of(
                        new FileRoleQuotaRepository.QuotaLimit(unlimitedRole, true, 0L, null),
                        new FileRoleQuotaRepository.QuotaLimit(nullRole, true, null, null));
        when(roles.quotaLimits(Set.of(unlimitedRole, nullRole))).thenReturn(assignedRows);
        when(roles.assignedQuotaLimits(userId)).thenReturn(assignedRows);

        var adapter = new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000));
        var resolved = adapter.forRoles(Set.of(unlimitedRole, nullRole));
        var assigned = adapter.forUser(userId);

        assertEquals(0, resolved.maximumBytes());
        assertEquals(1_000, resolved.maximumFiles());
        assertEquals(0, assigned.maximumBytes());
        assertEquals(1_000, assigned.maximumFiles());
    }

    @Test
    void finiteByteRoleStillConstrainsAnUnlimitedByteRole() {
        var roles = mock(FileRoleQuotaRepository.class);
        var unlimitedRole = UUID.randomUUID();
        var finiteRole = UUID.randomUUID();
        when(roles.quotaLimits(Set.of(unlimitedRole, finiteRole)))
                .thenReturn(
                        List.of(
                                new FileRoleQuotaRepository.QuotaLimit(
                                        unlimitedRole, true, 0L, null),
                                new FileRoleQuotaRepository.QuotaLimit(
                                        finiteRole, true, 4 * GIB, null)));

        var resolved =
                new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000))
                        .forRoles(Set.of(unlimitedRole, finiteRole));

        assertEquals(4 * GIB, resolved.maximumBytes());
    }

    @Test
    void positiveFileCountLimitWinsOverUnlimitedRoleAndFallback() {
        var roles = mock(FileRoleQuotaRepository.class);
        var unlimitedRole = UUID.randomUUID();
        var finiteRole = UUID.randomUUID();
        when(roles.quotaLimits(Set.of(unlimitedRole, finiteRole)))
                .thenReturn(
                        List.of(
                                new FileRoleQuotaRepository.QuotaLimit(
                                        unlimitedRole, true, null, 0L),
                                new FileRoleQuotaRepository.QuotaLimit(
                                        finiteRole, true, null, 12L)));

        var resolved =
                new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000))
                        .forRoles(Set.of(unlimitedRole, finiteRole));

        assertEquals(GIB, resolved.maximumBytes());
        assertEquals(12, resolved.maximumFiles());
    }

    @Test
    void allZeroFileCountRolesExplicitlyRemoveTheFallbackLimit() {
        var roles = mock(FileRoleQuotaRepository.class);
        var firstRole = UUID.randomUUID();
        var secondRole = UUID.randomUUID();
        when(roles.quotaLimits(Set.of(firstRole, secondRole)))
                .thenReturn(
                        List.of(
                                new FileRoleQuotaRepository.QuotaLimit(firstRole, true, null, 0L),
                                new FileRoleQuotaRepository.QuotaLimit(
                                        secondRole, true, null, 0L)));

        var resolved =
                new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000))
                        .forRoles(Set.of(firstRole, secondRole));

        assertEquals(GIB, resolved.maximumBytes());
        assertEquals(0, resolved.maximumFiles());
    }

    @Test
    void disabledAndUnassignedRolesDoNotConstrainTheFallback() {
        var roles = mock(FileRoleQuotaRepository.class);
        var userId = UUID.randomUUID();
        var disabledRole = UUID.randomUUID();
        when(roles.quotaLimits(Set.of(disabledRole)))
                .thenReturn(
                        List.of(
                                new FileRoleQuotaRepository.QuotaLimit(
                                        disabledRole, false, 1L, 1L)));
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
    void readsCurrentSystemDefaultsForEachResolution() {
        var roles = mock(FileRoleQuotaRepository.class);
        var userId = UUID.randomUUID();
        when(roles.assignedQuotaLimits(userId)).thenReturn(List.of());
        var defaults = new AtomicReference<>(new FileQuotaPolicyPort.Limits(GIB, 1_000));
        var adapter = new RoleQuotaPolicyAdapter(roles, defaults::get);

        assertEquals(new FileQuotaPolicyPort.Limits(GIB, 1_000), adapter.forUser(userId));
        defaults.set(new FileQuotaPolicyPort.Limits(2 * GIB, 2_000));
        assertEquals(new FileQuotaPolicyPort.Limits(2 * GIB, 2_000), adapter.forUser(userId));
    }

    @Test
    void stillRejectsNullRoleIds() {
        var roles = mock(FileRoleQuotaRepository.class);
        var roleIds = new HashSet<UUID>();
        roleIds.add(null);

        assertThrows(
                ResponseStatusException.class,
                () ->
                        new RoleQuotaPolicyAdapter(roles, new FileQuota(GIB, 1_000))
                                .forRoles(roleIds));
    }
}
