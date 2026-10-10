package com.lumira.platform.roles;

import com.lumira.platform.common.*;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import com.lumira.platform.identity.api.AuthenticationReachabilityPort;
import com.lumira.platform.identity.api.SecondFactorProvider;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoleAuthenticationPolicyService {
    private static final String DEFAULT_CHANGE_REASON =
            "Role authentication policy updated without a supplied change reason.";

    public record View(
            UUID roleId,
            long roleVersion,
            long version,
            UUID revision,
            RoleAuthenticationPolicyPort.LoginMode loginMode,
            List<PrimaryMethod> methods,
            RoleAuthenticationPolicyPort.MfaMode mfaMode,
            RoleAuthenticationPolicyPort.CaptchaMode captchaMode,
            long affectedAccountCount) {}

    public record Input(
            long roleVersion,
            long version,
            RoleAuthenticationPolicyPort.LoginMode loginMode,
            List<PrimaryMethod> methods,
            RoleAuthenticationPolicyPort.MfaMode mfaMode,
            RoleAuthenticationPolicyPort.CaptchaMode captchaMode,
            String changeReason) {}

    private final JdbcClient jdbc;
    private final RoleAuthenticationPolicyRepository repository;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final GlobalAuthenticationPolicyPort global;
    private final AuthenticationReachabilityPort reachability;
    private final AuthenticationPolicyWriteLock policyLock;
    private final PluginExtensionsPort plugins;
    private final ObjectProvider<CaptchaProofPort> captcha;
    private final AuthenticationMethodAvailabilityPort methodAvailability;

    public RoleAuthenticationPolicyService(
            JdbcClient jdbc,
            RoleAuthenticationPolicyRepository repository,
            AdministrationPort guard,
            AuditPort audit,
            GlobalAuthenticationPolicyPort global,
            AuthenticationReachabilityPort reachability,
            AuthenticationPolicyWriteLock policyLock,
            PluginExtensionsPort plugins,
            ObjectProvider<CaptchaProofPort> captcha,
            AuthenticationMethodAvailabilityPort methodAvailability) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.guard = guard;
        this.audit = audit;
        this.global = global;
        this.reachability = reachability;
        this.policyLock = policyLock;
        this.plugins = plugins;
        this.captcha = captcha;
        this.methodAvailability = methodAvailability;
    }

    @Transactional(readOnly = true)
    public View read(UUID roleId, String actor) {
        guard.require(actor, "platform:roles:read");
        guard.requireGlobal(actor, "platform:roles:read");
        guard.require(actor, "platform:settings:read");
        guard.requireGlobal(actor, "platform:settings:read");
        return view(roleId);
    }

    private View view(UUID roleId) {
        RoleAuthenticationPolicyPort.RolePolicy policy = repository.forRole(roleId);
        if (policy == null) throw error(HttpStatus.NOT_FOUND, "roleNotFound");
        return view(policy);
    }

    @Transactional
    public View save(UUID roleId, Input input, String actor) {
        validate(input);
        // Policy lock must precede the guard's session boundary; preview transitions use
        // the same order (policy SHARE -> session FOR UPDATE).
        policyLock.lockForUpdate();
        guard.require(actor, "platform:roles:write");
        guard.requireGlobal(actor, "platform:roles:write");
        guard.require(actor, "platform:settings:write");
        guard.requireGlobal(actor, "platform:settings:write");
        Long currentRoleVersion =
                jdbc.sql("SELECT version FROM platform_role WHERE id=:id FOR UPDATE")
                        .param("id", roleId)
                        .query(Long.class)
                        .optional()
                        .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "roleNotFound"));
        RoleAuthenticationPolicyPort.RolePolicy before = repository.forRole(roleId);
        if (before.roleVersion() != input.roleVersion()
                || currentRoleVersion != input.roleVersion()
                || before.version() != input.version())
            throw error(HttpStatus.CONFLICT, "staleRecord");
        if (input.captchaMode() == RoleAuthenticationPolicyPort.CaptchaMode.ALWAYS
                && (captcha.getIfAvailable() == null
                        || !captcha.getIfAvailable().readyForRequiredPolicy()))
            throw error(HttpStatus.CONFLICT, "captchaUnavailable");

        Set<PrimaryMethod> methods =
                input.methods().isEmpty()
                        ? EnumSet.noneOf(PrimaryMethod.class)
                        : EnumSet.copyOf(input.methods());
        var newlyEnabled = EnumSet.noneOf(PrimaryMethod.class);
        newlyEnabled.addAll(methods);
        if (before.loginMode() == RoleAuthenticationPolicyPort.LoginMode.RESTRICT)
            newlyEnabled.removeAll(before.methods());
        if (!methodAvailability.availableMethods().containsAll(newlyEnabled))
            throw error(HttpStatus.CONFLICT, "authenticationMethodUnavailable");
        if (input.loginMode() == RoleAuthenticationPolicyPort.LoginMode.RESTRICT) {
            if (!global.current().enabledMethods().containsAll(methods))
                throw error(HttpStatus.CONFLICT, "authenticationPolicyConflict");
            if (input.methods().isEmpty())
                throw error(HttpStatus.BAD_REQUEST, "invalidAuthenticationPolicy");
        } else if (!methods.isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "invalidAuthenticationPolicy");
        }
        if (input.mfaMode() == RoleAuthenticationPolicyPort.MfaMode.REQUIRED && !mfaReady())
            throw error(HttpStatus.CONFLICT, "secondFactorUnavailable");

        UUID revision = UUID.randomUUID();
        String reason = changeReason(input.changeReason());
        if (input.version() == 0) {
            var statement =
                    bindPolicyValues(
                            jdbc.sql(
                                    """
                            INSERT INTO platform_role_authentication_policy
                                (role_id,version,revision,login_mode,methods,mfa_mode,captcha_mode,updated_by,change_reason)
                            VALUES (:roleId,1,:revision,:loginMode,:methods,:mfaMode,:captchaMode,:actor,:reason)
                            ON CONFLICT(role_id) DO NOTHING
                            """),
                            roleId,
                            revision,
                            input,
                            methods,
                            actor,
                            reason);
            int inserted = statement.update();
            if (inserted != 1) throw error(HttpStatus.CONFLICT, "staleRecord");
        } else {
            var statement =
                    bindPolicyValues(
                                    jdbc.sql(
                                            """
                            UPDATE platform_role_authentication_policy
                               SET version=version+1,revision=:revision,login_mode=:loginMode,
                                   methods=:methods,mfa_mode=:mfaMode,captcha_mode=:captchaMode,
                                   updated_by=:actor,change_reason=:reason,updated_at=CURRENT_TIMESTAMP
                             WHERE role_id=:roleId AND version=:version
                            """),
                                    roleId,
                                    revision,
                                    input,
                                    methods,
                                    actor,
                                    reason)
                            .param("version", input.version());
            int updated = statement.update();
            if (updated != 1) throw error(HttpStatus.CONFLICT, "staleRecord");
        }
        if (!reachability.assignedAccountsReachable(roleId, global.current().enabledMethods()))
            throw error(HttpStatus.CONFLICT, "authenticationPolicyUnreachable");
        audit.append(actor, "authentication.policy.role", roleId);
        return view(roleId);
    }

    private static JdbcClient.StatementSpec bindPolicyValues(
            JdbcClient.StatementSpec statement,
            UUID roleId,
            UUID revision,
            Input input,
            Set<PrimaryMethod> methods,
            String actor,
            String reason) {
        return statement
                .param("roleId", roleId)
                .param("revision", revision)
                .param("loginMode", input.loginMode().name())
                .param("methods", methods.stream().map(Enum::name).sorted().toArray(String[]::new))
                .param("mfaMode", input.mfaMode().name())
                .param("captchaMode", input.captchaMode().name())
                .param("actor", actor)
                .param("reason", reason);
    }

    private static void validate(Input input) {
        if (input == null
                || input.roleVersion() < 0
                || input.version() < 0
                || input.loginMode() == null
                || input.methods() == null
                || input.methods().size() > PrimaryMethod.values().length
                || input.methods().stream().anyMatch(Objects::isNull)
                || input.methods().stream().distinct().count() != input.methods().size()
                || input.mfaMode() == null
                || input.captchaMode() == null
                || input.changeReason() != null && input.changeReason().strip().length() > 1000) {
            throw error(HttpStatus.BAD_REQUEST, "invalidAuthenticationPolicy");
        }
    }

    private boolean mfaReady() {
        long availableProviders =
                plugins
                        .extensions(
                                SecondFactorProvider.class,
                                provider ->
                                        provider.id().equals("mfa")
                                                && provider.available()
                                                && provider
                                                        .supportsRestrictedEnrollmentAndRecovery())
                        .stream()
                        .filter(Boolean.TRUE::equals)
                        .count();
        return availableProviders == 1;
    }

    private View view(RoleAuthenticationPolicyPort.RolePolicy policy) {
        return new View(
                policy.roleId(),
                policy.roleVersion(),
                policy.version(),
                policy.revision(),
                policy.loginMode(),
                policy.methods().stream().sorted().toList(),
                policy.mfaMode(),
                policy.captchaMode(),
                jdbc.sql(
                                """
                                SELECT CASE WHEN r.enabled THEN count(DISTINCT u.id) ELSE 0 END
                                  FROM platform_role r
                                  LEFT JOIN platform_user_role ur ON ur.role_id=r.id
                                  LEFT JOIN platform_user u ON u.id=ur.user_id
                                        AND u.enabled AND u.deleted_at IS NULL
                                 WHERE r.id=:roleId
                                 GROUP BY r.enabled
                                """)
                        .param("roleId", policy.roleId())
                        .query(Long.class)
                        .single());
    }

    private static ResponseStatusException error(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }

    private static String changeReason(String supplied) {
        String reason = supplied == null ? "" : supplied.strip();
        return reason.isEmpty() ? DEFAULT_CHANGE_REASON : reason;
    }
}
