package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.audit.AuditRepository;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.MailConfigurationPort;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import com.lumira.platform.mail.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class PasswordRecoveryServiceTest {
    JdbcTemplate admin, jdbc;
    TransactionTemplate tx;
    String schema;
    UUID id = UUID.randomUUID();
    PasswordRecoveryService service;
    MailDeliveryStore mail;
    CaptchaProofAccess captcha;
    SecurityPolicyPort security;
    SmtpConnectionSource smtp = mock(SmtpConnectionSource.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_recovery_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        var client = JdbcClient.create(ds);
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        jdbc.execute(
                "CREATE TABLE platform_user(id UUID PRIMARY KEY,username TEXT,password_hash TEXT,enabled BOOLEAN,deleted_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE platform_password_change_requirement(user_id UUID PRIMARY KEY)");
        jdbc.execute(
                "CREATE TABLE platform_bootstrap_password_change_requirement(user_id UUID PRIMARY KEY,primary_credential_revision UUID NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,completed_at TIMESTAMPTZ,CHECK(completed_at IS NULL OR completed_at>=created_at))");
        jdbc.update(
                "INSERT INTO platform_user(id,username,password_hash,enabled) VALUES(?,'fixture','old-hash',true)",
                id);
        jdbc.execute("CREATE TABLE spring_session(principal_name TEXT)");
        jdbc.execute("INSERT INTO spring_session VALUES('fixture'),('other')");
        jdbc.execute(TestSchema.table("platform_mail_delivery"));
        jdbc.execute(TestSchema.table("platform_account_email"));
        jdbc.execute(TestSchema.table("platform_password_reset"));
        jdbc.update(
                "INSERT INTO platform_account_email(user_id,email,verified_at) VALUES(?,'verified@example.test',now())",
                id);
        jdbc.execute(TestSchema.table("platform_audit"));
        mail =
                new MailDeliveryStore(
                        client,
                        new SecretKeys(
                                "primary=" + Base64.getEncoder().encodeToString(new byte[32]),
                                "primary"),
                        new ObjectMapper());
        when(encoder.encode(anyString())).thenReturn("new-hash");
        when(encoder.matches("old-password-123", "old-hash")).thenReturn(true);
        when(smtp.load())
                .thenAnswer(
                        i ->
                                new SmtpConnection(
                                        new MailConfigurationPort.Settings(
                                                true,
                                                "localhost",
                                                587,
                                                "STARTTLS",
                                                false,
                                                "",
                                                "sender@example.test",
                                                ""),
                                        null));
        captcha = mock(CaptchaProofAccess.class);
        var captchaPort = mock(CaptchaProofPort.class);
        var optionalGate =
                new CaptchaProofAccess.Gate(
                        captchaPort,
                        CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST,
                        new CaptchaProofPort.Requirement(false, "captcha-off", null));
        when(captcha.begin(any(), any(), any(), any())).thenReturn(optionalGate);
        security = mock(SecurityPolicyPort.class);
        when(security.currentPolicy()).thenReturn(SecurityPolicyPort.Policy.defaults());
        service =
                new PasswordRecoveryService(
                        client,
                        new PasswordResetStore(client, security),
                        new MailSenderAdapter(mail, smtp),
                        new AuditRepository(client),
                        encoder,
                        security,
                        captcha,
                        manager);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    void request(String name) {
        tx.executeWithoutResult(
                s -> service.request(name, "zh-CN", null, new MockHttpServletRequest()));
    }

    String issue() {
        request("fixture");
        var claim = tx.execute(s -> mail.claim().orElseThrow());
        var outgoing = mail.message(claim).orElseThrow();
        assertEquals("verified@example.test", outgoing.recipient());
        assertEquals("重置账号密码", outgoing.subject());
        return outgoing.text().substring(outgoing.text().lastIndexOf('\n') + 1);
    }

    boolean confirm(String token, String password) {
        return service.confirm("fixture", token, password, null, new MockHttpServletRequest());
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void queueToConfirmationChangesPasswordAndRevokesSessions() {
        String code = issue();
        assertEquals(43, code.length());
        assertTrue(confirm(code, "new-password-123"));
        assertFalse(confirm(code, "new-password-123"));
        assertEquals(
                "new-hash",
                jdbc.queryForObject("SELECT password_hash FROM platform_user", String.class));
        assertEquals(
                List.of("other"),
                jdbc.queryForList("SELECT principal_name FROM spring_session", String.class));
        assertEquals(3, count("platform_audit"));
    }

    @Test
    void ineligibleAndCooldownRequestsHaveNoReturnPayloadOrDuplicateMail() {
        request("unknown");
        jdbc.update("UPDATE platform_user SET enabled=false");
        request("fixture");
        jdbc.update("UPDATE platform_user SET enabled=true");
        jdbc.update("UPDATE platform_account_email SET email=NULL,verified_at=NULL");
        request("fixture");
        assertEquals(0, count("platform_password_reset"));
        assertEquals(0, count("platform_mail_delivery"));
        assertEquals(0, count("platform_audit"));
        jdbc.update(
                "UPDATE platform_account_email SET email='verified@example.test',verified_at=now()");
        request("fixture");
        request("fixture");
        assertEquals(1, count("platform_mail_delivery"));
    }

    @Test
    void invalidProofCommitsButSamePasswordRuleDoesNotExposeAccountBeforeProof() {
        String code = issue();
        assertFalse(confirm("invalid", "old-password-123"));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT failures FROM platform_password_reset", Integer.class));
        var error =
                assertThrows(
                        ResponseStatusException.class, () -> confirm(code, "old-password-123"));
        assertEquals("passwordMustDiffer", error.getReason());
        assertTrue(confirm(code, "new-password-123"));
        assertFalse(
                service.confirm(
                        "unknown", code, "new-password-123", null, new MockHttpServletRequest()));
    }

    @Test
    void requestAuditFailureRollsBackChallengeAndQueue() {
        failAudit();
        assertThrows(org.springframework.dao.DataAccessException.class, () -> request("fixture"));
        assertEquals(0, count("platform_password_reset"));
        assertEquals(0, count("platform_mail_delivery"));
    }

    @Test
    void confirmationAuditFailureRollsBackEverything() {
        String code = issue();
        failAudit();
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> confirm(code, "new-password-123"));
        assertEquals(
                "old-hash",
                jdbc.queryForObject("SELECT password_hash FROM platform_user", String.class));
        assertEquals(2, count("spring_session"));
        assertNotNull(
                jdbc.queryForObject(
                        "SELECT token_hash FROM platform_password_reset", String.class));
    }

    @Test
    void policyStrengthenedDuringHashingDoesNotCommitAWeakReplacementOrConsumeToken() {
        String code = issue();
        var stricter =
                new SecurityPolicyPort.Policy(
                        12, true, false, true, true, 30, 120, 10, 60, 600, 60, 10, 10, 5);
        when(security.currentPolicy())
                .thenReturn(
                        SecurityPolicyPort.Policy.defaults(),
                        SecurityPolicyPort.Policy.defaults(),
                        stricter);
        when(encoder.encode("new-password-123")).thenAnswer(invocation -> "new-hash");

        var error =
                assertThrows(
                        ResponseStatusException.class, () -> confirm(code, "new-password-123"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("passwordPolicyChanged", error.getReason());
        assertEquals(
                "old-hash",
                jdbc.queryForObject("SELECT password_hash FROM platform_user", String.class));
        assertEquals(
                VerificationToken.digest(code),
                jdbc.queryForObject(
                        "SELECT token_hash FROM platform_password_reset", String.class));
        assertEquals(2, count("spring_session"));
    }

    @Test
    void unavailableSmtpAndInvalidPasswordCreateNoWrites() {
        when(smtp.load()).thenThrow(new IllegalStateException("Fixture unavailable"));
        assertThrows(IllegalStateException.class, () -> request("unknown"));
        assertThrows(IllegalStateException.class, () -> request("fixture"));
        assertThrows(ResponseStatusException.class, () -> confirm("invalid", "short"));
        verifyNoInteractions(encoder);
        assertEquals(0, count("platform_password_reset"));
        assertEquals(0, count("platform_mail_delivery"));
    }

    @Test
    void requiredCaptchaCannotBeSkippedForLegacyEmailRecoveryActions() {
        var requestGate =
                new CaptchaProofAccess.Gate(
                        mock(CaptchaProofPort.class),
                        CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST,
                        new CaptchaProofPort.Requirement(true, "captcha-required", "IMAGE_TEXT"));
        when(captcha.begin(
                        eq(CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST), any(), any(), any()))
                .thenReturn(requestGate);
        doCallRealMethod().when(captcha).consume(any(), any(), any(), any());
        var requestError = assertThrows(ResponseStatusException.class, () -> request("fixture"));
        assertEquals(HttpStatus.BAD_REQUEST, requestError.getStatusCode());
        assertEquals("captchaRequired", requestError.getReason());
        assertEquals(0, count("platform_password_reset"));
        assertEquals(0, count("platform_mail_delivery"));

        var confirmGate =
                new CaptchaProofAccess.Gate(
                        mock(CaptchaProofPort.class),
                        CaptchaProofPort.Action.PASSWORD_RECOVERY_CONFIRM,
                        new CaptchaProofPort.Requirement(true, "captcha-required", "IMAGE_TEXT"));
        when(captcha.begin(
                        eq(CaptchaProofPort.Action.PASSWORD_RECOVERY_CONFIRM), any(), any(), any()))
                .thenReturn(confirmGate);
        doCallRealMethod().when(captcha).preflight(any(), any(), any(), any());
        var confirmError =
                assertThrows(
                        ResponseStatusException.class,
                        () -> confirm("unused-proof", "new-password-123"));
        assertEquals(HttpStatus.BAD_REQUEST, confirmError.getStatusCode());
        assertEquals("captchaRequired", confirmError.getReason());
        assertEquals(
                "old-hash",
                jdbc.queryForObject("SELECT password_hash FROM platform_user", String.class));
    }

    void failAudit() {
        jdbc.execute(
                "CREATE FUNCTION fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Fixture failure'; END $$; CREATE TRIGGER fail_audit BEFORE INSERT ON platform_audit FOR EACH ROW EXECUTE FUNCTION fail_audit()");
    }
}
