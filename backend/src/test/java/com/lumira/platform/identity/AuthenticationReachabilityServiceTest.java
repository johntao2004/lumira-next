package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import com.lumira.platform.identity.api.WechatAvailabilityPort;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class AuthenticationReachabilityServiceTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private AuthenticationReachabilityService service;
    private AuthenticationMethodAvailabilityPort methodAvailability;
    private ObjectProvider<WechatAvailabilityPort> wechatProvider;
    private WechatAvailabilityPort wechat;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_auth_reach_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY, enabled boolean NOT NULL, deleted_at timestamptz, password_hash text)");
        jdbc.execute("CREATE TABLE platform_role(id uuid PRIMARY KEY, enabled boolean NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_user_role(user_id uuid NOT NULL, role_id uuid NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_role_authentication_policy(role_id uuid PRIMARY KEY, login_mode varchar(16) NOT NULL, methods text[] NOT NULL)");
        jdbc.execute("CREATE TABLE platform_passkey_credential(user_id uuid NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_account_email(user_id uuid PRIMARY KEY, email text, verified_at timestamptz)");
        jdbc.execute(
                "CREATE TABLE platform_account_phone(user_id uuid PRIMARY KEY, phone text, verified_at timestamptz)");
        jdbc.execute(
                "CREATE TABLE platform_wechat_binding(user_id uuid PRIMARY KEY, app_id varchar(64), open_id_digest char(64), version bigint NOT NULL)");
        methodAvailability = mock(AuthenticationMethodAvailabilityPort.class);
        when(methodAvailability.availableMethods())
                .thenReturn(
                        Set.of(
                                PrimaryMethod.PASSWORD,
                                PrimaryMethod.PASSKEY,
                                PrimaryMethod.EMAIL_OTP,
                                PrimaryMethod.SMS_OTP,
                                PrimaryMethod.WECHAT));
        wechat = mock(WechatAvailabilityPort.class);
        when(wechat.ready()).thenReturn(true);
        when(wechat.currentAppId()).thenReturn("app-current");
        wechatProvider = mock(ObjectProvider.class);
        when(wechatProvider.getIfAvailable()).thenReturn(wechat);
        service =
                new AuthenticationReachabilityService(
                        org.springframework.jdbc.core.simple.JdbcClient.create(source),
                        methodAvailability,
                        wechatProvider);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void globalPasswordOnlyPolicyCannotEraseAnExistingRolePasskeyRestriction() {
        UUID user = UUID.randomUUID();
        UUID passkeyOnly = UUID.randomUUID();
        addUser(user, "present-password");
        addRole(passkeyOnly, "PASSKEY");
        assign(user, passkeyOnly);
        jdbc.update("INSERT INTO platform_passkey_credential(user_id) VALUES (?)", user);

        assertFalse(service.allActiveAccountsReachable(Set.of(PrimaryMethod.PASSWORD)));
        assertTrue(
                service.allActiveAccountsReachable(
                        Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.PASSKEY)));
    }

    @Test
    void multipleRoleRestrictionsAreIntersectedAndCredentialsMustActuallyExist() {
        UUID user = UUID.randomUUID();
        UUID both = UUID.randomUUID();
        UUID passkeyOnly = UUID.randomUUID();
        addUser(user, "present-password");
        addRole(both, "PASSWORD", "PASSKEY");
        addRole(passkeyOnly, "PASSKEY");
        assign(user, both);
        assign(user, passkeyOnly);
        jdbc.update("INSERT INTO platform_passkey_credential(user_id) VALUES (?)", user);

        assertTrue(
                service.allActiveAccountsReachable(
                        Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.PASSKEY)));
        jdbc.update(
                "UPDATE platform_role_authentication_policy SET methods=ARRAY['PASSWORD']::text[] WHERE role_id=?",
                both);
        assertFalse(
                service.allActiveAccountsReachable(
                        Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.PASSKEY)));
        assertFalse(
                service.assignedAccountsReachable(
                        passkeyOnly, Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.PASSKEY)));
    }

    @Test
    void passwordMethodWithoutStoredPasswordDoesNotCountAsReachable() {
        UUID user = UUID.randomUUID();
        addUser(user, null);

        assertFalse(service.allActiveAccountsReachable(Set.of(PrimaryMethod.PASSWORD)));
    }

    @Test
    void verifiedEmailAndPhoneCountOnlyWhenTheirCurrentChannelsAreAvailableAndRoleAllowsThem() {
        UUID emailUser = UUID.randomUUID();
        UUID phoneUser = UUID.randomUUID();
        addUser(emailUser, null);
        addUser(phoneUser, null);
        jdbc.update(
                "INSERT INTO platform_account_email(user_id,email,verified_at) VALUES (?, 'mail@example.test', now())",
                emailUser);
        jdbc.update(
                "INSERT INTO platform_account_phone(user_id,phone,verified_at) VALUES (?, '+15555550123', now())",
                phoneUser);

        assertTrue(
                service.allActiveAccountsReachable(
                        Set.of(PrimaryMethod.EMAIL_OTP, PrimaryMethod.SMS_OTP)));
        assertFalse(service.allActiveAccountsReachable(Set.of(PrimaryMethod.EMAIL_OTP)));
        when(methodAvailability.availableMethods())
                .thenReturn(
                        Set.of(
                                PrimaryMethod.PASSWORD,
                                PrimaryMethod.PASSKEY,
                                PrimaryMethod.SMS_OTP,
                                PrimaryMethod.WECHAT));
        assertFalse(
                service.allActiveAccountsReachable(
                        Set.of(PrimaryMethod.EMAIL_OTP, PrimaryMethod.SMS_OTP)));
    }

    @Test
    void otpReachabilityRequiresTheVerifiedChannelPermittedByEveryRestrictingRole() {
        UUID user = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        addUser(user, null);
        jdbc.update(
                "INSERT INTO platform_account_email(user_id,email,verified_at) VALUES (?, 'mail@example.test', now())",
                user);
        addRole(role, "SMS_OTP");
        assign(user, role);

        assertFalse(
                service.allActiveAccountsReachable(
                        Set.of(PrimaryMethod.EMAIL_OTP, PrimaryMethod.SMS_OTP)));
        jdbc.update(
                "UPDATE platform_role_authentication_policy SET methods=ARRAY['EMAIL_OTP']::text[] WHERE role_id=?",
                role);
        assertTrue(
                service.assignedAccountsReachable(
                        role, Set.of(PrimaryMethod.EMAIL_OTP, PrimaryMethod.SMS_OTP)));
    }

    @Test
    void wechatReachabilityRequiresCurrentReadyAppBindingAndEveryRoleRestriction() {
        UUID user = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        addUser(user, null);
        jdbc.update(
                "INSERT INTO platform_wechat_binding(user_id,app_id,open_id_digest,version) VALUES (?, 'app-current', ?, 1)",
                user,
                "a".repeat(64));
        addRole(role, "WECHAT");
        assign(user, role);

        assertTrue(service.assignedAccountsReachable(role, Set.of(PrimaryMethod.WECHAT)));
        jdbc.update("UPDATE platform_wechat_binding SET app_id='app-old' WHERE user_id=?", user);
        assertFalse(service.assignedAccountsReachable(role, Set.of(PrimaryMethod.WECHAT)));
        jdbc.update(
                "UPDATE platform_wechat_binding SET app_id='app-current' WHERE user_id=?", user);
        jdbc.update(
                "UPDATE platform_role_authentication_policy SET methods=ARRAY['PASSWORD']::text[] WHERE role_id=?",
                role);
        assertFalse(service.assignedAccountsReachable(role, Set.of(PrimaryMethod.WECHAT)));
        when(wechat.ready()).thenReturn(false);
        jdbc.update(
                "UPDATE platform_role_authentication_policy SET methods=ARRAY['WECHAT']::text[] WHERE role_id=?",
                role);
        assertFalse(service.assignedAccountsReachable(role, Set.of(PrimaryMethod.WECHAT)));
    }

    private void addUser(UUID id, String passwordHash) {
        jdbc.update(
                "INSERT INTO platform_user(id,enabled,password_hash) VALUES (?,true,?)",
                id,
                passwordHash);
    }

    private void addRole(UUID id, String... methods) {
        jdbc.update("INSERT INTO platform_role(id,enabled) VALUES (?,true)", id);
        String array =
                String.join(
                        ",",
                        java.util.Arrays.stream(methods)
                                .map(method -> "'" + method + "'")
                                .toList());
        jdbc.execute(
                "INSERT INTO platform_role_authentication_policy(role_id,login_mode,methods) VALUES ('"
                        + id
                        + "','RESTRICT',ARRAY["
                        + array
                        + "]::text[])");
    }

    private void assign(UUID user, UUID role) {
        jdbc.update("INSERT INTO platform_user_role(user_id,role_id) VALUES (?,?)", user, role);
    }
}
