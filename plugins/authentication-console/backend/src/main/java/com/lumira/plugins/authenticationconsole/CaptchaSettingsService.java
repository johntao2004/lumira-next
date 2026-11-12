package com.lumira.plugins.authenticationconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.identity.api.CaptchaConfigurationPort;
import com.lumira.platform.identity.api.CaptchaConfigurationPort.*;
import com.lumira.platform.settings.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Host-owned local CAPTCHA configuration. Former Tencent credentials remain stored but inert. */
@Service
public class CaptchaSettingsService {
    private static final String DEFAULT_CHANGE_REASON =
            "CAPTCHA settings saved without a supplied change reason.";

    public record Input(long version, Data data, String changeReason) {}

    public record View(
            long version,
            UUID revision,
            Data data,
            boolean secretEncryptionConfigured,
            Readiness readiness) {}

    private final JdbcClient jdbc;
    private final CaptchaConfigurationPort configuration;
    private final AuthenticationPolicyWriteLock policyLock;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final ObjectMapper mapper;

    public CaptchaSettingsService(
            JdbcClient jdbc,
            CaptchaConfigurationPort configuration,
            AuthenticationPolicyWriteLock policyLock,
            AdministrationPort guard,
            AuditPort audit,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.configuration = configuration;
        this.policyLock = policyLock;
        this.guard = guard;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Snapshot current() {
        return configuration.current();
    }

    @Transactional(readOnly = true)
    public View read(String actor) {
        requireRead(actor);
        return view(current());
    }

    @Transactional(readOnly = true)
    public Readiness readiness() {
        return readiness(current().data());
    }

    private View view(Snapshot snapshot) {
        return new View(
                snapshot.version(),
                snapshot.revision(),
                snapshot.data(),
                configuration.encryptionConfigured(),
                readiness(snapshot.data()));
    }

    private Readiness readiness(Data data) {
        return configuration.readiness(data);
    }

    @Transactional
    public View save(String actor, Input input) {
        if (input == null || input.data() == null) invalid("invalidCaptchaSettings");
        try {
            input.data().validate();
        } catch (CaptchaConfigurationPort.Invalid invalid) {
            invalid("invalidCaptchaSettings");
        }
        policyLock.lockForUpdate();
        requireWrite(actor);
        String reason = input.changeReason() == null ? "" : input.changeReason().strip();
        if (reason.length() > 1000 || reason.codePoints().anyMatch(Character::isISOControl))
            invalid("invalidChangeReason");
        if (reason.isEmpty()) reason = DEFAULT_CHANGE_REASON;

        Snapshot before = configuration.lockCurrent();
        if (before.version() != input.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        UUID revision = UUID.randomUUID();
        int updated =
                jdbc.sql(
                                "UPDATE platform_captcha_settings SET version=version+1,revision=:revision,enabled=:enabled,challenge_type=:type,actions=CAST(:actions AS jsonb),updated_by=:actor,change_reason=:reason,updated_at=now() WHERE id=1 AND version=:version")
                        .param("revision", revision)
                        .param("enabled", input.data().enabled())
                        .param("type", input.data().type())
                        .param("actions", mapper.writeValueAsString(input.data().parsedActions()))
                        .param("actor", actor)
                        .param("reason", reason)
                        .param("version", input.version())
                        .update();
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");

        if (input.data().enabled() && !readyFor(input.data())) invalid("captchaProviderNotReady");
        boolean referencedByRole =
                jdbc.sql(
                                """
                        SELECT EXISTS(
                            SELECT 1 FROM platform_role_authentication_policy
                             WHERE captcha_mode='ALWAYS'
                        )
                        """)
                        .query(Boolean.class)
                        .single();
        if (referencedByRole && !readyFor(input.data()))
            invalid("captchaRequiredProviderReferenced");
        audit.append(actor, "setting.captchaUpdated", revision);
        return view(current());
    }

    public boolean readyFor(Data data) {
        return readiness(data).configured();
    }

    private void requireRead(String actor) {
        guard.requirePageGlobal(actor, PlatformPageId.CAPTCHA);
    }

    private void requireWrite(String actor) {
        guard.requireOperationGlobal(actor, PlatformOperation.CAPTCHA_UPDATE);
    }

    private static void invalid(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }
}
