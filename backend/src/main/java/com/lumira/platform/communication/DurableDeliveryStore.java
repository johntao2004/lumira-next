package com.lumira.platform.communication;

import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.common.TerminalDeliveryQueue;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared durable-delivery state machine for provider-specific queues.
 *
 * <p>The table name and payload context are supplied by module-owned adapters and validated as
 * identifiers. No request value is ever interpolated into the SQL. Mail and SMS therefore share the
 * same lease, fencing, retry and retention semantics while keeping their payload and gateway types
 * private.
 */
public abstract class DurableDeliveryStore<P> implements TerminalDeliveryQueue {
    public record Claim(UUID id, UUID token, int attempts) {}

    public record Recovery(UUID id, String state) {}

    public record Status(
            UUID id, String state, int attempts, String lastResult, OffsetDateTime expiresAt) {}

    private final JdbcClient jdbc;
    private final SecretKeys keys;
    private final ObjectMapper mapper;
    private final String channel;
    private final String table;
    private final Class<P> payloadType;

    protected DurableDeliveryStore(
            JdbcClient jdbc,
            SecretKeys keys,
            ObjectMapper mapper,
            String channel,
            String table,
            Class<P> payloadType) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.channel = validateIdentifier(channel, "channel");
        this.table = validateIdentifier(table, "table");
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
    }

    private static String validateIdentifier(String value, String name) {
        if (value == null || !value.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid delivery " + name);
        }
        return value;
    }

    private String context(UUID id) {
        return channel + ":" + id + ":payload";
    }

    /** First enqueue wins; repeat IDs never replace an existing message or deadline. */
    @Transactional
    public boolean enqueue(UUID id, P payload, int lifetimeSeconds) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(payload, "payload");
        if (lifetimeSeconds < 1 || lifetimeSeconds > 86400) {
            throw new IllegalArgumentException("Invalid " + channel + " lifetime");
        }
        byte[] bytes = mapper.writeValueAsBytes(payload);
        String cipher;
        try {
            if (bytes.length > 65536) {
                throw new IllegalArgumentException(channel + " payload too large");
            }
            cipher = keys.require().encrypt(context(id), bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        return jdbc.sql(
                                "INSERT INTO "
                                        + table
                                        + "(id,payload,expires_at) VALUES(:id,:payload,now()+:lifetime*interval '1 second') ON CONFLICT(id) DO NOTHING")
                        .param("id", id)
                        .param("payload", cipher)
                        .param("lifetime", lifetimeSeconds)
                        .update()
                == 1;
    }

    @Transactional(readOnly = true)
    public Optional<Status> status(UUID id) {
        return jdbc.sql(
                        "SELECT id,state,attempts,last_result,expires_at FROM "
                                + table
                                + " WHERE id=:id")
                .param("id", id)
                .query(Status.class)
                .optional();
    }

    @Transactional
    public Optional<Claim> claim() {
        UUID token = UUID.randomUUID();
        return jdbc.sql(
                        """
            WITH candidate AS (
              SELECT id FROM %s
              WHERE state='PENDING' AND available_at<=now() AND expires_at>now() AND attempts<5
              ORDER BY available_at,created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
            ) UPDATE %s m SET state='LEASED',attempts=attempts+1,lease_token=:token,
              lease_until=now()+interval '2 minutes',updated_at=now()
              FROM candidate c WHERE m.id=c.id RETURNING m.id,m.lease_token AS token,m.attempts
            """
                                .formatted(table, table))
                .param("token", token)
                .query(Claim.class)
                .optional();
    }

    /** Load before beginSending; no DB transaction surrounds subsequent provider IO. */
    @Transactional(readOnly = true)
    public Optional<P> message(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return jdbc.sql(
                        "SELECT payload FROM "
                                + table
                                + " WHERE id=:id AND lease_token=:token AND state='LEASED' AND lease_until>now() AND expires_at>now()")
                .param("id", claim.id())
                .param("token", claim.token())
                .query(String.class)
                .optional()
                .map(
                        cipher -> {
                            byte[] bytes = keys.require().decrypt(context(claim.id()), cipher);
                            try {
                                return mapper.readValue(bytes, payloadType);
                            } finally {
                                Arrays.fill(bytes, (byte) 0);
                            }
                        });
    }

    @Transactional
    public boolean beginSending(Claim claim) {
        return jdbc.sql(
                                "UPDATE "
                                        + table
                                        + " SET state='SENDING',updated_at=now() WHERE id=:id AND lease_token=:token AND state='LEASED' AND lease_until>now() AND expires_at>now()")
                        .param("id", claim.id())
                        .param("token", claim.token())
                        .update()
                == 1;
    }

    /** Extend only the current worker's live lease; a lost lease fences completion. */
    @Transactional
    public boolean renewLease(Claim claim) {
        return jdbc.sql(
                                "UPDATE "
                                        + table
                                        + " SET lease_until=now()+interval '2 minutes',updated_at=now() WHERE id=:id AND lease_token=:token AND state='SENDING' AND lease_until>now() AND expires_at>now()")
                        .param("id", claim.id())
                        .param("token", claim.token())
                        .update()
                == 1;
    }

    @Transactional
    public boolean failBeforeSending(Claim claim) {
        return jdbc.sql(
                                "UPDATE "
                                        + table
                                        + " SET state='FAILED',last_result='CONFIGURATION_UNAVAILABLE',lease_token=NULL,lease_until=NULL,updated_at=now() WHERE id=:id AND lease_token=:token AND state='LEASED'")
                        .param("id", claim.id())
                        .param("token", claim.token())
                        .update()
                == 1;
    }

    /** Complete a provider attempt using the shared result names used by both gateways. */
    @Transactional
    protected boolean completeResult(Claim claim, String resultName) {
        String target =
                switch (Objects.requireNonNull(resultName, "resultName")) {
                    case "ACCEPTED" -> "ACCEPTED";
                    case "DELIVERY_UNCERTAIN" -> "UNCERTAIN";
                    case "AUTHENTICATION_FAILED", "PROVIDER_REJECTED" -> "FAILED";
                    case "CONNECTION_FAILED" -> "PENDING";
                    default -> throw new IllegalArgumentException("Unknown delivery result");
                };
        return jdbc.sql(
                                """
            UPDATE %s SET
              state=CASE WHEN :target='PENDING' THEN CASE WHEN expires_at<=now() THEN 'EXPIRED' WHEN attempts>=5 THEN 'FAILED' ELSE 'PENDING' END ELSE :target END,
              available_at=CASE WHEN :target='PENDING' THEN now()+least(960,60*power(2,attempts-1))*interval '1 second' ELSE available_at END,
              last_result=:result,lease_token=NULL,lease_until=NULL,updated_at=now()
            WHERE id=:id AND lease_token=:token AND state='SENDING'
            """
                                        .formatted(table))
                        .param("target", target)
                        .param("result", resultName)
                        .param("id", claim.id())
                        .param("token", claim.token())
                        .update()
                == 1;
    }

    /** A worker may have died after provider submission; never requeue expired SENDING leases. */
    @Transactional
    public List<Recovery> recoverExpiredEntries() {
        return jdbc.sql(
                        """
            WITH expired AS (
              SELECT id FROM %s
              WHERE (state IN ('LEASED','SENDING') AND lease_until<=now()) OR (state='PENDING' AND expires_at<=now())
              ORDER BY updated_at,id FOR UPDATE SKIP LOCKED LIMIT 1000
            ) UPDATE %s m SET
              state=CASE WHEN state='SENDING' THEN 'UNCERTAIN' WHEN expires_at<=now() THEN 'EXPIRED' WHEN attempts>=5 THEN 'FAILED' ELSE 'PENDING' END,
              last_result=CASE WHEN state='SENDING' THEN 'DELIVERY_UNCERTAIN' ELSE 'LEASE_EXPIRED' END,
              lease_token=NULL,lease_until=NULL,updated_at=now()
            FROM expired e WHERE m.id=e.id RETURNING m.id,m.state
            """
                                .formatted(table, table))
                .query(Recovery.class)
                .list();
    }

    @Override
    @Transactional
    public int purgeTerminal(Duration retention, int batchSize) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException(channel + " retention must be positive");
        }
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException(
                    channel + " retention batch must be between 1 and 10000");
        }
        var cutoff = OffsetDateTime.now().minus(retention);
        return jdbc.sql(
                        """
            WITH expired AS (
              SELECT id FROM %s
              WHERE state IN ('ACCEPTED','FAILED','UNCERTAIN','EXPIRED')
                AND updated_at<:cutoff
              ORDER BY updated_at,id FOR UPDATE SKIP LOCKED LIMIT :limit
            ) DELETE FROM %s m USING expired e WHERE m.id=e.id
            """
                                .formatted(table, table))
                .param("cutoff", cutoff)
                .param("limit", batchSize)
                .update();
    }
}
