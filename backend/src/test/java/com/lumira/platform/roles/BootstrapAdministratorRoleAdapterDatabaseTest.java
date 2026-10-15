package com.lumira.platform.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lumira.platform.TestSchema;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Fixed-role bootstrap assignment checks against an isolated PostgreSQL schema. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class BootstrapAdministratorRoleAdapterDatabaseTest {
    private JdbcTemplate adminJdbc;
    private JdbcTemplate jdbc;
    private BootstrapAdministratorRoleAdapter roles;
    private String schema;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        adminJdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_bootstrap_role_" + UUID.randomUUID().toString().replace("-", "");
        adminJdbc.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        roles = new BootstrapAdministratorRoleAdapter(JdbcClient.create(dataSource));
        jdbc.execute(TestSchema.table("platform_role"));
        jdbc.execute(TestSchema.table("platform_role_authority"));
        jdbc.execute(TestSchema.table("platform_user_role"));
        jdbc.execute(TestSchema.table("platform_authority"));
        TestSchema.seeds("platform_role").forEach(jdbc::execute);
        TestSchema.seeds("platform_role_authority").forEach(jdbc::execute);
    }

    @AfterEach
    void tearDown() {
        if (adminJdbc != null && schema != null)
            adminJdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void assignsOnlyTheFixedEnabledAdminRoleAndPreservesDirectGrants() {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_authority(user_id,authority) VALUES(?, 'platform:custom:retained')",
                userId);

        roles.assignAdministratorRole(userId);

        assertEquals(
                BootstrapAdministratorRoleAdapter.ADMINISTRATOR_ROLE_ID,
                jdbc.queryForObject(
                        "SELECT role_id FROM platform_user_role WHERE user_id=?",
                        UUID.class,
                        userId));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_authority WHERE user_id=? AND authority='platform:custom:retained'",
                        Integer.class,
                        userId));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_authority WHERE user_id=?",
                        Integer.class,
                        userId));
    }

    @Test
    void failsClosedWhenFixedAdminRoleCodeDoesNotMatch() {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "UPDATE platform_role SET code='support' WHERE id=?",
                BootstrapAdministratorRoleAdapter.ADMINISTRATOR_ROLE_ID);

        assertThrows(IllegalStateException.class, () -> roles.assignAdministratorRole(userId));

        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM platform_user_role", Integer.class));
    }

    @Test
    void failsClosedWhenOnlyAnAdminCodeExistsAtAnotherId() {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "UPDATE platform_role SET id=? WHERE id=?",
                UUID.randomUUID(),
                BootstrapAdministratorRoleAdapter.ADMINISTRATOR_ROLE_ID);

        assertThrows(IllegalStateException.class, () -> roles.assignAdministratorRole(userId));

        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM platform_user_role", Integer.class));
    }

    @Test
    void failsClosedWhenFixedAdminRoleIsDisabled() {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "UPDATE platform_role SET enabled=false WHERE id=?",
                BootstrapAdministratorRoleAdapter.ADMINISTRATOR_ROLE_ID);

        assertThrows(IllegalStateException.class, () -> roles.assignAdministratorRole(userId));

        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM platform_user_role", Integer.class));
    }

    @Test
    void failsClosedWhenFixedAdminRoleDoesNotHaveGlobalScope() {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "UPDATE platform_role SET data_scope='SELF' WHERE id=?",
                BootstrapAdministratorRoleAdapter.ADMINISTRATOR_ROLE_ID);

        assertThrows(IllegalStateException.class, () -> roles.assignAdministratorRole(userId));

        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM platform_user_role", Integer.class));
    }
}
