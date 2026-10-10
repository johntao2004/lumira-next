package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.SecretKeys;
import java.util.Base64;
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
class WechatSettingsChangeReasonDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private WechatSettingsService service;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_wechat_reason_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                """
                CREATE TABLE platform_setting(
                    id uuid PRIMARY KEY,
                    section varchar(100) NOT NULL UNIQUE,
                    version bigint NOT NULL,
                    payload jsonb NOT NULL,
                    updated_by varchar(100) NOT NULL,
                    updated_at timestamptz NOT NULL DEFAULT now())
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_setting_revision(
                    setting_id uuid NOT NULL,
                    version bigint NOT NULL,
                    payload jsonb NOT NULL,
                    changed_by varchar(100) NOT NULL,
                    change_reason varchar(1000) NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    source_version bigint)
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_secret(
                    secret_context varchar(512) PRIMARY KEY,
                    ciphertext text,
                    version bigint NOT NULL DEFAULT 1,
                    updated_at timestamptz NOT NULL DEFAULT now())
                """);
        var client = JdbcClient.create(dataSource);
        var keys =
                new SecretKeys(
                        "primary=" + Base64.getEncoder().encodeToString(new byte[32]), "primary");
        var guard = mock(AdministrationPort.class);
        service =
                new WechatSettingsService(
                        new SettingStore(client, guard, mock(AuditPort.class)),
                        new SecretStore(client, keys),
                        keys,
                        guard,
                        mock(AuthenticationPolicyWriteLock.class),
                        mock(
                                com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort
                                        .class),
                        mock(AuditPort.class),
                        new ObjectMapper(),
                        client,
                        "");
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void disabledWechatSettingsSaveWithoutAReasonAndKeepTheirRevisionRecord() {
        var saved =
                service.save(
                        "qa-admin",
                        new WechatSettingsService.Input(
                                0,
                                false,
                                "",
                                "",
                                8,
                                new WechatSettingsService.Credentials("KEEP", null),
                                null));

        assertEquals(1, saved.version());
        assertEquals(
                SettingStore.SYSTEM_SAVE_REASON,
                jdbc.queryForObject(
                        "SELECT change_reason FROM platform_setting_revision WHERE version=1",
                        String.class));
    }
}
