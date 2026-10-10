package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.GlobalAuthenticationPolicyPort;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.RoleAuthenticationPolicyPort;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import com.lumira.platform.identity.api.AuthenticationReachabilityPort;
import com.lumira.platform.identity.api.SecondFactorProvider;
import com.lumira.platform.roles.RoleAuthenticationPolicyRepository;
import com.lumira.platform.roles.RoleAuthenticationPolicyService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class SecondFactorPolicyReadinessDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_mfa_policy_ready_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                """
                CREATE TABLE platform_global_authentication_policy(
                    id smallint PRIMARY KEY,
                    version bigint NOT NULL,
                    revision uuid NOT NULL,
                    enabled_methods text[] NOT NULL,
                    mfa_required boolean NOT NULL,
                    updated_by text,
                    change_reason text,
                    updated_at timestamptz NOT NULL DEFAULT now())
                """);
        jdbc.execute(
                "CREATE TABLE platform_role(id uuid PRIMARY KEY, version bigint NOT NULL, enabled boolean NOT NULL DEFAULT true)");
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY, enabled boolean NOT NULL, deleted_at timestamptz)");
        jdbc.execute(
                "CREATE TABLE platform_user_role(user_id uuid NOT NULL, role_id uuid NOT NULL)");
        jdbc.execute(
                """
                CREATE TABLE platform_role_authentication_policy(
                    role_id uuid PRIMARY KEY,
                    version bigint NOT NULL,
                    revision uuid NOT NULL,
                    login_mode text NOT NULL,
                    methods text[] NOT NULL,
                    mfa_mode text NOT NULL,
                    captcha_mode text NOT NULL,
                    updated_by text,
                    change_reason text NOT NULL DEFAULT 'Initial role authentication policy.',
                    updated_at timestamptz NOT NULL DEFAULT now())
                """);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void legacyVerificationOnlyProviderCannotEnableGlobalRequiredMfa() {
        jdbc.update(
                "INSERT INTO platform_global_authentication_policy(id,version,revision,enabled_methods,mfa_required) VALUES (1,1,?,ARRAY['PASSWORD']::text[],false)",
                UUID.randomUUID());
        var plugins = legacyOnlyMfaProvider();
        var service =
                new GlobalAuthenticationPolicyService(
                        JdbcClient.create(jdbc.getDataSource()),
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        mock(AuthenticationPolicyWriteLock.class),
                        plugins,
                        mock(AuthenticationReachabilityPort.class),
                        methodAvailability());

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.save(
                                        new GlobalAuthenticationPolicyService.Input(
                                                1,
                                                List.of(PrimaryMethod.PASSWORD),
                                                true,
                                                "Require MFA"),
                                        "admin"));

        assertEquals("secondFactorUnavailable", error.getReason());
        assertEquals(
                false,
                jdbc.queryForObject(
                        "SELECT mfa_required FROM platform_global_authentication_policy WHERE id=1",
                        Boolean.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyVerificationOnlyProviderCannotEnableRoleRequiredMfa() {
        UUID roleId = UUID.randomUUID();
        jdbc.update("INSERT INTO platform_role(id,version) VALUES (?,1)", roleId);
        PluginExtensionsPort plugins = legacyOnlyMfaProvider();
        ObjectProvider<com.lumira.platform.common.CaptchaProofPort> captcha =
                (ObjectProvider<com.lumira.platform.common.CaptchaProofPort>)
                        mock(ObjectProvider.class);
        var service =
                new RoleAuthenticationPolicyService(
                        JdbcClient.create(jdbc.getDataSource()),
                        new RoleAuthenticationPolicyRepository(
                                JdbcClient.create(jdbc.getDataSource())),
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        mock(GlobalAuthenticationPolicyPort.class),
                        mock(AuthenticationReachabilityPort.class),
                        mock(AuthenticationPolicyWriteLock.class),
                        plugins,
                        captcha,
                        methodAvailability());

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.save(
                                        roleId,
                                        new RoleAuthenticationPolicyService.Input(
                                                1,
                                                0,
                                                RoleAuthenticationPolicyPort.LoginMode.INHERIT,
                                                List.of(),
                                                RoleAuthenticationPolicyPort.MfaMode.REQUIRED,
                                                RoleAuthenticationPolicyPort.CaptchaMode.INHERIT,
                                                "Require MFA"),
                                        "admin"));

        assertEquals("secondFactorUnavailable", error.getReason());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_role_authentication_policy WHERE role_id=?",
                        Integer.class,
                        roleId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void roleAffectedAccountCountExcludesDisabledAndDeletedUsersAndDisabledRoles() {
        UUID roleId = UUID.randomUUID();
        jdbc.update("INSERT INTO platform_role(id,version,enabled) VALUES (?,1,true)", roleId);
        UUID activeA = UUID.randomUUID();
        UUID activeB = UUID.randomUUID();
        UUID disabled = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_user(id,enabled,deleted_at) VALUES (?,?,NULL),(?,?,NULL),(?,?,NULL),(?,?,now())",
                activeA,
                true,
                activeB,
                true,
                disabled,
                false,
                deleted,
                true);
        jdbc.update(
                "INSERT INTO platform_user_role(user_id,role_id) VALUES (?,?),(?,?),(?,?),(?,?)",
                activeA,
                roleId,
                activeB,
                roleId,
                disabled,
                roleId,
                deleted,
                roleId);

        ObjectProvider<com.lumira.platform.common.CaptchaProofPort> captcha =
                (ObjectProvider<com.lumira.platform.common.CaptchaProofPort>)
                        mock(ObjectProvider.class);
        var service =
                new RoleAuthenticationPolicyService(
                        JdbcClient.create(jdbc.getDataSource()),
                        new RoleAuthenticationPolicyRepository(
                                JdbcClient.create(jdbc.getDataSource())),
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        mock(GlobalAuthenticationPolicyPort.class),
                        mock(AuthenticationReachabilityPort.class),
                        mock(AuthenticationPolicyWriteLock.class),
                        mock(PluginExtensionsPort.class),
                        captcha,
                        methodAvailability());

        assertEquals(2, service.read(roleId, "admin").affectedAccountCount());
        jdbc.update("UPDATE platform_role SET enabled=false WHERE id=?", roleId);
        assertEquals(0, service.read(roleId, "admin").affectedAccountCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void roleAuthenticationPolicyCanBeSavedWithoutAChangeReason() {
        UUID roleId = UUID.randomUUID();
        jdbc.update("INSERT INTO platform_role(id,version) VALUES (?,1)", roleId);
        var global = mock(GlobalAuthenticationPolicyPort.class);
        when(global.current())
                .thenReturn(
                        new GlobalAuthenticationPolicyPort.Policy(
                                1, UUID.randomUUID(), Set.of(PrimaryMethod.PASSWORD), false));
        var reachability = mock(AuthenticationReachabilityPort.class);
        when(reachability.assignedAccountsReachable(roleId, Set.of(PrimaryMethod.PASSWORD)))
                .thenReturn(true);
        ObjectProvider<com.lumira.platform.common.CaptchaProofPort> captcha =
                (ObjectProvider<com.lumira.platform.common.CaptchaProofPort>)
                        mock(ObjectProvider.class);
        var service =
                new RoleAuthenticationPolicyService(
                        JdbcClient.create(jdbc.getDataSource()),
                        new RoleAuthenticationPolicyRepository(
                                JdbcClient.create(jdbc.getDataSource())),
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        global,
                        reachability,
                        mock(AuthenticationPolicyWriteLock.class),
                        mock(PluginExtensionsPort.class),
                        captcha,
                        methodAvailability());

        var saved =
                service.save(
                        roleId,
                        new RoleAuthenticationPolicyService.Input(
                                1,
                                0,
                                RoleAuthenticationPolicyPort.LoginMode.INHERIT,
                                List.of(),
                                RoleAuthenticationPolicyPort.MfaMode.INHERIT,
                                RoleAuthenticationPolicyPort.CaptchaMode.INHERIT,
                                null),
                        "admin");

        assertEquals(1, saved.version());
        assertEquals(
                "Role authentication policy updated without a supplied change reason.",
                jdbc.queryForObject(
                        "SELECT change_reason FROM platform_role_authentication_policy WHERE role_id=?",
                        String.class,
                        roleId));

        var updated =
                service.save(
                        roleId,
                        new RoleAuthenticationPolicyService.Input(
                                1,
                                1,
                                RoleAuthenticationPolicyPort.LoginMode.INHERIT,
                                List.of(),
                                RoleAuthenticationPolicyPort.MfaMode.INHERIT,
                                RoleAuthenticationPolicyPort.CaptchaMode.INHERIT,
                                null),
                        "admin");

        assertEquals(2, updated.version());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT cardinality(methods) FROM platform_role_authentication_policy WHERE role_id=?",
                        Integer.class,
                        roleId));
    }

    private static AuthenticationMethodAvailabilityPort methodAvailability() {
        var availability = mock(AuthenticationMethodAvailabilityPort.class);
        when(availability.availableMethods()).thenReturn(Set.of(PrimaryMethod.PASSWORD));
        return availability;
    }

    @SuppressWarnings("unchecked")
    private static PluginExtensionsPort legacyOnlyMfaProvider() {
        PluginExtensionsPort plugins = mock(PluginExtensionsPort.class);
        var provider = new LegacyVerificationOnlyMfaProvider();
        when(plugins.<SecondFactorProvider, Object>extensions(
                        eq(SecondFactorProvider.class), any()))
                .thenAnswer(
                        invocation -> {
                            Function<SecondFactorProvider, Object> mapper =
                                    invocation.getArgument(1);
                            return List.of(mapper.apply(provider));
                        });
        return plugins;
    }

    /**
     * Models an older installed plugin that implements verification but not the new policy
     * contract.
     */
    private static final class LegacyVerificationOnlyMfaProvider implements SecondFactorProvider {
        @Override
        public String id() {
            return "mfa";
        }

        @Override
        public boolean verify(UUID userId, String code) {
            return false;
        }
    }
}
