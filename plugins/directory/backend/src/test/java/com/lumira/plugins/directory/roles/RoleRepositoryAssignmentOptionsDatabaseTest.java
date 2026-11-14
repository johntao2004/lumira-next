package com.lumira.plugins.directory.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.platform.common.PageRequest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class RoleRepositoryAssignmentOptionsDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private RoleRepository roles;
    private String schema;
    private UUID assignedOne;
    private UUID assignedTwo;
    private UUID delegableCandidate;
    private UUID unrelatedAdmin;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_role_options_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        roles = new RoleRepository(JdbcClient.create(source));
        jdbc.execute(
                "CREATE TABLE platform_role(id uuid PRIMARY KEY, code text NOT NULL, name text NOT NULL, enabled boolean NOT NULL, data_scope text NOT NULL, file_quota_bytes_limit bigint, file_quota_files_limit bigint)");
        jdbc.execute(
                "CREATE TABLE platform_role_authority(role_id uuid NOT NULL, authority text NOT NULL)");

        assignedOne = UUID.randomUUID();
        assignedTwo = UUID.randomUUID();
        delegableCandidate = UUID.randomUUID();
        unrelatedAdmin = UUID.randomUUID();
        addRole(assignedOne, "hr-staff", "HR Staff", true, "SELF", "platform:roles:create");
        addRole(assignedTwo, "hr-operator", "HR Operator", true, "SELF", "platform:users:delete");
        addRole(
                delegableCandidate,
                "hr-delegable",
                "HR Delegable",
                true,
                "SELF",
                "platform:users:update");
        addRole(
                unrelatedAdmin,
                "hr-superuser",
                "HR Superuser",
                true,
                "ALL",
                "platform:roles:delete");
    }

    @AfterEach
    void cleanUp() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void readOnlyLookupReturnsOnlyAssignedRowsAcrossSearchAndPages() {
        var assigned = Set.of(assignedOne, assignedTwo);
        var first =
                roles.assignmentOptions(
                        new PageRequest(1, 1, "HR"),
                        assigned,
                        Set.of(),
                        Set.of(),
                        RoleRepository.AuthorityScopeCeiling.none(),
                        false);
        var second =
                roles.assignmentOptions(
                        new PageRequest(2, 1, "HR"),
                        assigned,
                        Set.of(),
                        Set.of(),
                        RoleRepository.AuthorityScopeCeiling.none(),
                        false);

        assertEquals(2, first.total());
        assertEquals(assignedTwo, first.items().getFirst().id());
        assertEquals(assignedOne, second.items().getFirst().id());
        assertFalse(first.items().getFirst().assignable());
        assertFalse(second.items().getFirst().assignable());
        assertTrue(first.items().stream().allMatch(option -> assigned.contains(option.id())));
        assertTrue(second.items().stream().allMatch(option -> assigned.contains(option.id())));
    }

    @Test
    void assignmentLookupKeepsCurrentNonDelegableRoleAndFiltersCandidates() {
        var result =
                roles.assignmentOptions(
                        new PageRequest(1, 10, "HR"),
                        Set.of(assignedOne),
                        Set.of("platform:users:update"),
                        Set.of(
                                "platform:roles:create",
                                "platform:users:delete",
                                "platform:users:update",
                                "platform:roles:delete"),
                        RoleRepository.AuthorityScopeCeiling.unrestricted(
                                Set.of("platform:users:update")),
                        true);
        var byId =
                result.items().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        RoleRepository.AssignmentOption::id, option -> option));

        assertEquals(2, result.total());
        assertFalse(byId.get(assignedOne).assignable());
        assertTrue(byId.get(delegableCandidate).assignable());
        assertFalse(byId.containsKey(assignedTwo));
        assertFalse(byId.containsKey(unrelatedAdmin));
    }

    @Test
    void organizationCeilingExcludesAWiderRoleThatContainsOnlyDelegablePermissions() {
        UUID wider = UUID.randomUUID();
        UUID sameScope = UUID.randomUUID();
        addRole(wider, "hr-global", "HR Global", true, "ALL", "platform:users:update");
        addRole(sameScope, "hr-org", "HR Org", true, "ORGANIZATION", "platform:users:update");
        var ceiling =
                new RoleRepository.AuthorityScopeCeiling(
                        Set.of("platform:users:update"),
                        Set.of("platform:users:update"),
                        Set.of(),
                        Set.of());

        var result =
                roles.assignmentOptions(
                        new PageRequest(1, 20, "HR"),
                        Set.of(),
                        Set.of("platform:users:update"),
                        Set.of("platform:users:update"),
                        ceiling,
                        true);
        var ids = result.items().stream().map(RoleRepository.AssignmentOption::id).toList();

        assertTrue(ids.contains(delegableCandidate));
        assertTrue(ids.contains(sameScope));
        assertFalse(ids.contains(wider));
    }

    @Test
    void emptyReadOnlyAssignmentReturnsNoRoleCatalogRows() {
        var result =
                roles.assignmentOptions(
                        new PageRequest(1, 10, ""),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        RoleRepository.AuthorityScopeCeiling.none(),
                        false);

        assertEquals(0, result.total());
        assertTrue(result.items().isEmpty());
    }

    private void addRole(
            UUID id,
            String code,
            String name,
            boolean enabled,
            String dataScope,
            String authority) {
        jdbc.update(
                "INSERT INTO platform_role(id,code,name,enabled,data_scope) VALUES(?,?,?,?,?)",
                id,
                code,
                name,
                enabled,
                dataScope);
        jdbc.update(
                "INSERT INTO platform_role_authority(role_id,authority) VALUES(?,?)",
                id,
                authority);
    }
}
