package com.lumira.platform.identity;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.SecretKeys;
import java.util.EnumMap;
import java.util.Map;
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

    public enum Mode {
        OFF,
        RISK,
        ALWAYS
    }

    public record Data(boolean enabled, String provider, String type, Map<String, Mode> actions) {
        public Data {
            actions = Map.copyOf(actions == null ? Map.of() : actions);
        }

        public static Data defaults() {
            var actions = new java.util.LinkedHashMap<String, Mode>();
            for (var action : CaptchaProofPort.Action.values())
                actions.put(action.name(), Mode.OFF);
            return new Data(false, "LOCAL", "IMAGE_TEXT", actions);
        }

        public Map<CaptchaProofPort.Action, Mode> parsedActions() {
            var result = new EnumMap<CaptchaProofPort.Action, Mode>(CaptchaProofPort.Action.class);
            for (var action : CaptchaProofPort.Action.values()) {
                Mode mode = actions.get(action.name());
                result.put(action, mode == null ? Mode.OFF : mode);
            }
            return Map.copyOf(result);
        }

        public void validate() {
            if (!"LOCAL".equals(provider)
                    || !("IMAGE_TEXT".equals(type) || "SLIDER".equals(type))
                    || actions.size() > CaptchaProofPort.Action.values().length
                    || actions.keySet().stream()
                            .anyMatch(
                                    key -> {
                                        try {
                                            CaptchaProofPort.Action.valueOf(key);
                                            return false;
                                        } catch (RuntimeException error) {
                                            return true;
                                        }
                                    })
                    || actions.values().stream().anyMatch(java.util.Objects::isNull))
                invalid("invalidCaptchaSettings");
            boolean required = parsedActions().values().stream().anyMatch(mode -> mode != Mode.OFF);
            if (!enabled && required) invalid("invalidCaptchaSettings");
        }
    }

    public record Input(long version, Data data, String changeReason) {}

    public record Readiness(boolean configured, boolean accessibleAlternativeAvailable) {}

    public record View(
            long version,
            UUID revision,
            Data data,
            boolean secretEncryptionConfigured,
            Readiness readiness) {}

    public record Snapshot(long version, UUID revision, Data data) {}

    private final JdbcClient jdbc;
    private final SecretKeys keys;
    private final LocalCaptchaAssets assets;
    private final AuthenticationPolicyWriteLock policyLock;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final ObjectMapper mapper;

    public CaptchaSettingsService(
            JdbcClient jdbc,
            SecretKeys keys,
            LocalCaptchaAssets assets,
            AuthenticationPolicyWriteLock policyLock,
            AdministrationPort guard,
            AuditPort audit,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.assets = assets;
        this.policyLock = policyLock;
        this.guard = guard;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Snapshot current() {
        return jdbc.sql(
                        "SELECT version,revision,enabled,challenge_type,actions::text AS actions FROM platform_captcha_settings WHERE id=1")
                .query(
                        (rs, row) ->
                                new Snapshot(
                                        rs.getLong("version"),
                                        rs.getObject("revision", UUID.class),
                                        new Data(
                                                rs.getBoolean("enabled"),
                                                "LOCAL",
                                                rs.getString("challenge_type"),
                                                mapper.readValue(
                                                        rs.getString("actions"),
                                                        mapper.getTypeFactory()
                                                                .constructMapType(
                                                                        Map.class,
                                                                        String.class,
                                                                        Mode.class)))))
                .single();
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
                keys.configured(),
                readiness(snapshot.data()));
    }

    private Readiness readiness(Data data) {
        boolean accessible = keys.configured() && assets.audioAvailable();
        boolean configured =
                data != null
                        && data.enabled()
                        && keys.configured()
                        && assets.typeAvailable(data.type())
                        && accessible;
        return new Readiness(configured, accessible);
    }

    @Transactional
    public View save(String actor, Input input) {
        if (input == null || input.data() == null) invalid("invalidCaptchaSettings");
        input.data().validate();
        policyLock.lockForUpdate();
        requireWrite(actor);
        String reason = input.changeReason() == null ? "" : input.changeReason().strip();
        if (reason.length() > 1000 || reason.codePoints().anyMatch(Character::isISOControl))
            invalid("invalidChangeReason");
        if (reason.isEmpty()) reason = DEFAULT_CHANGE_REASON;

        Snapshot before = currentForUpdate();
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

    private Snapshot currentForUpdate() {
        return jdbc.sql(
                        "SELECT version,revision,enabled,challenge_type,actions::text AS actions FROM platform_captcha_settings WHERE id=1 FOR UPDATE")
                .query(
                        (rs, row) ->
                                new Snapshot(
                                        rs.getLong("version"),
                                        rs.getObject("revision", UUID.class),
                                        new Data(
                                                rs.getBoolean("enabled"),
                                                "LOCAL",
                                                rs.getString("challenge_type"),
                                                mapper.readValue(
                                                        rs.getString("actions"),
                                                        mapper.getTypeFactory()
                                                                .constructMapType(
                                                                        Map.class,
                                                                        String.class,
                                                                        Mode.class)))))
                .single();
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
