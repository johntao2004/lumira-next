package com.lumira.plugins.directory.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.lumira.platform.TestSchema;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

/** Checks role/organization import references against the current B52 database model. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class UserImportCodeReferenceDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private UserXlsxImportService service;
    private String schema;

    @BeforeEach
    void createCurrentSchema() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_user_import_codes_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(TestSchema.table("platform_role"));
        TestSchema.indexes("platform_role").forEach(jdbc::execute);
        jdbc.execute(TestSchema.table("platform_organization"));
        TestSchema.indexes("platform_organization").forEach(jdbc::execute);
        service =
                new UserXlsxImportService(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        JdbcClient.create(dataSource),
                        mock(PlatformTransactionManager.class));
    }

    @AfterEach
    void dropDisposableSchema() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void mapsEnabledOrganizationCodesAndRejectsDisabledTargetsAndStaleVersions() {
        UUID activeId = UUID.randomUUID();
        UUID disabledId = UUID.randomUUID();
        insertOrganization(activeId, "org-active", true, 4);
        insertOrganization(disabledId, "org-disabled", false, 7);
        var errors = new HashSet<String>();

        assertEquals(Map.of("org-active", 4L), service.codeVersions("org-active", false, errors));
        assertTrue(errors.isEmpty());
        assertEquals(Set.of(activeId), service.resolveCodes("org-active", false));

        assertTrue(service.codeVersions("org-disabled", false, errors).isEmpty());
        assertTrue(errors.contains("invalidOrganizationCodes"));
        assertThrows(
                ResponseStatusException.class, () -> service.resolveCodes("org-disabled", false));

        var stale =
                new UserXlsxImportService.RowPlan(
                        2,
                        "update",
                        UUID.randomUUID(),
                        0L,
                        "fixture",
                        Map.of("organizations", "org-active"),
                        Map.of(),
                        Map.of("org-active", 4L),
                        List.of());
        service.validateCodeVersions(stale, false);
        jdbc.update("UPDATE platform_organization SET version=5 WHERE id=?", activeId);
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.validateCodeVersions(stale, false));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void mapsOnlyEnabledRoleCodes() {
        UUID activeId = UUID.randomUUID();
        UUID disabledId = UUID.randomUUID();
        insertRole(activeId, "role-active", true, 2);
        insertRole(disabledId, "role-disabled", false, 3);
        var errors = new HashSet<String>();

        assertEquals(Map.of("role-active", 2L), service.codeVersions("role-active", true, errors));
        assertTrue(service.resolveCodes("role-active", true).contains(activeId));
        assertFalse(
                service.codeVersions("role-disabled", true, errors).containsKey("role-disabled"));
        assertTrue(errors.contains("invalidRoleCodes"));
        assertThrows(
                ResponseStatusException.class, () -> service.resolveCodes("role-disabled", true));
    }

    private void insertOrganization(UUID id, String code, boolean enabled, long version) {
        jdbc.update(
                "INSERT INTO platform_organization(id,parent_id,code,name,enabled,version,sort_no) VALUES (?,NULL,?,?,?, ?,0)",
                id,
                code,
                code,
                enabled,
                version);
    }

    private void insertRole(UUID id, String code, boolean enabled, long version) {
        jdbc.update(
                "INSERT INTO platform_role(id,code,name,enabled,version,data_scope) VALUES (?,?,?,?,?,'ALL')",
                id,
                code,
                code,
                enabled,
                version);
    }
}
