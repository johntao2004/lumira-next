package com.lumira.plugins.directory.organizations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.UserDirectoryPort;
import com.lumira.plugins.directory.organizations.api.OrganizationResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/** Verifies organization ordering and versioned updates against the current baseline schema. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class OrganizationManualSortDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private OrganizationRepository repository;
    private OrganizationService service;
    private String schema;

    @BeforeEach
    void createCurrentSchema() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_organization_sort_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);

        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(TestSchema.table("platform_organization"));
        TestSchema.indexes("platform_organization").forEach(jdbc::execute);
        jdbc.execute(
                "CREATE FUNCTION platform_visible_organizations(actor_name text, "
                        + "permission_name text) RETURNS TABLE(organization_id uuid) "
                        + "LANGUAGE SQL STABLE AS $$ SELECT id FROM platform_organization $$");

        repository = new OrganizationRepository(JdbcClient.create(dataSource));
        service =
                new OrganizationService(
                        repository,
                        mock(AdministrationPort.class),
                        mock(UserDirectoryPort.class),
                        mock(AuditPort.class));
    }

    @AfterEach
    void dropDisposableSchema() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void sortsRootAndChildListsByCurrentManualOrder() {
        UUID root = id(10);
        insert(root, null, "zeta", 0);
        insert(id(9), null, "alpha", 0);

        assertEquals(
                List.of("alpha", "zeta"),
                repository.list(page(), true, null, "admin").stream()
                        .map(OrganizationRepository.Organization::code)
                        .toList());

        insert(id(20), root, "same-code", 5);
        insert(id(21), root, "later-code", 5);
        insert(id(22), root, "early", 1);

        assertEquals(
                List.of("early", "later-code", "same-code"),
                repository.list(page(), true, root, "admin").stream()
                        .map(OrganizationRepository.Organization::code)
                        .toList());
    }

    @Test
    void defaultsNewSortAndPreservesOmittedUpdatesWithAtomicVersionGuard() {
        var created =
                service.create(
                        new OrganizationService.Input(
                                null, "manual-sort", "Manual Sort", true, null),
                        "admin");
        assertEquals(0, created.sortNo());

        var updated =
                service.update(
                        created.id(),
                        new OrganizationService.Input(
                                null, null, "Renamed", true, created.version()),
                        "admin");
        assertEquals(0, updated.sortNo());

        var maximum =
                service.update(
                        updated.id(),
                        new OrganizationService.Input(
                                null, null, "Renamed", true, updated.version(), Integer.MAX_VALUE),
                        "admin");
        var preservedMaximum =
                service.update(
                        maximum.id(),
                        new OrganizationService.Input(
                                null, null, "Renamed", true, maximum.version()),
                        "admin");
        assertEquals(Integer.MAX_VALUE, OrganizationResponse.from(preservedMaximum).sortNo());

        assertTrue(
                repository.update(
                        preservedMaximum.id(), null, "Fresh", true, 7, preservedMaximum.version()));
        assertFalse(
                repository.update(
                        preservedMaximum.id(), null, "Stale", true, 8, preservedMaximum.version()));
        var persisted = repository.find(preservedMaximum.id());
        assertEquals("Fresh", persisted.name());
        assertEquals(7, persisted.sortNo());
        assertEquals(preservedMaximum.version() + 1, persisted.version());

        var negative =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.create(
                                        new OrganizationService.Input(
                                                null, "negative-sort", "Negative", true, null, -1),
                                        "admin"));
        assertEquals(400, negative.getStatusCode().value());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_organization WHERE code='negative-sort'",
                        Integer.class));
    }

    private void insert(UUID id, UUID parentId, String code, int sortNo) {
        jdbc.update(
                "INSERT INTO platform_organization(id,parent_id,code,name,enabled,sort_no) "
                        + "VALUES(?,?,?,?,?,?)",
                id,
                parentId,
                code,
                code,
                true,
                sortNo);
    }

    private UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private PageRequest page() {
        return new PageRequest(1, 50, "", Map.of(), Map.of());
    }
}
