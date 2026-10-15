package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AuditPort;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public pre-fix API regressions, confined to a random disposable schema and temporary directory.
 */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class FilePhysicalQuotaRegressionTest {
    private JdbcTemplate admin, jdbc;
    private String schema;
    private FileRepository files;
    private PostgresBlobStore blobs;
    private DataSourceTransactionManager manager;
    private final UUID owner = UUID.randomUUID();
    private AuditPort audit;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_physical_quota_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        // Extract current table definitions, without global migrations or application rows.
        jdbc.execute(TestSchema.table("platform_file"));
        TestSchema.indexes("platform_file").forEach(jdbc::execute);
        jdbc.execute(TestSchema.table("platform_file_blob_chunk"));
        TestSchema.indexes("platform_file_blob_chunk").forEach(jdbc::execute);
        jdbc.execute(TestSchema.table("platform_feedback_file"));
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY,username text,display_name text,version bigint,deleted_at timestamptz)");
        jdbc.update("INSERT INTO platform_user VALUES(?,'fixture','Fixture',0,NULL)", owner);
        manager = new DataSourceTransactionManager(ds);
        files = spy(new FileRepository(JdbcClient.create(ds)));
        // This separate orphan-expiration query deliberately qualifies public.platform_file;
        // do not let a test touch that schema. All deletion paths below use random-schema rows.
        doReturn(List.of()).when(files).expireOrphanedFiles();
        doReturn(List.of()).when(files).expirePendingBrandingImages();
        blobs = new PostgresBlobStore(jdbc, files, manager);
        audit = mock(AuditPort.class);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private UUID completed(byte[] bytes) throws Exception {
        UUID id = UUID.randomUUID();
        files.reserve(id, owner, "postgres", "fixture.txt", Math.max(1, bytes.length));
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        jdbc.update(
                "UPDATE platform_file SET size_bytes=?,sha256=?,state='DELETING',version=2,updated_at=now()-interval '2 minutes' WHERE id=?",
                bytes.length,
                digest,
                id);
        if (bytes.length > 0)
            jdbc.update(
                    "INSERT INTO platform_file_blob_chunk(file_id,chunk_index,payload) VALUES(?,0,?)",
                    id,
                    bytes);
        return id;
    }

    private void sweep() {
        new FileCleanup(files, blobs, audit, manager).sweep();
    }

    private void retryNow(UUID id) {
        jdbc.update(
                "UPDATE platform_file SET updated_at=now()-interval '2 minutes' WHERE id=?", id);
    }

    private long chunks(UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform_file_blob_chunk WHERE file_id=?", Long.class, id);
    }

    @Test
    void quotaRemainsChargedUntilPhysicalDeletionAndOpenDownloadKeepsGrace() throws Exception {
        byte[] content = "synthetic quota data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID id = completed(content);
        try (var download = blobs.open(id)) {
            assertEquals(content[0], download.read());
            sweep();
            assertEquals(
                    new FileRepository.Usage(content.length, 1, 1, content.length),
                    files.usage(owner));
            assertEquals(1, chunks(id));
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            new FileQuota(content.length, 1)
                                    .requireCapacity(
                                            files.usage(owner).chargedBytes(),
                                            files.usage(owner).fileCount(),
                                            1));
            assertArrayEquals(
                    Arrays.copyOfRange(content, 1, content.length), download.readAllBytes());
        }
        var originalRetirement =
                jdbc.queryForObject(
                        "SELECT retire_after FROM platform_file_blob_chunk WHERE file_id=?",
                        java.time.OffsetDateTime.class,
                        id);
        assertTrue(originalRetirement.isAfter(java.time.OffsetDateTime.now().plusHours(23)));
        retryNow(id);
        sweep();
        assertEquals(
                originalRetirement,
                jdbc.queryForObject(
                        "SELECT retire_after FROM platform_file_blob_chunk WHERE file_id=?",
                        java.time.OffsetDateTime.class,
                        id));
        verify(audit, never()).append("system:file-cleanup", "file.cleanup", id);
        jdbc.update(
                "UPDATE platform_file_blob_chunk SET retire_after=now()-interval '1 second' WHERE file_id=?",
                id);
        blobs.purgeRetiredChunks();
        retryNow(id);
        sweep();
        sweep();
        assertEquals(new FileRepository.Usage(0, 0), files.usage(owner));
        verify(audit, times(1)).append("system:file-cleanup", "file.cleanup", id);
        assertDoesNotThrow(() -> new FileQuota(content.length, 1).requireCapacity(0, 0, 1));
    }

    @Test
    void finalMetadataGuardRejectsRemainingChunksAndPartialRetirement() throws Exception {
        UUID id = completed(new byte[] {1});
        jdbc.update(
                "INSERT INTO platform_file_blob_chunk(file_id,chunk_index,payload,retire_after) VALUES(?,1,?,now()+interval '24 hours')",
                id,
                new byte[] {2});
        jdbc.update(
                "UPDATE platform_file_blob_chunk SET retire_after=now()-interval '1 second' WHERE file_id=? AND chunk_index=0",
                id);
        blobs.purgeRetiredChunks();
        assertEquals(1, chunks(id));
        assertFalse(files.finishDelete(id, 2));
        assertEquals(1, files.usage(owner).fileCount());
        assertFalse(files.finishDelete(id, 1));
    }

    @Test
    void aHundredGracePeriodRowsCannotStarveLaterDeletion() throws Exception {
        for (int i = 0; i < 100; i++) completed(new byte[] {1});
        UUID last = completed(new byte[] {2});
        jdbc.update(
                "UPDATE platform_file SET updated_at=now()-interval '90 seconds' WHERE id=?", last);
        sweep();
        sweep();
        assertEquals(101, files.usage(owner).fileCount());
        assertNotNull(
                jdbc.queryForObject(
                        "SELECT retire_after FROM platform_file_blob_chunk WHERE file_id=?",
                        java.time.OffsetDateTime.class,
                        last));
    }

    @Test
    void boundedPurgerMakesProgressBeyondOneThousandRows() throws Exception {
        UUID id = completed(new byte[] {1});
        // Tiny synthetic chunks exercise batch cardinality without writing 64 MiB of test data.
        jdbc.update(
                "INSERT INTO platform_file_blob_chunk(file_id,chunk_index,payload,retire_after) SELECT ?,i,decode('01','hex'),now()-interval '1 second' FROM generate_series(1,1000) i",
                id);
        jdbc.update(
                "UPDATE platform_file_blob_chunk SET retire_after=now()-interval '1 second' WHERE file_id=?",
                id);
        blobs.purgeRetiredChunks();
        assertEquals(0, chunks(id));
        assertTrue(files.finishDelete(id, 2));
    }

    @Test
    void unpublishedOrOrphanChunksDeleteImmediatelyAndZeroByteFilesComplete() throws Exception {
        UUID pending = UUID.randomUUID();
        files.reserve(pending, owner, "postgres", "failed.txt", 20);
        jdbc.update(
                "INSERT INTO platform_file_blob_chunk(file_id,chunk_index,payload) VALUES(?,0,?)",
                pending,
                new byte[] {1});
        assertTrue(files.abandonUpload(pending));
        blobs.delete(pending);
        assertEquals(0, chunks(pending));
        assertTrue(files.finishDelete(pending, 1));
        UUID orphan = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_file_blob_chunk(file_id,chunk_index,payload) VALUES(?,0,?)",
                orphan,
                new byte[] {1});
        blobs.delete(orphan);
        assertEquals(0, chunks(orphan));
        UUID empty = completed(new byte[0]);
        sweep();
        assertTrue(files.storageBackend(empty).isEmpty());
    }

    @Test
    void migratedLocalCopyFailureKeepsQuotaUntilBothCopiesAreGone() throws Exception {
        UUID id = completed(new byte[] {1});
        jdbc.update("UPDATE platform_file SET legacy_local_cleanup_pending=true WHERE id=?", id);
        var local = mock(LocalBlobStore.class);
        doThrow(new IOException("synthetic unlink failure")).doNothing().when(local).delete(id);
        var routed = new RoutedBlobStore(files, local, blobs);
        new FileCleanup(files, routed, audit, manager).sweep();
        assertEquals(1, files.usage(owner).fileCount());
        jdbc.update(
                "UPDATE platform_file_blob_chunk SET retire_after=now()-interval '1 second' WHERE file_id=?",
                id);
        blobs.purgeRetiredChunks();
        assertFalse(files.finishDelete(id, 2));
        retryNow(id);
        new FileCleanup(files, routed, audit, manager).sweep();
        assertEquals(0, files.usage(owner).fileCount());
        verify(local, times(2)).delete(id);
    }

    @Test
    void failedPublicationCleansPhysicalChunksBeforeReleasingItsReservation() throws Exception {
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        when(accounts.id("actor", true)).thenReturn(owner);
        doThrow(new IllegalStateException("synthetic audit failure"))
                .when(audit)
                .append(eq("actor"), eq("file.upload"), any());
        var service =
                new FileService(files, accounts, blobs, manager, audit, new FileQuota(100, 1));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.upload(
                                "actor",
                                "fixture.txt",
                                new java.io.ByteArrayInputStream(new byte[] {1}),
                                1));
        assertEquals(new FileRepository.Usage(0, 0), files.usage(owner));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_file_blob_chunk", Integer.class));
    }

    @Test
    void localDeletionReleasesQuotaImmediatelyAfterUnlink(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        UUID id = completed(new byte[] {1});
        jdbc.update("DELETE FROM platform_file_blob_chunk WHERE file_id=?", id);
        jdbc.update("UPDATE platform_file SET storage_backend='local' WHERE id=?", id);
        var local = new LocalBlobStore(root);
        local.put(id, new java.io.ByteArrayInputStream(new byte[] {1}), 1);
        var routed = new RoutedBlobStore(files, local, blobs);
        new FileCleanup(files, routed, audit, manager).sweep();
        assertEquals(new FileRepository.Usage(0, 0), files.usage(owner));
        assertThrows(java.nio.file.NoSuchFileException.class, () -> local.open(id));
    }
}
