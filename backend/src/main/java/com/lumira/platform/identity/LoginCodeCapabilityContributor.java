package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationCapabilityContributor;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Adds only currently configured OTP methods backed by this account's verified unique contacts. */
@Service
public class LoginCodeCapabilityContributor implements AuthenticationCapabilityContributor {
    private final JdbcClient jdbc;
    private final AuthenticationMethodAvailabilityPort availability;

    public LoginCodeCapabilityContributor(
            JdbcClient jdbc, AuthenticationMethodAvailabilityPort availability) {
        this.jdbc = jdbc;
        this.availability = availability;
    }

    @Override
    public String id() {
        return "login-code";
    }

    @Override
    public Contribution authenticationCapabilities() {
        Set<PrimaryMethod> available = availability.availableMethods();
        var methods = EnumSet.noneOf(PrimaryMethod.class);
        if (available.contains(PrimaryMethod.EMAIL_OTP)) methods.add(PrimaryMethod.EMAIL_OTP);
        if (available.contains(PrimaryMethod.SMS_OTP)) methods.add(PrimaryMethod.SMS_OTP);
        return new Contribution(methods, !methods.isEmpty(), false, false, false, null, null);
    }

    @Override
    public AccountMethods accountMethods(UUID userId) {
        var snapshot =
                jdbc.sql(
                                """
                        SELECT u.primary_credential_revision,
                               e.email,e.verified_at AS email_verified,e.version AS email_version,
                               p.phone,p.verified_at AS phone_verified,p.version AS phone_version
                          FROM platform_user u
                          LEFT JOIN platform_account_email e ON e.user_id=u.id
                          LEFT JOIN platform_account_phone p ON p.user_id=u.id
                         WHERE u.id=:id AND u.enabled AND u.deleted_at IS NULL
                        """)
                        .param("id", userId)
                        .query(
                                (rs, row) ->
                                        new Snapshot(
                                                rs.getObject(
                                                        "primary_credential_revision", UUID.class),
                                                rs.getString("email"),
                                                rs.getObject(
                                                                "email_verified",
                                                                java.time.OffsetDateTime.class)
                                                        != null,
                                                (Long) rs.getObject("email_version"),
                                                rs.getString("phone"),
                                                rs.getObject(
                                                                "phone_verified",
                                                                java.time.OffsetDateTime.class)
                                                        != null,
                                                (Long) rs.getObject("phone_version")))
                        .optional()
                        .orElse(null);
        if (snapshot == null) return new AccountMethods(Set.of(), "inactive:" + userId);

        Set<PrimaryMethod> configured = availability.availableMethods();
        var methods = EnumSet.noneOf(PrimaryMethod.class);
        if (configured.contains(PrimaryMethod.EMAIL_OTP)
                && snapshot.emailVerified()
                && snapshot.email() != null
                && !snapshot.email().isBlank()) methods.add(PrimaryMethod.EMAIL_OTP);
        if (configured.contains(PrimaryMethod.SMS_OTP)
                && snapshot.phoneVerified()
                && snapshot.phone() != null
                && !snapshot.phone().isBlank()) methods.add(PrimaryMethod.SMS_OTP);
        String revision =
                String.valueOf(snapshot.primaryCredentialRevision())
                        + ":email="
                        + snapshot.emailVersion()
                        + "/"
                        + snapshot.emailVerified()
                        + ":phone="
                        + snapshot.phoneVersion()
                        + "/"
                        + snapshot.phoneVerified();
        return new AccountMethods(methods, revision);
    }

    private record Snapshot(
            UUID primaryCredentialRevision,
            String email,
            boolean emailVerified,
            Long emailVersion,
            String phone,
            boolean phoneVerified,
            Long phoneVersion) {}
}
