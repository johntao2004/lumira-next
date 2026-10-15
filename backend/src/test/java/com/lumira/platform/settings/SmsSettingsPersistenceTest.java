package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.SecretKeys;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Real persistence without modifying the local application's business configuration. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class SmsSettingsPersistenceTest {
    @Test
    void legacyUnconfiguredWebhookReloadsWithoutSelectingAnyProvider() {
        var legacy = new SmsSettings(false, "webhook", "", "Lumira", "Code {code}", 10);
        save(0, legacy, "KEEP", null);
        assertEquals("", service.read().data().provider());
        assertEquals("Code {code}", service.read().data().template());
        assertFalse(service.read().credentialConfigured());
        assertFalse(service.read().data().enabled());
        // Reading the new UI default never overwrites saved settings or history.
        assertEquals(
                "webhook",
                mapper.readValue(settings.find("sms").orElseThrow().payload(), SmsSettings.class)
                        .provider());
        var blank = save(1, SmsSettings.defaults(), "KEEP", null);
        assertEquals("", blank.data().provider());
        assertEquals("", service.read().data().provider());
    }

    JdbcTemplate admin, jdbc;
    TransactionTemplate tx;
    String schema;
    SettingStore settings;
    SecretStore secrets;
    SmsSettingsService service;
    SecretKeys keys;
    ObjectMapper mapper = new ObjectMapper();
    AdministrationPort guard = mock(AdministrationPort.class);
    AuditPort audit = mock(AuditPort.class);

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_sms_settings_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(TestSchema.table("platform_setting"));
        TestSchema.indexes("platform_setting").forEach(jdbc::execute);
        jdbc.execute(TestSchema.table("platform_setting_revision"));
        TestSchema.indexes("platform_setting_revision").forEach(jdbc::execute);
        jdbc.execute(TestSchema.table("platform_secret"));
        TestSchema.indexes("platform_secret").forEach(jdbc::execute);
        var client = JdbcClient.create(ds);
        keys =
                new SecretKeys(
                        "primary=" + Base64.getEncoder().encodeToString(new byte[32]), "primary");
        settings = new SettingStore(client, guard, audit);
        secrets = new SecretStore(client, keys);
        service = new SmsSettingsService(settings, secrets, keys, guard, mapper, audit);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    SmsSettings config(String provider, boolean enabled) {
        String endpoint =
                switch (provider) {
                    case "tencent" -> "https://sms.tencentcloudapi.com";
                    case "aliyun" -> "https://dysmsapi.aliyuncs.com";
                    case "alibaba_intl" -> "https://dysmsapi.ap-southeast-1.aliyuncs.com";
                    case "huawei" ->
                            "https://smsapi.cn-north-4.myhuaweicloud.com/sms/batchSendSms/v1";
                    case "twilio" -> "https://api.twilio.com";
                    default -> "https://sms.example.test/send";
                };
        return new SmsSettings(
                enabled,
                provider,
                endpoint,
                provider.equals("twilio") ? "+14155552671" : "Lumira",
                "Code {code}",
                10,
                provider.equals("twilio") ? "international" : "domestic",
                provider.equals("twilio") ? "us1" : "ap-guangzhou",
                provider.equals("twilio") ? "" : "fixture-id",
                provider.equals("twilio") ? "AC" + "a".repeat(32) : "1400000000",
                provider.equals("tencent") ? "12345" : "SMS_12345",
                provider.equals("tencent") || provider.equals("huawei")
                        ? "[\"{code}\"]"
                        : "{\"code\":\"{code}\"}",
                "",
                "");
    }

    SmsSettingsService.View save(long version, SmsSettings data, String action, String value) {
        return tx.execute(
                s ->
                        service.save(
                                "admin",
                                new SmsSettingsService.Input(
                                        version,
                                        data,
                                        new SmsSettingsService.Credentials(action, value),
                                        null,
                                        null)));
    }

    @Test
    void everyProviderReloadsWithEncryptedSecretAndCanBeDisabledWithoutLosingFields() {
        long version = 0;
        for (String provider :
                new String[] {"tencent", "aliyun", "alibaba_intl", "huawei", "twilio", "webhook"}) {
            var configured = config(provider, true);
            var saved = save(version, configured, "REPLACE", "fixture-secret");
            var reloaded =
                    new SmsSettingsService(settings, secrets, keys, guard, mapper, audit).read();
            assertEquals(configured, reloaded.data());
            assertTrue(reloaded.credentialConfigured());
            var loaded = service.load();
            try {
                assertEquals(provider, loaded.settings().provider());
                assertArrayEquals(
                        "fixture-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        loaded.secret());
            } finally {
                loaded.clear();
            }
            var disabled = config(provider, false);
            version = save(saved.version(), disabled, "KEEP", null).version();
            assertEquals(disabled, service.read().data());
            assertTrue(service.read().credentialConfigured());
            assertThrows(ResponseStatusException.class, service::load);
        }
        assertEquals(
                12,
                settings.history("sms", new com.lumira.platform.common.PageRequest(1, 20, ""))
                        .total());
        assertFalse(
                jdbc.queryForObject(
                                "SELECT string_agg(payload::text,' ') FROM platform_setting_revision",
                                String.class)
                        .contains("fixture-secret"));
        assertFalse(
                jdbc.queryForObject("SELECT ciphertext FROM platform_secret", String.class)
                        .contains("fixture-secret"));
    }

    @Test
    void providerSwitchRequiresNewSecretAndStaleWritesDoNotReplaceTheSavedAccount() {
        var saved = save(0, config("tencent", true), "REPLACE", "fixture-secret");
        var rejected =
                assertThrows(
                        ResponseStatusException.class,
                        () -> save(saved.version(), config("aliyun", true), "KEEP", null));
        assertEquals("smsCredentialReplacementRequired", rejected.getReason());
        assertEquals("tencent", service.read().data().provider());
        assertThrows(
                ResponseStatusException.class,
                () -> save(0, config("aliyun", true), "REPLACE", "another-fixture"));
        assertEquals(saved.version(), service.read().version());
        assertEquals(
                "aliyun",
                save(saved.version(), config("aliyun", true), "REPLACE", "another-fixture")
                        .data()
                        .provider());
    }
}
