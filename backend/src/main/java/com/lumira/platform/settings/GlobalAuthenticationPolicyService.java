package com.lumira.platform.settings;

import com.lumira.platform.common.*;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import com.lumira.platform.identity.api.AuthenticationReachabilityPort;
import com.lumira.platform.identity.api.SecondFactorProvider;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Settings-owned persistence and validation for the global login policy. */
@Service
public class GlobalAuthenticationPolicyService implements GlobalAuthenticationPolicyPort {
    private static final String DEFAULT_CHANGE_REASON =
            "Global authentication policy updated without a supplied change reason.";

    public record View(
            long version,
            UUID revision,
            List<PrimaryMethod> enabledMethods,
            List<PrimaryMethod> availableMethods,
            boolean mfaRequired,
            long affectedAccountCount) {}

    private final JdbcClient jdbc;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final AuthenticationPolicyWriteLock policyLock;
    private final PluginExtensionsPort plugins;
    private final AuthenticationReachabilityPort reachability;
    private final AuthenticationMethodAvailabilityPort methodAvailability;

    public GlobalAuthenticationPolicyService(
            JdbcClient jdbc,
            AdministrationPort guard,
            AuditPort audit,
            AuthenticationPolicyWriteLock policyLock,
            PluginExtensionsPort plugins,
            AuthenticationReachabilityPort reachability,
            AuthenticationMethodAvailabilityPort methodAvailability) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.policyLock = policyLock;
        this.plugins = plugins;
        this.reachability = reachability;
        this.methodAvailability = methodAvailability;
    }

    @Override
    @Transactional(readOnly = true)
    public Policy current() {
        return readPolicy();
    }

    @Transactional(readOnly = true)
    public View read(String actor) {
        guard.requirePageGlobal(actor, PlatformPageId.AUTHENTICATION);
        return view();
    }

    private View view() {
        Policy policy = readPolicy();
        var available = methodAvailability.availableMethods().stream().sorted().toList();
        return new View(
                policy.version(),
                policy.revision(),
                policy.enabledMethods().stream().sorted().toList(),
                available,
                policy.mfaRequired(),
                jdbc.sql("SELECT count(*) FROM platform_user WHERE enabled AND deleted_at IS NULL")
                        .query(Long.class)
                        .single());
    }

    @Transactional
    public View save(Input input, String actor) {
        if (input == null
                || input.version() < 1
                || input.enabledMethods() == null
                || input.enabledMethods().isEmpty()
                || input.enabledMethods().size() > PrimaryMethod.values().length
                || input.enabledMethods().stream().anyMatch(Objects::isNull)
                || input.enabledMethods().stream().distinct().count()
                        != input.enabledMethods().size()
                || input.changeReason() != null && input.changeReason().strip().length() > 1000) {
            throw error(HttpStatus.BAD_REQUEST, "invalidAuthenticationPolicy");
        }
        Set<PrimaryMethod> methods = EnumSet.copyOf(input.enabledMethods());
        if (input.mfaRequired() && !mfaReady())
            throw error(HttpStatus.CONFLICT, "secondFactorUnavailable");

        // Lock order is authentication policy -> request/session context -> settings rows.
        // The guard reaches the session boundary, so it must not run before this exclusive lock.
        policyLock.lockForUpdate();
        guard.requireOperationGlobal(actor, PlatformOperation.AUTHENTICATION_UPDATE);
        Policy before = readPolicy();
        if (before.version() != input.version()) throw error(HttpStatus.CONFLICT, "staleRecord");
        // Every submitted method must be ready at save time, even when the current policy already
        // contains it. This prevents an invalidated provider from being retained accidentally.
        if (!methodAvailability.availableMethods().containsAll(methods))
            throw error(HttpStatus.CONFLICT, "authenticationMethodUnavailable");
        ensureReachable(methods);
        UUID revision = UUID.randomUUID();
        int updated =
                jdbc.sql(
                                """
                        UPDATE platform_global_authentication_policy
                           SET version=version+1, revision=:revision, enabled_methods=:methods,
                               mfa_required=:mfa, updated_by=:actor, change_reason=:reason,
                               updated_at=CURRENT_TIMESTAMP
                         WHERE id=1 AND version=:version
                        """)
                        .param("revision", revision)
                        .param(
                                "methods",
                                methods.stream().map(Enum::name).sorted().toArray(String[]::new))
                        .param("mfa", input.mfaRequired())
                        .param("actor", actor)
                        .param("reason", changeReason(input.changeReason()))
                        .param("version", input.version())
                        .update();
        if (updated != 1) throw error(HttpStatus.CONFLICT, "staleRecord");
        audit.append(actor, "authentication.policy.global", revision);
        return view();
    }

    private Policy readPolicy() {
        return jdbc.sql(
                        """
                        SELECT version,revision,enabled_methods,mfa_required
                          FROM platform_global_authentication_policy WHERE id=1
                        """)
                .query(
                        (rs, row) -> {
                            var methods = EnumSet.noneOf(PrimaryMethod.class);
                            for (String method :
                                    (String[]) rs.getArray("enabled_methods").getArray())
                                methods.add(PrimaryMethod.valueOf(method));
                            return new Policy(
                                    rs.getLong("version"),
                                    rs.getObject("revision", UUID.class),
                                    methods,
                                    rs.getBoolean("mfa_required"));
                        })
                .single();
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

    private void ensureReachable(Set<PrimaryMethod> methods) {
        if (!reachability.allActiveAccountsReachable(methods))
            throw error(HttpStatus.CONFLICT, "authenticationPolicyUnreachable");
    }

    private static ResponseStatusException error(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }

    private static String changeReason(String supplied) {
        String reason = supplied == null ? "" : supplied.strip();
        return reason.isEmpty() ? DEFAULT_CHANGE_REASON : reason;
    }

    public record Input(
            long version,
            List<PrimaryMethod> enabledMethods,
            boolean mfaRequired,
            String changeReason) {}
}
