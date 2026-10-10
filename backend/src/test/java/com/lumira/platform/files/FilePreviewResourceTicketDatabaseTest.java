package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.AuthorizationContextBoundaryPort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class FilePreviewResourceTicketDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private UUID actor;
    private UUID fileId;
    private String revision;
    private String sessionId;
    private FileService files;
    private AuthorizationContextBoundaryPort boundary;
    private FilePreviewResourceTicketService service;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_preview_ticket_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                """
                CREATE TABLE platform_role_preview_resource_ticket(
                    ticket_id uuid PRIMARY KEY,
                    session_primary_id character(36) NOT NULL,
                    actor_id uuid NOT NULL,
                    context_revision uuid NOT NULL,
                    resource_type varchar(32) NOT NULL CHECK (resource_type='FILE_PREVIEW'),
                    resource_id uuid NOT NULL,
                    page_number integer NOT NULL CHECK (page_number BETWEEN 1 AND 500),
                    expires_at timestamptz NOT NULL,
                    consumed_at timestamptz,
                    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        actor = UUID.randomUUID();
        fileId = UUID.randomUUID();
        revision = UUID.randomUUID().toString();
        sessionId = UUID.randomUUID().toString();
        files = mock(FileService.class);
        when(files.find("alice", fileId))
                .thenReturn(
                        new FileRepository.Entry(
                                fileId,
                                actor,
                                "postgres",
                                "preview.pdf",
                                "READY",
                                128L,
                                "digest",
                                1,
                                Instant.now(),
                                Instant.now(),
                                null));
        when(files.preview("alice", fileId, 3))
                .thenReturn(new FilePreview("page content", false, null, 3, 3));
        boundary = mock(AuthorizationContextBoundaryPort.class);
        service =
                new FilePreviewResourceTicketService(
                        JdbcClient.create(dataSource),
                        files,
                        boundary,
                        new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void ticketIsBoundToOneActorSessionRevisionAndConsumedOnce() throws Exception {
        var ticket =
                withContext(
                        actor,
                        sessionId,
                        () ->
                                service.issue(
                                        "/api/account/files/" + fileId + "/preview?page=3",
                                        revision));
        UUID ticketId = UUID.fromString(ticket.url().substring(ticket.url().lastIndexOf('/') + 1));
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT page_number FROM platform_role_preview_resource_ticket WHERE ticket_id=?",
                        Integer.class,
                        ticketId));

        assertEquals(
                new FilePreview("page content", false, null, 3, 3),
                withContext(actor, sessionId, () -> service.consumeAndRead(ticketId)));
        verify(boundary, org.mockito.Mockito.times(2)).lockAndValidateCurrentRequest();
        assertEquals(
                "resourceTicketUnavailable",
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        withContext(
                                                actor,
                                                sessionId,
                                                () -> service.consumeAndRead(ticketId)))
                        .getReason());
    }

    @Test
    void rejectsStaleRevisionsAndPathsOutsideTheExactPreviewWhitelist() {
        var stale =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                withContext(
                                        actor,
                                        sessionId,
                                        () ->
                                                service.issue(
                                                        "/api/account/files/"
                                                                + fileId
                                                                + "/preview?page=3",
                                                        UUID.randomUUID().toString())));
        assertEquals("authorizationContextChanged", stale.getReason());

        for (String path :
                Set.of(
                        "https://example.com/api/account/files/" + fileId + "/preview",
                        "/api/account/files/" + fileId + "/content",
                        "/api/account/files/" + fileId + "/preview?page=501",
                        "//api/account/files/" + fileId + "/preview")) {
            assertEquals(
                    "invalidResourceTicketPath",
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            withContext(
                                                    actor,
                                                    sessionId,
                                                    () -> service.issue(path, revision)))
                            .getReason());
        }
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_role_preview_resource_ticket",
                        Integer.class));
    }

    @Test
    void aTicketCannotBeConsumedFromAnotherSessionOrActor() throws Exception {
        var issued =
                withContext(
                        actor,
                        sessionId,
                        () ->
                                service.issue(
                                        "/api/account/files/" + fileId + "/preview?page=3",
                                        revision));
        UUID ticketId = UUID.fromString(issued.url().substring(issued.url().lastIndexOf('/') + 1));
        UUID anotherActor = UUID.randomUUID();
        UUID anotherSession = UUID.randomUUID();
        assertEquals(
                "resourceTicketUnavailable",
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        withContext(
                                                anotherActor,
                                                anotherSession.toString(),
                                                () -> service.consumeAndRead(ticketId)))
                        .getReason());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_role_preview_resource_ticket WHERE consumed_at IS NOT NULL",
                        Integer.class));
    }

    private <T> T withContext(UUID userId, String sessionPrimaryId, CheckedSupplier<T> action)
            throws Exception {
        try (var ignored =
                AuthenticationRequestContext.bind(
                        new AuthenticationRequestContext.Snapshot(
                                "alice",
                                userId,
                                (EffectiveAuthenticationPolicyPort.EffectivePolicy) null,
                                authorizationContext(userId, sessionPrimaryId)))) {
            return action.get();
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private RequestAuthorizationContext authorizationContext(UUID userId, String session) {
        return new RequestAuthorizationContext(
                "alice",
                userId,
                session,
                Set.of("platform:files:read"),
                Set.of(),
                Map.of(),
                Set.of("platform:files:read"),
                Set.of(),
                Map.of(),
                revision,
                revision,
                true,
                new RequestAuthorizationContext.Preview(
                        "ACTIVE",
                        UUID.randomUUID(),
                        "Viewer",
                        1,
                        OffsetDateTime.now().minusSeconds(1),
                        OffsetDateTime.now().plusMinutes(5),
                        false,
                        null));
    }
}
