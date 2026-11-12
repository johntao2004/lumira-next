package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.identity.api.AccountOwnerPort;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Opt-in PostgreSQL coverage; each test owns a temporary schema. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class FilePurgeQuotaDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_file_purge_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                """
                CREATE TABLE platform_file(
                    id uuid PRIMARY KEY,
                    owner_id uuid NOT NULL,
                    storage_backend varchar(32) NOT NULL,
                    original_name varchar(255) NOT NULL,
                    state varchar(24) NOT NULL,
                    size_bytes bigint,
                    reserved_bytes bigint NOT NULL DEFAULT 0,
                    sha256 varchar(64),
                    version bigint NOT NULL,
                    legacy_local_cleanup_pending boolean NOT NULL DEFAULT false,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now(),
                    trashed_at timestamptz
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_file_blob_chunk(
                    file_id uuid NOT NULL,
                    chunk_index integer NOT NULL CHECK (chunk_index >= 0),
                    payload bytea NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 65536),
                    retire_after timestamptz,
                    PRIMARY KEY (file_id, chunk_index)
                )
                """);
        jdbc.execute(
                "CREATE INDEX platform_file_blob_chunk_retirement_idx ON platform_file_blob_chunk(retire_after,file_id,chunk_index) WHERE retire_after IS NOT NULL");
        jdbc.execute(
                """
                CREATE TABLE platform_branding_file(
                    file_id uuid PRIMARY KEY,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    published_at timestamptz
                )
                """);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void successfulPurgeReleasesChargedMetadataBeforeReturningAndReportsPendingUsage()
            throws Exception {
        var owner = UUID.randomUUID();
        var target = UUID.randomUUID();
        var pending = UUID.randomUUID();
        insertFile(target, owner, "TRASHED", 555, 3);
        insertFile(pending, owner, "DELETING", 777, 8);

        var accounts = mock(AccountOwnerPort.class);
        when(accounts.id("alice", false)).thenReturn(owner);
        when(accounts.id("alice", true)).thenReturn(owner);
        var blobs = mock(BlobStore.class);
        doAnswer(
                        invocation -> {
                            assertFalse(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return null;
                        })
                .when(blobs)
                .delete(target);
        var repository = new FileRepository(JdbcClient.create(jdbc.getDataSource()));
        var quota = new FileQuota(Long.MAX_VALUE, 1000);
        var service =
                new TestFileWorkflow(
                        repository,
                        accounts,
                        blobs,
                        new DataSourceTransactionManager(jdbc.getDataSource()),
                        mock(AuditPort.class),
                        quota);

        var before = service.usage("alice");
        assertEquals(1332, before.chargedBytes());
        assertEquals(2, before.fileCount());
        assertEquals(1, before.pendingDeletingCount());
        assertEquals(777, before.pendingDeletingBytes());

        service.purge("alice", target, 3);

        var after = service.usage("alice");
        assertEquals(777, after.chargedBytes());
        assertEquals(1, after.fileCount());
        assertEquals(1, after.pendingDeletingCount());
        assertEquals(777, after.pendingDeletingBytes());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_file WHERE id=?", Integer.class, target));
        assertEquals(
                "DELETING",
                jdbc.queryForObject(
                        "SELECT state FROM platform_file WHERE id=?", String.class, pending));
    }

    @Test
    void storageFailureKeepsPurgedMetadataChargedAndPending() throws Exception {
        var owner = UUID.randomUUID();
        var target = UUID.randomUUID();
        insertFile(target, owner, "TRASHED", 555, 3);

        var accounts = mock(AccountOwnerPort.class);
        when(accounts.id("alice", false)).thenReturn(owner);
        when(accounts.id("alice", true)).thenReturn(owner);
        var blobs = mock(BlobStore.class);
        doThrow(new java.io.IOException("temporary storage failure")).when(blobs).delete(target);
        var repository = new FileRepository(JdbcClient.create(jdbc.getDataSource()));
        var service =
                new TestFileWorkflow(
                        repository,
                        accounts,
                        blobs,
                        new DataSourceTransactionManager(jdbc.getDataSource()),
                        mock(AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000));

        service.purge("alice", target, 3);

        var usage = service.usage("alice");
        assertEquals(555, usage.chargedBytes());
        assertEquals(1, usage.fileCount());
        assertEquals(1, usage.pendingDeletingCount());
        assertEquals(555, usage.pendingDeletingBytes());
        assertEquals(
                "DELETING",
                jdbc.queryForObject(
                        "SELECT state FROM platform_file WHERE id=?", String.class, target));
        assertEquals(
                4,
                jdbc.queryForObject(
                        "SELECT version FROM platform_file WHERE id=?", Long.class, target));
    }

    private void insertFile(UUID id, UUID owner, String state, long size, long version) {
        jdbc.update(
                "INSERT INTO platform_file(id,owner_id,storage_backend,original_name,state,size_bytes,sha256,version,created_at,updated_at,trashed_at) VALUES(?,?,?,?,?,?,?,?,now(),now(),CASE WHEN ?='TRASHED' THEN now() ELSE NULL END)",
                id,
                owner,
                "postgres",
                "file.bin",
                state,
                size,
                "a".repeat(64),
                version,
                state);
    }
}
