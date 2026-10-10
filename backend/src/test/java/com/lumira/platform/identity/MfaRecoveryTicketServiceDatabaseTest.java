package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.identity.api.MfaRecoveryTicketPort;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
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

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class MfaRecoveryTicketServiceDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private TransactionTemplate tx;
    private MfaRecoveryTicketService tickets;
    private UUID userId;
    private UUID otherUserId;
    private UUID factorRevision;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_mfa_recovery_ticket_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY,enabled boolean NOT NULL,deleted_at timestamptz,factor_credential_revision uuid NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_second_factor_requirement(user_id uuid PRIMARY KEY REFERENCES platform_user(id),provider_id text NOT NULL,self_enabled boolean NOT NULL,recovery_pending boolean NOT NULL)");
        jdbc.execute(
                """
                CREATE TABLE platform_mfa_recovery_request_rate (
                    user_id uuid PRIMARY KEY REFERENCES platform_user(id) ON DELETE CASCADE,
                    requested_at timestamptz,
                    request_day date,
                    request_count integer NOT NULL DEFAULT 0 CHECK (request_count BETWEEN 0 AND 1000),
                    failures integer NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 20),
                    failure_day date
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_mfa_recovery_ticket (
                    id uuid PRIMARY KEY,
                    user_id uuid NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
                    token_digest char(64) NOT NULL CHECK (token_digest ~ '^[a-f0-9]{64}$'),
                    factor_credential_revision uuid NOT NULL,
                    expires_at timestamptz NOT NULL,
                    consumed_at timestamptz,
                    revoked_at timestamptz,
                    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT platform_mfa_recovery_ticket_state_check
                        CHECK (NOT (consumed_at IS NOT NULL AND revoked_at IS NOT NULL))
                )
                """);
        jdbc.execute(
                "CREATE UNIQUE INDEX platform_mfa_recovery_ticket_active_user_idx ON platform_mfa_recovery_ticket(user_id) WHERE consumed_at IS NULL AND revoked_at IS NULL");

        userId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        factorRevision = UUID.randomUUID();
        insertRecoveryUser(userId, factorRevision);
        insertRecoveryUser(otherUserId, UUID.randomUUID());
        var dataSourceManager = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(dataSourceManager);
        tickets =
                new MfaRecoveryTicketService(
                        JdbcClient.create(dataSource),
                        new SecretKeys(
                                "primary=" + Base64.getEncoder().encodeToString(new byte[32]),
                                "primary"));
    }

    private void insertRecoveryUser(UUID id, UUID revision) {
        jdbc.update("INSERT INTO platform_user VALUES (?,true,NULL,?)", id, revision);
        jdbc.update(
                "INSERT INTO platform_second_factor_requirement VALUES (?,'mfa',true,true)", id);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private MfaRecoveryTicketPort.Issued issue(UUID user) {
        return tx.execute(status -> tickets.issue(user));
    }

    private MfaRecoveryTicketPort.ConsumeResult consume(UUID user, String value) {
        return tx.execute(status -> tickets.consume(user, value));
    }

    @Test
    void ticketIsOpaqueAccountBoundAndExactlyOnce() {
        var issued = issue(userId);
        assertEquals(64, issued.value().length());
        assertNotEquals(
                issued.value(),
                jdbc.queryForObject(
                                "SELECT token_digest FROM platform_mfa_recovery_ticket WHERE user_id=?",
                                String.class,
                                userId)
                        .strip());

        assertEquals(
                MfaRecoveryTicketPort.ConsumeResult.INVALID, consume(otherUserId, issued.value()));
        assertEquals(MfaRecoveryTicketPort.ConsumeResult.CONSUMED, consume(userId, issued.value()));
        assertEquals(MfaRecoveryTicketPort.ConsumeResult.INVALID, consume(userId, issued.value()));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_mfa_recovery_ticket WHERE user_id=? AND consumed_at IS NOT NULL",
                        Integer.class,
                        userId));
    }

    @Test
    void ticketCannotSurviveFactorRevisionChange() {
        var issued = issue(userId);
        jdbc.update(
                "UPDATE platform_user SET factor_credential_revision=? WHERE id=?",
                UUID.randomUUID(),
                userId);

        assertEquals(MfaRecoveryTicketPort.ConsumeResult.INVALID, consume(userId, issued.value()));
        assertNull(
                jdbc.queryForObject(
                        "SELECT consumed_at FROM platform_mfa_recovery_ticket WHERE user_id=?",
                        java.sql.Timestamp.class,
                        userId));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT failures FROM platform_mfa_recovery_request_rate WHERE user_id=?",
                        Integer.class,
                        userId));
    }

    @Test
    void accountFailureLimitPersistsAndResetsOnNewDay() {
        var issued = issue(userId);
        for (int attempt = 1; attempt < 20; attempt++)
            assertEquals(
                    MfaRecoveryTicketPort.ConsumeResult.INVALID, consume(userId, "bad-" + attempt));
        assertEquals(MfaRecoveryTicketPort.ConsumeResult.LOCKED, consume(userId, "bad-20"));
        assertEquals(MfaRecoveryTicketPort.ConsumeResult.LOCKED, consume(userId, issued.value()));
        assertEquals(
                20,
                jdbc.queryForObject(
                        "SELECT failures FROM platform_mfa_recovery_request_rate WHERE user_id=?",
                        Integer.class,
                        userId));

        jdbc.update(
                "UPDATE platform_mfa_recovery_request_rate SET failure_day=CURRENT_DATE-1 WHERE user_id=?",
                userId);
        assertEquals(MfaRecoveryTicketPort.ConsumeResult.CONSUMED, consume(userId, issued.value()));
    }

    @Test
    void concurrentConsumptionHasOneWinner() throws Exception {
        var issued = issue(userId);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return consume(userId, issued.value());
                            });
            var second =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return consume(userId, issued.value());
                            });
            var values =
                    java.util.List.of(
                            first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    values.stream()
                            .filter(value -> value == MfaRecoveryTicketPort.ConsumeResult.CONSUMED)
                            .count());
            assertEquals(
                    1,
                    values.stream()
                            .filter(value -> value == MfaRecoveryTicketPort.ConsumeResult.INVALID)
                            .count());
        }
    }
}
