package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 在临时角色和 schema 中证明降权、迁移 DDL 与间接提权拒绝。 Prove restricted roles, migration DDL and indirect-elevation
 * rejection with disposable identities.
 */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class DatabasePrivilegeConfigurationTest {
    @Test
    void schemaOwnerCanMigrateButCannotEscalateClusterPrivileges() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        var adminSource =
                new DriverManagerDataSource(
                        url,
                        System.getenv("LUMIRA_TEST_DB_USER"),
                        System.getenv("LUMIRA_TEST_DB_PASSWORD"));
        var admin = new JdbcTemplate(adminSource);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String role = "qa_app_" + suffix;
        String middle = "qa_middle_" + suffix;
        String privileged = "qa_priv_" + suffix;
        String password = UUID.randomUUID().toString();
        String schema = "qa_schema_" + suffix;
        admin.execute(
                "CREATE ROLE "
                        + role
                        + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
                        + password
                        + "'");
        try {
            admin.execute("CREATE SCHEMA " + schema + " AUTHORIZATION " + role);
            var source = new DriverManagerDataSource(url, role, password);
            var app = new JdbcTemplate(source);
            assertDoesNotThrow(() -> DatabasePrivilegeConfiguration.verify(source));
            app.execute("CREATE TABLE " + schema + ".plugin_probe(id bigint PRIMARY KEY)");
            app.execute("ALTER TABLE " + schema + ".plugin_probe ADD COLUMN value text");
            app.update("INSERT INTO " + schema + ".plugin_probe VALUES (1, 'retained')");
            assertEquals(
                    "retained",
                    app.queryForObject(
                            "SELECT value FROM " + schema + ".plugin_probe", String.class));
            assertThrows(Exception.class, () -> app.execute("CREATE ROLE qa_forbidden"));
            assertThrows(Exception.class, () -> app.execute("CREATE DATABASE qa_forbidden"));
            assertThrows(Exception.class, () -> app.execute("ALTER ROLE " + role + " SUPERUSER"));
            assertThrows(Exception.class, () -> app.execute("SELECT * FROM pg_authid"));
            admin.execute("CREATE ROLE " + privileged + " NOLOGIN CREATEDB");
            admin.execute("CREATE ROLE " + middle + " NOLOGIN");
            try {
                admin.execute("GRANT " + privileged + " TO " + middle);
                admin.execute("GRANT " + middle + " TO " + role);
                assertThrows(
                        IllegalStateException.class,
                        () -> DatabasePrivilegeConfiguration.verify(source));
            } finally {
                admin.execute("REVOKE " + middle + " FROM " + role);
                admin.execute("DROP ROLE " + middle);
                admin.execute("DROP ROLE " + privileged);
            }
            admin.execute("GRANT pg_read_all_data TO " + role);
            try {
                assertThrows(
                        IllegalStateException.class,
                        () -> DatabasePrivilegeConfiguration.verify(source));
            } finally {
                admin.execute("REVOKE pg_read_all_data FROM " + role);
            }
            assertDoesNotThrow(() -> DatabasePrivilegeConfiguration.verify(source));
            assertThrows(
                    IllegalStateException.class,
                    () -> DatabasePrivilegeConfiguration.verify(adminSource));
        } finally {
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            admin.execute("DROP ROLE " + role);
        }
    }
}
