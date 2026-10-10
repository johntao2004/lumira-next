package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.FixedWindowBuckets;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class IdentityRateLimiterDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private AnnotationConfigApplicationContext context;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_identity_rate_" + java.util.UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE platform_rate_bucket(bucket_key text PRIMARY KEY, window_start timestamptz NOT NULL, attempts integer NOT NULL)");

        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(JdbcClient.class, () -> JdbcClient.create(dataSource));
        context.registerBean(
                FixedWindowBuckets.class,
                () -> new FixedWindowBuckets(context.getBean(JdbcClient.class)));
        context.registerBean(
                PasswordChangeLimiter.class,
                () -> new PasswordChangeLimiter(context.getBean(FixedWindowBuckets.class)));
        context.registerBean(
                PasskeyRateLimiter.class,
                () ->
                        new PasskeyRateLimiter(
                                context.getBean(FixedWindowBuckets.class), 3, 2, 1, 3, 2, 1));
        context.register(TransactionConfiguration.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void passkeyBudgetsConsumeGlobalThenSourceThenExistingSessionForEachOperation() {
        PasskeyRateLimiter limiter = context.getBean(PasskeyRateLimiter.class);

        assertTrue(limiter.allow("source-a", "session-a", false));
        assertFalse(limiter.allow("source-a", "session-a", false));
        assertFalse(limiter.allow("source-a", "session-b", false));
        assertFalse(limiter.allow("source-b", "session-b", false));

        assertTrue(limiter.allow("source-a", "session-a", true));
        assertFalse(limiter.allow("source-a", "session-a", true));
        assertFalse(limiter.allow("source-a", "session-b", true));
        assertFalse(limiter.allow("source-b", "session-b", true));

        assertEquals(
                4,
                jdbc.queryForObject(
                        "SELECT attempts FROM platform_rate_bucket WHERE bucket_key=?",
                        Integer.class,
                        FixedWindowBuckets.key("pk-issue", "global")));
        assertEquals(
                4,
                jdbc.queryForObject(
                        "SELECT attempts FROM platform_rate_bucket WHERE bucket_key=?",
                        Integer.class,
                        FixedWindowBuckets.key("pk-verify", "global")));
    }

    @Test
    void passwordAndPasskeyBucketsSurviveTheCallerTransactionRollingBack() {
        var manager = context.getBean(PlatformTransactionManager.class);
        var transaction = new TransactionTemplate(manager);
        PasswordChangeLimiter passwordLimiter = context.getBean(PasswordChangeLimiter.class);
        PasskeyRateLimiter passkeyLimiter = context.getBean(PasskeyRateLimiter.class);

        transaction.executeWithoutResult(
                status -> {
                    assertTrue(passwordLimiter.allow("rollback-user"));
                    assertTrue(passkeyLimiter.allow("rollback-source", "rollback-session", false));
                    status.setRollbackOnly();
                });

        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT attempts FROM platform_rate_bucket WHERE bucket_key=?",
                        Integer.class,
                        FixedWindowBuckets.key("password-chg", "rollback-user")));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT attempts FROM platform_rate_bucket WHERE bucket_key=?",
                        Integer.class,
                        FixedWindowBuckets.key("pk-issue", "global")));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT attempts FROM platform_rate_bucket WHERE bucket_key=?",
                        Integer.class,
                        FixedWindowBuckets.key("pk-issue-src", "rollback-source")));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT attempts FROM platform_rate_bucket WHERE bucket_key=?",
                        Integer.class,
                        FixedWindowBuckets.key("pk-issue-sess", "rollback-session")));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {}
}
