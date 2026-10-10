package com.lumira.platform.settings;

import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.common.SecretStorePort;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal only: typed services must enforce authorization and audit in the enclosing transaction.
 * Secrets are deliberately excluded from SettingStore payloads and revision responses.
 */
@Repository
public class SecretStore implements SecretStorePort {
    private final JdbcClient jdbc;
    private final SecretKeys keys;

    public SecretStore(JdbcClient jdbc, SecretKeys keys) {
        this.jdbc = jdbc;
        this.keys = keys;
    }

    private static void context(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_:/.-]{1,512}"))
            throw new IllegalArgumentException("Invalid secret context");
    }

    @Override
    public SecretStorePort.State state(String context) {
        context(context);
        return jdbc.sql(
                        "SELECT version,ciphertext IS NOT NULL AS configured FROM platform_secret WHERE secret_context=:context")
                .param("context", context)
                .query(
                        (rs, n) ->
                                new SecretStorePort.State(
                                        rs.getLong("version"), rs.getBoolean("configured")))
                .optional()
                .orElse(new SecretStorePort.State(0, false));
    }

    /** Returns owned plaintext bytes for short-lived use; the caller should clear them. */
    @Override
    public Optional<byte[]> reveal(String context) {
        context(context);
        return jdbc.sql(
                        "SELECT ciphertext FROM platform_secret WHERE secret_context=:context AND ciphertext IS NOT NULL")
                .param("context", context)
                .query(String.class)
                .optional()
                .map(value -> keys.require().decrypt(context, value));
    }

    @Transactional
    @Override
    public SecretStorePort.State save(String context, long expectedVersion, byte[] plaintext) {
        context(context);
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidVersion");
        String ciphertext = plaintext == null ? null : keys.require().encrypt(context, plaintext);
        int updated =
                jdbc.sql(
                                "UPDATE platform_secret SET ciphertext=:ciphertext,version=version+1,updated_at=now() WHERE secret_context=:context AND version=:version")
                        .param("context", context)
                        .param("ciphertext", ciphertext)
                        .param("version", expectedVersion)
                        .update();
        if (updated == 0 && expectedVersion == 0)
            updated =
                    jdbc.sql(
                                    "INSERT INTO platform_secret(secret_context,ciphertext,version) VALUES(:context,:ciphertext,1) ON CONFLICT DO NOTHING")
                            .param("context", context)
                            .param("ciphertext", ciphertext)
                            .update();
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        return new SecretStorePort.State(expectedVersion + 1, plaintext != null);
    }
}
