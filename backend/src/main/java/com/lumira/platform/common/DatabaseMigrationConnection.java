package com.lumira.platform.common;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 将迁移连接保留在宿主配置中，业务与插件默认注入仅含 DML 权限的数据源。 Keep migration connections in host configuration; business
 * and plugin injection receives the DML data source.
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseMigrationConnection {
    private final DataSource source;
    private final DataSource runtime;

    public DatabaseMigrationConnection(
            DataSource runtime,
            @Value("${spring.datasource.url}") String url,
            @Value("${LUMIRA_DB_MIGRATION_USER:}") String user,
            @Value("${LUMIRA_DB_MIGRATION_PASSWORD:}") String password,
            @Value("${LUMIRA_DB_REQUIRE_UNPRIVILEGED:false}") boolean required) {
        this.runtime = runtime;
        if (user.isBlank() != password.isBlank() || (required && user.isBlank())) {
            throw new IllegalStateException("Separate migration database credentials are required");
        }
        // 开发兼容仅在非强制模式保留；生产不回退到业务连接执行 DDL。
        // Development fallback is allowed only outside enforced mode; production never uses runtime
        // DDL.
        if (user.isBlank()) {
            source = runtime;
        } else {
            var migration = new DriverManagerDataSource(url, user, password);
            var properties = new java.util.Properties();
            properties.setProperty("connectTimeout", "10");
            properties.setProperty("socketTimeout", "600");
            migration.setConnectionProperties(properties);
            source = migration;
        }
    }

    public DataSource source() {
        return source;
    }

    // 历史表只允许迁移身份写入，默认业务表授权不能扩大到校验记录。
    // Only the migration identity may write history; default business grants must not cover
    // checksums.
    public void protectHistory(String table) {
        if (source == runtime) return;
        if (!table.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("Invalid history table");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        String role =
                new org.springframework.jdbc.core.JdbcTemplate(runtime)
                        .queryForObject("SELECT current_user", String.class);
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table))) {
            jdbc.execute(
                    "REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE public.\""
                            + table
                            + "\" FROM \""
                            + role.replace("\"", "\"\"")
                            + "\"");
            // 不擅改旧组授权；继承或 PUBLIC 写权限残留时阻断并要求 DBA 修复。
            // Do not alter legacy group grants; fail closed when inherited or PUBLIC writes remain.
            Boolean writable =
                    new org.springframework.jdbc.core.JdbcTemplate(runtime)
                            .queryForObject(
                                    "SELECT has_table_privilege(current_user, ?, 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",
                                    Boolean.class,
                                    "public." + table);
            if (!Boolean.FALSE.equals(writable)) {
                throw new IllegalStateException(
                        "Runtime role retains inherited migration history write privileges");
            }
        }
    }

    @Bean
    FlywayConfigurationCustomizer migrationDataSource() {
        return configuration -> configuration.dataSource(source);
    }
}
