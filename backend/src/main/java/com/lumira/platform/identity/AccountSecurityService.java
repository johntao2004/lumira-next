package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.PasswordContract;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountSecurityService implements AccountSecurityPort {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuthenticationPolicyWriteLock policyLock;

    public AccountSecurityService(
            JdbcClient jdbc, PasswordEncoder encoder, AuthenticationPolicyWriteLock policyLock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.policyLock = policyLock;
    }

    public void checkPassword(String username, String password) {
        var hash =
                jdbc.sql(
                                "SELECT password_hash FROM platform_user WHERE username=:name AND enabled AND deleted_at IS NULL")
                        .param("name", username)
                        .query(String.class)
                        .optional();
        if (hash.isEmpty()
                || !PasswordContract.compatibleWithStoredHash(password, hash.get())
                || !encoder.matches(password, hash.get()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "wrongCurrentPassword");
    }

    public UUID requireSecondFactor(UUID id, String provider) {
        return setSecondFactorSources(id, provider, true, false);
    }

    @Override
    public UUID setSecondFactorSources(
            UUID id, String provider, boolean selfEnabled, boolean recoveryPending) {
        policyLock.lockForAuthentication();
        lockUser(id);
        jdbc.sql(
                        "INSERT INTO platform_second_factor_requirement(user_id,provider_id,self_enabled,recovery_pending) VALUES(:id,:provider,:self,:recovery) ON CONFLICT(user_id) DO UPDATE SET self_enabled=:self,recovery_pending=:recovery,requirement_version=gen_random_uuid() WHERE platform_second_factor_requirement.provider_id=:provider")
                .param("id", id)
                .param("provider", provider)
                .param("self", selfEnabled)
                .param("recovery", recoveryPending)
                .update();
        var requirement =
                jdbc.sql(
                                "SELECT provider_id,requirement_version FROM platform_second_factor_requirement WHERE user_id=:id")
                        .param("id", id)
                        .query(
                                (rs, row) ->
                                        new Requirement(
                                                rs.getString("provider_id"),
                                                rs.getObject("requirement_version", UUID.class)))
                        .single();
        if (!requirement.provider().equals(provider))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "secondFactorAlreadyRequired");
        if (!selfEnabled && !recoveryPending) {
            jdbc.sql(
                            "DELETE FROM platform_second_factor_requirement WHERE user_id=:id AND provider_id=:provider")
                    .param("id", id)
                    .param("provider", provider)
                    .update();
        }
        return requirement.version();
    }

    @Override
    public void markRecoveryPending(UUID id, String provider) {
        policyLock.lockForAuthentication();
        lockUser(id);
        int updated =
                jdbc.sql(
                                """
                        INSERT INTO platform_second_factor_requirement
                            (user_id,provider_id,self_enabled,recovery_pending)
                        VALUES (:id,:provider,false,true)
                        ON CONFLICT(user_id) DO UPDATE
                            SET recovery_pending=true,requirement_version=gen_random_uuid()
                          WHERE platform_second_factor_requirement.provider_id=:provider
                        """)
                        .param("id", id)
                        .param("provider", provider)
                        .update();
        if (updated != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "secondFactorAlreadyRequired");
        bumpFactorCredentialRevision(id);
    }

    @Override
    public void completeRecovery(UUID id, String provider) {
        policyLock.lockForAuthentication();
        lockUser(id);
        jdbc.sql(
                        """
                        UPDATE platform_second_factor_requirement
                           SET recovery_pending=false,requirement_version=gen_random_uuid()
                         WHERE user_id=:id AND provider_id=:provider AND recovery_pending=true
                        """)
                .param("id", id)
                .param("provider", provider)
                .update();
        jdbc.sql(
                        "DELETE FROM platform_second_factor_requirement WHERE user_id=:id AND provider_id=:provider AND self_enabled=false AND recovery_pending=false")
                .param("id", id)
                .param("provider", provider)
                .update();
        bumpFactorCredentialRevision(id);
    }

    public void clearSecondFactor(UUID id, String provider) {
        policyLock.lockForAuthentication();
        lockUser(id);
        jdbc.sql(
                        """
                        UPDATE platform_second_factor_requirement
                           SET self_enabled=false,requirement_version=gen_random_uuid()
                         WHERE user_id=:id AND provider_id=:provider
                        """)
                .param("id", id)
                .param("provider", provider)
                .update();
        jdbc.sql(
                        "DELETE FROM platform_second_factor_requirement WHERE user_id=:id AND provider_id=:provider AND self_enabled=false AND recovery_pending=false")
                .param("id", id)
                .param("provider", provider)
                .update();
        bumpFactorCredentialRevision(id);
    }

    @Override
    public UUID bumpPrimaryCredentialRevision(UUID id) {
        policyLock.lockForAuthentication();
        lockUser(id);
        return jdbc.sql(
                        "UPDATE platform_user SET primary_credential_revision=gen_random_uuid() WHERE id=:id RETURNING primary_credential_revision")
                .param("id", id)
                .query(UUID.class)
                .single();
    }

    @Override
    public UUID bumpFactorCredentialRevision(UUID id) {
        policyLock.lockForAuthentication();
        lockUser(id);
        return jdbc.sql(
                        "UPDATE platform_user SET factor_credential_revision=gen_random_uuid() WHERE id=:id RETURNING factor_credential_revision")
                .param("id", id)
                .query(UUID.class)
                .single();
    }

    public void revokeSessions(UUID id, String retained) {
        jdbc.sql(
                        "DELETE FROM spring_session WHERE principal_name=(SELECT username FROM platform_user WHERE id=:id) AND session_id<>:retained")
                .param("id", id)
                .param("retained", retained == null ? "" : retained)
                .update();
    }

    private void lockUser(UUID id) {
        jdbc.sql("SELECT id FROM platform_user WHERE id=:id AND deleted_at IS NULL FOR UPDATE")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "userNotFound"));
    }

    private record Requirement(String provider, UUID version) {}
}
