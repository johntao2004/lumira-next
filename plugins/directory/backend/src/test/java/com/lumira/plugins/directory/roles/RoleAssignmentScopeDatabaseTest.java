package com.lumira.plugins.directory.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.UserDirectoryPort;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/**
 * An organization-scoped assigner must not grant a role whose data scope is wider than the scope at
 * which they hold its permissions, including when the target account is their own.
 */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class RoleAssignmentScopeDatabaseTest {
    private static final String ACTOR = "v-ops";
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private JdbcClient client;
    private String schema;
    private UUID actorId;
    private UUID colleagueId;
    private UUID orgRoleId;
    private UUID globalRoleId;
    private UUID disabledRoleId;
    private RoleService service;
    private RoleRepository roles;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_role_scope_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        client = JdbcClient.create(source);
        jdbc.execute(
                """
                CREATE TABLE platform_user(
                    id uuid PRIMARY KEY, username text NOT NULL, enabled boolean NOT NULL,
                    deleted_at timestamptz, version bigint NOT NULL)
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_role(
                    id uuid PRIMARY KEY, code text NOT NULL, name text NOT NULL,
                    enabled boolean NOT NULL, version bigint NOT NULL, data_scope text NOT NULL,
                    file_quota_bytes_limit bigint, file_quota_files_limit bigint)
                """);
        jdbc.execute(
                "CREATE TABLE platform_role_authority(role_id uuid NOT NULL, authority text NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_user_role(user_id uuid NOT NULL, role_id uuid NOT NULL, PRIMARY KEY(user_id, role_id))");
        jdbc.execute(
                "CREATE TABLE platform_authority(user_id uuid NOT NULL, authority text NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_effective_authority(user_id uuid NOT NULL, authority text NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_registration_policy(id integer PRIMARY KEY, default_role_id uuid)");
        jdbc.execute(
                "CREATE FUNCTION platform_has_recovery_administrators() RETURNS boolean LANGUAGE sql AS $$ SELECT true $$");
        jdbc.execute(
                """
                CREATE VIEW platform_global_authority AS
                SELECT grants.user_id, grants.authority FROM platform_authority grants
                UNION
                SELECT assigned.user_id, grants.authority
                  FROM platform_user_role assigned
                  JOIN platform_role role ON role.id=assigned.role_id AND role.enabled AND role.data_scope='ALL'
                  JOIN platform_role_authority grants ON grants.role_id=role.id
                """);

        actorId = UUID.randomUUID();
        colleagueId = UUID.randomUUID();
        orgRoleId = UUID.randomUUID();
        globalRoleId = UUID.randomUUID();
        disabledRoleId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_user(id,username,enabled,deleted_at,version) VALUES (?,?,true,NULL,1)",
                actorId,
                ACTOR);
        jdbc.update(
                "INSERT INTO platform_user(id,username,enabled,deleted_at,version) VALUES (?,?,true,NULL,1)",
                colleagueId,
                "u-target");
        addRole(orgRoleId, "t-userops-org", "Org users", true, "ORGANIZATION");
        grant(
                orgRoleId,
                "platform:users:access",
                "platform:users:update",
                "platform:users:assign-roles");
        jdbc.update(
                "INSERT INTO platform_user_role(user_id,role_id) VALUES (?,?)", actorId, orgRoleId);
        for (String authority :
                java.util.List.of(
                        "platform:users:access",
                        "platform:users:update",
                        "platform:users:assign-roles"))
            jdbc.update(
                    "INSERT INTO platform_effective_authority(user_id,authority) VALUES (?,?)",
                    actorId,
                    authority);
        addRole(globalRoleId, "t-users-all", "All users", true, "ALL");
        grant(globalRoleId, "platform:users:access", "platform:users:update");
        addRole(disabledRoleId, "t-disabled", "Disabled org", false, "ORGANIZATION");
        grant(disabledRoleId, "platform:users:access", "platform:users:update");

        var guard = mock(AdministrationPort.class);
        when(guard.require(anyString(), anyString())).thenReturn(Set.of());
        doNothing().when(guard).requireOperationUser(anyString(), any(), any());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal(anyString(), anyString());
        when(guard.hasGlobal(anyString(), anyString())).thenReturn(false);
        var users = mock(UserDirectoryPort.class);
        when(users.version(any())).thenReturn(1L);
        roles = new RoleRepository(client);
        service =
                new RoleService(
                        roles, users, guard, mock(AuditPort.class), null, null, client, null, null);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void orgScopedAssignerCannotGrantOrSeeAGlobalRoleIncludingOnTheirOwnAccount() {
        var options = service.assignmentOptions(colleagueId, new PageRequest(1, 20, "t-"), ACTOR);
        var global =
                options.items().stream().filter(item -> item.id().equals(globalRoleId)).findFirst();
        assertTrue(global.isEmpty() || !global.get().assignable());

        var denied =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.assign(
                                        actorId,
                                        new RoleService.Assignment(
                                                1L, Set.of(orgRoleId, globalRoleId)),
                                        ACTOR));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("rolePermissionDelegationDenied", denied.getReason());
        assertFalse(roles.userRoles(actorId).contains(globalRoleId));
    }

    @Test
    void orgScopedAssignerCanAssignAndRevokeASameScopeRoleEvenWhenItIsDisabled() {
        service.assign(colleagueId, new RoleService.Assignment(1L, Set.of(disabledRoleId)), ACTOR);
        assertTrue(roles.userRoles(colleagueId).contains(disabledRoleId));

        service.assign(colleagueId, new RoleService.Assignment(1L, Set.of()), ACTOR);
        assertTrue(roles.userRoles(colleagueId).isEmpty());
    }

    @Test
    void importPreviewRejectsGlobalRolesForExistingAndNewAccounts() {
        for (UUID userId : java.util.Arrays.asList(colleagueId, null)) {
            var denied =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    service.validateImportAssignment(
                                            userId, Set.of(globalRoleId), ACTOR));
            assertEquals("rolePermissionDelegationDenied", denied.getReason());
        }
        assertTrue(roles.userRoles(colleagueId).isEmpty());
    }

    @Test
    void currentWiderRoleRemainsVisibleButCannotBeGrantedOrRevoked() {
        jdbc.update(
                "INSERT INTO platform_user_role(user_id,role_id) VALUES (?,?)",
                colleagueId,
                globalRoleId);
        var option =
                service
                        .assignmentOptions(colleagueId, new PageRequest(1, 20, "t-"), ACTOR)
                        .items()
                        .stream()
                        .filter(item -> item.id().equals(globalRoleId))
                        .findFirst()
                        .orElseThrow();
        assertFalse(option.assignable());
        var denied =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.assign(
                                        colleagueId,
                                        new RoleService.Assignment(1L, Set.of()),
                                        ACTOR));
        assertEquals("rolePermissionRevocationDenied", denied.getReason());
        assertTrue(roles.userRoles(colleagueId).contains(globalRoleId));
    }

    @Test
    void anUnrelatedWiderGrantDoesNotWidenAnotherPermission() {
        jdbc.update("UPDATE platform_role SET data_scope='SELF' WHERE id=?", orgRoleId);
        jdbc.update(
                "INSERT INTO platform_authority(user_id,authority) VALUES (?,?)",
                actorId,
                "platform:users:access");
        var denied =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.assign(
                                        colleagueId,
                                        new RoleService.Assignment(1L, Set.of(disabledRoleId)),
                                        ACTOR));
        assertEquals("rolePermissionDelegationDenied", denied.getReason());
        assertTrue(roles.userRoles(colleagueId).isEmpty());
    }

    @Test
    void widerScopeCanDelegateNarrowerAndEqualScopes() {
        jdbc.update("UPDATE platform_role SET data_scope='ALL' WHERE id=?", orgRoleId);
        service.assign(
                colleagueId,
                new RoleService.Assignment(1L, Set.of(disabledRoleId, globalRoleId)),
                ACTOR);
        assertEquals(
                Set.of(disabledRoleId, globalRoleId), Set.copyOf(roles.userRoles(colleagueId)));
    }

    private void addRole(UUID id, String code, String name, boolean enabled, String dataScope) {
        jdbc.update(
                "INSERT INTO platform_role(id,code,name,enabled,version,data_scope) VALUES (?,?,?,?,0,?)",
                id,
                code,
                name,
                enabled,
                dataScope);
    }

    private void grant(UUID roleId, String... authorities) {
        for (String authority : authorities)
            jdbc.update(
                    "INSERT INTO platform_role_authority(role_id,authority) VALUES (?,?)",
                    roleId,
                    authority);
    }
}
