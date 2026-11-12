package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.plugins.authenticationconsole.PasskeySettingsService;
import com.lumira.plugins.authenticationconsole.SecuritySettingsService;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class SettingsCacheTransactionDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private TransactionTemplate tx;
    private SettingStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        var url = System.getenv("LUMIRA_TEST_DB_URL");
        var user = System.getenv("LUMIRA_TEST_DB_USER");
        var password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_policy_cache_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        for (var table : java.util.List.of("platform_setting", "platform_setting_revision")) {
            jdbc.execute(TestSchema.table(table));
            for (var index : TestSchema.indexes(table)) jdbc.execute(index);
        }
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        store =
                new SettingStore(
                        JdbcClient.create(source),
                        mock(AdministrationPort.class),
                        mock(AuditPort.class));
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void securityRollbackNeverPublishesTheUncommittedPolicyToAnotherRequest() {
        var reader = new SecurityPolicyReader(store, mapper);
        var service = new SecuritySettingsService(store, mapper, reader);
        var before = reader.current();
        var map = mapper.convertValue(before, java.util.Map.class);
        map.put("passwordMinLength", 16);
        var changed = mapper.convertValue(map, SecuritySettings.class);
        tx.executeWithoutResult(
                status -> {
                    service.save(changed, "fixture", 0, "QA rollback");
                    assertEquals(changed, reader.current(), "Writer must see its own transaction");
                    assertEquals(
                            before,
                            other(reader::current),
                            "Another request must not observe an uncommitted policy");
                    assertEquals(changed, service.read().data());
                    status.setRollbackOnly();
                });
        assertEquals(before, reader.current());
        assertEmptyStorage();
    }

    @Test
    void passkeyRollbackNeverPublishesTheUncommittedPolicyToAnotherRequest() {
        var reader = new PasskeyPolicyReader(store, mapper);
        var service = new PasskeySettingsService(store, mapper, reader);
        var before = reader.current();
        var changed =
                new PasskeySettings(
                        before.enabled(),
                        before.passwordlessEnabled(),
                        before.selfBindingEnabled(),
                        before.rpId(),
                        "QA changed RP",
                        before.allowedOrigins(),
                        before.challengeTtlSeconds());
        tx.executeWithoutResult(
                status -> {
                    service.save(changed, "fixture", 0, "QA rollback");
                    assertEquals(changed, reader.current());
                    assertEquals(before, other(reader::current));
                    assertEquals(changed, service.read().data());
                    status.setRollbackOnly();
                });
        assertEquals(before, reader.current());
        assertEmptyStorage();
    }

    @Test
    void securityCommitInvalidatesTheOldWarmCacheImmediately() {
        var reader = new SecurityPolicyReader(store, mapper);
        var service = new SecuritySettingsService(store, mapper, reader);
        var before = reader.current();
        var map = mapper.convertValue(before, java.util.Map.class);
        map.put("passwordMinLength", 18);
        var changed = mapper.convertValue(map, SecuritySettings.class);
        tx.executeWithoutResult(
                status -> {
                    service.save(changed, "fixture", 0, "QA commit");
                    assertEquals(before, other(reader::current));
                });
        assertEquals(changed, other(reader::current));
        assertEquals(1L, store.find("security").orElseThrow().version());
    }

    @Test
    void passkeyCommitInvalidatesTheOldWarmCacheImmediately() {
        var reader = new PasskeyPolicyReader(store, mapper);
        var service = new PasskeySettingsService(store, mapper, reader);
        var before = reader.current();
        var changed =
                new PasskeySettings(
                        before.enabled(),
                        before.passwordlessEnabled(),
                        before.selfBindingEnabled(),
                        before.rpId(),
                        "QA committed RP",
                        before.allowedOrigins(),
                        before.challengeTtlSeconds());
        tx.executeWithoutResult(
                status -> {
                    service.save(changed, "fixture", 0, "QA commit");
                    assertEquals(before, other(reader::current));
                });
        assertEquals(changed, other(reader::current));
        assertEquals(1L, store.find("passkey").orElseThrow().version());
    }

    private <T> T other(java.util.function.Supplier<T> read) {
        try {
            return CompletableFuture.supplyAsync(read).get(5, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private void assertEmptyStorage() {
        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM platform_setting", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_setting_revision", Integer.class));
    }
}
