package com.lumira.platform.common;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 在生产迁移前拒绝集群特权，应用仍保留自身 schema 的迁移能力。 Reject cluster privileges before production migrations while
 * retaining application-schema DDL.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "LUMIRA_DB_REQUIRE_UNPRIVILEGED", havingValue = "true")
public class DatabasePrivilegeConfiguration {
    @Bean
    FlywayMigrationStrategy unprivilegedDatabaseMigration() {
        return flyway -> {
            verify(flyway.getConfiguration().getDataSource());
            flyway.migrate();
        };
    }

    // 成员关系也可能通过 SET ROLE 提权，不能只检查当前角色标志。
    // Membership may elevate via SET ROLE; checking only the current role flags is insufficient.
    static void verify(DataSource source) {
        Boolean unsafe =
                new JdbcTemplate(source)
                        .queryForObject(
                                """
                SELECT EXISTS (
                    SELECT 1 FROM pg_roles r
                    WHERE pg_has_role(current_user, r.oid, 'MEMBER')
                      AND (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole
                           OR r.rolreplication OR r.rolbypassrls
                           OR r.rolname LIKE 'pg\\_%' ESCAPE '\\')
                )
                """,
                                Boolean.class);
        if (!Boolean.FALSE.equals(unsafe)) {
            throw new IllegalStateException(
                    "Application database role has cluster privileges; use the documented non-superuser deployment role");
        }
    }
}
