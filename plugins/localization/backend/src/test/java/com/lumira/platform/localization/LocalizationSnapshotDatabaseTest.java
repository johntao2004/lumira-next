package com.lumira.platform.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Opt-in PostgreSQL coverage for source snapshot changes racing with target translation saves. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class LocalizationSnapshotDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private LocalizationRepository repository;
    private LocalizationService localization;
    private TransactionTemplate transaction;
    private UUID entryId;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_localization_snapshot_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                """
                CREATE TABLE platform_localization_locale(
                    locale_code varchar(63) PRIMARY KEY, display_name varchar(100) NOT NULL,
                    source_locale varchar(63) NOT NULL, fallback_locale varchar(63) NOT NULL,
                    built_in boolean NOT NULL, enabled boolean NOT NULL, version bigint NOT NULL,
                    created_by varchar(100) NOT NULL, updated_by varchar(100) NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now())
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_localization_entry(
                    id uuid PRIMARY KEY, message_key varchar(200) NOT NULL, namespace varchar(80) NOT NULL,
                    default_message text NOT NULL, translations jsonb NOT NULL, enabled boolean NOT NULL,
                    version bigint NOT NULL, created_by varchar(100) NOT NULL, updated_by varchar(100) NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
                    deleted boolean NOT NULL DEFAULT false)
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_localization_release(
                    locale_code varchar(63) PRIMARY KEY, release_version bigint NOT NULL,
                    published_by varchar(100) NOT NULL, published_at timestamptz NOT NULL DEFAULT now())
                """);
        jdbc.update(
                "INSERT INTO platform_localization_locale(locale_code,display_name,source_locale,fallback_locale,built_in,enabled,version,created_by,updated_by) VALUES('en','English','en','en',true,true,1,'qa','qa'),('zh-CN','简体中文','en','en',true,true,1,'qa','qa')");
        jdbc.update(
                "INSERT INTO platform_localization_release(locale_code,release_version,published_by) VALUES('en',7,'qa'),('zh-CN',3,'qa')");
        entryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_localization_entry(id,message_key,namespace,default_message,translations,enabled,version,created_by,updated_by) VALUES(?,?,? ,?,CAST(? AS jsonb),true,1,'qa','qa')",
                entryId,
                "custom.snapshot.source",
                "custom",
                "Source A",
                "{\"en\":\"Source A\",\"zh-CN\":\"Old target\"}");

        var client = JdbcClient.create(dataSource);
        repository = new LocalizationRepository(client, new tools.jackson.databind.ObjectMapper());
        var localeRepository = new LocalizationLocaleRepository(client);
        var builtins = mock(LocalizationBuiltinCatalog.class);
        org.mockito.Mockito.when(builtins.sourceLocale()).thenReturn("en");
        org.mockito.Mockito.when(builtins.keys()).thenReturn(Set.of());
        org.mockito.Mockito.when(builtins.messages(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Map.of());
        localization =
                new LocalizationService(
                        repository,
                        localeRepository,
                        builtins,
                        mock(AdministrationPort.class),
                        mock(AuditPort.class));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void targetSaveWaitsForCatalogWriterAndRejectsItsNowStaleSourceSnapshot() throws Exception {
        var snapshot = localization.bundle("en");
        assertEquals(7L, snapshot.releaseVersion());
        assertEquals("Source A", snapshot.messages().get("custom.snapshot.source"));

        var writerHasLock = new CountDownLatch(1);
        var allowWriterCommit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer =
                    pool.submit(
                            () ->
                                    transaction.execute(
                                            status -> {
                                                repository.lockCatalogForWrite();
                                                writerHasLock.countDown();
                                                try {
                                                    if (!allowWriterCommit.await(
                                                            5, TimeUnit.SECONDS))
                                                        throw new AssertionError(
                                                                "test did not release localization writer");
                                                } catch (InterruptedException interrupted) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(interrupted);
                                                }
                                                jdbc.update(
                                                        "UPDATE platform_localization_entry SET translations=jsonb_set(translations,'{en}','\"Source B\"'::jsonb),version=version+1 WHERE id=?",
                                                        entryId);
                                                return null;
                                            }));
            assertTrue(writerHasLock.await(5, TimeUnit.SECONDS));

            var saveStarted = new CountDownLatch(1);
            var staleSave =
                    pool.submit(
                            () -> {
                                saveStarted.countDown();
                                return transaction.execute(
                                        status ->
                                                localization.saveOverrides(
                                                        new LocalizationService.OverrideRequest(
                                                                "zh-CN",
                                                                List.of(
                                                                        new LocalizationService
                                                                                .OverrideEntry(
                                                                                "custom.snapshot.source",
                                                                                "Translated from A",
                                                                                1L)),
                                                                true,
                                                                "en",
                                                                snapshot.snapshotToken()),
                                                        "admin"));
                            });
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            assertTrue(
                    awaitCatalogLockWait(),
                    "save must reach PostgreSQL and wait on the catalog lock");
            assertFalse(
                    staleSave.isDone(),
                    "save must wait while another transaction owns the catalog snapshot lock");

            allowWriterCommit.countDown();
            writer.get(10, TimeUnit.SECONDS);
            try {
                staleSave.get(10, TimeUnit.SECONDS);
                throw new AssertionError("stale source snapshot should be rejected");
            } catch (java.util.concurrent.ExecutionException error) {
                assertTrue(error.getCause() instanceof ResponseStatusException);
                assertEquals(
                        "localizationSourceChanged",
                        ((ResponseStatusException) error.getCause()).getReason());
            }
        }

        var current = localization.bundle("en");
        assertEquals(
                snapshot.releaseVersion(),
                current.releaseVersion(),
                "test source edit does not publish");
        assertNotEquals(snapshot.snapshotToken(), current.snapshotToken());
        assertEquals(
                "Old target",
                jdbc.queryForObject(
                        "SELECT translations->>'zh-CN' FROM platform_localization_entry WHERE id=?",
                        String.class,
                        entryId));
    }

    @Test
    void currentSourceSnapshotAllowsTargetTranslationSave() {
        var snapshot = localization.bundle("en");
        var result =
                transaction.execute(
                        status ->
                                localization.saveOverrides(
                                        new LocalizationService.OverrideRequest(
                                                "zh-CN",
                                                List.of(
                                                        new LocalizationService.OverrideEntry(
                                                                "custom.snapshot.source",
                                                                "New target",
                                                                1L)),
                                                true,
                                                "en",
                                                snapshot.snapshotToken()),
                                        "admin"));

        assertEquals(1, result.saved());
        assertEquals(4L, result.releaseVersion());
        assertEquals(
                "New target",
                jdbc.queryForObject(
                        "SELECT translations->>'zh-CN' FROM platform_localization_entry WHERE id=?",
                        String.class,
                        entryId));
    }

    private boolean awaitCatalogLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting =
                    admin.queryForObject(
                            "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted AND pid<>pg_backend_pid()",
                            Integer.class);
            if (waiting != null && waiting > 0) return true;
            Thread.sleep(20);
        }
        return false;
    }
}
