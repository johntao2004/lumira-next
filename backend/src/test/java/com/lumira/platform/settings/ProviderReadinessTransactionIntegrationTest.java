package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.identity.AuthenticationMethodAvailabilityService;
import com.lumira.platform.identity.api.PasskeyPolicyPort;
import com.lumira.platform.identity.api.WechatAvailabilityPort;
import com.lumira.platform.mail.MailDeliveryStore;
import com.lumira.platform.mail.MailSenderAdapter;
import com.lumira.platform.mail.SmtpConnectionSource;
import com.lumira.platform.sms.SmsConnectionSource;
import com.lumira.platform.sms.SmsDeliveryStore;
import com.lumira.platform.sms.SmsSenderAdapter;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Exercises the proxied settings readiness methods inside a caller-owned database transaction. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class ProviderReadinessTransactionIntegrationTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private AnnotationConfigApplicationContext context;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_provider_ready_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        dataSource =
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
                CREATE TABLE platform_secret(
                    secret_context varchar(512) PRIMARY KEY,
                    ciphertext text,
                    version bigint NOT NULL DEFAULT 1,
                    updated_at timestamptz NOT NULL DEFAULT now())
                """);
        jdbc.execute("CREATE TABLE readiness_commit_probe(id integer PRIMARY KEY)");

        var client = JdbcClient.create(dataSource);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);
        var settings = new SettingStore(client, guard, audit);
        var keys =
                new SecretKeys(
                        "primary=" + Base64.getEncoder().encodeToString(new byte[32]), "primary");
        var secrets = new SecretStore(client, keys);
        var mapper = new ObjectMapper();

        context = new AnnotationConfigApplicationContext();
        context.registerBean(
                "transactionManager",
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(
                "smtpSettingsService",
                SmtpConfigurationReader.class,
                () -> new SmtpConfigurationReader(settings, secrets, keys, mapper));
        context.registerBean(
                "smsSettingsService",
                SmsConfigurationReader.class,
                () -> new SmsConfigurationReader(settings, secrets, keys, mapper));
        context.register(TransactionalReadinessConfiguration.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void missingSmtpAndSmsConfigurationReturnsFalseWithoutPoisoningAnOuterTransaction() {
        var transactions =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transactions.execute(
                status -> {
                    assertEquals(Set.of(PrimaryMethod.PASSWORD), availability().availableMethods());
                    jdbc.update("INSERT INTO readiness_commit_probe(id) VALUES (1)");
                    return null;
                });

        assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM readiness_commit_probe", Integer.class));
    }

    @Test
    void disabledSmtpAndSmsConfigurationReturnsFalseWithoutPoisoningAnOuterTransaction() {
        jdbc.update(
                """
                INSERT INTO platform_setting(id,section,version,payload,updated_by)
                VALUES (?, 'smtp', 1, CAST(? AS jsonb), 'fixture'),
                       (?, 'sms', 1, CAST(? AS jsonb), 'fixture')
                """,
                UUID.randomUUID(),
                "{\"enabled\":false,\"host\":\"\",\"port\":587,\"security\":\"STARTTLS\",\"authentication\":true,\"username\":\"\",\"fromAddress\":\"\",\"fromName\":\"\"}",
                UUID.randomUUID(),
                "{\"enabled\":false,\"provider\":\"\",\"endpoint\":\"\",\"sender\":\"\",\"template\":\"Lumira verification code: {code}\",\"timeoutSeconds\":10}");

        var transactions =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transactions.execute(
                status -> {
                    assertEquals(Set.of(PrimaryMethod.PASSWORD), availability().availableMethods());
                    jdbc.update("INSERT INTO readiness_commit_probe(id) VALUES (2)");
                    return null;
                });

        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM readiness_commit_probe WHERE id=2", Integer.class));
    }

    @Test
    void databaseFailureDuringReadinessPropagatesInsteadOfLookingUnconfigured() {
        admin.execute("DROP TABLE " + schema + ".platform_setting");
        var transactions =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        assertThrows(
                DataAccessException.class,
                () ->
                        transactions.execute(
                                status -> {
                                    availability().availableMethods();
                                    jdbc.update(
                                            "INSERT INTO readiness_commit_probe(id) VALUES (3)");
                                    return null;
                                }));
        assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM readiness_commit_probe", Integer.class));
    }

    private AuthenticationMethodAvailabilityService availability() {
        return new AuthenticationMethodAvailabilityService(
                new MailSenderAdapter(
                        mock(MailDeliveryStore.class),
                        new SmtpConnectionSource(context.getBean(SmtpConfigurationReader.class))),
                new SmsSenderAdapter(
                        mock(SmsDeliveryStore.class),
                        new SmsConnectionSource(context.getBean(SmsConfigurationReader.class))),
                mock(PasskeyPolicyPort.class),
                wechatProviders());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionalReadinessConfiguration {}

    @SuppressWarnings("unchecked")
    private static ObjectProvider<WechatAvailabilityPort> wechatProviders() {
        return (ObjectProvider<WechatAvailabilityPort>) mock(ObjectProvider.class);
    }
}
