/**
 * 以同一数据库锁行协调认证共享读取和策略排他写入，避免最终认证检查与并发策略变更交错。 Coordinates shared authentication reads and exclusive
 * policy writes through one database lock row, preventing final authentication checks from racing
 * policy changes.
 */
package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 通过短时 PostgreSQL 行锁，将策略变更与最终认证检查串行化。
 *
 * <p>Serializes policy mutations with final authentication checks using one short PostgreSQL row
 * lock.
 */
@Component
public final class IdentityAuthenticationPolicyWriteLock implements AuthenticationPolicyWriteLock {
    private final JdbcClient jdbc;

    public IdentityAuthenticationPolicyWriteLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockForAuthentication() {
        jdbc.sql("SELECT id FROM platform_authentication_policy_lock WHERE id=1 FOR SHARE")
                .query(Integer.class)
                .single();
    }

    @Override
    public void lockForUpdate() {
        jdbc.sql("SELECT id FROM platform_authentication_policy_lock WHERE id=1 FOR UPDATE")
                .query(Integer.class)
                .single();
    }
}
