package com.lumira.platform.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence for discoverable WebAuthn credentials. */
@Repository
public class PasskeyCredentialStore {
    public record Credential(
            UUID id,
            UUID userId,
            byte[] userHandle,
            byte[] credentialId,
            byte[] publicKeyCose,
            long signCount,
            String transports,
            boolean backupEligible,
            boolean backupState,
            String label,
            OffsetDateTime lastUsedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    private static final String SELECT =
            """
        SELECT id,user_id,user_handle,credential_id,public_key_cose,sign_count,transports,
               backup_eligible,backup_state,label,last_used_at,created_at,updated_at
          FROM platform_passkey_credential
        """;
    private final JdbcClient jdbc;

    public PasskeyCredentialStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Credential> list(UUID userId) {
        return jdbc.sql(SELECT + " WHERE user_id=:userId ORDER BY created_at,id")
                .param("userId", userId)
                .query(this::map)
                .list();
    }

    /** Locks the credential while its signature counter is checked and advanced. */
    public Optional<Credential> findByCredentialIdForUpdate(byte[] credentialId) {
        return jdbc.sql(SELECT + " WHERE credential_id=:credentialId FOR UPDATE")
                .param("credentialId", credentialId)
                .query(this::map)
                .optional();
    }

    public Optional<UUID> userIdByCredentialId(byte[] credentialId) {
        return jdbc.sql(
                        "SELECT user_id FROM platform_passkey_credential WHERE credential_id=:credentialId")
                .param("credentialId", credentialId)
                .query(UUID.class)
                .optional();
    }

    public boolean existsByCredentialId(byte[] credentialId) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_passkey_credential WHERE credential_id=:credentialId)")
                .param("credentialId", credentialId)
                .query(Boolean.class)
                .single();
    }

    public Credential insert(
            UUID userId,
            byte[] userHandle,
            byte[] credentialId,
            byte[] publicKeyCose,
            long signCount,
            String transports,
            boolean backupEligible,
            boolean backupState,
            String label) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
                INSERT INTO platform_passkey_credential
                    (id,user_id,user_handle,credential_id,public_key_cose,sign_count,transports,
                     backup_eligible,backup_state,label)
                VALUES (:id,:userId,:userHandle,:credentialId,:publicKeyCose,:signCount,:transports,
                        :backupEligible,:backupState,:label)
                """)
                .param("id", id)
                .param("userId", userId)
                .param("userHandle", userHandle)
                .param("credentialId", credentialId)
                .param("publicKeyCose", publicKeyCose)
                .param("signCount", signCount)
                .param("transports", transports)
                .param("backupEligible", backupEligible)
                .param("backupState", backupState)
                .param("label", label)
                .update();
        return jdbc.sql(SELECT + " WHERE id=:id").param("id", id).query(this::map).single();
    }

    public Optional<Credential> rename(UUID id, UUID userId, String label) {
        return jdbc.sql(
                        """
                UPDATE platform_passkey_credential SET label=:label,updated_at=now()
                 WHERE id=:id AND user_id=:userId
                RETURNING id,user_id,user_handle,credential_id,public_key_cose,sign_count,transports,
                          backup_eligible,backup_state,label,last_used_at,created_at,updated_at
                """)
                .param("id", id)
                .param("userId", userId)
                .param("label", label)
                .query(this::map)
                .optional();
    }

    public boolean delete(UUID id, UUID userId) {
        return jdbc.sql("DELETE FROM platform_passkey_credential WHERE id=:id AND user_id=:userId")
                        .param("id", id)
                        .param("userId", userId)
                        .update()
                == 1;
    }

    /** Advances the counter only when the authenticator supplied a newer value. */
    public boolean updateUsage(UUID id, UUID userId, long signCount, boolean backupState) {
        return jdbc.sql(
                                """
                UPDATE platform_passkey_credential
                   SET sign_count=GREATEST(sign_count,:signCount), backup_state=:backupState,
                       last_used_at=now(),updated_at=now()
                 WHERE id=:id AND user_id=:userId
                """)
                        .param("id", id)
                        .param("userId", userId)
                        .param("signCount", signCount)
                        .param("backupState", backupState)
                        .update()
                == 1;
    }

    private Credential map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Credential(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getBytes("user_handle"),
                rs.getBytes("credential_id"),
                rs.getBytes("public_key_cose"),
                rs.getLong("sign_count"),
                rs.getString("transports"),
                rs.getBoolean("backup_eligible"),
                rs.getBoolean("backup_state"),
                rs.getString("label"),
                rs.getObject("last_used_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
