package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Serializes policy mutations with final authentication checks using one short PostgreSQL row lock.
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
