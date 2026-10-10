package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.cbor.CBORFactory;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class PasskeyReauthenticationDatabaseTest {
    private static final UUID USER_ID = UUID.fromString("dbfed55f-dfa1-4bf9-8f2a-679d511398a3");
    private static final UUID PRIMARY_REVISION =
            UUID.fromString("539abcb1-f711-4d8f-a4bd-7b53fd6b35ec");
    private static final UUID FACTOR_REVISION =
            UUID.fromString("fcae581a-74a3-46e6-a824-5027bb32f8d0");
    private static final String USERNAME = "passkey-reauth";
    private static final String SESSION_ID = "passkey-reauth-session";
    private static final String RP_ID = "example.test";
    private static final String ORIGIN = "https://example.test";

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private TransactionTemplate tx;
    private EffectiveAuthenticationPolicyPort.EffectivePolicy effective;
    private PasskeyCredentialStore credentials;
    private PasskeyChallengeStore challenges;
    private ReauthenticationService reauthentication;
    private PasskeyService passkeys;
    private PasswordChangeLimiter passwordLimiter;
    private PasskeyRateLimiter passkeyLimiter;
    private MockHttpServletRequest request;
    private KeyPair keyPair;
    private byte[] credentialId;
    private UUID credentialRecordId;
    private ObjectMapper cbor;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_passkey_reauth_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        createSchema();

        effective = policy(UUID.randomUUID(), PRIMARY_REVISION);
        var policies = mock(EffectiveAuthenticationPolicyPort.class);
        when(policies.resolve(USER_ID)).thenAnswer(invocation -> effective);
        when(policies.secondFactorAvailable()).thenReturn(true);
        var owner = mock(AccountOwnerPort.class);
        when(owner.id(USERNAME, false)).thenReturn(USER_ID);
        when(owner.id(USERNAME, true)).thenReturn(USER_ID);
        var client = JdbcClient.create(dataSource);
        var buckets = new FixedWindowBuckets(client);
        passwordLimiter = new PasswordChangeLimiter(buckets);
        passkeyLimiter = new PasskeyRateLimiter(buckets, 1000, 120, 10, 2000, 240, 20);
        var policyLock = new IdentityAuthenticationPolicyWriteLock(client);
        var extensions =
                new PluginExtensionsPort() {
                    @Override
                    public <T, R> List<R> extensions(
                            Class<T> type, java.util.function.Function<T, R> call) {
                        return List.of();
                    }
                };
        var publicCrypto =
                new PublicAuthCrypto(
                        new SecretKeys(
                                "primary=" + Base64.getEncoder().encodeToString(new byte[32]),
                                "primary"));
        var methods =
                new AuthenticationMethodAvailabilityPort() {
                    @Override
                    public Set<PrimaryMethod> availableMethods() {
                        return Set.of(PrimaryMethod.PASSKEY);
                    }
                };
        reauthentication =
                new ReauthenticationService(
                        client,
                        tx,
                        policyLock,
                        owner,
                        policies,
                        extensions,
                        mock(PasswordEncoder.class),
                        new LoginCodeAccountStore(client),
                        publicCrypto,
                        methods,
                        mock(MailSender.class),
                        mock(SmsSender.class),
                        buckets,
                        new ClientAddressResolver(""));
        PasskeyPolicyPort passkeyPolicy =
                () ->
                        new PasskeyPolicyPort.Policy(
                                true, true, true, RP_ID, "Lumira", List.of(ORIGIN), 300);
        challenges = new PasskeyChallengeStore(client);
        credentials = new PasskeyCredentialStore(client);
        passkeys =
                new PasskeyService(
                        challenges,
                        credentials,
                        passkeyPolicy,
                        client,
                        new ObjectMapper(),
                        mock(PasswordEncoder.class),
                        mock(
                                org.springframework.security.core.userdetails.UserDetailsService
                                        .class),
                        mock(MaintenancePolicyPort.class),
                        mock(LoginAudit.class),
                        new ClientAddressResolver(""),
                        passwordLimiter,
                        passkeyLimiter,
                        mock(SecondFactorLoginService.class),
                        tx,
                        policyLock,
                        mock(AccountSecurityPort.class),
                        mock(CaptchaProofAccess.class),
                        reauthentication);

        request =
                new MockHttpServletRequest("POST", "/api/account/reauthentication/passkey/options");
        request.setRemoteAddr("127.0.0.1");
        request.setSession(new MockHttpSession(null, SESSION_ID));
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        credentialId = new byte[32];
        new java.security.SecureRandom().nextBytes(credentialId);
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        byte[] cose =
                cborMap(
                        Map.of(
                                1, 2,
                                3, -7,
                                -1, 1,
                                -2, unsigned(publicKey.getW().getAffineX(), 32),
                                -3, unsigned(publicKey.getW().getAffineY(), 32)));
        credentialRecordId =
                credentials
                        .insert(
                                USER_ID,
                                new byte[32],
                                credentialId,
                                cose,
                                0,
                                "internal",
                                true,
                                false,
                                "Test key")
                        .id();
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void signedReauthenticationAssertionCreatesOnlyTheParentActionTicket() throws Exception {
        var start =
                reauthentication.start(USERNAME, ReauthenticationPort.Action.MFA_DISABLE, request);
        var options = passkeys.reauthenticationOptions(actor(), start.challengeId(), request);
        UUID passkeyChallengeId = UUID.fromString(options.challengeId());
        assertEquals(
                "REAUTHENTICATION",
                jdbc.queryForObject(
                        "SELECT kind FROM platform_passkey_challenge WHERE id=?",
                        String.class,
                        passkeyChallengeId));
        assertEquals(
                start.challengeId(),
                jdbc.queryForObject(
                        "SELECT reauthentication_challenge_id FROM platform_passkey_challenge WHERE id=?",
                        UUID.class,
                        passkeyChallengeId));
        assertEquals(
                PRIMARY_REVISION,
                jdbc.queryForObject(
                        "SELECT primary_credential_revision FROM platform_passkey_challenge WHERE id=?",
                        UUID.class,
                        passkeyChallengeId));

        var proof =
                passkeys.completeReauthentication(
                        actor(),
                        start.challengeId(),
                        signedAssertion(
                                options.challengeId(),
                                (String) options.publicKey().get("challenge"),
                                1),
                        request);
        var ticket =
                reauthentication.completePasskey(
                        USERNAME, start.challengeId(), proof, null, request);

        assertEquals("MFA_DISABLE", ticket.action());
        assertEquals(
                "COMPLETED",
                jdbc.queryForObject(
                        "SELECT state FROM platform_reauthentication_challenge WHERE id=?",
                        String.class,
                        start.challengeId()));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_reauthentication_ticket WHERE id=? AND action='MFA_DISABLE'",
                        Integer.class,
                        ticket.ticketId()));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_passkey_challenge WHERE id=? AND consumed_at IS NOT NULL",
                        Integer.class,
                        passkeyChallengeId));
        assertThrows(
                ResponseStatusException.class,
                () -> consume(ticket.ticketId(), ReauthenticationPort.Action.MFA_RENAME));
        consume(ticket.ticketId(), ReauthenticationPort.Action.MFA_DISABLE);
    }

    @Test
    void ordinaryLoginAssertionCannotBeConsumedAsReauthenticationAndCredentialEpochIsChecked()
            throws Exception {
        var start =
                reauthentication.start(USERNAME, ReauthenticationPort.Action.MFA_REPLACE, request);
        var reauthOptions = passkeys.reauthenticationOptions(actor(), start.challengeId(), request);
        var loginChallenge =
                challenges.create("AUTHENTICATION", new byte[32], null, null, SESSION_ID, 300);
        var loginAssertion =
                signedAssertion(
                        loginChallenge.id().toString(),
                        Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(loginChallenge.challenge()),
                        1);

        var reuse =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                passkeys.completeReauthentication(
                                        actor(), start.challengeId(), loginAssertion, request));
        assertEquals("passkeyChallengeExpired", reuse.getReason());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_passkey_challenge WHERE id=? AND consumed_at IS NOT NULL",
                        Integer.class,
                        loginChallenge.id()));

        UUID nextPrimary = UUID.randomUUID();
        jdbc.update(
                "UPDATE platform_user SET primary_credential_revision=? WHERE id=?",
                nextPrimary,
                USER_ID);
        effective = policy(UUID.randomUUID(), nextPrimary);
        var changedEpoch =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                passkeys.completeReauthentication(
                                        actor(),
                                        start.challengeId(),
                                        signedAssertion(
                                                reauthOptions.challengeId(),
                                                (String) reauthOptions.publicKey().get("challenge"),
                                                1),
                                        request));
        assertEquals("reauthenticationExpired", changedEpoch.getReason());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_passkey_challenge WHERE id=? AND consumed_at IS NOT NULL",
                        Integer.class,
                        UUID.fromString(reauthOptions.challengeId())));
    }

    @Test
    void pendingChallengeCapIsScopedToSessionAndKindAndDropsStaleRows() {
        List<UUID> ids = new ArrayList<>();
        for (int attempt = 0;
                attempt < PasskeyChallengeStore.MAX_PENDING_PER_SESSION_KIND;
                attempt++) {
            ids.add(
                    tx.execute(
                            status ->
                                    challenges
                                            .create(
                                                    "REGISTRATION",
                                                    new byte[32],
                                                    USER_ID,
                                                    new byte[32],
                                                    "bounded-session",
                                                    300,
                                                    PRIMARY_REVISION)
                                            .id()));
        }
        var limited =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                tx.execute(
                                        status ->
                                                challenges.create(
                                                        "REGISTRATION",
                                                        new byte[32],
                                                        USER_ID,
                                                        new byte[32],
                                                        "bounded-session",
                                                        300,
                                                        PRIMARY_REVISION)));
        assertEquals(429, limited.getStatusCode().value());
        assertEquals("passkeyRateLimited", limited.getReason());

        jdbc.update(
                "UPDATE platform_passkey_challenge SET expires_at=now()-interval '1 second' WHERE id=?",
                ids.getFirst());
        UUID replacement =
                tx.execute(
                        status ->
                                challenges
                                        .create(
                                                "REGISTRATION",
                                                new byte[32],
                                                USER_ID,
                                                new byte[32],
                                                "bounded-session",
                                                300,
                                                PRIMARY_REVISION)
                                        .id());
        assertEquals(
                5,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_passkey_challenge WHERE session_id='bounded-session' AND kind='REGISTRATION' AND consumed_at IS NULL",
                        Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_passkey_challenge WHERE id=?",
                        Integer.class,
                        ids.getFirst()));
        assertNotNull(replacement);

        assertDoesNotThrow(
                () ->
                        tx.execute(
                                status ->
                                        challenges.create(
                                                "AUTHENTICATION",
                                                new byte[32],
                                                null,
                                                null,
                                                "bounded-session",
                                                300)));
        assertDoesNotThrow(
                () ->
                        tx.execute(
                                status ->
                                        challenges.create(
                                                "REGISTRATION",
                                                new byte[32],
                                                USER_ID,
                                                new byte[32],
                                                "another-session",
                                                300,
                                                PRIMARY_REVISION)));
    }

    @Test
    void concurrentChallengeIssuanceCannotExceedTheSessionKindCap() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int attempt = 0; attempt < 6; attempt++) {
                outcomes.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        tx.execute(
                                                status ->
                                                        challenges.create(
                                                                "AUTHENTICATION",
                                                                new byte[32],
                                                                null,
                                                                null,
                                                                "concurrent-session",
                                                                300));
                                        return true;
                                    } catch (ResponseStatusException limited) {
                                        assertEquals(429, limited.getStatusCode().value());
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> outcome : outcomes)
                if (outcome.get(15, TimeUnit.SECONDS)) accepted++;
            assertEquals(PasskeyChallengeStore.MAX_PENDING_PER_SESSION_KIND, accepted);
        }
        assertEquals(
                PasskeyChallengeStore.MAX_PENDING_PER_SESSION_KIND,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_passkey_challenge WHERE session_id='concurrent-session' AND kind='AUTHENTICATION' AND consumed_at IS NULL",
                        Integer.class));
    }

    @Test
    void cleanupDrainsASeveralBatchBacklog() {
        jdbc.batchUpdate(
                "INSERT INTO platform_passkey_challenge(id,kind,challenge,session_id,expires_at) VALUES(?, 'AUTHENTICATION', ?, ?, now()-interval '1 second')",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement statement, int index)
                            throws java.sql.SQLException {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setBytes(2, new byte[32]);
                        statement.setString(3, "backlog-session-" + index);
                    }

                    @Override
                    public int getBatchSize() {
                        return 4_500;
                    }
                });

        new PasskeyChallengeMaintenance(challenges).purge();

        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_passkey_challenge", Integer.class));
    }

    @Test
    void passkeyPasswordProofSharesTheAccountBudgetAndStopsBeforeHashing() {
        for (int attempt = 0; attempt < PasswordChangeLimiter.ATTEMPTS_PER_MINUTE - 1; attempt++)
            assertTrue(passwordLimiter.allow(USERNAME));

        var fifthAttempt =
                assertThrows(
                        ResponseStatusException.class,
                        () -> passkeys.rename(actor(), credentialRecordId, "Changed", "wrong"));
        assertEquals("wrongCurrentPassword", fifthAttempt.getReason());

        var sixthAttempt =
                assertThrows(
                        ResponseStatusException.class,
                        () -> passkeys.rename(actor(), credentialRecordId, "Changed", "wrong"));
        assertEquals(429, sixthAttempt.getStatusCode().value());
        assertEquals("passwordChangeLimited", sixthAttempt.getReason());
        assertEquals(
                PasswordChangeLimiter.ATTEMPTS_PER_MINUTE + 1,
                jdbc.queryForObject(
                        "SELECT attempts FROM platform_rate_bucket WHERE bucket_key=?",
                        Integer.class,
                        FixedWindowBuckets.key("password-chg", USERNAME)));
    }

    private PasskeyRequests.Authentication signedAssertion(
            String challengeId, String challenge, long counter) throws Exception {
        byte[] clientData =
                new ObjectMapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "type",
                                        "webauthn.get",
                                        "challenge",
                                        challenge,
                                        "origin",
                                        ORIGIN,
                                        "crossOrigin",
                                        false));
        byte[] authData =
                ByteBuffer.allocate(37)
                        .put(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(RP_ID.getBytes(StandardCharsets.UTF_8)))
                        .put((byte) 0x05)
                        .putInt((int) counter)
                        .array();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(authData);
        signer.update(java.security.MessageDigest.getInstance("SHA-256").digest(clientData));
        String encodedCredential =
                Base64.getUrlEncoder().withoutPadding().encodeToString(credentialId);
        return new PasskeyRequests.Authentication(
                challengeId,
                encodedCredential,
                encodedCredential,
                "public-key",
                new PasskeyRequests.Authentication.Response(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(clientData),
                        Base64.getUrlEncoder().withoutPadding().encodeToString(authData),
                        Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()),
                        null),
                "platform");
    }

    private void consume(UUID ticket, ReauthenticationPort.Action action) {
        tx.executeWithoutResult(
                status -> reauthentication.consume(ticket, USER_ID, action, SESSION_ID));
    }

    private static UsernamePasswordAuthenticationToken actor() {
        return new UsernamePasswordAuthenticationToken(USERNAME, "", List.of());
    }

    private EffectiveAuthenticationPolicyPort.EffectivePolicy policy(UUID revision, UUID primary) {
        return new EffectiveAuthenticationPolicyPort.EffectivePolicy(
                revision,
                Set.of(PrimaryMethod.PASSKEY),
                Set.of(PrimaryMethod.PASSKEY),
                false,
                List.of(),
                false,
                primary,
                FACTOR_REVISION);
    }

    private byte[] cborMap(Map<Integer, Object> values) throws Exception {
        return new ObjectMapper(new CBORFactory()).writeValueAsBytes(values);
    }

    private static byte[] unsigned(BigInteger value, int length) {
        byte[] encoded = value.toByteArray();
        int start = encoded.length > 1 && encoded[0] == 0 ? 1 : 0;
        byte[] output = new byte[length];
        System.arraycopy(
                encoded, start, output, length - (encoded.length - start), encoded.length - start);
        return output;
    }

    private void createSchema() {
        jdbc.execute(
                """
                CREATE TABLE platform_user (
                    id uuid PRIMARY KEY, username varchar(100) UNIQUE NOT NULL, display_name text,
                    enabled boolean NOT NULL, deleted_at timestamptz, password_hash text NOT NULL,
                    primary_credential_revision uuid NOT NULL, factor_credential_revision uuid NOT NULL
                )
                """);
        jdbc.update(
                """
                INSERT INTO platform_user(id,username,display_name,enabled,password_hash,
                                          primary_credential_revision,factor_credential_revision)
                VALUES (?, ?, 'Passkey User', true, 'hash', ?, ?)
                """,
                USER_ID,
                USERNAME,
                PRIMARY_REVISION,
                FACTOR_REVISION);
        jdbc.execute(
                "CREATE TABLE platform_authentication_policy_lock(id smallint PRIMARY KEY CHECK(id=1))");
        jdbc.update("INSERT INTO platform_authentication_policy_lock VALUES (1)");
        jdbc.execute(
                "CREATE TABLE platform_rate_bucket(bucket_key text PRIMARY KEY, window_start timestamptz NOT NULL, attempts integer NOT NULL)");
        jdbc.execute(
                """
                CREATE TABLE platform_reauthentication_challenge (
                    id uuid PRIMARY KEY, user_id uuid NOT NULL, session_hash char(64) NOT NULL,
                    action varchar(48) NOT NULL, authentication_revision uuid NOT NULL,
                    expires_at timestamptz NOT NULL, attempts integer NOT NULL DEFAULT 0,
                    completed_at timestamptz, ticket_id uuid, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    state varchar(16) NOT NULL DEFAULT 'PENDING'
                )
                """);
        jdbc.execute(
                "CREATE UNIQUE INDEX reauth_pending_session ON platform_reauthentication_challenge(user_id,session_hash) WHERE state='PENDING'");
        jdbc.execute(
                """
                CREATE TABLE platform_reauthentication_code_challenge (
                    id uuid PRIMARY KEY,
                    reauthentication_challenge_id uuid NOT NULL
                        REFERENCES platform_reauthentication_challenge(id) ON DELETE CASCADE,
                    user_id uuid NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
                    session_hash char(64) NOT NULL,
                    method varchar(16) NOT NULL,
                    contact_digest char(64) NOT NULL,
                    contact_version bigint NOT NULL,
                    primary_credential_revision uuid NOT NULL,
                    factor_credential_revision uuid NOT NULL,
                    code_digest char(64),
                    state varchar(16) NOT NULL,
                    attempts smallint NOT NULL DEFAULT 0,
                    expires_at timestamptz NOT NULL,
                    consumed_at timestamptz,
                    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute(
                "CREATE UNIQUE INDEX reauth_code_pending ON platform_reauthentication_code_challenge(reauthentication_challenge_id) WHERE state='PENDING'");
        jdbc.execute(
                """
                CREATE TABLE platform_reauthentication_ticket (
                    id uuid PRIMARY KEY, challenge_id uuid NOT NULL UNIQUE REFERENCES platform_reauthentication_challenge(id) ON DELETE CASCADE,
                    user_id uuid NOT NULL, session_hash char(64) NOT NULL, action varchar(48) NOT NULL,
                    authentication_revision uuid NOT NULL, expires_at timestamptz NOT NULL,
                    consumed_at timestamptz, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_passkey_challenge (
                    id uuid PRIMARY KEY, kind varchar(20) NOT NULL, challenge bytea NOT NULL,
                    user_id uuid, user_handle bytea, session_id varchar(200) NOT NULL,
                    expires_at timestamptz NOT NULL, consumed_at timestamptz,
                    primary_credential_revision uuid, captcha_policy_revision varchar(128),
                    captcha_satisfied boolean NOT NULL DEFAULT false, reauthentication_challenge_id uuid
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_passkey_credential (
                    id uuid PRIMARY KEY, user_id uuid NOT NULL, user_handle bytea NOT NULL,
                    credential_id bytea UNIQUE NOT NULL, public_key_cose bytea NOT NULL,
                    sign_count bigint NOT NULL, transports text NOT NULL, backup_eligible boolean NOT NULL,
                    backup_state boolean NOT NULL, label varchar(128) NOT NULL,
                    last_used_at timestamptz, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }
}
