package com.lumira.platform.common;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 迁移前校验两种身份，业务连接不得拥有 schema 或切换到拥有者。 Verify both identities before migration; runtime connections
 * must not own schemas or assume an owner role.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "LUMIRA_DB_REQUIRE_UNPRIVILEGED", havingValue = "true")
public class DatabasePrivilegeConfiguration {
    @Bean
    FlywayMigrationStrategy unprivilegedDatabaseMigration(
            DataSource runtime, DatabaseMigrationConnection migration) {
        return flyway -> {
            verify(flyway.getConfiguration().getDataSource());
            verify(runtime);
            verifyRuntime(runtime);
            try {
                flyway.migrate();
            } finally {
                migration.protectHistory(flyway.getConfiguration().getTable());
            }
        };
    }

    // 直接授予 CREATE 和经成员关系获得所有者身份都必须拒绝。
    // Reject direct CREATE grants and ownership reachable through role membership.
    static void verifyRuntime(DataSource source) {
        Boolean unsafe =
                new JdbcTemplate(source)
                        .queryForObject(
                                """
            SELECT has_database_privilege(current_user, current_database(), 'CREATE')
                OR has_schema_privilege(current_user, 'public', 'CREATE')
                OR EXISTS (SELECT 1 FROM pg_namespace n WHERE n.nspname = 'public'
                    AND pg_has_role(current_user, n.nspowner, 'MEMBER'))
                OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public' AND pg_has_role(current_user,c.relowner,'MEMBER'))
            """,
                                Boolean.class);
        if (!Boolean.FALSE.equals(unsafe)) {
            throw new IllegalStateException(
                    "Runtime database role must not own or create application objects");
        }
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
