package com.lumira.platform.identity;

import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.identity.api.MfaRecoveryTicketPort;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable, purpose-isolated ticket storage; the clear ticket exists only in the issue response. */
@Service
public class MfaRecoveryTicketService implements MfaRecoveryTicketPort {
    private static final int LIFETIME_SECONDS = 900;

    private final JdbcClient jdbc;
    private final SecretKeys keys;
    private final SecureRandom random = new SecureRandom();

    public MfaRecoveryTicketService(JdbcClient jdbc, SecretKeys keys) {
        this.jdbc = jdbc;
        this.keys = keys;
    }

    @Override
    public Issued issue(UUID userId) {
        requireTransaction();
        if (userId == null) throw new IllegalArgumentException("userId");
        var account =
                jdbc.sql(
                                """
                        SELECT u.factor_credential_revision
                          FROM platform_user u
                         WHERE u.id=:id AND u.enabled AND u.deleted_at IS NULL
                           AND EXISTS (SELECT 1 FROM platform_second_factor_requirement r
                                        WHERE r.user_id=u.id AND r.recovery_pending)
                         FOR UPDATE
                        """)
                        .param("id", userId)
                        .query((rs, row) -> rs.getObject("factor_credential_revision", UUID.class))
                        .optional()
                        .orElseThrow(() -> new IllegalStateException("mfaRecoveryNotPending"));

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = HexFormat.of().formatHex(bytes);
        java.util.Arrays.fill(bytes, (byte) 0);
        UUID id = UUID.randomUUID();
        String digest = digest(userId, value);
        jdbc.sql(
                        """
                        UPDATE platform_mfa_recovery_ticket
                           SET revoked_at=CURRENT_TIMESTAMP
                         WHERE user_id=:user AND consumed_at IS NULL AND revoked_at IS NULL
                        """)
                .param("user", userId)
                .update();
        Instant expiresAt = Instant.now().plusSeconds(LIFETIME_SECONDS);
        jdbc.sql(
                        """
                        INSERT INTO platform_mfa_recovery_ticket
                            (id,user_id,token_digest,factor_credential_revision,expires_at)
                        VALUES (:id,:user,:digest,:revision,:expires)
                        """)
                .param("id", id)
                .param("user", userId)
                .param("digest", digest)
                .param("revision", account)
                .param("expires", java.sql.Timestamp.from(expiresAt))
                .update();
        return new Issued(value, expiresAt);
    }

    @Override
    public ConsumeResult consume(UUID userId, String value) {
        requireTransaction();
        if (userId == null) return ConsumeResult.INVALID;
        // Lock the account first, following the credential-change lock order used by the caller.
        var currentRevision =
                jdbc.sql(
                                """
                        SELECT factor_credential_revision
                          FROM platform_user
                         WHERE id=:id AND enabled AND deleted_at IS NULL
                         FOR UPDATE
                        """)
                        .param("id", userId)
                        .query((rs, row) -> rs.getObject("factor_credential_revision", UUID.class))
                        .optional();
        if (currentRevision.isEmpty()) return ConsumeResult.INVALID;
        int failures =
                jdbc.sql(
                                """
                        SELECT CASE WHEN failure_day=CURRENT_DATE THEN failures ELSE 0 END
                          FROM platform_mfa_recovery_request_rate
                         WHERE user_id=:user FOR UPDATE
                        """)
                        .param("user", userId)
                        .query(Integer.class)
                        .optional()
                        .orElse(0);
        if (failures >= 20) return ConsumeResult.LOCKED;
        boolean wellFormed = value != null && value.matches("[0-9a-f]{64}");
        boolean consumed =
                wellFormed
                        && jdbc.sql(
                                        """
                        UPDATE platform_mfa_recovery_ticket t
                           SET consumed_at=CURRENT_TIMESTAMP
                          FROM platform_second_factor_requirement r
                         WHERE t.user_id=:user AND r.user_id=t.user_id AND r.recovery_pending
                           AND t.factor_credential_revision=:revision
                           AND t.token_digest=:digest AND t.expires_at>CURRENT_TIMESTAMP
                           AND t.consumed_at IS NULL AND t.revoked_at IS NULL
                         RETURNING t.id
                        """)
                                .param("user", userId)
                                .param("revision", currentRevision.get())
                                .param("digest", digest(userId, value))
                                .query(UUID.class)
                                .optional()
                                .isPresent();
        if (consumed) {
            jdbc.sql(
                            """
                            UPDATE platform_mfa_recovery_request_rate
                               SET failures=0,failure_day=CURRENT_DATE
                             WHERE user_id=:user
                            """)
                    .param("user", userId)
                    .update();
            return ConsumeResult.CONSUMED;
        }
        int failuresAfter =
                jdbc.sql(
                                """
                        INSERT INTO platform_mfa_recovery_request_rate(user_id,failures,failure_day)
                        VALUES (:user,1,CURRENT_DATE)
                        ON CONFLICT(user_id) DO UPDATE
                            SET failures=CASE
                                  WHEN platform_mfa_recovery_request_rate.failure_day=CURRENT_DATE
                                  THEN LEAST(20,platform_mfa_recovery_request_rate.failures+1)
                                  ELSE 1
                                END,
                                failure_day=CURRENT_DATE
                        RETURNING failures
                        """)
                        .param("user", userId)
                        .query(Integer.class)
                        .single();
        return failuresAfter >= 20 ? ConsumeResult.LOCKED : ConsumeResult.INVALID;
    }

    @Override
    public void revoke(UUID userId) {
        requireTransaction();
        jdbc.sql(
                        """
                        UPDATE platform_mfa_recovery_ticket
                           SET revoked_at=CURRENT_TIMESTAMP
                         WHERE user_id=:user AND consumed_at IS NULL AND revoked_at IS NULL
                        """)
                .param("user", userId)
                .update();
    }

    private String digest(UUID userId, String value) {
        return keys.require()
                .authenticate(
                        "mfa-recovery-ticket:" + userId,
                        value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("mfaRecoveryTransactionRequired");
    }
}
