package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.LoginResponse;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import com.lumira.platform.identity.api.MfaRecoveryTicketPort;
import com.lumira.platform.identity.api.SecondFactorProvider;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import com.lumira.platform.identity.api.TemporaryPasswordResetPort;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs forced password changes against an isolated PostgreSQL schema, never local business data.
 */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class TemporaryPasswordResetDatabaseTest {
    private static final String ADMIN = "reset-admin";
    private static final String USERNAME = "reset-target";
    private static final UUID ADMIN_ID = UUID.fromString("6cbf7c9f-55bd-4c7e-80dd-3a20a8e59ac6");
    private static final UUID USER_ID = UUID.fromString("1a2e80f4-3a87-422a-a11e-3eb953142d61");
    private static final String INITIAL_PASSWORD = "InitialPassword!8172";
    private static final String REPLACEMENT_PASSWORD = "W!m8Q$zR4#vP7k2X";
    private static final String RESET_PERMISSION =
            PlatformOperation.USER_RESET_PASSWORD.permission();
    private static final String USERS_PAGE_ACCESS = PlatformPageId.USERS.accessPermission();
    private static final long INITIAL_VERSION = 7;
    private static final Set<PrimaryMethod> METHODS =
            Set.of(
                    PrimaryMethod.PASSWORD,
                    PrimaryMethod.PASSKEY,
                    PrimaryMethod.EMAIL_OTP,
                    PrimaryMethod.SMS_OTP,
                    PrimaryMethod.WECHAT);

    private JdbcTemplate adminJdbc;
    private JdbcTemplate jdbc;
    private JdbcClient client;
    private TransactionTemplate transaction;
    private String schema;
    private BCryptPasswordEncoder encoder;
    private SecurityPolicyPort.Policy passwordPolicy;
    private AuditPort audit;
    private PasswordCredentialProof proofs;
    private TemporaryPasswordResetService resets;
    private SecondFactorLoginService login;
    private ProviderManager passwordProvider;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String dbUser = System.getenv("LUMIRA_TEST_DB_USER");
        String dbPassword = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        adminJdbc = new JdbcTemplate(new DriverManagerDataSource(url, dbUser, dbPassword));
        schema = "qa_temporary_password_" + UUID.randomUUID().toString().replace("-", "");
        adminJdbc.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        dbUser,
                        dbPassword);
        jdbc = new JdbcTemplate(dataSource);
        client = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        createSchema();

        encoder = new BCryptPasswordEncoder(4);
        passwordPolicy =
                new SecurityPolicyPort.Policy(
                        12, true, true, true, false, 30, 120, 10, 60, 600, 60, 10, 10, 5);
        UUID factorRevision = UUID.fromString("633f1b77-1310-4eaa-bcbf-98da86a3b5be");
        jdbc.update(
                "INSERT INTO platform_user(id,username,password_hash,enabled,primary_credential_revision,factor_credential_revision,version) VALUES(?,?,?,true,?,?,?)",
                ADMIN_ID,
                ADMIN,
                encoder.encode("AdminPassword!9182"),
                UUID.randomUUID(),
                factorRevision,
                1);
        jdbc.update(
                "INSERT INTO platform_user(id,username,password_hash,enabled,primary_credential_revision,factor_credential_revision,version) VALUES(?,?,?,true,?,?,?)",
                USER_ID,
                USERNAME,
                encoder.encode(INITIAL_PASSWORD),
                UUID.randomUUID(),
                factorRevision,
                INITIAL_VERSION);
        jdbc.update(
                "INSERT INTO platform_authority(user_id,authority) VALUES(?,?)",
                ADMIN_ID,
                RESET_PERMISSION);
        jdbc.update(
                "INSERT INTO platform_effective_authority(user_id,authority) VALUES(?,?)",
                ADMIN_ID,
                RESET_PERMISSION);
        jdbc.update(
                "INSERT INTO platform_effective_authority(user_id,authority) VALUES(?,?)",
                ADMIN_ID,
                USERS_PAGE_ACCESS);
        jdbc.update(
                "INSERT INTO platform_visible_user(actor,permission,target_user_id) VALUES(?,?,?)",
                ADMIN,
                RESET_PERMISSION,
                USER_ID);
        jdbc.update(
                "INSERT INTO platform_visible_user(actor,permission,target_user_id) VALUES(?,?,?)",
                ADMIN,
                RESET_PERMISSION,
                ADMIN_ID);
        jdbc.update(
                "INSERT INTO platform_visible_user(actor,permission,target_user_id) VALUES(?,?,?)",
                ADMIN,
                USERS_PAGE_ACCESS,
                USER_ID);
        jdbc.update(
                "INSERT INTO platform_visible_user(actor,permission,target_user_id) VALUES(?,?,?)",
                ADMIN,
                USERS_PAGE_ACCESS,
                ADMIN_ID);
        jdbc.update(
                "INSERT INTO platform_second_factor_requirement(user_id,provider_id,requirement_version,self_enabled,recovery_pending) VALUES(?,'mfa',?,true,false)",
                USER_ID,
                UUID.randomUUID());

        var administration = new AdministrationGuard(client);
        var policyLock = new IdentityAuthenticationPolicyWriteLock(client);
        var policies = effectivePolicies(factorRevision);
        var security = (SecurityPolicyPort) () -> passwordPolicy;
        var buckets = new FixedWindowBuckets(client);
        var limiter = new PasswordChangeLimiter(buckets);
        audit = mock(AuditPort.class);
        resets =
                new TemporaryPasswordResetService(
                        client,
                        encoder,
                        administration,
                        policyLock,
                        policies,
                        security,
                        audit,
                        limiter,
                        new DataSourceTransactionManager(dataSource));

        proofs = new PasswordCredentialProof();
        var provider = factorProvider();
        PluginExtensionsPort plugins =
                new PluginExtensionsPort() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T, R> List<R> extensions(
                            Class<T> type, java.util.function.Function<T, R> call) {
                        if (type != SecondFactorProvider.class) return List.of();
                        R result = call.apply((T) provider);
                        return result == null ? List.of() : List.of(result);
                    }
                };
        UserDetailsService users =
                username -> {
                    var user = user(username);
                    return new PasswordProofUserDetails(
                            user.username(),
                            user.passwordHash(),
                            user.enabled(),
                            user.credentialRevision(),
                            List.of(new SimpleGrantedAuthority("platform:users:read")));
                };
        var dao =
                new org.springframework.security.authentication.dao.DaoAuthenticationProvider(
                        users);
        dao.setPasswordEncoder(encoder);
        passwordProvider =
                new ProviderManager(List.of(new PasswordProofAuthenticationProvider(dao, proofs)));
        var maintenance = mock(MaintenancePolicyPort.class);
        when(maintenance.canLogin(any(String.class))).thenReturn(true);
        var captcha = mock(CaptchaProofAccess.class);
        when(captcha.begin(
                        eq(CaptchaProofPort.Action.POST_PRIMARY_AUTH),
                        eq(USER_ID),
                        isNull(),
                        any()))
                .thenReturn(
                        new CaptchaProofAccess.Gate(
                                mock(CaptchaProofPort.class),
                                CaptchaProofPort.Action.POST_PRIMARY_AUTH,
                                new CaptchaProofPort.Requirement(
                                        false, "test-captcha-policy", null)));
        login =
                new SecondFactorLoginService(
                        client,
                        transaction,
                        plugins,
                        users,
                        maintenance,
                        mock(LoginAudit.class),
                        mock(ClientAddressResolver.class),
                        buckets,
                        policyLock,
                        policies,
                        mock(AccountSecurityPort.class),
                        proofs,
                        captcha,
                        mock(MfaRecoveryTicketPort.class),
                        resets);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (adminJdbc != null && schema != null)
            adminJdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void resetForcesUnauthenticatedChangeAndTheNextPasswordLoginStillRequiresMfa() {
        var reset = reset(USER_ID, ADMIN);
        UserRow resetUser = user(USERNAME);
        assertTrue(encoder.matches(reset.temporaryPassword(), resetUser.passwordHash()));
        assertFalse(encoder.matches(INITIAL_PASSWORD, resetUser.passwordHash()));
        assertThrows(
                org.springframework.security.authentication.BadCredentialsException.class,
                () ->
                        passwordProvider.authenticate(
                                UsernamePasswordAuthenticationToken.unauthenticated(
                                        USERNAME, INITIAL_PASSWORD)));
        assertTrue(passwordPolicy.passwordValid(reset.temporaryPassword()));
        assertTrue(Duration.between(java.time.Instant.now(), reset.expiresAt()).toMinutes() <= 30);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));

        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        LoginResponse required = loginAs(reset.temporaryPassword(), request);
        assertEquals("PASSWORD_CHANGE_REQUIRED", required.state());
        assertFalse(required.authenticated());
        assertFalse(required.secondFactorRequired());
        assertEquals("/login/password-change", required.nextPath());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(
                required.challengeId(),
                UUID.fromString(
                        (String)
                                request.getSession()
                                        .getAttribute(SecondFactorLoginService.PENDING)));
        assertEquals(required, login.pending(request));

        assertFailure(
                "passwordConfirmationMismatch",
                () ->
                        resets.changePassword(
                                required.challengeId(),
                                REPLACEMENT_PASSWORD,
                                "different",
                                request));
        assertFailure(
                "passwordPolicy",
                () -> resets.changePassword(required.challengeId(), "short", "short", request));
        assertFailure(
                "newPasswordMustDiffer",
                () ->
                        resets.changePassword(
                                required.challengeId(),
                                reset.temporaryPassword(),
                                reset.temporaryPassword(),
                                request));

        String pendingSessionDigest =
                FixedWindowBuckets.key("mfa-session", request.getSession().getId()).substring(12);
        jdbc.update(
                "INSERT INTO spring_session(primary_id,session_id,principal_name,expiry_time) VALUES(?,?,?,?)",
                UUID.randomUUID().toString(),
                request.getSession().getId(),
                USERNAME,
                System.currentTimeMillis() + 60_000);
        resets.changePassword(
                required.challengeId(), REPLACEMENT_PASSWORD, REPLACEMENT_PASSWORD, request);

        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_login_challenge", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_password_change_requirement",
                        Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(encoder.matches(REPLACEMENT_PASSWORD, user(USERNAME).passwordHash()));
        assertFalse(encoder.matches(reset.temporaryPassword(), user(USERNAME).passwordHash()));
        assertTrue(
                new PasswordChangeLimiter(new FixedWindowBuckets(client))
                        .allowPendingSession(pendingSessionDigest));

        LoginResponse mfa =
                loginAs(
                        REPLACEMENT_PASSWORD,
                        new MockHttpServletRequest("POST", "/api/auth/login"));
        assertEquals("MFA_REQUIRED", mfa.state());
        assertTrue(mfa.secondFactorRequired());
        assertFalse(mfa.authenticated());
        verify(audit).append(ADMIN, "user.passwordReset", USER_ID);
        verify(audit).append(USERNAME, "account.forcedPasswordChanged", USER_ID);
    }

    @Test
    void resetRequiresPageAndActionGrantsAndBothTargetScopes() {
        jdbc.update(
                "DELETE FROM platform_effective_authority WHERE user_id=? AND authority=?",
                ADMIN_ID,
                USERS_PAGE_ACCESS);
        assertFailure("forbidden", () -> reset(USER_ID, ADMIN));
        assertEquals(INITIAL_VERSION, version(USER_ID));
        jdbc.update(
                "INSERT INTO platform_effective_authority(user_id,authority) VALUES(?,?)",
                ADMIN_ID,
                USERS_PAGE_ACCESS);
        jdbc.update(
                "DELETE FROM platform_effective_authority WHERE user_id=? AND authority=?",
                ADMIN_ID,
                RESET_PERMISSION);
        assertFailure("forbidden", () -> reset(USER_ID, ADMIN));
        assertEquals(INITIAL_VERSION, version(USER_ID));
        jdbc.update(
                "INSERT INTO platform_effective_authority(user_id,authority) VALUES(?,?)",
                ADMIN_ID,
                RESET_PERMISSION);
        jdbc.update(
                "DELETE FROM platform_visible_user WHERE actor=? AND permission=? AND target_user_id=?",
                ADMIN,
                RESET_PERMISSION,
                USER_ID);
        assertFailure("forbidden", () -> reset(USER_ID, ADMIN));
        assertEquals(INITIAL_VERSION, version(USER_ID));
        jdbc.update(
                "INSERT INTO platform_visible_user(actor,permission,target_user_id) VALUES(?,?,?)",
                ADMIN,
                RESET_PERMISSION,
                USER_ID);
        jdbc.update(
                "DELETE FROM platform_visible_user WHERE actor=? AND permission=? AND target_user_id=?",
                ADMIN,
                USERS_PAGE_ACCESS,
                USER_ID);
        assertFailure("forbidden", () -> reset(USER_ID, ADMIN));
        assertEquals(INITIAL_VERSION, version(USER_ID));
    }

    @Test
    void scopedResetGrantDoesNotRequireGlobalActionGrant() {
        jdbc.update(
                "DELETE FROM platform_authority WHERE user_id=? AND authority=?",
                ADMIN_ID,
                RESET_PERMISSION);

        var result = reset(USER_ID, ADMIN);

        assertTrue(encoder.matches(result.temporaryPassword(), user(USERNAME).passwordHash()));
        assertEquals(INITIAL_VERSION + 1, version(USER_ID));
    }

    @Test
    void versionSelfAndLastRecoveryAdministratorRestrictionsAreEnforced() {
        assertFailure("staleRecord", () -> reset(USER_ID, ADMIN, INITIAL_VERSION - 1));
        assertFailure("protectedAccount", () -> reset(ADMIN_ID, ADMIN, 1));
        jdbc.update("INSERT INTO qa_last_recovery_administrator(user_id) VALUES(?)", USER_ID);
        assertFailure("protectedAccount", () -> reset(USER_ID, ADMIN));
        assertEquals(INITIAL_VERSION, version(USER_ID));
    }

    @Test
    void expiredTemporaryPasswordCannotOpenThePasswordLoginStage() {
        var reset = reset(USER_ID, ADMIN);
        jdbc.update(
                "UPDATE platform_password_change_requirement SET created_at=now()-interval '61 minutes',expires_at=now()-interval '31 minutes' WHERE user_id=?",
                USER_ID);
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        assertFailure(
                "temporaryPasswordExpired", () -> loginAs(reset.temporaryPassword(), request));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_login_challenge", Integer.class));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void aSecondResetInvalidatesTheOldPendingChallengeAndTemporaryPassword() {
        var first = reset(USER_ID, ADMIN);
        var oldSession = new MockHttpServletRequest("POST", "/api/auth/login");
        LoginResponse challenge = loginAs(first.temporaryPassword(), oldSession);
        var second = reset(USER_ID, ADMIN, version(USER_ID));

        assertEquals("EXPIRED", login.pending(oldSession).state());
        assertFailure(
                "secondFactorExpired",
                () ->
                        resets.changePassword(
                                challenge.challengeId(),
                                REPLACEMENT_PASSWORD,
                                REPLACEMENT_PASSWORD,
                                oldSession));
        assertFalse(encoder.matches(first.temporaryPassword(), user(USERNAME).passwordHash()));
        assertTrue(encoder.matches(second.temporaryPassword(), user(USERNAME).passwordHash()));
    }

    @Test
    void onlyOneConcurrentLoginCanClaimATemporaryPassword() throws Exception {
        var reset = reset(USER_ID, ADMIN);
        var firstRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        var secondRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        var firstAuth = passwordAuthentication(reset.temporaryPassword());
        var secondAuth = passwordAuthentication(reset.temporaryPassword());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> attempt(firstAuth, firstRequest));
            var second = pool.submit(() -> attempt(secondAuth, secondRequest));
            List<String> results =
                    List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("PASSWORD_CHANGE_REQUIRED"::equals).count());
            assertEquals(1, results.stream().filter("temporaryPasswordUsed"::equals).count());
        }
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_login_challenge", Integer.class));
    }

    @Test
    void pendingPasswordAttemptsAreCommittedAndSessionBound() {
        PasswordChangeLimiter limiter = new PasswordChangeLimiter(new FixedWindowBuckets(client));
        String first = "a".repeat(64);
        String second = "b".repeat(64);
        for (int index = 0; index < PasswordChangeLimiter.ATTEMPTS_PER_MINUTE; index++)
            assertTrue(limiter.allowPendingSession(first));
        assertFalse(limiter.allowPendingSession(first));
        assertTrue(limiter.allowPendingSession(second));
        assertFalse(limiter.allowPendingSession("invalid"));
    }

    private LoginResponse loginAs(String password, MockHttpServletRequest request) {
        return login.primary(
                passwordAuthentication(password), request, new MockHttpServletResponse());
    }

    private org.springframework.security.core.Authentication passwordAuthentication(
            String password) {
        return passwordProvider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(USERNAME, password));
    }

    private String attempt(
            org.springframework.security.core.Authentication authentication,
            MockHttpServletRequest request) {
        try {
            return login.primary(authentication, request, new MockHttpServletResponse()).state();
        } catch (ResponseStatusException error) {
            return error.getReason();
        }
    }

    private TemporaryPasswordResetPort.ResetResult reset(UUID userId, String actor) {
        return reset(userId, actor, version(userId));
    }

    private TemporaryPasswordResetPort.ResetResult reset(
            UUID userId, String actor, long expectedVersion) {
        return resets.reset(userId, expectedVersion, actor);
    }

    private long version(UUID userId) {
        return jdbc.queryForObject(
                "SELECT version FROM platform_user WHERE id=?", Long.class, userId);
    }

    private UserRow user(String username) {
        return jdbc.queryForObject(
                "SELECT username,password_hash,enabled,primary_credential_revision FROM platform_user WHERE username=?",
                (rs, row) ->
                        new UserRow(
                                rs.getString("username"),
                                rs.getString("password_hash"),
                                rs.getBoolean("enabled"),
                                rs.getObject("primary_credential_revision", UUID.class)),
                username);
    }

    private static void assertFailure(String code, Runnable operation) {
        ResponseStatusException failure =
                assertThrows(ResponseStatusException.class, operation::run);
        assertEquals(code, failure.getReason());
    }

    private EffectiveAuthenticationPolicyPort effectivePolicies(UUID factorRevision) {
        return new EffectiveAuthenticationPolicyPort() {
            @Override
            public EffectivePolicy resolve(UUID userId) {
                UUID primaryRevision =
                        jdbc.queryForObject(
                                "SELECT primary_credential_revision FROM platform_user WHERE id=?",
                                UUID.class,
                                userId);
                UUID policyRevision =
                        UUID.nameUUIDFromBytes(
                                ("password-policy:" + primaryRevision)
                                        .getBytes(StandardCharsets.UTF_8));
                return new EffectivePolicy(
                        policyRevision,
                        METHODS,
                        METHODS,
                        true,
                        List.of(),
                        false,
                        primaryRevision,
                        factorRevision);
            }

            @Override
            public boolean requiredByPolicyExceptSelf(UUID ignored) {
                return true;
            }

            @Override
            public boolean secondFactorAvailable() {
                return true;
            }
        };
    }

    private static SecondFactorProvider factorProvider() {
        return new SecondFactorProvider() {
            @Override
            public String id() {
                return "mfa";
            }

            @Override
            public boolean verify(UUID ignored, String code) {
                return false;
            }

            @Override
            public AccountStatus status(UUID ignored) {
                return new AccountStatus(true, 5);
            }
        };
    }

    private void createSchema() {
        jdbc.execute(
                "CREATE TABLE platform_authentication_policy_lock(id integer PRIMARY KEY CHECK(id=1))");
        jdbc.update("INSERT INTO platform_authentication_policy_lock VALUES(1)");
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY,username text UNIQUE NOT NULL,password_hash text NOT NULL,enabled boolean NOT NULL,deleted_at timestamptz,primary_credential_revision uuid NOT NULL,factor_credential_revision uuid NOT NULL,version bigint NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_authority(user_id uuid NOT NULL,authority text NOT NULL,PRIMARY KEY(user_id,authority))");
        jdbc.execute(
                "CREATE TABLE platform_effective_authority(user_id uuid NOT NULL,authority text NOT NULL,PRIMARY KEY(user_id,authority))");
        jdbc.execute(
                "CREATE TABLE platform_role(id uuid PRIMARY KEY,enabled boolean NOT NULL,data_scope text NOT NULL)");
        jdbc.execute("CREATE TABLE platform_user_role(user_id uuid,role_id uuid)");
        jdbc.execute("CREATE TABLE platform_role_authority(role_id uuid,authority text)");
        jdbc.execute(
                "CREATE TABLE platform_visible_user(actor text NOT NULL,permission text NOT NULL,target_user_id uuid NOT NULL,PRIMARY KEY(actor,permission,target_user_id))");
        jdbc.execute(
                "CREATE FUNCTION platform_visible_users(actor text, permission text) RETURNS TABLE(user_id uuid) LANGUAGE sql AS $$ SELECT target_user_id FROM platform_visible_user WHERE platform_visible_user.actor=$1 AND platform_visible_user.permission=$2 $$");
        jdbc.execute("CREATE TABLE qa_last_recovery_administrator(user_id uuid PRIMARY KEY)");
        jdbc.execute(
                "CREATE FUNCTION platform_is_last_recovery_administrator(uuid) RETURNS boolean LANGUAGE sql AS $$ SELECT EXISTS(SELECT 1 FROM qa_last_recovery_administrator WHERE user_id=$1) $$");
        jdbc.execute(
                "CREATE TABLE platform_login_challenge(id uuid PRIMARY KEY,user_id uuid NOT NULL,provider_id text NOT NULL,session_hash char(64) NOT NULL,requirement_version uuid,primary_method varchar(16) NOT NULL,state varchar(32) NOT NULL CHECK(state IN ('MFA_REQUIRED','ENROLLMENT_REQUIRED','RECOVERY_REQUIRED','CAPTCHA_REQUIRED','PASSWORD_CHANGE_REQUIRED')),policy_revision uuid NOT NULL,captcha_policy_revision varchar(128),captcha_satisfied boolean NOT NULL DEFAULT false,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,expires_at timestamptz NOT NULL,attempts integer NOT NULL DEFAULT 0)");
        jdbc.execute(
                "CREATE TABLE platform_password_change_requirement(user_id uuid PRIMARY KEY REFERENCES platform_user(id) ON DELETE CASCADE,primary_credential_revision uuid NOT NULL,expires_at timestamptz NOT NULL,claimed_at timestamptz,issued_by uuid NOT NULL REFERENCES platform_user(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,CHECK(expires_at>created_at),CHECK(claimed_at IS NULL OR claimed_at>=created_at))");
        jdbc.execute(
                "CREATE TABLE spring_session(primary_id char(36) PRIMARY KEY,session_id char(36) NOT NULL UNIQUE,principal_name varchar(100) NOT NULL,expiry_time bigint NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_rate_bucket(bucket_key text PRIMARY KEY,window_start timestamptz NOT NULL,attempts integer NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_second_factor_requirement(user_id uuid PRIMARY KEY,provider_id text NOT NULL,requirement_version uuid NOT NULL,self_enabled boolean NOT NULL,recovery_pending boolean NOT NULL)");
    }

    private record UserRow(
            String username, String passwordHash, boolean enabled, UUID credentialRevision) {}
}
