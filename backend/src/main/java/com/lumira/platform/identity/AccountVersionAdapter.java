package com.lumira.platform.identity;

import com.lumira.platform.common.UserDirectoryPort;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Identity account version/locking contract, available independently of directory administration.
 */
@Repository
public class AccountVersionAdapter implements UserDirectoryPort {
    private final JdbcClient jdbc;

    public AccountVersionAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long version(UUID id) {
        return jdbc.sql(
                        "SELECT version FROM platform_user WHERE id=:id AND deleted_at IS NULL FOR UPDATE")
                .param("id", id)
                .query(Long.class)
                .optional()
                .orElseThrow(
                        () ->
                                new org.springframework.web.server.ResponseStatusException(
                                        org.springframework.http.HttpStatus.NOT_FOUND));
    }

    @Override
    public long versionSnapshot(UUID id) {
        return jdbc.sql("SELECT version FROM platform_user WHERE id=:id AND deleted_at IS NULL")
                .param("id", id)
                .query(Long.class)
                .optional()
                .orElseThrow(
                        () ->
                                new org.springframework.web.server.ResponseStatusException(
                                        org.springframework.http.HttpStatus.NOT_FOUND));
    }
}
