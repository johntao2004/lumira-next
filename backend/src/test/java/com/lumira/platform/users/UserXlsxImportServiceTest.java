package com.lumira.platform.users;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.DocumentWorkerClient;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.TemplateUserDataPort;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import com.lumira.platform.identity.api.UserProfileValuesPort;
import com.lumira.platform.organizations.OrganizationService;
import com.lumira.platform.roles.RoleRepository;
import com.lumira.platform.roles.RoleService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class UserXlsxImportServiceTest {
    private static final String ACTOR = "admin";

    @Test
    void updateOnlyUsernameIsMatchableButNeverBecomesAnUpdateField() {
        var target = new UserSummary(UUID.randomUUID(), "fixture", "Before", true, 4, null, null);
        var repository = mock(UserRepository.class);
        when(repository.findByUsernameVisible(
                        "fixture",
                        ACTOR,
                        null,
                        PlatformOperation.USER_IMPORT.permission(),
                        PlatformPageId.USERS.accessPermission()))
                .thenReturn(target);
        var userData = mock(TemplateUserDataPort.class);
        when(userData.readValues(target.id(), ACTOR)).thenReturn(Map.of("displayName", "Before"));
        var service = service(repository, userData, profileSchema());

        var row =
                service.planRow(
                        2,
                        Map.of("username", "fixture", "displayName", "After"),
                        "update",
                        Map.of("fixture", 1),
                        ACTOR);

        assertEquals("update", row.action());
        assertEquals(target.id(), row.matchedUserId());
        assertEquals(Map.of("displayName", "After"), row.changes());
        assertFalse(row.changes().containsKey("username"));
    }

    @Test
    void updateOnlyFieldCatalogKeepsUsernameAsWritableMatchKey() {
        var schema = profileSchema();
        var adapter =
                new TemplateUserDataAdapter(
                        new AllowUpdateOnlyGuard(),
                        mock(UserService.class),
                        schema,
                        mock(UserProfileValuesPort.class),
                        mock(RoleService.class),
                        mock(RoleRepository.class),
                        mock(OrganizationService.class),
                        mock(JdbcClient.class),
                        new ObjectMapper());

        var fields = adapter.importableFields(ACTOR);

        assertTrue(
                fields.stream()
                        .anyMatch(field -> field.key().equals("username") && field.writable()));
        assertFalse(fields.stream().anyMatch(field -> field.key().equals("password")));
    }

    @Test
    void assignmentOnlyCanUseUsernameAndDownloadStandardTemplateWithoutReadableOldRoles() {
        var guard = new AssignOnlyGuard();
        var schema = profileSchema();
        var adapter =
                new TemplateUserDataAdapter(
                        guard,
                        mock(UserService.class),
                        schema,
                        mock(UserProfileValuesPort.class),
                        mock(RoleService.class),
                        mock(RoleRepository.class),
                        mock(OrganizationService.class),
                        mock(JdbcClient.class),
                        new ObjectMapper());

        var importable = adapter.importableFields(ACTOR);
        assertTrue(
                importable.stream()
                        .anyMatch(field -> field.key().equals("username") && field.writable()));
        assertFalse(
                importable.stream()
                        .anyMatch(field -> field.key().equals("displayName") && field.writable()));
        assertTrue(
                importable.stream()
                        .anyMatch(field -> field.key().equals("roles") && field.writable()));
        assertTrue(
                importable.stream()
                        .anyMatch(
                                field -> field.key().equals("organizations") && field.writable()));
        assertFalse(
                adapter.readableFields(ACTOR).stream()
                        .anyMatch(field -> field.key().equals("roles")));

        UUID userId = UUID.randomUUID();
        var repository = mock(UserRepository.class);
        when(repository.findByUsernameVisible(
                        "fixture",
                        ACTOR,
                        null,
                        PlatformOperation.USER_IMPORT.permission(),
                        PlatformPageId.USERS.accessPermission()))
                .thenReturn(new UserSummary(userId, "fixture", "Existing", true, 4, null, null));
        var service = serviceWithGuard(repository, adapter, schema, guard);
        byte[] workbook = {1, 2, 3};
        try (MockedStatic<DocumentWorkerClient> worker = mockStatic(DocumentWorkerClient.class)) {
            worker.when(() -> DocumentWorkerClient.writeWorkbook(anyList())).thenReturn(workbook);

            assertArrayEquals(workbook, service.standardTemplate(ACTOR));

            worker.verify(
                    () ->
                            DocumentWorkerClient.writeWorkbook(
                                    List.of(List.of("username", "roles", "organizations"))));
        }

        var assigned =
                service.planRow(
                        2,
                        Map.of("username", "fixture", "roles", "", "organizations", ""),
                        "update",
                        Map.of("fixture", 1),
                        ACTOR);
        assertEquals("update", assigned.action());
        assertFalse(assigned.changes().containsKey("username"));
        assertTrue(
                guard.requiredUserPermissions.contains(
                        PlatformOperation.USER_ASSIGN_ROLES.permission()));
        assertTrue(
                guard.requiredUserPermissions.contains(
                        PlatformOperation.USER_ASSIGN_ORGANIZATIONS.permission()));

        var targetDenied =
                new AssignOnlyGuard(Set.of(PlatformOperation.USER_ASSIGN_ROLES.permission()));
        var deniedService = serviceWithGuard(repository, adapter, schema, targetDenied);
        var denied =
                deniedService.planRow(
                        2,
                        Map.of("username", "fixture", "roles", "", "organizations", ""),
                        "update",
                        Map.of("fixture", 1),
                        ACTOR);
        assertEquals("invalid", denied.action());
        assertTrue(denied.errors().contains("forbidden"));
    }

    @Test
    void upsertNewRowWithoutDisplayNameIsRejectedDuringPreviewPlanning() {
        var repository = mock(UserRepository.class);
        when(repository.findByUsernameVisible(
                        "fixture",
                        ACTOR,
                        null,
                        PlatformOperation.USER_IMPORT.permission(),
                        PlatformPageId.USERS.accessPermission()))
                .thenReturn(null);
        var service = service(repository, mock(TemplateUserDataPort.class), profileSchema());

        var row =
                service.planRow(
                        2, Map.of("username", "fixture"), "upsert", Map.of("fixture", 1), ACTOR);

        assertEquals("invalid", row.action());
        assertTrue(row.errors().contains("missingDisplayName"));
    }

    @Test
    void displayNameOverLimitIsRejectedDuringPreviewPlanning() {
        var repository = mock(UserRepository.class);
        when(repository.findByUsernameVisible(
                        "fixture",
                        ACTOR,
                        null,
                        PlatformOperation.USER_IMPORT.permission(),
                        PlatformPageId.USERS.accessPermission()))
                .thenReturn(null);
        var service = service(repository, mock(TemplateUserDataPort.class), profileSchema());

        var row =
                service.planRow(
                        2,
                        Map.of("username", "fixture", "displayName", "x".repeat(201)),
                        "upsert",
                        Map.of("fixture", 1),
                        ACTOR);

        assertEquals("invalid", row.action());
        assertTrue(row.errors().contains("invalidProfileValues"));
    }

    @Test
    void newAccountCanImportBasicProfileWhenInitialAccountVersionIsZero() {
        UUID id = UUID.randomUUID();
        var created = new UserSummary(id, "fixture", "Fixture", true, 0, null, null);
        var updated = new UserSummary(id, "fixture", "Fixture", true, 1, null, null);
        var users = mock(UserService.class);
        when(users.createImported("fixture", "Fixture", ACTOR)).thenReturn(created);
        when(users.find(id, ACTOR)).thenReturn(created);
        when(users.updateBasicProfileFields(id, 0, Map.of("realName", "Example"), ACTOR))
                .thenReturn(updated);
        var editor = mock(UserEditorService.class);
        var service =
                service(
                        mock(UserRepository.class),
                        mock(TemplateUserDataPort.class),
                        profileSchema(),
                        users,
                        editor);
        var row =
                new UserXlsxImportService.RowPlan(
                        2,
                        "create",
                        null,
                        null,
                        "fixture",
                        Map.of("displayName", "Fixture", "realName", "Example"),
                        Map.of(),
                        Map.of(),
                        List.of());

        service.applyRow(row, null, ACTOR);

        verify(users).updateBasicProfileFields(id, 0, Map.of("realName", "Example"), ACTOR);
        org.mockito.Mockito.verifyNoInteractions(editor);
    }

    private static UserXlsxImportService service(
            UserRepository repository,
            TemplateUserDataPort userData,
            ProfileSchemaPort profileSchema) {
        return service(
                repository,
                userData,
                profileSchema,
                mock(UserService.class),
                mock(UserEditorService.class));
    }

    private static UserXlsxImportService service(
            UserRepository repository,
            TemplateUserDataPort userData,
            ProfileSchemaPort profileSchema,
            UserService users,
            UserEditorService editor) {
        return serviceWithGuard(
                repository, userData, profileSchema, users, editor, new AllowUpdateOnlyGuard());
    }

    private static UserXlsxImportService serviceWithGuard(
            UserRepository repository,
            TemplateUserDataPort userData,
            ProfileSchemaPort profileSchema,
            AdministrationPort guard) {
        return serviceWithGuard(
                repository,
                userData,
                profileSchema,
                mock(UserService.class),
                mock(UserEditorService.class),
                guard);
    }

    private static UserXlsxImportService serviceWithGuard(
            UserRepository repository,
            TemplateUserDataPort userData,
            ProfileSchemaPort profileSchema,
            UserService users,
            UserEditorService editor,
            AdministrationPort guard) {
        UserImportStore store = mock(UserImportStore.class);
        var values = mock(UserProfileValuesPort.class);
        return new UserXlsxImportService(
                store,
                repository,
                users,
                editor,
                values,
                profileSchema,
                userData,
                guard,
                mock(AuditPort.class),
                mock(JdbcClient.class),
                mock(PlatformTransactionManager.class));
    }

    @Test
    void importPreviewRecordsRoleDelegationBeforeCommit() {
        UUID roleId = UUID.randomUUID();
        var repository = mock(UserRepository.class);
        var jdbc = mock(JdbcClient.class);
        when(jdbc.sql(anyString()))
                .thenAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            var spec = mock(JdbcClient.StatementSpec.class);
                            var mapped = mock(JdbcClient.MappedQuerySpec.class);
                            when(spec.param(anyString(), any())).thenReturn(spec);
                            when(spec.query(any(RowMapper.class))).thenReturn(mapped);
                            if (sql.contains("version"))
                                when(mapped.list()).thenReturn(List.of(Map.entry("qa", 3L)));
                            else when(mapped.list()).thenReturn(List.of(roleId));
                            return spec;
                        });
        var roles = mock(RoleService.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "rolePermissionDelegationDenied"))
                .when(roles)
                .validateImportAssignment(isNull(), eq(Set.of(roleId)), eq(ACTOR));
        var service =
                new UserXlsxImportService(
                        mock(UserImportStore.class),
                        repository,
                        mock(UserService.class),
                        mock(UserEditorService.class),
                        mock(UserProfileValuesPort.class),
                        profileSchema(),
                        mock(TemplateUserDataPort.class),
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        jdbc,
                        mock(PlatformTransactionManager.class),
                        roles);

        var row =
                service.planRow(
                        2,
                        Map.of("username", "fixture", "displayName", "Fixture", "roles", "qa"),
                        "create",
                        Map.of("fixture", 1),
                        ACTOR);

        assertEquals("invalid", row.action());
        assertTrue(row.errors().contains("rolePermissionDelegationDenied"));
    }

    private static ProfileSchemaPort profileSchema() {
        var schema = mock(ProfileSchemaPort.class);
        when(schema.read())
                .thenReturn(new ProfileSchemaPort.Snapshot(0, new ProfileFields(List.of())));
        return schema;
    }

    private static final class AllowUpdateOnlyGuard implements AdministrationPort {
        @Override
        public Set<String> require(String actor, String permission) {
            if (PlatformOperation.USER_CREATE.permission().equals(permission))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
            return Set.of(permission);
        }

        @Override
        public void requireUser(String actor, UUID target, String permission) {}

        @Override
        public void requireGlobal(String actor, String permission) {}

        @Override
        public boolean hasGlobal(String actor, String permission) {
            return !PlatformOperation.USER_CREATE.permission().equals(permission);
        }
    }

    private static final class AssignOnlyGuard implements AdministrationPort {
        private static final Set<String> PERMISSIONS =
                Set.of(
                        PlatformPageId.USERS.accessPermission(),
                        PlatformOperation.USER_IMPORT.permission(),
                        PlatformOperation.USER_ASSIGN_ROLES.permission(),
                        PlatformOperation.USER_ASSIGN_ORGANIZATIONS.permission(),
                        PlatformPageId.ORGANIZATIONS.accessPermission());
        private final Set<String> deniedUserPermissions;
        private final Set<String> requiredUserPermissions = new java.util.HashSet<>();

        private AssignOnlyGuard() {
            this(Set.of());
        }

        private AssignOnlyGuard(Set<String> deniedUserPermissions) {
            this.deniedUserPermissions = deniedUserPermissions;
        }

        @Override
        public Set<String> require(String actor, String permission) {
            if (!PERMISSIONS.contains(permission))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
            return Set.of(permission);
        }

        @Override
        public void requireUser(String actor, UUID target, String permission) {
            if (PlatformOperation.USER_ASSIGN_ROLES.permission().equals(permission)
                    || PlatformOperation.USER_ASSIGN_ORGANIZATIONS.permission().equals(permission))
                requiredUserPermissions.add(permission);
            if (deniedUserPermissions.contains(permission))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }

        @Override
        public void requireGlobal(String actor, String permission) {}

        @Override
        public boolean hasGlobal(String actor, String permission) {
            return false;
        }
    }
}
