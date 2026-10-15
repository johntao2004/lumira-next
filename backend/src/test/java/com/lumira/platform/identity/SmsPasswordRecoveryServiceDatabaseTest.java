package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.common.SmsSender;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class SmsPasswordRecoveryServiceDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private UUID accountId;
    private UUID otherAccountId;
    private String sessionId;
    private SmsPasswordRecoveryService service;
    private SmsSender sms;
    private PasswordEncoder passwords;
    private List<String> sentCodes;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_sms_recovery_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY,username text UNIQUE,password_hash text,enabled boolean NOT NULL,deleted_at timestamptz,primary_credential_revision uuid NOT NULL)");
        jdbc.execute("CREATE TABLE platform_password_change_requirement(user_id uuid PRIMARY KEY)");
        jdbc.execute(
                "CREATE TABLE platform_bootstrap_password_change_requirement(user_id uuid PRIMARY KEY,primary_credential_revision uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,completed_at timestamptz)");
        jdbc.execute("CREATE TABLE platform_role(id uuid PRIMARY KEY)");
        for (String table :
                List.of(
                        "platform_public_auth_challenge",
                        "platform_public_auth_rate",
                        "platform_account_phone")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        jdbc.execute(
                "CREATE TABLE platform_rate_bucket(bucket_key text PRIMARY KEY,window_start timestamptz NOT NULL,attempts integer NOT NULL)");
        jdbc.execute(
                "CREATE TABLE spring_session(principal_name text NOT NULL,session_id text NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_second_factor_requirement(user_id uuid PRIMARY KEY,provider_id text NOT NULL,self_enabled boolean NOT NULL DEFAULT false,recovery_pending boolean NOT NULL DEFAULT false)");
        jdbc.execute(
                """
                CREATE TABLE platform_audit(
                    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),actor varchar(100) NOT NULL,
                    action varchar(100) NOT NULL,target_id uuid,created_at timestamptz NOT NULL DEFAULT now())
                """);

        accountId = UUID.randomUUID();
        otherAccountId = UUID.randomUUID();
        sessionId = "sms-recovery-session";
        UUID originalRevision = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_user VALUES (?, 'phone-user', 'old-hash', true, NULL, ?)",
                accountId,
                originalRevision);
        jdbc.update(
                "INSERT INTO platform_user VALUES (?, 'other-user', 'other-hash', true, NULL, ?)",
                otherAccountId,
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO platform_second_factor_requirement(user_id,provider_id,self_enabled,recovery_pending) VALUES (?, 'mfa', true, false)",
                accountId);
        jdbc.update(
                "INSERT INTO platform_account_phone(user_id,phone,verified_at,version) VALUES (?, '+15555550123', now(), 7)",
                accountId);
        jdbc.update(
                "INSERT INTO spring_session VALUES ('phone-user','old-session-1'),('phone-user','old-session-2'),('other-user','keep-session')");

        var client = JdbcClient.create(dataSource);
        var security = mock(SecurityPolicyPort.class);
        when(security.currentPolicy()).thenReturn(SecurityPolicyPort.Policy.defaults());
        var buckets = new FixedWindowBuckets(client);
        var limiter = new PublicAuthRateLimiter(client, buckets, security);
        var captcha = mock(CaptchaProofAccess.class);
        var proofPort = mock(CaptchaProofPort.class);
        var gate =
                new CaptchaProofAccess.Gate(
                        proofPort,
                        CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST,
                        new CaptchaProofPort.Requirement(false, "captcha-off-v1", null));
        when(captcha.begin(any(), any(), any(), any())).thenReturn(gate);
        passwords = mock(PasswordEncoder.class);
        when(passwords.encode(anyString())).thenReturn("encoded-new-password");
        when(passwords.matches(anyString(), anyString())).thenReturn(false);
        when(passwords.matches("old-password-123", "old-hash")).thenReturn(true);
        var address = mock(ClientAddressResolver.class);
        when(address.resolve(any(HttpServletRequest.class))).thenReturn("127.0.0.1");
        sms = mock(SmsSender.class);
        when(sms.ready()).thenReturn(true);
        sentCodes = java.util.Collections.synchronizedList(new ArrayList<>());
        when(sms.verificationMessage(anyString()))
                .thenAnswer(invocation -> "verification code " + invocation.getArgument(0));
        doCallRealMethod().when(sms).enqueueVerification(any(), anyString(), anyString(), anyInt());
        when(sms.enqueue(any(), anyString(), anyString(), anyInt()))
                .thenAnswer(
                        invocation -> {
                            String message = invocation.getArgument(2);
                            sentCodes.add(message.substring(message.lastIndexOf(' ') + 1));
                            return true;
                        });
        var accountSecurity = mock(AccountSecurityPort.class);
        doAnswer(
                        invocation -> {
                            UUID id = invocation.getArgument(0);
                            UUID revision = UUID.randomUUID();
                            jdbc.update(
                                    "UPDATE platform_user SET primary_credential_revision=? WHERE id=?",
                                    revision,
                                    id);
                            return revision;
                        })
                .when(accountSecurity)
                .bumpPrimaryCredentialRevision(any());
        doAnswer(
                        invocation -> {
                            UUID id = invocation.getArgument(0);
                            String retained = invocation.getArgument(1);
                            String username =
                                    jdbc.queryForObject(
                                            "SELECT username FROM platform_user WHERE id=?",
                                            String.class,
                                            id);
                            jdbc.update(
                                    "DELETE FROM spring_session WHERE principal_name=? AND session_id<>?",
                                    username,
                                    retained == null ? "" : retained);
                            return null;
                        })
                .when(accountSecurity)
                .revokeSessions(any(), any());

        service =
                new SmsPasswordRecoveryService(
                        security,
                        mock(AuthenticationPolicyWriteLock.class),
                        new PublicAuthChallengeStore(client),
                        new LoginCodeAccountStore(client),
                        new PublicAuthCrypto(
                                new SecretKeys(
                                        "primary="
                                                + java.util.Base64.getEncoder()
                                                        .encodeToString(new byte[32]),
                                        "primary")),
                        limiter,
                        captcha,
                        address,
                        sms,
                        passwords,
                        accountSecurity,
                        mock(AuditPort.class),
                        client,
                        new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void verifiedPhoneCanResetPasswordAndRevokesOnlyThatAccountsSessionsWithoutTouchingMfa() {
        var request = request();
        var sent = service.requestChallenge(request("+15555550123"), request);
        assertFalse(sent.limited());
        assertEquals(1, sentCodes.size());
        var verified = verify(sent.challenge().challengeId(), sentCodes.getFirst(), request);
        assertTrue(verified.valid());

        var confirmation = confirmation(verified.verification().proofId(), "new-password-123");
        assertTrue(service.confirm(confirmation, request));
        assertEquals(
                "encoded-new-password",
                jdbc.queryForObject(
                        "SELECT password_hash FROM platform_user WHERE id=?",
                        String.class,
                        accountId));
        assertNotNull(
                jdbc.queryForObject(
                        "SELECT primary_credential_revision FROM platform_user WHERE id=?",
                        UUID.class,
                        accountId));
        assertEquals(
                List.of("keep-session"),
                jdbc.queryForList(
                        "SELECT session_id FROM spring_session ORDER BY session_id", String.class));
        assertEquals(
                "CONSUMED",
                jdbc.queryForObject(
                        "SELECT state FROM platform_public_auth_challenge WHERE purpose='SMS_RECOVERY'",
                        String.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_second_factor_requirement", Integer.class));
        assertTrue(
                jdbc.queryForObject(
                        "SELECT self_enabled FROM platform_second_factor_requirement WHERE user_id=?",
                        Boolean.class,
                        accountId));
    }

    @Test
    void stalePhoneRevisionCannotResetAndWrongCodeAttemptsCommit() {
        var request = request();
        var sent = service.requestChallenge(request("+15555550123"), request);
        var invalid = verify(sent.challenge().challengeId(), "000000", request);
        assertFalse(invalid.valid());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT attempts FROM platform_public_auth_challenge WHERE id=?",
                        Integer.class,
                        UUID.fromString(sent.challenge().challengeId())));
        var verified = verify(sent.challenge().challengeId(), sentCodes.getFirst(), request);
        assertTrue(verified.valid());

        jdbc.update(
                "UPDATE platform_account_phone SET version=version+1 WHERE user_id=?", accountId);
        assertFalse(
                service.confirm(
                        confirmation(verified.verification().proofId(), "new-password-123"),
                        request));
        assertEquals(
                "old-hash",
                jdbc.queryForObject(
                        "SELECT password_hash FROM platform_user WHERE id=?",
                        String.class,
                        accountId));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));
    }

    @Test
    void samePasswordFailureLeavesProofAvailableForAValidRetry() {
        jdbc.update("UPDATE platform_user SET password_hash='old-hash' WHERE id=?", accountId);
        var request = request();
        var sent = service.requestChallenge(request("+15555550123"), request);
        var verified = verify(sent.challenge().challengeId(), sentCodes.getFirst(), request);
        assertTrue(verified.valid());

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.confirm(
                                        confirmation(
                                                verified.verification().proofId(),
                                                "old-password-123"),
                                        request));
        assertEquals("passwordMustDiffer", error.getReason());
        assertEquals(
                "VERIFIED",
                jdbc.queryForObject(
                        "SELECT state FROM platform_public_auth_challenge WHERE id=?",
                        String.class,
                        UUID.fromString(sent.challenge().challengeId())));
        assertTrue(
                service.confirm(
                        confirmation(verified.verification().proofId(), "new-password-123"),
                        request));
    }

    @Test
    void unknownContactReceivesSameChallengeShapeWithoutSmsAndSameSessionSendsSupersedePriorCodes()
            throws Exception {
        var request = request();
        var unknown = service.requestChallenge(request("+15555550999"), request);
        assertNotNull(unknown.challenge().challengeId());
        assertEquals(0, sentCodes.size());

        jdbc.update(
                "INSERT INTO platform_account_phone(user_id,phone,verified_at,version) VALUES (?, '+15555550456', now(), 3)",
                otherAccountId);
        var firstRequest = request("+15555550123");
        var secondRequest = request("+15555550456");
        CountDownLatch ready = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                if (!ready.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("send barrier timeout");
                                return service.requestChallenge(firstRequest, request);
                            });
            var second =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                if (!ready.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("send barrier timeout");
                                return service.requestChallenge(secondRequest, request);
                            });
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }

        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_public_auth_challenge WHERE purpose='SMS_RECOVERY' AND session_digest=(SELECT session_digest FROM platform_public_auth_challenge WHERE purpose='SMS_RECOVERY' ORDER BY created_at LIMIT 1) AND state='PENDING'",
                        Integer.class));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_public_auth_challenge WHERE purpose='SMS_RECOVERY' AND state='SUPERSEDED'",
                        Integer.class));
    }

    private SmsPasswordRecoveryService.SendResult requestChallenge(
            String contact, MockHttpServletRequest request) {
        return service.requestChallenge(request(contact), request);
    }

    private PublicAuthRequests.RecoveryRequest request(String contact) {
        var input = new PublicAuthRequests.RecoveryRequest();
        input.channel = "SMS";
        input.contact = contact;
        input.locale = "en";
        return input;
    }

    private SmsPasswordRecoveryService.VerifyResult verify(
            String challengeId, String code, HttpServletRequest request) {
        var input = new PublicAuthRequests.RecoveryVerify();
        input.challengeId = challengeId;
        input.code = code;
        return service.verifyChallenge(input, request);
    }

    private PublicAuthRequests.RecoveryConfirmation confirmation(String proofId, String password) {
        var input = new PublicAuthRequests.RecoveryConfirmation();
        input.proofId = proofId;
        input.newPassword = password;
        return input;
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession(null, sessionId));
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
