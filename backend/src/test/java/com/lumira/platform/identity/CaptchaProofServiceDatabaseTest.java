package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.common.RoleAuthenticationPolicyPort;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.identity.CaptchaSettingsService.Mode;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class CaptchaProofServiceDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private JdbcClient client;
    private TransactionTemplate transactions;
    private String schema;
    private CaptchaSettingsService settings;
    private CaptchaProofService service;
    private String source;
    private PublicAuthCrypto crypto;
    private AtomicReference<Mode> policyMode;
    private AtomicReference<String> challengeType;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_captcha_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        client = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        createBaseTables();
        jdbc.execute(resource("/db/migration/V34__captcha_challenge_and_policy.sql"));
        jdbc.execute(resource("/db/migration/V43__local_captcha_verification.sql"));

        policyMode = new AtomicReference<>(Mode.ALWAYS);
        challengeType = new AtomicReference<>("IMAGE_TEXT");
        settings = mock(CaptchaSettingsService.class);
        when(settings.current()).thenAnswer(invocation -> snapshot(policyMode.get()));
        when(settings.readyFor(any())).thenReturn(true);
        when(settings.readiness()).thenReturn(new CaptchaSettingsService.Readiness(true, true));
        var localAssets = mock(LocalCaptchaAssets.class);
        when(localAssets.create(anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            String type = invocation.getArgument(0);
                            if ("AUDIO".equals(type))
                                return new LocalCaptchaAssets.Assets(
                                        null,
                                        null,
                                        null,
                                        "data:audio/wav;base64,fixture",
                                        0,
                                        0,
                                        0,
                                        0,
                                        0,
                                        "654321",
                                        null);
                            if ("SLIDER".equals(type))
                                return new LocalCaptchaAssets.Assets(
                                        null,
                                        "data:image/png;base64,background",
                                        "data:image/png;base64,piece",
                                        null,
                                        320,
                                        160,
                                        52,
                                        52,
                                        42,
                                        null,
                                        150);
                            return new LocalCaptchaAssets.Assets(
                                    "data:image/png;base64,image",
                                    null,
                                    null,
                                    null,
                                    180,
                                    64,
                                    0,
                                    0,
                                    0,
                                    "123456",
                                    null);
                        });
        var addresses = new ClientAddressResolver("");
        var secretKeys =
                new SecretKeys(
                        "primary=" + java.util.Base64.getEncoder().encodeToString(new byte[32]),
                        "primary");
        crypto = new PublicAuthCrypto(secretKeys);
        @SuppressWarnings("unchecked")
        ObjectProvider<RoleAuthenticationPolicyPort> rolePolicies = mock(ObjectProvider.class);
        service =
                new CaptchaProofService(
                        client,
                        settings,
                        localAssets,
                        addresses,
                        crypto,
                        new FixedWindowBuckets(client),
                        rolePolicies,
                        new DataSourceTransactionManager(dataSource));
        source = "192.0.2.55";
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void proofIsSessionActionAndAttemptBoundSingleUseAndRefreshRevokesOldProof() {
        var request = requestWithSession(new MockHttpSession());
        var facts = facts(request);
        var requirement =
                service.requirement(
                        CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST, null, null, facts);
        var firstChallenge =
                start(CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST, false, request);
        assertEquals(
                64,
                jdbc.queryForObject(
                        "SELECT length(session_digest) FROM platform_captcha_challenge WHERE id=?",
                        Integer.class,
                        firstChallenge.challengeId()));
        assertEquals(
                "LOCAL",
                jdbc.queryForObject(
                        "SELECT provider_app_id FROM platform_captcha_challenge WHERE id=?",
                        String.class,
                        firstChallenge.challengeId()));
        assertEquals(
                64,
                jdbc.queryForObject(
                        "SELECT length(verification_digest) FROM platform_captcha_challenge WHERE id=?",
                        Integer.class,
                        firstChallenge.challengeId()));
        assertTrue(
                !jdbc.queryForObject(
                                "SELECT verification_digest FROM platform_captcha_challenge WHERE id=?",
                                String.class,
                                firstChallenge.challengeId())
                        .contains("123456"));
        var firstProof =
                service.verify(imageAnswer(firstChallenge.challengeId(), "123456"), request);

        var wrongSession = requestWithSession(new MockHttpSession());
        var sessionError =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                transactions.executeWithoutResult(
                                        status ->
                                                service.consume(
                                                        firstProof.captchaProofId().toString(),
                                                        CaptchaProofPort.Action
                                                                .PASSWORD_RECOVERY_REQUEST,
                                                        requirement.policyRevision(),
                                                        facts(wrongSession),
                                                        null)));
        assertEquals("captchaProofExpired", sessionError.getReason());

        var actionError =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                transactions.executeWithoutResult(
                                        status ->
                                                service.consume(
                                                        firstProof.captchaProofId().toString(),
                                                        CaptchaProofPort.Action
                                                                .PASSWORD_RECOVERY_VERIFY,
                                                        service.requirement(
                                                                        CaptchaProofPort.Action
                                                                                .PASSWORD_RECOVERY_VERIFY,
                                                                        null,
                                                                        null,
                                                                        facts)
                                                                .policyRevision(),
                                                        facts,
                                                        null)));
        assertEquals("captchaProofExpired", actionError.getReason());
        var attemptError =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                transactions.executeWithoutResult(
                                        status ->
                                                service.consume(
                                                        firstProof.captchaProofId().toString(),
                                                        CaptchaProofPort.Action
                                                                .PASSWORD_RECOVERY_REQUEST,
                                                        requirement.policyRevision(),
                                                        facts,
                                                        "different-attempt")));
        assertEquals("captchaProofExpired", attemptError.getReason());
        transactions.executeWithoutResult(
                status ->
                        service.consume(
                                firstProof.captchaProofId().toString(),
                                CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST,
                                requirement.policyRevision(),
                                facts,
                                null));
        var replayError =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                transactions.executeWithoutResult(
                                        status ->
                                                service.consume(
                                                        firstProof.captchaProofId().toString(),
                                                        CaptchaProofPort.Action
                                                                .PASSWORD_RECOVERY_REQUEST,
                                                        requirement.policyRevision(),
                                                        facts,
                                                        null)));
        assertEquals("captchaProofExpired", replayError.getReason());

        var secondChallenge =
                start(CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST, false, request);
        var secondProof =
                service.verify(imageAnswer(secondChallenge.challengeId(), "123456"), request);
        var refreshed = start(CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST, false, request);
        assertTrue(
                jdbc.queryForObject(
                        "SELECT consumed_at IS NOT NULL FROM platform_captcha_proof WHERE id=?",
                        Boolean.class,
                        secondProof.captchaProofId()));
        var oldChallengeError =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.verify(
                                        imageAnswer(secondChallenge.challengeId(), "123456"),
                                        request));
        assertEquals("captchaChallengeExpired", oldChallengeError.getReason());
        assertEquals(CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST, refreshed.action());
    }

    @Test
    void wrongAnswersLockAfterFiveFailuresAndExpiredChallengesCannotVerify() {
        var request = requestWithSession(new MockHttpSession());
        var challenge = start(CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST, false, request);
        for (int attempt = 1; attempt <= 5; attempt++) {
            var error =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    service.verify(
                                            imageAnswer(challenge.challengeId(), "000000"),
                                            request));
            assertEquals(
                    attempt == 5 ? "captchaChallengeLocked" : "captchaInvalid", error.getReason());
        }
        assertEquals(
                "LOCKED",
                jdbc.queryForObject(
                        "SELECT state FROM platform_captcha_challenge WHERE id=?",
                        String.class,
                        challenge.challengeId()));

        var expired = start(CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST, false, request);
        jdbc.update(
                "UPDATE platform_captcha_challenge SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",
                expired.challengeId());
        var expiredError =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.verify(
                                        imageAnswer(expired.challengeId(), "123456"), request));
        assertEquals("captchaChallengeExpired", expiredError.getReason());
    }

    @Test
    void changingTheActionPolicyExpiresTheChallengeBeforeProofIssuance() {
        var request = requestWithSession(new MockHttpSession());
        var challenge = start(CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST, false, request);
        policyMode.set(Mode.OFF);
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.verify(
                                        imageAnswer(challenge.challengeId(), "123456"), request));
        assertEquals("captchaChallengeExpired", error.getReason());
        assertEquals(
                "EXPIRED",
                jdbc.queryForObject(
                        "SELECT state FROM platform_captcha_challenge WHERE id=?",
                        String.class,
                        challenge.challengeId()));
    }

    @Test
    void localSliderAcceptsKeyboardSizedTrailAndRejectsNoMovement() {
        challengeType.set("SLIDER");
        var request = requestWithSession(new MockHttpSession());
        var challenge = start(CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST, false, request);
        var noMovement =
                new CaptchaProofService.VerifyRequest(
                        challenge.challengeId(),
                        null,
                        0.0,
                        20,
                        java.util.List.of(
                                new CaptchaProofService.PointerSample(0, 80, 0),
                                new CaptchaProofService.PointerSample(0, 80, 20)));
        var invalid =
                assertThrows(
                        ResponseStatusException.class, () -> service.verify(noMovement, request));
        assertEquals("captchaInvalid", invalid.getReason());
        var keyboardTrail =
                new CaptchaProofService.VerifyRequest(
                        challenge.challengeId(),
                        null,
                        157.0,
                        20,
                        java.util.List.of(
                                new CaptchaProofService.PointerSample(0, 80, 0),
                                new CaptchaProofService.PointerSample(157, 80, 20)));
        assertEquals(
                CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST,
                service.verify(keyboardTrail, request).action());
    }

    @Test
    void accessibleAudioUsesLocalWaveAndSameProofContract() {
        var request = requestWithSession(new MockHttpSession());
        var challenge = start(CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST, true, request);
        assertEquals("LOCAL", challenge.provider());
        assertEquals("AUDIO", challenge.type());
        assertTrue(challenge.accessibleAlternativeAvailable());
        assertTrue(challenge.audioDataUrl().startsWith("data:audio/wav;base64,"));
        assertEquals(
                CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST,
                service.verify(imageAnswer(challenge.challengeId(), "654321"), request).action());
    }

    @Test
    void riskRequirementUsesTheSameHashedSourceBucketAsPublicAuthenticationLimiter() {
        policyMode.set(Mode.RISK);
        var request = requestWithSession(new MockHttpSession());
        var sourceBucket = FixedWindowBuckets.key("login-source", crypto.sourceDigest(source));
        jdbc.update(
                "INSERT INTO platform_rate_bucket(bucket_key,window_start,attempts) VALUES(?,date_trunc('minute',CURRENT_TIMESTAMP),7)",
                sourceBucket);
        var requestFacts = facts(request);
        var belowThreshold =
                service.requirement(
                        CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST,
                        null,
                        null,
                        requestFacts);
        assertTrue(!belowThreshold.required());

        jdbc.update("UPDATE platform_rate_bucket SET attempts=8 WHERE bucket_key=?", sourceBucket);
        var atThreshold =
                service.requirement(
                        CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST,
                        null,
                        null,
                        requestFacts);
        assertTrue(atThreshold.required());
    }

    private CaptchaProofService.ChallengeResponse start(
            CaptchaProofPort.Action action, boolean accessible, MockHttpServletRequest request) {
        return transactions.execute(
                status ->
                        service.start(
                                new CaptchaProofService.ChallengeRequest(
                                        action, null, accessible, "zh-CN"),
                                request));
    }

    private CaptchaProofService.VerifyRequest imageAnswer(UUID challengeId, String answer) {
        return new CaptchaProofService.VerifyRequest(challengeId, answer, null, null, null);
    }

    private CaptchaProofPort.RequestFacts facts(MockHttpServletRequest request) {
        return CaptchaRequestFacts.from(request, new ClientAddressResolver(""), crypto, false);
    }

    private CaptchaSettingsService.Snapshot snapshot(Mode mode) {
        var actions = new EnumMap<CaptchaProofPort.Action, Mode>(CaptchaProofPort.Action.class);
        for (var action : CaptchaProofPort.Action.values()) actions.put(action, Mode.OFF);
        actions.put(CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST, mode);
        Map<String, Mode> serialized = new java.util.LinkedHashMap<>();
        actions.forEach((action, value) -> serialized.put(action.name(), value));
        var data =
                new CaptchaSettingsService.Data(
                        mode != Mode.OFF, "LOCAL", challengeType.get(), serialized);
        return new CaptchaSettingsService.Snapshot(
                1, UUID.fromString("11111111-1111-4111-8111-111111111111"), data);
    }

    private MockHttpServletRequest requestWithSession(MockHttpSession session) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(source);
        request.setSession(session);
        return request;
    }

    private String resource(String path) throws Exception {
        try (var input = CaptchaProofServiceDatabaseTest.class.getResourceAsStream(path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void createBaseTables() {
        jdbc.execute(
                """
                CREATE TABLE platform_login_challenge (
                    state varchar(32) NOT NULL,
                    CONSTRAINT platform_login_challenge_state_check
                        CHECK (state IN ('MFA_REQUIRED','ENROLLMENT_REQUIRED','RECOVERY_REQUIRED'))
                )
                """);
        jdbc.execute("CREATE TABLE platform_passkey_challenge(id uuid PRIMARY KEY)");
        jdbc.execute(
                """
                CREATE TABLE platform_rate_bucket (
                    bucket_key varchar(128) PRIMARY KEY,
                    window_start timestamptz NOT NULL,
                    attempts integer NOT NULL
                )
                """);
    }
}
