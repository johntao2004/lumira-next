package com.lumira.platform.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads uniquely verified login-code contacts and locks account then contact for completion. */
@Repository
public class LoginCodeAccountStore {
    public record AccountContact(
            UUID accountId,
            String username,
            UUID primaryCredentialRevision,
            String contact,
            OffsetDateTime verifiedAt,
            Long contactVersion) {
        public boolean verified() {
            return contact != null && verifiedAt != null && contactVersion != null;
        }
    }

    private final JdbcClient jdbc;

    public LoginCodeAccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The unique DB contact index should yield one row; ambiguity fails closed. */
    public Optional<AccountContact> findEnabledVerifiedContact(String channel, String contact) {
        Source source = source(channel);
        String match =
                source.column().equals("email") ? "lower(c.email)=:contact" : "c.phone=:contact";
        List<AccountContact> rows =
                jdbc.sql(
                                """
                        SELECT u.id AS account_id,u.username,u.primary_credential_revision,
                               c.%s AS contact,c.verified_at,c.version AS contact_version
                          FROM %s c
                          JOIN platform_user u ON u.id=c.user_id AND u.enabled AND u.deleted_at IS NULL
                         WHERE %s AND c.verified_at IS NOT NULL
                         ORDER BY u.id LIMIT 2
                        """
                                        .formatted(source.column(), source.table(), match))
                        .param("contact", contact)
                        .query(LoginCodeAccountStore::map)
                        .list();
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    /** Lock order is user row then contact row, matching account email/phone confirmation. */
    public Optional<AccountContact> lockEnabledAccountContact(UUID accountId, String channel) {
        var user =
                jdbc.sql(
                                """
                        SELECT id AS account_id,username,primary_credential_revision
                          FROM platform_user
                         WHERE id=:id AND enabled AND deleted_at IS NULL
                         FOR UPDATE
                        """)
                        .param("id", accountId)
                        .query(
                                (rs, row) ->
                                        new User(
                                                rs.getObject("account_id", UUID.class),
                                                rs.getString("username"),
                                                rs.getObject(
                                                        "primary_credential_revision", UUID.class)))
                        .optional();
        if (user.isEmpty()) return Optional.empty();

        Source source = source(channel);
        return jdbc.sql(
                        "SELECT %s AS contact,verified_at,version AS contact_version FROM %s WHERE user_id=:id FOR UPDATE"
                                .formatted(source.column(), source.table()))
                .param("id", accountId)
                .query(
                        (rs, row) ->
                                new AccountContact(
                                        user.get().id(),
                                        user.get().username(),
                                        user.get().primaryCredentialRevision(),
                                        rs.getString("contact"),
                                        rs.getObject("verified_at", OffsetDateTime.class),
                                        (Long) rs.getObject("contact_version")))
                .optional();
    }

    private static AccountContact map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AccountContact(
                rs.getObject("account_id", UUID.class),
                rs.getString("username"),
                rs.getObject("primary_credential_revision", UUID.class),
                rs.getString("contact"),
                rs.getObject("verified_at", OffsetDateTime.class),
                (Long) rs.getObject("contact_version"));
    }

    private static Source source(String channel) {
        return switch (channel) {
            case "EMAIL" -> new Source("platform_account_email", "email");
            case "SMS" -> new Source("platform_account_phone", "phone");
            default -> throw new IllegalArgumentException("invalidLoginCodeChannel");
        };
    }

    private record Source(String table, String column) {}

    private record User(UUID id, String username, UUID primaryCredentialRevision) {}
}
