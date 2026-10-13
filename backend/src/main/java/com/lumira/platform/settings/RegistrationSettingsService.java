package com.lumira.platform.settings;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Versioned registration settings plus current-channel readiness, kept outside identity. */
@Service
public class RegistrationSettingsService implements RegistrationPolicyPort {
    private static final UUID AUDIT_TARGET =
            UUID.fromString("00000000-0000-0000-0000-000000000031");
    private static final String DEFAULT_CHANGE_REASON =
            "Registration settings updated without a supplied change reason.";

    public record Readiness(
            boolean emailAvailable,
            boolean smsAvailable,
            boolean phoneRecoverySupported,
            boolean agreementsAvailable,
            boolean defaultRoleEligible,
            boolean maintenance,
            boolean captchaAvailable) {}

    public record View(
            long version,
            boolean enabled,
            List<Channel> channels,
            UUID defaultRoleId,
            Readiness readiness) {}

    public record Input(
            Long version,
            Boolean enabled,
            List<Channel> channels,
            UUID defaultRoleId,
            String changeReason) {}

    private final JdbcClient jdbc;
    private final AdministrationPort guard;
    private final AuthenticationPolicyWriteLock policyLock;
    private final RegistrationRolePort roles;
    private final AgreementSnapshotPort agreements;
    private final MailSender mail;
    private final SmsSender sms;
    private final MaintenanceService maintenance;
    private final AuditPort audit;
    private final ObjectProvider<CaptchaProofPort> captcha;
    private final ObjectProvider<SmsPasswordRecoveryPort> smsRecovery;

    public RegistrationSettingsService(
            JdbcClient jdbc,
            AdministrationPort guard,
            AuthenticationPolicyWriteLock policyLock,
            RegistrationRolePort roles,
            AgreementSnapshotPort agreements,
            MailSender mail,
            SmsSender sms,
            MaintenanceService maintenance,
            AuditPort audit,
            ObjectProvider<CaptchaProofPort> captcha,
            ObjectProvider<SmsPasswordRecoveryPort> smsRecovery) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.policyLock = policyLock;
        this.roles = roles;
        this.agreements = agreements;
        this.mail = mail;
        this.sms = sms;
        this.maintenance = maintenance;
        this.audit = audit;
        this.captcha = captcha;
        this.smsRecovery = smsRecovery;
    }

    @Override
    @Transactional(readOnly = true)
    public Policy current() {
        return policy(false);
    }

    @Override
    @Transactional
    public Policy lockCurrent() {
        return policy(true);
    }

    private Policy policy(boolean lock) {
        return jdbc.sql(
                        "SELECT version,enabled,channels,default_role_id FROM platform_registration_policy WHERE id=1"
                                + (lock ? " FOR SHARE" : ""))
                .query(
                        (rs, row) -> {
                            var channels = EnumSet.noneOf(Channel.class);
                            for (String channel : (String[]) rs.getArray("channels").getArray())
                                channels.add(Channel.valueOf(channel));
                            return new Policy(
                                    rs.getLong("version"),
                                    rs.getBoolean("enabled"),
                                    channels,
                                    rs.getObject("default_role_id", UUID.class));
                        })
                .single();
    }

    @Transactional(readOnly = true)
    public View read(String actor) {
        guard.requirePageGlobal(actor, PlatformPageId.REGISTRATION);
        Policy policy = current();
        return view(policy);
    }

    @Transactional
    public View save(Input input, String actor) {
        if (!valid(input)) throw badRequest("invalidRegistrationPolicy");

        // Match preview/session writers: acquire the shared global-policy mutex before the
        // AdministrationGuard takes the current session's shared context lock.
        policyLock.lockForUpdate();
        guard.requireOperationGlobal(actor, PlatformOperation.REGISTRATION_UPDATE);
        Policy previous = current();
        if (previous.version() != input.version()) throw conflict("staleRecord");
        if (!Objects.equals(previous.defaultRoleId(), input.defaultRoleId()))
            guard.requireOperationGlobal(actor, PlatformOperation.REGISTRATION_ASSIGN_DEFAULT_ROLE);
        if (input.defaultRoleId() != null) roles.lockAndRequireEligible(input.defaultRoleId());
        if (Boolean.TRUE.equals(input.enabled())
                && input.channels().contains(Channel.SMS)
                && !smsRecoverySupported()) throw conflict("phoneRecoveryUnavailable");

        int updated =
                jdbc.sql(
                                "UPDATE platform_registration_policy SET version=version+1,enabled=:enabled,channels=:channels,default_role_id=:role,updated_by=:actor,change_reason=:reason,updated_at=CURRENT_TIMESTAMP WHERE id=1 AND version=:version")
                        .param("enabled", input.enabled())
                        .param(
                                "channels",
                                input.channels().stream()
                                        .map(Enum::name)
                                        .sorted()
                                        .toArray(String[]::new))
                        .param("role", input.defaultRoleId())
                        .param("actor", actor)
                        .param("reason", changeReason(input.changeReason()))
                        .param("version", input.version())
                        .update();
        if (updated != 1) throw conflict("staleRecord");
        audit.append(actor, "registration.policy.update", AUDIT_TARGET);
        return view(current());
    }

    @Transactional(readOnly = true)
    public PageResult<RegistrationRolePort.Candidate> roles(String actor, PageRequest page) {
        guard.requirePageGlobal(actor, PlatformPageId.REGISTRATION);
        try {
            guard.requireOperationGlobal(actor, PlatformOperation.REGISTRATION_READ_ROLES);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() != HttpStatus.FORBIDDEN) throw exception;
            guard.requireOperationGlobal(actor, PlatformOperation.REGISTRATION_ASSIGN_DEFAULT_ROLE);
        }
        return roles.candidates(page);
    }

    private View view(Policy policy) {
        return new View(
                policy.version(),
                policy.enabled(),
                policy.channels().stream().sorted().toList(),
                policy.defaultRoleId(),
                readiness(policy.defaultRoleId()));
    }

    private Readiness readiness(UUID defaultRoleId) {
        var document = agreements.current();
        boolean eligible = defaultRoleId != null && roles.candidate(defaultRoleId).eligible();
        return new Readiness(
                emailAvailable(),
                smsAvailable(),
                smsRecoverySupported(),
                document.complete(),
                eligible,
                maintenance.read().data().activeAt(Instant.now()),
                captchaAvailable(defaultRoleId));
    }

    private boolean emailAvailable() {
        return mail.ready();
    }

    private boolean smsAvailable() {
        return sms.ready();
    }

    private boolean smsRecoverySupported() {
        var recovery = smsRecovery.getIfAvailable();
        return recovery != null && recovery.supported();
    }

    private boolean captchaAvailable(UUID defaultRoleId) {
        var provider = captcha.getIfAvailable();
        if (provider == null) return false;
        return java.util.Set.of(
                        CaptchaProofPort.Action.REGISTRATION_SEND,
                        CaptchaProofPort.Action.REGISTRATION_VERIFY,
                        CaptchaProofPort.Action.REGISTRATION_COMPLETE)
                .stream()
                .allMatch(action -> provider.readyFor(action, null, defaultRoleId));
    }

    private static boolean valid(Input input) {
        return input != null
                && input.version() != null
                && input.version() >= 1
                && input.version() < Long.MAX_VALUE
                && input.enabled() != null
                && input.channels() != null
                && input.channels().size() <= Channel.values().length
                && input.channels().stream().noneMatch(Objects::isNull)
                && input.channels().stream().distinct().count() == input.channels().size()
                && (!input.enabled()
                        || !input.channels().isEmpty() && input.defaultRoleId() != null)
                && (input.changeReason() == null || input.changeReason().strip().length() <= 1000);
    }

    private static String changeReason(String supplied) {
        String reason = supplied == null ? "" : supplied.strip();
        return reason.isEmpty() ? DEFAULT_CHANGE_REASON : reason;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
