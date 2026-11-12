package com.lumira.platform.identity;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Minimal account insertion needed by public registration, independent of directory administration.
 */
@Repository
public class AccountRegistrationRepository {
    private final JdbcClient jdbc;

    public AccountRegistrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean create(UUID id, String username, String displayName, String hash) {
        return jdbc.sql(
                        "INSERT INTO platform_user(id,username,display_name,password_hash) VALUES(:id,:username,:displayName,:hash) ON CONFLICT(username) DO NOTHING RETURNING id")
                .param("id", id)
                .param("username", username)
                .param("displayName", displayName)
                .param("hash", hash)
                .query(UUID.class)
                .optional()
                .isPresent();
    }
}
