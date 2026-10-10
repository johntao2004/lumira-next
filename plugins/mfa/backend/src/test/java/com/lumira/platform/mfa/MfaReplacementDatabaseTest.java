package com.lumira.platform.mfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.MailSender;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.MfaRecoveryTicketPort;
import com.lumira.platform.identity.api.ReauthenticationPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
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
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class MfaReplacementDatabaseTest {
    private static final UUID USER_ID = UUID.fromString("809e5d42-62a9-4d9c-a145-187778579050");
    private static final UUID FACTOR_REVISION =
            UUID.fromString("9aa48228-57f1-4b90-8233-07e851c2709a");
    private static final UUID POLICY_REVISION =
            UUID.fromString("a109e5d2-4af0-4bd3-a6fc-bcdbaf5acba9");

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private TransactionTemplate tx;
    private MfaService service;
    private MfaStore store;
    private AccountOwnerPort owners;
    private AccountSecurityPort security;
    private EffectiveAuthenticationPolicyPort policies;
    private AuthenticationPolicyWriteLock policyLock;
    private ReauthenticationPort reauthentication;
    private AuditPort audit;
    private AdministrationPort administration;
    private MailSender mail;
    private MfaRecoveryTicketPort recoveryTickets;
    private JdbcClient client;
    private SecretKeys nodeAKeys;
    private SecretKeys nodeBKeys;
    private byte[] originalSecret;

    @BeforeEach
    void setUp() throws IOException {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String username = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        schema = "qa_mfa_replacement_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        username,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        createHostFixture();
        applyPluginMigrations();

        String encodedKey = Base64.getEncoder().encodeToString(new byte[32]);
        nodeAKeys = new SecretKeys("primary=" + encodedKey, "primary");
        nodeBKeys = new SecretKeys("primary=" + encodedKey, "primary");
        originalSecret = new byte[20];
        Arrays.fill(originalSecret, (byte) 0x4d);

        jdbc.update(
                """
                INSERT INTO platform_user(id,username,enabled,deleted_at,factor_credential_revision)
                VALUES (?, 'mfa-replacement-user', true, NULL, ?)
                """,
                USER_ID,
                FACTOR_REVISION);
        jdbc.update(
                """
                INSERT INTO plugin_mfa_authenticator
                    (user_id,label,secret_envelope,confirmed,expires_at,last_step)
                VALUES (?, 'Existing authenticator', ?, true, NULL, 1234)
                """,
                USER_ID,
                nodeAKeys.require().encrypt(activeContext(), originalSecret));

        owners = mock(AccountOwnerPort.class);
        when(owners.id("mfa-replacement-user", true)).thenReturn(USER_ID);
        policies = mock(EffectiveAuthenticationPolicyPort.class);
        when(policies.resolve(USER_ID))
                .thenReturn(
                        new EffectiveAuthenticationPolicyPort.EffectivePolicy(
                                POLICY_REVISION,
                                Set.of(PrimaryMethod.PASSWORD),
                                Set.of(PrimaryMethod.PASSWORD),
                                false,
                                List.of(),
                                false,
                                UUID.randomUUID(),
                                FACTOR_REVISION));
        reauthentication = mock(ReauthenticationPort.class);
        security = mock(AccountSecurityPort.class);
        store = new MfaStore(JdbcClient.create(dataSource));
        policyLock = mock(AuthenticationPolicyWriteLock.class);
        audit = mock(AuditPort.class);
        administration = mock(AdministrationPort.class);
        mail = mock(MailSender.class);
        recoveryTickets = mock(MfaRecoveryTicketPort.class);
        client = JdbcClient.create(dataSource);
        service = newService(nodeAKeys);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        if (originalSecret != null) Arrays.fill(originalSecret, (byte) 0);
    }

    @Test
    void replacementSecretSurvivesV1ThroughV5AndIsReadableByAnotherHostKeyRing() {
        UUID ticketId = UUID.randomUUID();

        var setup =
                service.replacementSetup(
                        "mfa-replacement-user", ticketId, "New authenticator", "qa-session");

        assertNotNull(setup.challengeId());
        assertNotNull(setup.secret());
        String pendingEnvelope =
                jdbc.queryForObject(
                        "SELECT secret_envelope FROM plugin_mfa_replacement WHERE user_id=?",
                        String.class,
                        USER_ID);
        byte[] replacementSecret = nodeBKeys.require().decrypt(pendingContext(), pendingEnvelope);
        byte[] activeSecret = null;
        try {
            assertEquals(20, replacementSecret.length);
            assertFalse(Arrays.equals(originalSecret, replacementSecret));
            assertEquals(
                    -1L,
                    jdbc.queryForObject(
                            "SELECT last_step FROM plugin_mfa_replacement WHERE user_id=?",
                            Long.class,
                            USER_ID));
            assertEquals(
                    setup.challengeId(),
                    jdbc.queryForObject(
                            "SELECT challenge_id FROM plugin_mfa_replacement WHERE user_id=?",
                            UUID.class,
                            USER_ID));
            assertEquals(
                    FACTOR_REVISION,
                    jdbc.queryForObject(
                            "SELECT factor_credential_revision FROM plugin_mfa_replacement WHERE user_id=?",
                            UUID.class,
                            USER_ID));
            assertEquals(
                    POLICY_REVISION,
                    jdbc.queryForObject(
                            "SELECT authentication_revision FROM plugin_mfa_replacement WHERE user_id=?",
                            UUID.class,
                            USER_ID));
            assertEquals(
                    "Existing authenticator",
                    jdbc.queryForObject(
                            "SELECT label FROM plugin_mfa_authenticator WHERE user_id=?",
                            String.class,
                            USER_ID));
            assertEquals(
                    1234L,
                    jdbc.queryForObject(
                            "SELECT last_step FROM plugin_mfa_authenticator WHERE user_id=?",
                            Long.class,
                            USER_ID));

            long acceptedStep = Instant.now().getEpochSecond() / 30;
            String replacementCode = Totp.code(replacementSecret, acceptedStep, 6);
            var recovery =
                    service.replacementConfirm(
                            "mfa-replacement-user",
                            setup.challengeId(),
                            replacementCode,
                            "qa-session");
            assertEquals(10, recovery.recoveryCodes().size());
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM plugin_mfa_replacement WHERE user_id=?",
                            Integer.class,
                            USER_ID));

            String persistedActiveEnvelope =
                    jdbc.queryForObject(
                            "SELECT secret_envelope FROM plugin_mfa_authenticator WHERE user_id=?",
                            String.class,
                            USER_ID);
            activeSecret = nodeBKeys.require().decrypt(activeContext(), persistedActiveEnvelope);
            assertTrue(Arrays.equals(replacementSecret, activeSecret));
            assertEquals(
                    "New authenticator",
                    jdbc.queryForObject(
                            "SELECT label FROM plugin_mfa_authenticator WHERE user_id=?",
                            String.class,
                            USER_ID));

            long verifyTime = Instant.now().getEpochSecond();
            long currentStep = verifyTime / 30;
            long replacementVerifyStep = Math.max(currentStep, acceptedStep + 1);
            assertTrue(replacementVerifyStep <= currentStep + 1);
            Long oldCodeStep = null;
            for (long step = currentStep - 1; step <= currentStep + 1; step++) {
                String oldCode = Totp.code(originalSecret, step, 6);
                if (Totp.verify(replacementSecret, oldCode, verifyTime, acceptedStep) < 0) {
                    oldCodeStep = step;
                    break;
                }
            }
            assertNotNull(
                    oldCodeStep, "The test fixture must have a non-colliding old factor code");
            var nodeBService = newService(nodeBKeys);
            assertNull(
                    nodeBService.verifyEvidence(
                            USER_ID, Totp.code(originalSecret, oldCodeStep, 6), null));
            assertEquals(
                    "TOTP",
                    nodeBService.verifyEvidence(
                            USER_ID, Totp.code(replacementSecret, replacementVerifyStep, 6), null));
        } finally {
            Arrays.fill(replacementSecret, (byte) 0);
            if (activeSecret != null) Arrays.fill(activeSecret, (byte) 0);
        }

        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM plugin_mfa_authenticator WHERE user_id=? AND confirmed AND setup_challenge_id IS NULL",
                        Integer.class,
                        USER_ID));
        verify(reauthentication)
                .consume(ticketId, USER_ID, ReauthenticationPort.Action.MFA_REPLACE, "qa-session");
    }

    private MfaService newService(SecretKeys keys) {
        return new MfaService(
                store,
                owners,
                security,
                policies,
                policyLock,
                reauthentication,
                keys,
                tx,
                audit,
                administration,
                mail,
                recoveryTickets,
                client);
    }

    private static String activeContext() {
        return "mfa:" + USER_ID + ":secret";
    }

    private static String pendingContext() {
        return activeContext() + ":replacement";
    }

    private void createHostFixture() {
        jdbc.execute(
                """
                CREATE TABLE platform_user (
                    id uuid PRIMARY KEY,
                    username varchar(100) NOT NULL UNIQUE,
                    enabled boolean NOT NULL,
                    deleted_at timestamptz,
                    factor_credential_revision uuid NOT NULL
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_authority (
                    user_id uuid NOT NULL REFERENCES platform_user(id),
                    authority varchar(100) NOT NULL,
                    PRIMARY KEY(user_id,authority)
                )
                """);
    }

    private void applyPluginMigrations() throws IOException {
        for (String migration :
                List.of(
                        "V1__authenticator.sql",
                        "V2__bind_setup_to_session_challenge.sql",
                        "V3__atomic_authenticator_replacement.sql",
                        "V4__bind_replacement_to_policy_revision.sql",
                        "V5__replacement_totp_step.sql")) {
            jdbc.execute(
                    Files.readString(
                            repositoryRoot().resolve("plugins/mfa/migrations").resolve(migration)));
        }
    }

    private Path repositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isDirectory(path.resolve("plugins/mfa/migrations"))) return path;
        }
        throw new IllegalStateException(
                "Cannot locate plugins/mfa/migrations from the test working directory");
    }
}
