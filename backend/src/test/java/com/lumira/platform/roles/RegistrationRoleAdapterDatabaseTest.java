package com.lumira.platform.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.UserDirectoryPort;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/** Public registration only accepts the self-scoped ordinary-user seed role. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class RegistrationRoleAdapterDatabaseTest {
    private static final String ADMIN_ACTOR = "manager";
    private static final UUID ADMIN_ROLE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000052");
    private static final UUID USER_ROLE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000053");
    private static final List<String> USER_ROLE_AUTHORITIES =
            List.of(
                    "platform:feedback:access",
                    "platform:feedback:read",
                    "platform:feedback:create");

    private JdbcTemplate adminJdbc;
    private JdbcTemplate jdbc;
    private RegistrationRoleAdapter roles;
    private String schema;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        adminJdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_registration_role_" + UUID.randomUUID().toString().replace("-", "");
        adminJdbc.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        roles = new RegistrationRoleAdapter(JdbcClient.create(dataSource));
        jdbc.execute(TestSchema.table("platform_role"));
        jdbc.execute(TestSchema.table("platform_role_authority"));
        jdbc.execute(TestSchema.table("platform_registration_policy"));
        TestSchema.seeds("platform_role").forEach(jdbc::execute);
        TestSchema.seeds("platform_role_authority").forEach(jdbc::execute);
        TestSchema.seeds("platform_registration_policy").forEach(jdbc::execute);
    }

    @AfterEach
    void tearDown() {
        if (adminJdbc != null && schema != null)
            adminJdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void defaultUserRoleWithFeedbackAccessReadAndCreateIsEligible() {
        var candidate = roles.candidate(USER_ROLE_ID);

        assertTrue(candidate.eligible());
        assertEquals("user", candidate.code());
        assertTrue(roles.isDefaultRole(USER_ROLE_ID));
        roles.lockAndRequireEligible(USER_ROLE_ID);
    }

    @Test
    void administratorRoleCannotBeSelectedForPublicRegistration() {
        var candidate = roles.candidate(ADMIN_ROLE_ID);

        assertFalse(candidate.eligible());
        assertEquals("registrationRoleAdministrator", candidate.reason());
        assertFalse(roles.isDefaultRole(ADMIN_ROLE_ID));
        assertThrows(
                ResponseStatusException.class, () -> roles.lockAndRequireEligible(ADMIN_ROLE_ID));
    }

    @Test
    void defaultUserRoleCanBeUpdatedWithItsFeedbackPageGrant() {
        var roleRepository = mock(RoleRepository.class);
        var guard = mock(AdministrationPort.class);
        var role =
                new RoleRepository.Role(
                        USER_ROLE_ID,
                        "user",
                        "普通用户",
                        true,
                        1L,
                        "SELF",
                        null,
                        null,
                        USER_ROLE_AUTHORITIES,
                        true);
        when(roleRepository.find(USER_ROLE_ID)).thenReturn(role);
        when(roleRepository.hasAdministrators()).thenReturn(true);
        doReturn(Set.of(PlatformOperation.ROLE_UPDATE.permission()))
                .when(guard)
                .requireOperationGlobal(ADMIN_ACTOR, PlatformOperation.ROLE_UPDATE);
        var service =
                new RoleService(
                        roleRepository,
                        mock(UserDirectoryPort.class),
                        guard,
                        mock(AuditPort.class),
                        null,
                        null,
                        JdbcClient.create(jdbc.getDataSource()),
                        null,
                        null);
        var input =
                new RoleService.RoleInput(
                        "user",
                        "普通用户",
                        true,
                        1L,
                        Set.copyOf(USER_ROLE_AUTHORITIES),
                        "SELF",
                        null,
                        null);

        service.update(USER_ROLE_ID, input, ADMIN_ACTOR);

        verify(roleRepository).update(USER_ROLE_ID, "普通用户", true, "SELF", null, null);
        verify(roleRepository, never()).authorities(any(UUID.class), any());
    }
}
