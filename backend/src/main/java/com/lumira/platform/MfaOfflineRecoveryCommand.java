package com.lumira.platform;

import com.lumira.platform.common.SecretKeys;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Explicit, non-web recovery operation for the case where normal administrator delivery is
 * unavailable. It opens only a short JDBC transaction and never starts Spring or plugin workers.
 */
public final class MfaOfflineRecoveryCommand {
    private static final String FLAG = "--offline-mfa-recovery";
    private static final int TICKET_LIFETIME_SECONDS = 900;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(String url, String username, String password) throws SQLException;
    }

    private record Options(String username, String operator, String reason, Path ticketFile) {}

    private record Target(UUID id, String username, UUID factorRevision) {}

    private MfaOfflineRecoveryCommand() {}

    public static boolean requested(String[] args) {
        if (args == null) return false;
        for (String arg : args) if (FLAG.equals(arg)) return true;
        return false;
    }

    /** Runs before the Spring application context is created. Exit codes are stable for scripts. */
    public static int run(String[] args) {
        return run(
                args,
                System.getenv(),
                System.out,
                System.err,
                (url, username, password) -> {
                    Properties properties = new Properties();
                    properties.setProperty("user", username);
                    properties.setProperty("password", password);
                    properties.setProperty("connectTimeout", "5");
                    properties.setProperty("socketTimeout", "20");
                    return DriverManager.getConnection(url, properties);
                });
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            PrintStream output,
            PrintStream errors,
            ConnectionFactory connections) {
        Objects.requireNonNull(environment);
        Objects.requireNonNull(output);
        Objects.requireNonNull(errors);
        Objects.requireNonNull(connections);

        final Options options;
        try {
            options = parse(args);
            validateEnvironment(environment);
            validateTicketPath(options.ticketFile());
        } catch (IllegalArgumentException invalid) {
            errors.println("offlineMfaRecoveryInvalidArguments");
            return 2;
        }

        SecretKeys keys;
        try {
            keys =
                    new SecretKeys(
                            environment.get("LUMIRA_SECRET_KEYS"),
                            environment.getOrDefault("LUMIRA_SECRET_ACTIVE_KEY", "primary"));
            if (!keys.configured()) throw new IllegalStateException("secretKeyUnavailable");
        } catch (RuntimeException unavailable) {
            errors.println("offlineMfaRecoverySecretKeyUnavailable");
            return 3;
        }

        String ticket = randomTicket();
        Path createdFile = null;
        boolean commitAttempted = false;
        Connection connection = null;
        try {
            connection =
                    connections.open(
                            environment.get("LUMIRA_DB_URL"),
                            environment.get("LUMIRA_DB_USER"),
                            environment.get("LUMIRA_DB_PASSWORD"));
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '5s'");
                statement.execute("SET LOCAL statement_timeout = '15s'");
            }
            ensureSchemaReady(connection);
            lockGlobalPolicy(connection);
            Target target = lockTarget(connection, options.username());
            boolean eligible =
                    mfaIsRequired(connection, target.id())
                            || sourceEnabled(connection, target.id())
                            || confirmedAuthenticator(connection, target.id());
            if (!eligible) throw new CommandFailure("offlineMfaRecoveryNotEligible");

            String verifiedEmail = verifiedEmail(connection, target.id());
            String deliveryMethod;
            if (verifiedEmail != null) {
                if (options.ticketFile() != null)
                    throw new CommandFailure("offlineMfaRecoveryFileDeliveryNotAllowed");
                deliveryMethod = "MAIL";
            } else {
                if (options.ticketFile() == null)
                    throw new CommandFailure("offlineMfaRecoveryVerifiedChannelRequired");
                deliveryMethod = "FILE";
            }

            markRecoveryPending(connection, target.id());
            UUID factorRevision = rotateFactorRevision(connection, target.id());
            revokeSessionsAndChallenges(connection, target.id(), target.username());
            String ticketDigest = ticketDigest(keys, target.id(), ticket);
            UUID ticketId = UUID.randomUUID();
            Instant expiresAt = Instant.now().plusSeconds(TICKET_LIFETIME_SECONDS);
            revokeRecoveryTickets(connection, target.id());
            insertRecoveryTicket(
                    connection, ticketId, target.id(), ticketDigest, factorRevision, expiresAt);

            if ("MAIL".equals(deliveryMethod)) {
                if (!enqueueEncryptedMail(
                        connection, keys, target.id(), verifiedEmail, ticketId, ticket))
                    throw new CommandFailure("offlineMfaRecoveryMailQueueUnavailable");
            } else {
                createdFile = writeTicketFile(options.ticketFile(), ticket);
            }
            insertAudit(connection, target, options, deliveryMethod, expiresAt);

            commitAttempted = true;
            connection.commit();
            output.println("offlineMfaRecoveryCompleted");
            output.println("account=" + target.username());
            output.println("delivery=" + deliveryMethod);
            output.println("expiresAt=" + expiresAt);
            if (createdFile != null) output.println("ticketFile=" + createdFile);
            return 0;
        } catch (CommandFailure failure) {
            rollbackQuietly(connection);
            deleteFileQuietly(createdFile);
            errors.println(failure.code);
            return 4;
        } catch (Exception failure) {
            rollbackQuietly(connection);
            // Once commit was attempted, its result can be indeterminate. Keep the protected file
            // so that a committed ticket cannot be lost; do not echo exception details or secrets.
            if (!commitAttempted) deleteFileQuietly(createdFile);
            errors.println(
                    commitAttempted
                            ? "offlineMfaRecoveryOutcomeUncertain"
                            : "offlineMfaRecoveryFailed");
            return commitAttempted ? 5 : 4;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // The command result has already been decided.
                }
            }
        }
    }

    private static Options parse(String[] args) {
        if (args == null || args.length < 1 || !FLAG.equals(args[0]))
            throw new IllegalArgumentException("command flag required");
        String username = null;
        String operator = null;
        String reason = null;
        Path ticketFile = null;
        for (int index = 1; index < args.length; index++) {
            String key = args[index];
            if (index + 1 >= args.length)
                throw new IllegalArgumentException("missing option value");
            String value = args[++index];
            switch (key) {
                case "--username" -> {
                    if (username != null) throw new IllegalArgumentException("duplicate option");
                    username = value;
                }
                case "--operator" -> {
                    if (operator != null) throw new IllegalArgumentException("duplicate option");
                    operator = value;
                }
                case "--reason" -> {
                    if (reason != null) throw new IllegalArgumentException("duplicate option");
                    reason = value;
                }
                case "--ticket-file" -> {
                    if (ticketFile != null) throw new IllegalArgumentException("duplicate option");
                    ticketFile = Path.of(value);
                }
                default -> throw new IllegalArgumentException("unknown option");
            }
        }
        if (username == null || !username.matches("[A-Za-z0-9_.@-]{1,100}"))
            throw new IllegalArgumentException("invalid username");
        if (operator == null
                || operator.isBlank()
                || operator.length() > 100
                || operator.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid operator");
        if (reason == null
                || reason.strip().length() < 8
                || reason.length() > 500
                || reason.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid reason");
        return new Options(username, operator.strip(), reason.strip(), ticketFile);
    }

    private static void validateEnvironment(Map<String, String> environment) {
        String url = environment.get("LUMIRA_DB_URL");
        if (url == null
                || !url.startsWith("jdbc:postgresql:")
                || url.length() > 2048
                || environment.get("LUMIRA_DB_USER") == null
                || environment.get("LUMIRA_DB_USER").isBlank()
                || environment.get("LUMIRA_DB_PASSWORD") == null
                || environment.get("LUMIRA_DB_PASSWORD").isEmpty())
            throw new IllegalArgumentException("database configuration required");
    }

    private static void validateTicketPath(Path path) {
        if (path == null) return;
        if (!path.isAbsolute()
                || path.getFileName() == null
                || path.getParent() == null
                || Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("absolute unused ticket path required");
    }

    private static void ensureSchemaReady(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                ResultSet row =
                        statement.executeQuery(
                                """
                        SELECT to_regclass('platform_authentication_policy_lock') IS NOT NULL
                           AND to_regclass('platform_user') IS NOT NULL
                           AND to_regclass('platform_global_authentication_policy') IS NOT NULL
                           AND to_regclass('platform_role_authentication_policy') IS NOT NULL
                           AND to_regclass('platform_user_role') IS NOT NULL
                           AND to_regclass('platform_role') IS NOT NULL
                           AND to_regclass('platform_second_factor_requirement') IS NOT NULL
                           AND to_regclass('platform_mfa_recovery_ticket') IS NOT NULL
                           AND to_regclass('platform_mfa_offline_recovery_audit') IS NOT NULL
                           AND to_regclass('platform_mfa_recovery_request_rate') IS NOT NULL
                           AND to_regclass('platform_account_email') IS NOT NULL
                           AND to_regclass('platform_login_challenge') IS NOT NULL
                           AND to_regclass('platform_reauthentication_challenge') IS NOT NULL
                           AND to_regclass('platform_passkey_challenge') IS NOT NULL
                           AND to_regclass('platform_public_auth_challenge') IS NOT NULL
                           AND to_regclass('spring_session') IS NOT NULL
                           AND to_regclass('plugin_mfa_authenticator') IS NOT NULL
                           AND to_regclass('plugin_mfa_recovery') IS NOT NULL
                           AND to_regclass('plugin_mfa_replacement') IS NOT NULL
                           AND to_regclass('platform_mail_delivery') IS NOT NULL
                        """)) {
            if (!row.next() || !row.getBoolean(1))
                throw new CommandFailure("offlineMfaRecoverySchemaUnavailable");
        }
    }

    private static void lockGlobalPolicy(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                ResultSet row =
                        statement.executeQuery(
                                "SELECT id FROM platform_authentication_policy_lock WHERE id=1 FOR UPDATE")) {
            if (!row.next()) throw new CommandFailure("offlineMfaRecoverySchemaUnavailable");
        }
    }

    private static Target lockTarget(Connection connection, String username) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                SELECT id,username,factor_credential_revision FROM platform_user
                 WHERE username=? AND enabled AND deleted_at IS NULL FOR UPDATE
                """)) {
            statement.setString(1, username);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new CommandFailure("offlineMfaRecoveryAccountUnavailable");
                return new Target(
                        row.getObject(1, UUID.class),
                        row.getString(2),
                        row.getObject(3, UUID.class));
            }
        }
    }

    private static boolean mfaIsRequired(Connection connection, UUID id) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                SELECT (SELECT mfa_required FROM platform_global_authentication_policy WHERE id=1)
                    OR EXISTS (
                        SELECT 1 FROM platform_user_role ur
                        JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                        JOIN platform_role_authentication_policy p ON p.role_id=r.id
                         WHERE ur.user_id=? AND p.mfa_mode='REQUIRED'
                    )
                """)) {
            statement.setObject(1, id);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        }
    }

    private static boolean sourceEnabled(Connection connection, UUID id) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                SELECT provider_id='mfa' AND (self_enabled OR recovery_pending)
                  FROM platform_second_factor_requirement WHERE user_id=?
                """)) {
            statement.setObject(1, id);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        }
    }

    private static boolean confirmedAuthenticator(Connection connection, UUID id)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "SELECT confirmed FROM plugin_mfa_authenticator WHERE user_id=?")) {
            statement.setObject(1, id);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        }
    }

    private static String verifiedEmail(Connection connection, UUID id) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                SELECT email FROM platform_account_email
                 WHERE user_id=? AND email IS NOT NULL AND verified_at IS NOT NULL
                """)) {
            statement.setObject(1, id);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    private static void markRecoveryPending(Connection connection, UUID id) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                INSERT INTO platform_second_factor_requirement
                    (user_id,provider_id,self_enabled,recovery_pending)
                VALUES (?, 'mfa', false, true)
                ON CONFLICT(user_id) DO UPDATE
                    SET recovery_pending=true,requirement_version=gen_random_uuid()
                  WHERE platform_second_factor_requirement.provider_id='mfa'
                RETURNING user_id
                """)) {
            statement.setObject(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new CommandFailure("offlineMfaRecoveryProviderConflict");
            }
        }
    }

    private static UUID rotateFactorRevision(Connection connection, UUID id) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "UPDATE platform_user SET factor_credential_revision=gen_random_uuid() WHERE id=? RETURNING factor_credential_revision")) {
            statement.setObject(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new CommandFailure("offlineMfaRecoveryAccountUnavailable");
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static void revokeSessionsAndChallenges(Connection connection, UUID id, String username)
            throws SQLException {
        for (String sql :
                new String[] {
                    "DELETE FROM plugin_mfa_replacement WHERE user_id=?",
                    "DELETE FROM plugin_mfa_authenticator WHERE user_id=?",
                    "DELETE FROM platform_login_challenge WHERE user_id=?",
                    "DELETE FROM platform_reauthentication_challenge WHERE user_id=?",
                    "DELETE FROM platform_passkey_challenge WHERE user_id=?",
                    "DELETE FROM platform_public_auth_challenge WHERE user_id=? AND purpose IN ('LOGIN','SMS_RECOVERY')",
                    "UPDATE platform_mfa_recovery_ticket SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL",
                    "UPDATE platform_mfa_recovery_request_rate SET failures=0,failure_day=CURRENT_DATE WHERE user_id=?",
                    "DELETE FROM spring_session WHERE principal_name=?"
                }) {
            try (var statement = connection.prepareStatement(sql)) {
                if (sql.contains("principal_name=?")) statement.setString(1, username);
                else statement.setObject(1, id);
                statement.executeUpdate();
            }
        }
    }

    private static void revokeRecoveryTickets(Connection connection, UUID id) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                UPDATE platform_mfa_recovery_ticket SET revoked_at=CURRENT_TIMESTAMP
                 WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL
                """)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    private static void insertRecoveryTicket(
            Connection connection,
            UUID ticketId,
            UUID userId,
            String digest,
            UUID factorRevision,
            Instant expiresAt)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                INSERT INTO platform_mfa_recovery_ticket
                    (id,user_id,token_digest,factor_credential_revision,expires_at)
                SELECT ?,?,?,?,? WHERE EXISTS (
                    SELECT 1 FROM platform_second_factor_requirement
                     WHERE user_id=? AND provider_id='mfa' AND recovery_pending
                )
                """)) {
            statement.setObject(1, ticketId);
            statement.setObject(2, userId);
            statement.setString(3, digest);
            statement.setObject(4, factorRevision);
            statement.setObject(5, java.sql.Timestamp.from(expiresAt));
            statement.setObject(6, userId);
            if (statement.executeUpdate() != 1)
                throw new CommandFailure("offlineMfaRecoveryStateChanged");
        }
    }

    private static boolean enqueueEncryptedMail(
            Connection connection,
            SecretKeys keys,
            UUID userId,
            String recipient,
            UUID operationId,
            String ticket)
            throws Exception {
        if (recipient == null || recipient.isBlank()) return false;
        String text =
                "Your authenticator recovery ticket is valid for 15 minutes. "
                        + "It only unlocks restricted authenticator enrollment; it cannot create a full session.\n\n"
                        + ticket;
        byte[] payload =
                new ObjectMapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "recipient",
                                        recipient,
                                        "subject",
                                        "Lumira MFA recovery ticket",
                                        "text",
                                        text
                                                + "\n\nIf you did not expect this, contact your administrator immediately."));
        String encrypted;
        try {
            encrypted = keys.require().encrypt("mail:" + operationId + ":payload", payload);
        } finally {
            java.util.Arrays.fill(payload, (byte) 0);
        }
        try (var statement =
                connection.prepareStatement(
                        """
                INSERT INTO platform_mail_delivery(id,payload,expires_at)
                VALUES (?,?,CURRENT_TIMESTAMP+INTERVAL '15 minutes') ON CONFLICT(id) DO NOTHING
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, encrypted);
            return statement.executeUpdate() == 1;
        }
    }

    private static Path writeTicketFile(Path path, String ticket) throws Exception {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        try {
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(OWNER_ONLY))
                throw new CommandFailure("offlineMfaRecoveryTicketFilePermissions");
            try (FileChannel channel =
                    FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = StandardCharsets.US_ASCII.encode(ticket + "\n");
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            return path;
        } catch (Exception failure) {
            deleteFileQuietly(path);
            throw failure;
        }
    }

    private static void insertAudit(
            Connection connection,
            Target target,
            Options options,
            String delivery,
            Instant expiresAt)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                INSERT INTO platform_mfa_offline_recovery_audit
                    (event_id,target_user_id,target_username,operator,reason,delivery_method,ticket_expires_at)
                VALUES (?,?,?,?,?,?,?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, target.id());
            statement.setString(3, target.username());
            statement.setString(4, options.operator());
            statement.setString(5, options.reason());
            statement.setString(6, delivery);
            statement.setTimestamp(7, java.sql.Timestamp.from(expiresAt));
            statement.executeUpdate();
        }
    }

    private static String ticketDigest(SecretKeys keys, UUID userId, String ticket) {
        byte[] clear = ticket.getBytes(StandardCharsets.US_ASCII);
        try {
            return keys.require().authenticate("mfa-recovery-ticket:" + userId, clear);
        } finally {
            java.util.Arrays.fill(clear, (byte) 0);
        }
    }

    private static String randomTicket() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        try {
            return HexFormat.of().formatHex(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the generic operator-facing failure result.
        }
    }

    private static void deleteFileQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // A leftover 0600 file is safer than exposing its contents in output.
        }
    }

    private static final class CommandFailure extends RuntimeException {
        private final String code;

        private CommandFailure(String code) {
            super(code);
            this.code = code;
        }
    }
}
