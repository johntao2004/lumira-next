package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import com.lumira.platform.identity.api.AuthenticationReachabilityPort;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class GlobalAuthenticationPolicyAvailabilityDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_auth_method_ready_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(TestSchema.table("platform_global_authentication_policy"));
        jdbc.execute(TestSchema.table("platform_user"));
        insertUser(UUID.randomUUID(), "auth-method-enabled-a", true, false);
        insertUser(UUID.randomUUID(), "auth-method-enabled-b", true, false);
        insertUser(UUID.randomUUID(), "auth-method-disabled", false, false);
        insertUser(UUID.randomUUID(), "auth-method-deleted", true, true);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void providerReadyMethodsRemainVisibleWhenGlobalPolicyHasNotEnabledThemYet() {
        UUID revision = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_global_authentication_policy(id,version,revision,enabled_methods,mfa_required) VALUES (1,1,?,ARRAY['PASSWORD']::text[],false)",
                revision);

        var availability = mock(AuthenticationMethodAvailabilityPort.class);
        when(availability.availableMethods())
                .thenReturn(Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.EMAIL_OTP));
        var service =
                new GlobalAuthenticationPolicyService(
                        JdbcClient.create(jdbc.getDataSource()),
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        mock(AuthenticationPolicyWriteLock.class),
                        mock(PluginExtensionsPort.class),
                        mock(AuthenticationReachabilityPort.class),
                        availability);

        var view = service.read("admin");

        assertEquals(List.of(PrimaryMethod.PASSWORD), view.enabledMethods());
        assertEquals(
                List.of(PrimaryMethod.PASSWORD, PrimaryMethod.EMAIL_OTP), view.availableMethods());
        assertEquals(2, view.affectedAccountCount());
    }

    @Test
    void otpCanBeEnabledWithoutRequiringTheUnconfiguredPasskeyFromTheDefaultPolicy() {
        jdbc.update(
                "INSERT INTO platform_global_authentication_policy(id,version,revision,enabled_methods,mfa_required) VALUES (1,1,?,ARRAY['PASSWORD','PASSKEY']::text[],false)",
                UUID.randomUUID());
        var service = service(Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.EMAIL_OTP));

        var saved =
                service.save(
                        new GlobalAuthenticationPolicyService.Input(
                                1,
                                List.of(PrimaryMethod.PASSWORD, PrimaryMethod.EMAIL_OTP),
                                false,
                                null),
                        "admin");

        assertEquals(
                List.of(PrimaryMethod.PASSWORD, PrimaryMethod.EMAIL_OTP), saved.enabledMethods());
        assertEquals(
                List.of(PrimaryMethod.PASSWORD, PrimaryMethod.EMAIL_OTP), saved.availableMethods());
        assertEquals(2, saved.affectedAccountCount());
        assertEquals(
                "Global authentication policy updated without a supplied change reason.",
                jdbc.queryForObject(
                        "SELECT change_reason FROM platform_global_authentication_policy WHERE id=1",
                        String.class));
    }

    @Test
    void existingUnavailableMethodCannotBeRetainedAndRejectedSaveDoesNotPersist() {
        UUID revision = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_global_authentication_policy(id,version,revision,enabled_methods,mfa_required) VALUES (1,1,?,ARRAY['PASSWORD','PASSKEY']::text[],false)",
                revision);
        var service = service(Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.EMAIL_OTP));

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.save(
                                        new GlobalAuthenticationPolicyService.Input(
                                                1,
                                                List.of(
                                                        PrimaryMethod.PASSWORD,
                                                        PrimaryMethod.PASSKEY,
                                                        PrimaryMethod.EMAIL_OTP),
                                                false,
                                                "Retain passkey after provider became invalid"),
                                        "admin"));
        assertEquals("authenticationMethodUnavailable", error.getReason());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT version FROM platform_global_authentication_policy WHERE id=1",
                        Integer.class));
        assertEquals(
                revision,
                jdbc.queryForObject(
                        "SELECT revision FROM platform_global_authentication_policy WHERE id=1",
                        UUID.class));
        assertEquals(
                "{PASSWORD,PASSKEY}",
                jdbc.queryForObject(
                        "SELECT enabled_methods::text FROM platform_global_authentication_policy WHERE id=1",
                        String.class));
    }

    @Test
    void newlyEnabledUnavailableMethodIsRejectedWithoutPersistence() {
        UUID revision = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_global_authentication_policy(id,version,revision,enabled_methods,mfa_required) VALUES (1,1,?,ARRAY['PASSWORD']::text[],false)",
                revision);
        var service = service(Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.EMAIL_OTP));

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.save(
                                        new GlobalAuthenticationPolicyService.Input(
                                                1,
                                                List.of(
                                                        PrimaryMethod.PASSWORD,
                                                        PrimaryMethod.SMS_OTP),
                                                false,
                                                "Try unconfigured SMS"),
                                        "admin"));

        assertEquals("authenticationMethodUnavailable", error.getReason());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT version FROM platform_global_authentication_policy WHERE id=1",
                        Integer.class));
        assertEquals(
                revision,
                jdbc.queryForObject(
                        "SELECT revision FROM platform_global_authentication_policy WHERE id=1",
                        UUID.class));
        assertEquals(
                "{PASSWORD}",
                jdbc.queryForObject(
                        "SELECT enabled_methods::text FROM platform_global_authentication_policy WHERE id=1",
                        String.class));
    }

    private GlobalAuthenticationPolicyService service(Set<PrimaryMethod> available) {
        var availability = mock(AuthenticationMethodAvailabilityPort.class);
        when(availability.availableMethods()).thenReturn(available);
        var reachability = mock(AuthenticationReachabilityPort.class);
        when(reachability.allActiveAccountsReachable(org.mockito.ArgumentMatchers.anySet()))
                .thenReturn(true);
        return new GlobalAuthenticationPolicyService(
                JdbcClient.create(jdbc.getDataSource()),
                mock(AdministrationPort.class),
                mock(AuditPort.class),
                mock(AuthenticationPolicyWriteLock.class),
                mock(PluginExtensionsPort.class),
                reachability,
                availability);
    }

    private void insertUser(UUID id, String username, boolean enabled, boolean deleted) {
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash,enabled,deleted_at) VALUES (?,?,?,?,?,CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)",
                id,
                username,
                "Test " + username,
                "unused-hash",
                enabled,
                deleted);
    }
}
