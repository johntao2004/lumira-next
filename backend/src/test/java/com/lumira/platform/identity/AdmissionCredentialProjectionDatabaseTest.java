package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AuthenticationCapabilityContributor;
import com.lumira.platform.common.GlobalAuthenticationPolicyPort;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.RoleAuthenticationPolicyPort;
import com.lumira.platform.identity.api.AuthenticationSession;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort.EffectivePolicy;
import com.lumira.platform.identity.api.PasskeyPolicyPort;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class AdmissionCredentialProjectionDatabaseTest {
    private static final UUID ACTOR = UUID.fromString("81c968d4-0526-4a56-bf38-7048b2bcce80");
    private static final UUID PRIMARY = UUID.fromString("27ec67a0-dce2-4c84-8469-d3318a13b789");
    private static final UUID FACTOR = UUID.fromString("4851ff44-d750-4d2c-90c3-aae61cb91cba");
    private static final UUID POLICY = UUID.fromString("3ee64c0b-dfde-4476-aa82-ab59d55e8845");
    private static final String PERMISSION = "platform:dictionaries:access";
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private JdbcClient counting;
    private CurrentAuthorityFilter filter;
    private AuthenticationPolicyService policies;
    private PasskeyPolicyPort passkeys;
    private GlobalAuthenticationPolicyPort global;
    private String schema;
    private final AtomicReference<EffectivePolicy> observed = new AtomicReference<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_admission_credentials_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        for (String table :
                List.of(
                        "platform_user",
                        "platform_authority",
                        "platform_role",
                        "platform_user_role",
                        "platform_role_authority",
                        "platform_permission_retired_authority",
                        "platform_passkey_credential",
                        "platform_second_factor_requirement",
                        "platform_authentication_policy_lock"))
            jdbc.execute(TestSchema.table(table));
        jdbc.execute(TestSchema.view("platform_effective_authority"));
        jdbc.update("INSERT INTO platform_authentication_policy_lock(id) VALUES(1)");
        jdbc.update(
                """
                INSERT INTO platform_user(id,username,display_name,password_hash,
                    primary_credential_revision,factor_credential_revision)
                VALUES(?,'alice','Alice','fixture',?,?)
                """,
                ACTOR,
                PRIMARY,
                FACTOR);
        jdbc.update(
                "INSERT INTO platform_authority(user_id,authority) VALUES(?,?)", ACTOR, PERMISSION);
        counting = spy(JdbcClient.create(source));
        global = mock(GlobalAuthenticationPolicyPort.class);
        when(global.current())
                .thenReturn(
                        new GlobalAuthenticationPolicyPort.Policy(
                                1, POLICY, Set.of(PrimaryMethod.PASSWORD), false));
        var roles = mock(RoleAuthenticationPolicyPort.class);
        when(roles.activeForUser(ACTOR)).thenReturn(List.of());
        passkeys = mock(PasskeyPolicyPort.class);
        when(passkeys.currentPolicy())
                .thenReturn(
                        new PasskeyPolicyPort.Policy(
                                false, false, false, "localhost", "Lumira", List.of(), 120));
        ObjectProvider<AuthenticationCapabilityContributor> contributors =
                mock(ObjectProvider.class);
        when(contributors.orderedStream()).thenAnswer(ignored -> Stream.empty());
        policies =
                spy(
                        new AuthenticationPolicyService(
                                counting,
                                global,
                                roles,
                                passkeys,
                                mock(PluginExtensionsPort.class),
                                contributors));
        doAnswer(
                        invocation -> {
                            var result = (EffectivePolicy) invocation.callRealMethod();
                            observed.set(result);
                            return result;
                        })
                .when(policies)
                .resolve(ACTOR);
        var preview = mock(RolePreviewContextService.class);
        when(preview.resolve(any(), eq("alice"), eq(ACTOR), any()))
                .thenReturn(
                        new RequestAuthorizationContext(
                                "alice",
                                ACTOR,
                                "fixture-session",
                                Set.of(PERMISSION),
                                Set.of(PERMISSION),
                                java.util.Map.of(),
                                Set.of(PERMISSION),
                                Set.of(PERMISSION),
                                java.util.Map.of(),
                                UUID.randomUUID().toString(),
                                null,
                                true,
                                null));
        filter =
                new CurrentAuthorityFilter(
                        counting,
                        policies,
                        new IdentityAuthenticationPolicyWriteLock(counting),
                        new TransactionTemplate(new DataSourceTransactionManager(source)),
                        preview);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void admittedRequestDoesNotReadTheSameCredentialTupleTwice() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request(session()),
                response,
                (request, reply) -> {
                    assertEquals(
                            Set.of(PERMISSION),
                            SecurityContextHolder.getContext()
                                    .getAuthentication()
                                    .getAuthorities()
                                    .stream()
                                    .map(value -> value.getAuthority())
                                    .collect(java.util.stream.Collectors.toSet()));
                });
        assertEquals(200, response.getStatus());
        EffectivePolicy admitted = observed.get();
        assertEquals(PRIMARY, admitted.primaryCredentialRevision());
        assertEquals(FACTOR, admitted.factorCredentialRevision());
        assertEquals(
                UUID.fromString("932a5c3f-06dc-57be-893e-608522cf59a7"),
                admitted.revision(),
                "Retain the pre-optimization policy revision for the same persisted credential tuple");
        verify(counting, never()).sql(contains("FROM platform_user u WHERE u.id=:id"));
        assertEquals(
                admitted, policies.resolve(ACTOR), "Standalone callers must keep the same policy");
        verify(counting).sql(contains("FROM platform_user u WHERE u.id=:id"));
        verify(passkeys, never()).currentPolicy();
    }

    @Test
    void aChangedCredentialStillRevokesThePreviouslyAdmittedSession() throws Exception {
        var session = session();
        var first = new MockHttpServletResponse();
        filter.doFilter(request(session), first, (request, response) -> {});
        assertEquals(200, first.getStatus());
        jdbc.update(
                "UPDATE platform_user SET primary_credential_revision=? WHERE id=?",
                UUID.randomUUID(),
                ACTOR);
        var rejected = new MockHttpServletResponse();
        filter.doFilter(
                request(session),
                rejected,
                (request, response) -> fail("Revoked session reached the handler"));
        assertEquals(401, rejected.getStatus());
        assertTrue(session.isInvalid());
    }

    @Test
    void passkeyPolicyCheckedOnlyWhenPasskeyIsPresent() {
        when(global.current())
                .thenReturn(
                        new GlobalAuthenticationPolicyPort.Policy(
                                1,
                                POLICY,
                                Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.PASSKEY),
                                false));
        policies.resolve(ACTOR);
        verify(passkeys, never()).currentPolicy();

        when(passkeys.currentPolicy())
                .thenReturn(
                        new PasskeyPolicyPort.Policy(
                                true, true, false, "localhost", "Lumira", List.of(), 120));
        jdbc.update(
                """
                INSERT INTO platform_passkey_credential(
                    id,user_id,user_handle,credential_id,public_key_cose,
                    sign_count,transports,backup_eligible,backup_state,label)
                VALUES(?,?,?,?,?,0,'[]',false,false,'fixture')
                """,
                UUID.randomUUID(),
                ACTOR,
                new byte[16],
                new byte[16],
                new byte[16]);

        EffectivePolicy resolved = policies.resolve(ACTOR);
        verify(passkeys, times(1)).currentPolicy();
        assertTrue(resolved.usableMethods().contains(PrimaryMethod.PASSKEY));
    }

    private MockHttpServletRequest request(MockHttpSession session) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "alice", null, List.of()));
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.setSession(session);
        return request;
    }

    private MockHttpSession session() {
        var session = new MockHttpSession();
        session.setAttribute(AuthenticationSession.PRIMARY_METHOD, "PASSWORD");
        session.setAttribute(AuthenticationSession.PRIMARY_CREDENTIAL_REVISION, PRIMARY.toString());
        session.setAttribute(AuthenticationSession.FACTOR_CREDENTIAL_REVISION, FACTOR.toString());
        return session;
    }
}
