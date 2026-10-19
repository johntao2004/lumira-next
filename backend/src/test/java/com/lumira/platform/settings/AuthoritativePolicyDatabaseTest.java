package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 两个独立数据源和服务实例验证真实 PostgreSQL 提交可见性及回滚，不依赖等待缓存过期。 Two independent data sources and service
 * instances verify PostgreSQL commit visibility and rollback without waiting for cache expiry.
 */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class AuthoritativePolicyDatabaseTest {
    private JdbcTemplate admin;
    private String schema;
    private TransactionTemplate txA;
    private SecuritySettingsService securityA, securityB;
    private PasskeySettingsService passkeyA, passkeyB;
    private SettingStore storeA, storeB;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_policy_visibility_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        String scoped = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var sourceA = new DriverManagerDataSource(scoped, user, password);
        var sourceB = new DriverManagerDataSource(scoped, user, password);
        var jdbc = new JdbcTemplate(sourceA);
        for (String table : List.of("platform_setting", "platform_setting_revision")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        // 仅权限入口和审计副作用模拟；设置读写及事务均为真实 PG。
        // Only authorization entry and audit side effects are mocked; policy IO and transactions
        // use PG.
        storeA =
                new SettingStore(
                        JdbcClient.create(sourceA),
                        mock(AdministrationPort.class),
                        mock(AuditPort.class));
        storeB =
                new SettingStore(
                        JdbcClient.create(sourceB),
                        mock(AdministrationPort.class),
                        mock(AuditPort.class));
        securityA = new SecuritySettingsService(storeA, new ObjectMapper());
        securityB = new SecuritySettingsService(storeB, new ObjectMapper());
        passkeyA = new PasskeySettingsService(storeA, new ObjectMapper());
        passkeyB = new PasskeySettingsService(storeB, new ObjectMapper());
        txA = new TransactionTemplate(new DataSourceTransactionManager(sourceA));
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private static SecuritySettings tightenedSecurity() {
        return new SecuritySettings(16, true, true, true, false, 20, 100, 8, 50, 300, 60, 8, 8, 4);
    }

    private static PasskeySettings enabledPasskeys() {
        return new PasskeySettings(
                true,
                true,
                true,
                "example.test",
                "Replica QA",
                List.of("https://example.test"),
                120);
    }

    private void saveChanged() {
        securityA.save(tightenedSecurity(), "qa", 0, "Policy visibility QA");
        passkeyA.save(enabledPasskeys(), "qa", 0, "Policy visibility QA");
    }

    private void assertDefaults(SecuritySettingsService security, PasskeySettingsService passkey) {
        assertEquals(SecuritySettings.defaults().policy(), security.currentPolicy());
        assertFalse(passkey.currentPolicy().enabled());
        assertEquals(PasskeySettings.defaults().rpId(), passkey.currentPolicy().rpId());
    }

    private void assertChanged(SecuritySettingsService security, PasskeySettingsService passkey) {
        assertEquals(tightenedSecurity().policy(), security.currentPolicy());
        assertTrue(passkey.currentPolicy().enabled());
        assertEquals("example.test", passkey.currentPolicy().rpId());
    }

    @Test
    void independentlyWarmedInstancesSeeCommittedPolicyWithoutExpiryWait() {
        assertDefaults(securityA, passkeyA);
        assertDefaults(securityB, passkeyB);
        txA.executeWithoutResult(status -> saveChanged());
        // 不先调用 read()：旧实现的 read() 会刷新缓存并掩盖 currentPolicy() 的缺陷。
        // Do not call read() first: the old implementation refreshes its cache there.
        assertChanged(securityB, passkeyB);
        assertChanged(securityA, passkeyA);
        assertEquals(1L, storeB.find("security").orElseThrow().version());
        assertEquals(1L, storeB.find("passkey").orElseThrow().version());
    }

    @Test
    void rollbackDoesNotPublishUncommittedPolicyToEitherInstance() {
        assertDefaults(securityA, passkeyA);
        assertDefaults(securityB, passkeyB);
        txA.executeWithoutResult(
                status -> {
                    saveChanged();
                    assertChanged(securityA, passkeyA);
                    // 独立数据源 B 不加入 A 的线程绑定事务，必须仍观察已提交默认值。
                    // Independent source B does not join A's thread-bound transaction.
                    assertDefaults(securityB, passkeyB);
                    status.setRollbackOnly();
                });
        assertDefaults(securityA, passkeyA);
        assertDefaults(securityB, passkeyB);
        assertTrue(storeA.find("security").isEmpty());
        assertTrue(storeB.find("passkey").isEmpty());
    }

    @Test
    void readingUncommittedPolicyDoesNotLeakIntoLaterRequests() {
        txA.executeWithoutResult(
                status -> {
                    saveChanged();
                    securityA.read();
                    passkeyA.read();
                    assertChanged(securityA, passkeyA);
                    status.setRollbackOnly();
                });
        assertDefaults(securityA, passkeyA);
        assertDefaults(securityB, passkeyB);
    }
}
