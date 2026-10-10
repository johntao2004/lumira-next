package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.SecretKeys;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class CaptchaSettingsServiceDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private CaptchaSettingsService service;
    private AuditPort audit;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_captcha_settings_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                """
                CREATE TABLE platform_captcha_settings(
                    id smallint PRIMARY KEY,
                    version bigint NOT NULL,
                    revision uuid NOT NULL,
                    enabled boolean NOT NULL,
                    challenge_type varchar(16) NOT NULL,
                    actions jsonb NOT NULL,
                    updated_by varchar(100) NOT NULL,
                    change_reason varchar(1000) NOT NULL,
                    updated_at timestamptz NOT NULL DEFAULT now())
                """);
        jdbc.update(
                """
                INSERT INTO platform_captcha_settings(
                    id,version,revision,enabled,challenge_type,actions,updated_by,change_reason)
                VALUES (1,1,?,false,'IMAGE_TEXT','{}'::jsonb,'fixture','Initial test state')
                """,
                UUID.randomUUID());
        jdbc.execute(
                "CREATE TABLE platform_role_authentication_policy(captcha_mode varchar(16) NOT NULL)");

        var keys =
                new SecretKeys(
                        "primary=" + Base64.getEncoder().encodeToString(new byte[32]), "primary");
        audit = mock(AuditPort.class);
        service =
                new CaptchaSettingsService(
                        JdbcClient.create(dataSource),
                        keys,
                        new LocalCaptchaAssets(),
                        mock(AuthenticationPolicyWriteLock.class),
                        mock(AdministrationPort.class),
                        audit,
                        new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void saveResponseMatchesTheNormalizedSettingsReturnedByTheNextRead() {
        var partialActions = Map.of("PASSWORD_LOGIN", CaptchaSettingsService.Mode.ALWAYS);
        var submitted =
                new CaptchaSettingsService.Data(true, "LOCAL", "IMAGE_TEXT", partialActions);

        var saved = service.save("qa-admin", new CaptchaSettingsService.Input(1, submitted, null));
        var readAgain = service.read("qa-admin");

        assertEquals(CaptchaProofPort.Action.values().length, saved.data().actions().size());
        assertEquals(
                CaptchaSettingsService.Mode.OFF,
                saved.data().actions().get("PASSWORD_RECOVERY_VERIFY"));
        assertEquals(readAgain.data(), saved.data());
        assertEquals(readAgain.revision(), saved.revision());
        assertEquals(readAgain.version(), saved.version());
        assertEquals(
                "CAPTCHA settings saved without a supplied change reason.",
                jdbc.queryForObject(
                        "SELECT change_reason FROM platform_captcha_settings WHERE id=1",
                        String.class));
        verify(audit).append("qa-admin", "setting.captchaUpdated", saved.revision());
    }
}
