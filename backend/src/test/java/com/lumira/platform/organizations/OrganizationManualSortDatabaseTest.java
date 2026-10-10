package com.lumira.platform.organizations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.UserDirectoryPort;
import com.lumira.platform.organizations.api.OrganizationResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/** Uses a disposable per-test schema to exercise V40 and organization persistence. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class OrganizationManualSortDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private OrganizationRepository repository;
    private OrganizationService service;
    private String schema;
    private UUID legacyRootId;

    @BeforeEach
    void createLegacySchemaAndApplySortMigration() {
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
        jdbc.execute(
                "CREATE TABLE platform_organization ("
                        + "id uuid PRIMARY KEY, parent_id uuid, code text NOT NULL, name text NOT NULL, "
                        + "enabled boolean NOT NULL, version bigint NOT NULL DEFAULT 0)");

        legacyRootId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        jdbc.update(
                "INSERT INTO platform_organization(id,parent_id,code,name,enabled) VALUES(?,?,?,?,?)",
                legacyRootId,
                null,
                "legacy-zeta",
                "Legacy Zeta",
                true);
        jdbc.update(
                "INSERT INTO platform_organization(id,parent_id,code,name,enabled) VALUES(?,?,?,?,?)",
                UUID.fromString("00000000-0000-0000-0000-000000000009"),
                null,
                "legacy-alpha",
                "Legacy Alpha",
                true);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .baselineOnMigrate(true)
                .baselineVersion("39")
                .target("40")
                .load()
                .migrate();

        jdbc.execute(
                "CREATE FUNCTION platform_visible_organizations(actor_name text, permission_name text) "
                        + "RETURNS TABLE(organization_id uuid) LANGUAGE SQL AS $$ "
                        + "SELECT id FROM platform_organization $$");

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
        if (admin != null && schema != null) {
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void migratesLegacyRowsAndSortsRootAndChildListsByManualOrder() {
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_organization WHERE sort_no = 0",
                        Integer.class));
        assertEquals(
                List.of("legacy-alpha", "legacy-zeta"),
                repository.list(page(), true, null, "admin").stream()
                        .map(OrganizationRepository.Organization::code)
                        .toList());

        UUID childLowId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID childHighId = UUID.fromString("00000000-0000-0000-0000-000000000021");
        UUID childLaterCodeId = UUID.fromString("00000000-0000-0000-0000-000000000022");
        insert(childHighId, legacyRootId, "same-code", 5);
        insert(childLowId, legacyRootId, "same-code", 5);
        insert(childLaterCodeId, legacyRootId, "later-code", 5);
        insert(UUID.fromString("00000000-0000-0000-0000-000000000023"), legacyRootId, "early", 1);

        assertEquals(
                List.of("early", "later-code", "same-code", "same-code"),
                repository.list(page(), true, legacyRootId, "admin").stream()
                        .map(OrganizationRepository.Organization::code)
                        .toList());
        assertEquals(
                List.of(childLowId, childHighId),
                repository.list(page(), true, legacyRootId, "admin").stream()
                        .filter(row -> row.code().equals("same-code"))
                        .map(OrganizationRepository.Organization::id)
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

    private PageRequest page() {
        return new PageRequest(1, 50, "", Map.of(), Map.of());
    }
}
