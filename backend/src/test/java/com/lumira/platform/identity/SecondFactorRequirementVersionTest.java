package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import com.lumira.platform.identity.api.MfaRecoveryTicketPort;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class SecondFactorRequirementVersionTest {
    private static final String PENDING = "lumira.login.challenge";
    private static final UUID USER_ID = UUID.fromString("3d30d39f-f126-42aa-8990-01f58fcefc13");
    private static final UUID PRIMARY_REVISION =
            UUID.fromString("a1e2118c-137d-4b07-b11f-c221e1957134");

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private TransactionTemplate tx;
    private FixedWindowBuckets buckets;
    private PluginExtensionsPort plugins;
    private UserDetailsService users;
    private MaintenancePolicyPort maintenance;
    private ClientAddressResolver addresses;
    private AuthenticationPolicyWriteLock policyLock;
    private EffectiveAuthenticationPolicyPort policies;
    private PasswordCredentialProof passwordProofs;
    private CaptchaProofAccess captcha;
    private SecondFactorLoginService service;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_mfa_version_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute(
                """
                CREATE TABLE platform_user (
                    id uuid PRIMARY KEY, username text NOT NULL, enabled boolean NOT NULL,
                    deleted_at timestamptz, password_hash text,
                    primary_credential_revision uuid NOT NULL
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_second_factor_requirement (
                    user_id uuid PRIMARY KEY, provider_id text NOT NULL, requirement_version uuid NOT NULL,
                    self_enabled boolean NOT NULL DEFAULT true, recovery_pending boolean NOT NULL DEFAULT false
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_login_challenge (
                    id uuid PRIMARY KEY, user_id uuid NOT NULL, provider_id text NOT NULL,
                    session_hash char(64) NOT NULL, requirement_version uuid,
                    primary_method varchar(16) NOT NULL DEFAULT 'PASSWORD',
                    state varchar(32) NOT NULL DEFAULT 'MFA_REQUIRED', policy_revision uuid NOT NULL,
                    captcha_policy_revision varchar(128), captcha_satisfied boolean NOT NULL DEFAULT false,
                    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    expires_at timestamptz NOT NULL, attempts integer NOT NULL DEFAULT 0
                )
                """);
        jdbc.update(
                "INSERT INTO platform_user(id,username,enabled,password_hash,primary_credential_revision) VALUES (?,?,true,'unused',?)",
                USER_ID,
                "alice",
                PRIMARY_REVISION);
        buckets = mock(FixedWindowBuckets.class);
        plugins = mock(PluginExtensionsPort.class);
        users = mock(UserDetailsService.class);
        maintenance = mock(MaintenancePolicyPort.class);
        addresses = mock(ClientAddressResolver.class);
        policyLock = mock(AuthenticationPolicyWriteLock.class);
        policies = mock(EffectiveAuthenticationPolicyPort.class);
        passwordProofs = new PasswordCredentialProof();
        captcha = mock(CaptchaProofAccess.class);
        when(policies.resolve(USER_ID)).thenReturn(policy(UUID.randomUUID(), false));
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
        service =
                new SecondFactorLoginService(
                        JdbcClient.create(dataSource),
                        tx,
                        plugins,
                        users,
                        maintenance,
                        mock(LoginAudit.class),
                        addresses,
                        buckets,
                        policyLock,
                        policies,
                        mock(AccountSecurityPort.class),
                        passwordProofs,
                        captcha,
                        mock(MfaRecoveryTicketPort.class));
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void stalePreauthenticationChallengeIsDeletedAndCannotAuthenticate() {
        UUID challenge = UUID.randomUUID();
        UUID oldPolicyRevision = UUID.randomUUID();
        UUID newPolicyRevision = UUID.randomUUID();
        when(policies.resolve(USER_ID)).thenReturn(policy(newPolicyRevision, true));
        jdbc.update(
                "INSERT INTO platform_second_factor_requirement(user_id,provider_id,requirement_version) VALUES (?, 'mfa', ?)",
                USER_ID,
                UUID.randomUUID());
        var request = new MockHttpServletRequest("POST", "/api/auth/second-factor/confirm");
        var session = request.getSession();
        session.setAttribute(PENDING, challenge.toString());
        String sessionHash = FixedWindowBuckets.key("mfa-session", session.getId()).substring(12);
        jdbc.update(
                "INSERT INTO platform_login_challenge(id,user_id,provider_id,session_hash,requirement_version,policy_revision,expires_at) VALUES (?,?, 'mfa', ?, ?, ?, now()+interval '5 minutes')",
                challenge,
                USER_ID,
                sessionHash,
                UUID.randomUUID(),
                oldPolicyRevision);

        assertEquals("EXPIRED", service.pending(request).state());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_login_challenge", Integer.class));
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.confirm(
                                        challenge,
                                        "123456",
                                        request,
                                        new MockHttpServletResponse()));

        assertEquals("secondFactorExpired", error.getReason());
        assertEquals("NONE", service.pending(request).state());
        verifyNoInteractions(plugins);
    }

    @Test
    void primaryLoginUsesThePersistedSoftDeleteColumn() {
        when(maintenance.canLogin("alice")).thenReturn(true);
        when(users.loadUserByUsername("alice"))
                .thenReturn(
                        User.withUsername("alice")
                                .password("unused")
                                .authorities("platform:users:read")
                                .build());
        when(addresses.resolve(org.mockito.ArgumentMatchers.any())).thenReturn("192.0.2.1");
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        var response = new MockHttpServletResponse();

        var principal =
                new PasswordProofUserDetails(
                        "alice",
                        "unused",
                        true,
                        PRIMARY_REVISION,
                        List.of(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("platform:users:read")));
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities());
        authentication.setDetails(passwordProofs.capture("unused", PRIMARY_REVISION));
        var result = service.primary(authentication, request, response);

        assertTrue(result.authenticated());
        assertFalse(result.secondFactorRequired());
        assertEquals(200, response.getStatus());
        assertEquals(null, request.getSession().getAttribute(PENDING));
        var saved =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        assertTrue(
                saved.getPrincipal()
                        instanceof org.springframework.security.core.userdetails.UserDetails);
        assertEquals(
                null,
                ((org.springframework.security.core.userdetails.UserDetails) saved.getPrincipal())
                        .getPassword());
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void noPendingLoginStateDoesNotCreateSessionOrAuthenticate() {
        var request = new MockHttpServletRequest("GET", "/api/auth/login/state");
        var state = service.pending(request);

        assertEquals("NONE", state.state());
        assertFalse(state.authenticated());
        assertFalse(state.secondFactorRequired());
        assertEquals(null, request.getSession(false));
    }

    @Test
    void cancelOnlyConsumesTheExactChallengeNamedByCurrentSession() {
        UUID currentChallenge = UUID.randomUUID();
        UUID unrelatedChallenge = UUID.randomUUID();
        var request = new MockHttpServletRequest("POST", "/api/auth/login/cancel");
        var session = request.getSession();
        session.setAttribute(PENDING, currentChallenge.toString());
        String sessionHash = FixedWindowBuckets.key("mfa-session", session.getId()).substring(12);
        jdbc.update(
                "INSERT INTO platform_login_challenge(id,user_id,provider_id,session_hash,policy_revision,expires_at) VALUES (?,?,'mfa',?,?,now()+interval '5 minutes')",
                currentChallenge,
                USER_ID,
                sessionHash,
                UUID.randomUUID());

        var mismatch =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.cancel(unrelatedChallenge, request));

        assertEquals("secondFactorExpired", mismatch.getReason());
        assertEquals(currentChallenge.toString(), session.getAttribute(PENDING));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_login_challenge", Integer.class));

        service.cancel(currentChallenge, request);

        assertEquals(null, session.getAttribute(PENDING));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_login_challenge", Integer.class));
    }

    @Test
    void nonPasswordPrimaryMethodMustCarryTheEpochObservedByItsProof() {
        when(maintenance.canLogin("alice")).thenReturn(true);
        when(policies.resolve(USER_ID))
                .thenReturn(
                        new EffectiveAuthenticationPolicyPort.EffectivePolicy(
                                UUID.randomUUID(),
                                Set.of(PrimaryMethod.EMAIL_OTP),
                                Set.of(PrimaryMethod.EMAIL_OTP),
                                false,
                                List.of(),
                                false,
                                PRIMARY_REVISION,
                                UUID.randomUUID()));
        var request = new MockHttpServletRequest("POST", "/api/auth/login-code/complete");
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.primary(
                                        authentication,
                                        PrimaryMethod.EMAIL_OTP,
                                        request,
                                        new MockHttpServletResponse()));

        assertEquals("authenticationProofExpired", error.getReason());
    }

    @Test
    void passwordProofObservedBeforeCredentialRotationCannotSignInWithNewEpoch() {
        when(maintenance.canLogin("alice")).thenReturn(true);
        UUID rotatedRevision = UUID.randomUUID();
        when(policies.resolve(USER_ID))
                .thenReturn(
                        new EffectiveAuthenticationPolicyPort.EffectivePolicy(
                                UUID.randomUUID(),
                                Set.of(PrimaryMethod.PASSWORD),
                                Set.of(PrimaryMethod.PASSWORD),
                                false,
                                List.of(),
                                false,
                                rotatedRevision,
                                UUID.randomUUID()));
        var principal =
                new PasswordProofUserDetails("alice", null, true, PRIMARY_REVISION, List.of());
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        authentication.setDetails(passwordProofs.capture("unused", PRIMARY_REVISION));

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.primary(
                                        authentication,
                                        new MockHttpServletRequest("POST", "/api/auth/login"),
                                        new MockHttpServletResponse()));

        assertEquals("authenticationProofExpired", error.getReason());
    }

    private static EffectiveAuthenticationPolicyPort.EffectivePolicy policy(
            UUID revision, boolean mfaRequired) {
        return new EffectiveAuthenticationPolicyPort.EffectivePolicy(
                revision,
                Set.of(PrimaryMethod.PASSWORD),
                Set.of(PrimaryMethod.PASSWORD),
                mfaRequired,
                List.of(),
                false,
                PRIMARY_REVISION,
                UUID.randomUUID());
    }
}
