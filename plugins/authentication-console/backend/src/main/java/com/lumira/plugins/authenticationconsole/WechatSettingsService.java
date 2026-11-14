package com.lumira.plugins.authenticationconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.SecretCryptoPort;
import com.lumira.platform.common.SecretStorePort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.settings.*;
import com.lumira.platform.settings.WechatConfigurationReader.Data;
import com.lumira.platform.settings.WechatConfigurationReader.View;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** WeChat editor owns credential mutation, binding guards, publication and audit. */
@Service
public class WechatSettingsService {
    public record Credentials(
            String action,
            @com.fasterxml.jackson.annotation.JsonProperty(
                            access =
                                    com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
                    String value) {
        public void validate() {
            if (!("KEEP".equals(action) || "REPLACE".equals(action) || "CLEAR".equals(action)))
                invalid("invalidWechatSettings");
            if ("REPLACE".equals(action)) {
                if (value == null
                        || value.isEmpty()
                        || value.getBytes(StandardCharsets.UTF_8).length > 4096
                        || value.codePoints().anyMatch(Character::isISOControl))
                    invalid("invalidWechatSettings");
            } else if (value != null) invalid("invalidWechatSettings");
        }

        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    public record Input(
            long version,
            boolean enabled,
            String appId,
            String publicOrigin,
            int timeoutSeconds,
            Credentials appSecret,
            String changeReason) {}

    private static final String SECTION = "wechat";
    private final SettingStore settings;
    private final SecretStorePort secrets;
    private final SecretCryptoPort keys;
    private final AdministrationPort guard;
    private final AuthenticationPolicyWriteLock policyLock;
    private final EffectiveAuthenticationPolicyPort authentication;
    private final AuditPort audit;
    private final ObjectMapper mapper;
    private final JdbcClient jdbc;
    private final String configuredOrigin;
    private final WechatConfigurationReader configurations;

    public WechatSettingsService(
            SettingStore settings,
            SecretStorePort secrets,
            SecretCryptoPort keys,
            AdministrationPort guard,
            AuthenticationPolicyWriteLock policyLock,
            EffectiveAuthenticationPolicyPort authentication,
            AuditPort audit,
            ObjectMapper mapper,
            JdbcClient jdbc,
            WechatConfigurationReader configurations) {
        this.settings = settings;
        this.secrets = secrets;
        this.keys = keys;
        this.guard = guard;
        this.policyLock = policyLock;
        this.authentication = authentication;
        this.audit = audit;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.configurations = configurations;
        this.configuredOrigin = configurations.publicOrigin();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read() {
        return configurations.read();
    }

    @Transactional
    public View save(String actor, Input input) {
        if (input == null || input.appSecret() == null || input.appId() == null)
            invalid("invalidWechatSettings");
        if (input.version() < 0 || input.version() == Long.MAX_VALUE) invalid("invalidVersion");
        if (input.timeoutSeconds() < 1 || input.timeoutSeconds() > 30)
            invalid("invalidWechatSettings");
        Data data = new Data(input.enabled(), input.appId().strip(), input.timeoutSeconds());
        data.validate();
        input.appSecret().validate();
        String reason = input.changeReason() == null ? "" : input.changeReason().strip();
        if (reason.length() > 1000) invalid("invalidChangeReason");

        String requestedOrigin = canonicalOrigin(input.publicOrigin(), true);
        if (!requestedOrigin.equals(configuredOrigin)) invalid("invalidWechatSettings");
        if (input.enabled() && configuredOrigin.isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "wechatOriginUnavailable");

        // Keep the authentication-policy exclusive lock ahead of AdministrationGuard's
        // per-session revision boundary, matching all other authentication-policy writers.
        policyLock.lockForUpdate();
        guard.requireOperationGlobal(actor, PlatformOperation.WECHAT_UPDATE);
        var current = settings.find(SECTION);
        if (current.map(SettingStore.Snapshot::version).orElse(0L) != input.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        Data old =
                current.map(snapshot -> mapper.readValue(snapshot.payload(), Data.class))
                        .orElseGet(Data::defaults);
        var secretState =
                current.map(snapshot -> secrets.state(context(snapshot.id())))
                        .orElse(new SecretStorePort.State(0, false));
        String action = input.appSecret().action();
        boolean nextSecretConfigured =
                "REPLACE".equals(action) || "KEEP".equals(action) && secretState.configured();

        if (!old.appId().equals(data.appId()) && secretState.configured() && "KEEP".equals(action))
            invalid("wechatAppSecretReplacementRequired");
        if (!old.appId().equals(data.appId()) && current.isPresent() && hasBindings(old.appId()))
            conflict("wechatAppInUse");
        if (input.enabled() && !nextSecretConfigured) invalid("wechatAppSecretRequired");
        if ("REPLACE".equals(action) && !keys.configured())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "secretKeyUnavailable");
        if (!input.enabled() && old.enabled() && secretState.configured())
            requireOtherUsableMethodForBoundAccounts(old.appId());

        SettingStore.Snapshot saved =
                settings.save(
                        SECTION, mapper.writeValueAsString(data), actor, input.version(), reason);
        if (!"KEEP".equals(action)) {
            byte[] plaintext =
                    "REPLACE".equals(action)
                            ? input.appSecret().value().getBytes(StandardCharsets.UTF_8)
                            : null;
            try {
                secrets.save(context(saved.id()), secretState.version(), plaintext);
            } finally {
                if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            }
            audit.append(
                    actor,
                    "REPLACE".equals(action)
                            ? "setting.wechatAppSecretUpdated"
                            : "setting.wechatAppSecretCleared",
                    saved.id());
        }
        var savedSecret = secrets.state(context(saved.id()));
        return new View(
                saved.version(),
                data.enabled(),
                data.appId(),
                configuredOrigin,
                data.timeoutSeconds(),
                savedSecret.configured(),
                ready(data, savedSecret.configured()));
    }

    private boolean hasBindings(String appId) {
        if (appId == null || appId.isBlank()) return false;
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_wechat_binding WHERE app_id=:appId AND open_id_digest IS NOT NULL)")
                .param("appId", appId)
                .query(Boolean.class)
                .single();
    }

    private void requireOtherUsableMethodForBoundAccounts(String appId) {
        List<UUID> accounts =
                jdbc.sql(
                                "SELECT user_id FROM platform_wechat_binding WHERE app_id=:appId AND open_id_digest IS NOT NULL")
                        .param("appId", appId)
                        .query(UUID.class)
                        .list();
        for (UUID userId : accounts) {
            try {
                var effective = authentication.resolve(userId);
                if (effective.allowedMethods().contains(PrimaryMethod.WECHAT)
                        && effective.usableMethods().contains(PrimaryMethod.WECHAT)
                        && effective.usableMethods().stream()
                                .noneMatch(method -> method != PrimaryMethod.WECHAT))
                    conflict("wechatRequiredUsableMethod");
            } catch (ResponseStatusException missingAccount) {
                if (missingAccount.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value())
                    continue;
                throw missingAccount;
            }
        }
    }

    private boolean ready(Data data, boolean secretConfigured) {
        return configurations.ready(data, secretConfigured);
    }

    private static String context(UUID id) {
        return WechatConfigurationReader.context(id);
    }

    private static String canonicalOrigin(String value, boolean allowEmpty) {
        return WechatConfigurationReader.canonicalOrigin(value, allowEmpty);
    }

    private static void invalid(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static void conflict(String code) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
