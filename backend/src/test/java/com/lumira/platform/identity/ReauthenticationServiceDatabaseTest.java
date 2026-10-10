package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class ReauthenticationServiceDatabaseTest {
    private static final UUID USER_ID = UUID.fromString("7830c4a3-03c1-4385-a89c-30a39a676560");
    private static final UUID PRIMARY_REVISION =
            UUID.fromString("e6a6b60a-57b7-44ac-9dc0-6c84e2982c0c");

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private TransactionTemplate tx;
    private AtomicReference<EffectiveAuthenticationPolicyPort.EffectivePolicy> effective;
    private AtomicReference<List<SecondFactorProvider>> providers;
    private AtomicReference<String> mailedText;
    private ReauthenticationService service;
    private MockHttpServletRequest request;
    private UUID factorRevision;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_reauthentication_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        createSchema();

        factorRevision = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO platform_user(id,username,enabled,password_hash,primary_credential_revision,
                                          factor_credential_revision)
                VALUES (?, 'reauth-user', true, 'unused-hash', ?, ?)
                """,
                USER_ID,
                PRIMARY_REVISION,
                factorRevision);
        jdbc.update(
                """
                INSERT INTO platform_account_email(user_id,email,verified_at,version)
                VALUES (?, 'verified@example.test', CURRENT_TIMESTAMP, 0)
                """,
                USER_ID);

        effective = new AtomicReference<>(policy(UUID.randomUUID(), factorRevision, false));
        providers = new AtomicReference<>(List.of());
        mailedText = new AtomicReference<>();
        var owner = mock(AccountOwnerPort.class);
        when(owner.id("reauth-user", false)).thenReturn(USER_ID);
        when(owner.id("reauth-user", true)).thenReturn(USER_ID);
        var policies = mock(EffectiveAuthenticationPolicyPort.class);
        when(policies.resolve(USER_ID)).thenAnswer(invocation -> effective.get());
        when(policies.secondFactorAvailable()).thenReturn(true);
        var extensions =
                new PluginExtensionsPort() {
                    @Override
                    public <T, R> List<R> extensions(
                            Class<T> type, java.util.function.Function<T, R> call) {
                        if (type != SecondFactorProvider.class) return List.of();
                        return providers.get().stream()
                                .map(provider -> call.apply(type.cast(provider)))
                                .toList();
                    }
                };
        var mail = mock(MailSender.class);
        when(mail.ready()).thenReturn(true);
        when(mail.enqueue(any(), anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(
                        invocation -> {
                            mailedText.set(invocation.getArgument(3));
                            return true;
                        });
        var sms = mock(SmsSender.class);
        var availability =
                new AuthenticationMethodAvailabilityPort() {
                    @Override
                    public Set<PrimaryMethod> availableMethods() {
                        return Set.of(
                                PrimaryMethod.EMAIL_OTP,
                                PrimaryMethod.PASSWORD,
                                PrimaryMethod.PASSKEY);
                    }
                };
        var client = JdbcClient.create(dataSource);
        service =
                new ReauthenticationService(
                        client,
                        tx,
                        new IdentityAuthenticationPolicyWriteLock(client),
                        owner,
                        policies,
                        extensions,
                        mock(PasswordEncoder.class),
                        new LoginCodeAccountStore(client),
                        new PublicAuthCrypto(
                                new com.lumira.platform.common.SecretKeys(
                                        "primary="
                                                + Base64.getEncoder().encodeToString(new byte[32]),
                                        "primary")),
                        availability,
                        mail,
                        sms,
                        new FixedWindowBuckets(client),
                        new ClientAddressResolver(""));

        request = new MockHttpServletRequest("POST", "/api/account/reauthentication/start");
        request.setRemoteAddr("127.0.0.1");
        request.setSession(new MockHttpSession(null, "reauth-session-a"));
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void otpReauthenticationIsActionSessionAndSingleUseBoundAndAttemptsCommit() {
        var start = service.start("reauth-user", ReauthenticationPort.Action.MFA_DISABLE, request);
        assertEquals(List.of("EMAIL_OTP"), start.methods());
        var codeChallenge =
                service.requestCode("reauth-user", start.challengeId(), "EMAIL_OTP", request);
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT contact_version FROM platform_reauthentication_code_challenge WHERE id=?",
                        Long.class,
                        codeChallenge.codeChallengeId()));
        String code = codeFromMail();
        String wrong = code.equals("000000") ? "999999" : "000000";

        var invalid =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.completeCode(
                                        "reauth-user",
                                        start.challengeId(),
                                        codeChallenge.codeChallengeId(),
                                        "EMAIL_OTP",
                                        wrong,
                                        null,
                                        request));
        assertEquals("reauthenticationInvalid", invalid.getReason());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT attempts FROM platform_reauthentication_code_challenge WHERE id=?",
                        Integer.class,
                        codeChallenge.codeChallengeId()));

        var ticket =
                service.completeCode(
                        "reauth-user",
                        start.challengeId(),
                        codeChallenge.codeChallengeId(),
                        "EMAIL_OTP",
                        code,
                        null,
                        request);
        assertEquals("MFA_DISABLE", ticket.action());
        assertEquals(
                "COMPLETED",
                jdbc.queryForObject(
                        "SELECT state FROM platform_reauthentication_challenge WHERE id=?",
                        String.class,
                        start.challengeId()));

        assertForbiddenConsume(
                ticket.ticketId(), ReauthenticationPort.Action.MFA_ENROLL, "reauth-session-a");
        assertForbiddenConsume(
                ticket.ticketId(), ReauthenticationPort.Action.MFA_DISABLE, "different-session");
        consume(ticket.ticketId(), ReauthenticationPort.Action.MFA_DISABLE, "reauth-session-a");
        assertForbiddenConsume(
                ticket.ticketId(), ReauthenticationPort.Action.MFA_DISABLE, "reauth-session-a");
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_reauthentication_ticket WHERE id=? AND consumed_at IS NOT NULL",
                        Integer.class,
                        ticket.ticketId()));
    }

    @Test
    void cleanupRetainsCompletedChallengeWhileItsNewerTicketIsStillValid() {
        var start = service.start("reauth-user", ReauthenticationPort.Action.MFA_RENAME, request);
        var codeChallenge =
                service.requestCode("reauth-user", start.challengeId(), "EMAIL_OTP", request);
        var ticket =
                service.completeCode(
                        "reauth-user",
                        start.challengeId(),
                        codeChallenge.codeChallengeId(),
                        "EMAIL_OTP",
                        codeFromMail(),
                        null,
                        request);
        jdbc.update(
                "UPDATE platform_reauthentication_challenge SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",
                start.challengeId());

        tx.executeWithoutResult(status -> service.purgeExpired(100));

        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_reauthentication_challenge WHERE id=?",
                        Integer.class,
                        start.challengeId()));
        consume(ticket.ticketId(), ReauthenticationPort.Action.MFA_RENAME, "reauth-session-a");
    }

    @Test
    void oneSessionHasAtMostOnePendingChallengeAndStartIsRateLimited() {
        var starts = new ArrayList<ReauthenticationService.Start>();
        for (int index = 0; index < 12; index++)
            starts.add(
                    service.start("reauth-user", ReauthenticationPort.Action.MFA_DISABLE, request));

        var limited =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.start(
                                        "reauth-user",
                                        ReauthenticationPort.Action.MFA_DISABLE,
                                        request));
        assertEquals("reauthenticationStartLimited", limited.getReason());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_reauthentication_challenge WHERE state='PENDING'",
                        Integer.class));
        assertEquals(
                11,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_reauthentication_challenge WHERE state='SUPERSEDED'",
                        Integer.class));
        assertEquals(12, starts.size());
    }

    @Test
    void recoveryCodeRotationCreatesTicketAtTheNewFactorEpochAndRefreshesTheCurrentSession() {
        factorRevision = UUID.randomUUID();
        effective.set(policy(UUID.randomUUID(), factorRevision, true));
        providers.set(List.of(new RotatingRecoveryProvider()));
        var start = service.start("reauth-user", ReauthenticationPort.Action.MFA_REPLACE, request);
        var codeChallenge =
                service.requestCode("reauth-user", start.challengeId(), "EMAIL_OTP", request);

        var ticket =
                service.completeCode(
                        "reauth-user",
                        start.challengeId(),
                        codeChallenge.codeChallengeId(),
                        "EMAIL_OTP",
                        codeFromMail(),
                        "recovery-code",
                        request);

        assertEquals(
                effective.get().revision(),
                jdbc.queryForObject(
                        "SELECT authentication_revision FROM platform_reauthentication_ticket WHERE id=?",
                        UUID.class,
                        ticket.ticketId()));
        assertEquals(
                effective.get().factorCredentialRevision().toString(),
                request.getSession()
                        .getAttribute(AuthenticationSession.FACTOR_CREDENTIAL_REVISION));
        assertEquals(
                effective.get().revision().toString(),
                request.getSession().getAttribute(AuthenticationSession.AUTHENTICATION_REVISION));
        assertEquals(
                List.of("RECOVERY_CODE"),
                request.getSession().getAttribute(AuthenticationSession.VERIFIED_FACTORS));
        consume(ticket.ticketId(), ReauthenticationPort.Action.MFA_REPLACE, "reauth-session-a");
    }

    private void createSchema() {
        jdbc.execute(
                """
                CREATE TABLE platform_user (
                    id uuid PRIMARY KEY, username text UNIQUE NOT NULL, enabled boolean NOT NULL,
                    deleted_at timestamptz, password_hash text NOT NULL,
                    primary_credential_revision uuid NOT NULL, factor_credential_revision uuid NOT NULL
                )
                """);
        jdbc.execute(
                "CREATE TABLE platform_authentication_policy_lock(id smallint PRIMARY KEY CHECK(id=1))");
        jdbc.update("INSERT INTO platform_authentication_policy_lock VALUES (1)");
        jdbc.execute(
                """
                CREATE TABLE platform_account_email (
                    user_id uuid PRIMARY KEY REFERENCES platform_user(id), email text,
                    verified_at timestamptz, version bigint NOT NULL
                )
                """);
        jdbc.execute(
                "CREATE TABLE platform_rate_bucket(bucket_key text PRIMARY KEY, window_start timestamptz NOT NULL, attempts integer NOT NULL)");
        jdbc.execute(
                """
                CREATE TABLE platform_reauthentication_challenge (
                    id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES platform_user(id),
                    session_hash char(64) NOT NULL, action varchar(48) NOT NULL,
                    authentication_revision uuid NOT NULL, expires_at timestamptz NOT NULL,
                    attempts integer NOT NULL DEFAULT 0, completed_at timestamptz, ticket_id uuid,
                    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    state varchar(16) NOT NULL DEFAULT 'PENDING'
                )
                """);
        jdbc.execute(
                "CREATE UNIQUE INDEX reauth_pending_per_session ON platform_reauthentication_challenge(user_id,session_hash) WHERE state='PENDING'");
        jdbc.execute(
                """
                CREATE TABLE platform_reauthentication_code_challenge (
                    id uuid PRIMARY KEY,
                    reauthentication_challenge_id uuid NOT NULL REFERENCES platform_reauthentication_challenge(id) ON DELETE CASCADE,
                    user_id uuid NOT NULL REFERENCES platform_user(id), session_hash char(64) NOT NULL,
                    method varchar(16) NOT NULL CHECK (method IN ('EMAIL_OTP','SMS_OTP')),
                    contact_digest char(64) NOT NULL,
                    contact_version bigint NOT NULL CHECK (contact_version > 0),
                    primary_credential_revision uuid NOT NULL, factor_credential_revision uuid NOT NULL,
                    code_digest char(64), state varchar(16) NOT NULL, attempts smallint NOT NULL DEFAULT 0,
                    expires_at timestamptz NOT NULL, consumed_at timestamptz,
                    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CHECK ((state='CONSUMED') = (consumed_at IS NOT NULL))
                )
                """);
        jdbc.execute(readMigration("V39__reauthentication_contact_version_zero.sql"));
        jdbc.execute(
                """
                CREATE TABLE platform_reauthentication_ticket (
                    id uuid PRIMARY KEY, challenge_id uuid NOT NULL UNIQUE REFERENCES platform_reauthentication_challenge(id) ON DELETE CASCADE,
                    user_id uuid NOT NULL REFERENCES platform_user(id), session_hash char(64) NOT NULL,
                    action varchar(48) NOT NULL, authentication_revision uuid NOT NULL,
                    expires_at timestamptz NOT NULL, consumed_at timestamptz,
                    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private String readMigration(String fileName) {
        try (var stream = getClass().getResourceAsStream("/db/migration/" + fileName)) {
            if (stream == null)
                throw new IllegalStateException("Missing migration resource: " + fileName);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read migration resource: " + fileName, error);
        }
    }

    private String codeFromMail() {
        Matcher matcher = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)").matcher(mailedText.get());
        assertTrue(matcher.find(), "mail body should contain an OTP");
        return matcher.group(1);
    }

    private void assertForbiddenConsume(
            UUID ticketId, ReauthenticationPort.Action action, String sessionId) {
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                tx.executeWithoutResult(
                                        status ->
                                                service.consume(
                                                        ticketId, USER_ID, action, sessionId)));
        assertEquals("reauthenticationRequired", error.getReason());
    }

    private void consume(UUID ticketId, ReauthenticationPort.Action action, String sessionId) {
        tx.executeWithoutResult(status -> service.consume(ticketId, USER_ID, action, sessionId));
    }

    private EffectiveAuthenticationPolicyPort.EffectivePolicy policy(
            UUID revision, UUID factor, boolean mfaRequired) {
        var sources =
                mfaRequired
                        ? List.of(
                                new EffectiveAuthenticationPolicyPort.RequirementSource(
                                        "GLOBAL", null))
                        : List.<EffectiveAuthenticationPolicyPort.RequirementSource>of();
        return new EffectiveAuthenticationPolicyPort.EffectivePolicy(
                revision,
                Set.of(PrimaryMethod.EMAIL_OTP),
                Set.of(PrimaryMethod.EMAIL_OTP),
                mfaRequired,
                sources,
                false,
                PRIMARY_REVISION,
                factor);
    }

    private final class RotatingRecoveryProvider implements SecondFactorProvider {
        @Override
        public String id() {
            return "mfa";
        }

        @Override
        public boolean verify(UUID id, String code) {
            return false;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public AccountStatus status(UUID id) {
            return new AccountStatus(true, 9);
        }

        @Override
        public String verifyEvidence(UUID id, String code, String retainedSessionId) {
            if (!"recovery-code".equals(code)) return null;
            UUID nextFactor = UUID.randomUUID();
            jdbc.update(
                    "UPDATE platform_user SET factor_credential_revision=? WHERE id=?",
                    nextFactor,
                    id);
            var previous = effective.get();
            effective.set(
                    new EffectiveAuthenticationPolicyPort.EffectivePolicy(
                            UUID.randomUUID(),
                            previous.allowedMethods(),
                            previous.usableMethods(),
                            previous.mfaRequired(),
                            previous.requirementSources(),
                            previous.captchaRequired(),
                            previous.primaryCredentialRevision(),
                            nextFactor));
            return "RECOVERY_CODE";
        }
    }
}
