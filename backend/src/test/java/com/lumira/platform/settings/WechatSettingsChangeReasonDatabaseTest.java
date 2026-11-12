package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.SecretKeys;
import com.lumira.plugins.authenticationconsole.WechatSettingsService;
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
        for (var table :
                java.util.List.of(
                        "platform_setting", "platform_setting_revision", "platform_secret"))
            jdbc.execute(com.lumira.platform.TestSchema.table(table));
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
                        new WechatConfigurationReader(
                                new SettingStore(client, guard, mock(AuditPort.class)),
                                new SecretStore(client, keys),
                                keys,
                                new ObjectMapper(),
                                ""));
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void kernelProviderReaderRetainsConfigurationAndClearsOnlyItsBorrowedSecretBytes() {
        var client = JdbcClient.create(jdbc.getDataSource());
        var keys =
                new SecretKeys(
                        "primary=" + Base64.getEncoder().encodeToString(new byte[32]), "primary");
        var guard = mock(AdministrationPort.class);
        var store = new SettingStore(client, guard, mock(AuditPort.class));
        var secrets = new SecretStore(client, keys);
        var mapper = new ObjectMapper();
        var reader =
                new WechatConfigurationReader(
                        store, secrets, keys, mapper, "http://localhost:18180");
        var administration =
                new WechatSettingsService(
                        store,
                        secrets,
                        keys,
                        guard,
                        mock(AuthenticationPolicyWriteLock.class),
                        mock(
                                com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort
                                        .class),
                        mock(AuditPort.class),
                        mapper,
                        client,
                        reader);
        var plaintext = "Qa!WechatKernelReader";
        administration.save(
                "qa-admin",
                new WechatSettingsService.Input(
                        0,
                        true,
                        "qa_kernel_app",
                        "http://localhost:18180",
                        8,
                        new WechatSettingsService.Credentials("REPLACE", plaintext),
                        "QA independent reader"));
        org.junit.jupiter.api.Assertions.assertTrue(reader.snapshot().ready());
        assertEquals("qa_kernel_app", reader.currentAppId());
        var secretState =
                secrets.state(
                        WechatConfigurationReader.context(store.find("wechat").orElseThrow().id()));
        byte[] borrowed;
        try (var configuration = reader.providerConfiguration()) {
            borrowed = configuration.secret();
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8), borrowed);
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[borrowed.length], borrowed);
        try (var reread = reader.providerConfiguration()) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8), reread.secret());
        }
        assertEquals(
                secretState,
                secrets.state(
                        WechatConfigurationReader.context(
                                store.find("wechat").orElseThrow().id())));
        assertEquals(1, reader.snapshot().version());
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
