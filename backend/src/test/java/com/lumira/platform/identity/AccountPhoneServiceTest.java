package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.audit.AuditRepository;
import com.lumira.platform.common.AuthorizationContextBoundaryPort;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.common.SmsSender;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class AccountPhoneServiceTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private UUID owner = UUID.randomUUID();
    private AccountPhoneStore phones;
    private AccountPhoneService service;
    private SmsSender sms;
    private AuthorizationContextBoundaryPort boundary;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_phone_service_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE platform_user(id UUID PRIMARY KEY,username TEXT UNIQUE NOT NULL,enabled BOOLEAN NOT NULL DEFAULT true,password_hash TEXT NOT NULL,display_name TEXT NOT NULL DEFAULT '',real_name TEXT NOT NULL DEFAULT '',birth_month TEXT NOT NULL DEFAULT '',region TEXT NOT NULL DEFAULT '',version BIGINT NOT NULL DEFAULT 0)");
        jdbc.update(
                "INSERT INTO platform_user(id,username,password_hash) VALUES(?,?,?)",
                owner,
                "fixture",
                "fixture-hash");
        jdbc.execute(
                "CREATE TABLE platform_account_phone(user_id UUID PRIMARY KEY REFERENCES platform_user(id),phone VARCHAR(16),verified_at TIMESTAMPTZ,version BIGINT NOT NULL DEFAULT 0,pending_phone VARCHAR(16),token_hash VARCHAR(64),expires_at TIMESTAMPTZ,requested_at TIMESTAMPTZ,request_day DATE,request_count INTEGER NOT NULL DEFAULT 0,failures INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute(
                "CREATE TABLE platform_audit(id UUID PRIMARY KEY,actor TEXT NOT NULL,action TEXT NOT NULL,target_id UUID,created_at TIMESTAMPTZ NOT NULL DEFAULT now())");

        var client = JdbcClient.create(dataSource);
        var secrets =
                new SecretKeys(
                        "primary=" + Base64.getEncoder().encodeToString(new byte[32]), "primary");
        var codes = new PhoneVerificationCode(secrets);
        phones = new AccountPhoneStore(client, null, codes);
        sms = mock(SmsSender.class);
        when(sms.ready()).thenReturn(true);
        when(sms.enqueueVerification(any(), anyString(), anyString(), anyInt())).thenReturn(true);
        boundary = mock(AuthorizationContextBoundaryPort.class);
        doAnswer(
                        invocation -> {
                            assertTrue(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return null;
                        })
                .when(boundary)
                .lockAndValidateCurrentRequest();
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.matches("current", "fixture-hash")).thenReturn(true);
        var manager = new DataSourceTransactionManager(dataSource);
        service =
                new AccountPhoneService(
                        new ProfileRepository(client),
                        phones,
                        sms,
                        new AuditRepository(client),
                        client,
                        encoder,
                        codes,
                        null,
                        null,
                        boundary,
                        manager);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void requestAndConfirmRecheckRevisionInsideTheWriteTransactions() {
        var pending =
                service.request(
                        "fixture",
                        new AccountPhoneService.Request(0, "+15555550123", "current", "en"));
        assertEquals("+15555550123", pending.pendingPhone());
        assertEquals(1, pending.version());
        verify(boundary).lockAndValidateCurrentRequest();

        var code = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sms).enqueueVerification(any(), eq("+15555550123"), code.capture(), anyInt());
        assertTrue(service.confirm("fixture", code.getValue()));
        assertEquals("+15555550123", phones.read(owner).phone());
        assertEquals(2, phones.read(owner).version());
        verify(boundary, times(2)).lockAndValidateCurrentRequest();
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM platform_audit", Integer.class));
    }
}
