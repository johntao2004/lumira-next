package com.lumira.platform.settings;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.common.SecretStorePort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.WechatConfigurationPort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Typed WeChat application settings. The application secret is stored outside revisioned JSON. */
@Service
public class WechatSettingsService implements WechatConfigurationPort {
    public record Data(Boolean enabled, String appId, int timeoutSeconds) {
        public static Data defaults() {
            return new Data(false, "", 8);
        }

        public void validate() {
            if (enabled == null
                    || appId == null
                    || appId.length() > 64
                    || !appId.isEmpty() && !appId.matches("[A-Za-z0-9_-]{1,64}")
                    || timeoutSeconds < 1
                    || timeoutSeconds > 30) invalid("invalidWechatSettings");
            if (enabled && appId.isBlank()) invalid("invalidWechatSettings");
        }
    }

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

    public record View(
            long version,
            boolean enabled,
            String appId,
            String publicOrigin,
            int timeoutSeconds,
            boolean appSecretConfigured,
            boolean ready) {}

    /**
     * Short-lived secret-bearing configuration. Callers must close it after the provider request.
     */
    public static final class ProviderConfiguration
            implements WechatConfigurationPort.ProviderConfiguration {
        private final long version;
        private final String appId;
        private final String publicOrigin;
        private final int timeoutSeconds;
        private final byte[] secret;

        private ProviderConfiguration(
                long version,
                String appId,
                String publicOrigin,
                int timeoutSeconds,
                byte[] secret) {
            this.version = version;
            this.appId = appId;
            this.publicOrigin = publicOrigin;
            this.timeoutSeconds = timeoutSeconds;
            this.secret = secret;
        }

        public long version() {
            return version;
        }

        public String appId() {
            return appId;
        }

        public String publicOrigin() {
            return publicOrigin;
        }

        public int timeoutSeconds() {
            return timeoutSeconds;
        }

        public byte[] secret() {
            return secret;
        }

        @Override
        public void close() {
            Arrays.fill(secret, (byte) 0);
        }

        @Override
        public String toString() {
            return "ProviderConfiguration[redacted]";
        }
    }

    private static final String SECTION = "wechat";
    private final SettingStore settings;
    private final SecretStore secrets;
    private final SecretKeys keys;
    private final AdministrationPort guard;
    private final AuthenticationPolicyWriteLock policyLock;
    private final EffectiveAuthenticationPolicyPort authentication;
    private final AuditPort audit;
    private final ObjectMapper mapper;
    private final JdbcClient jdbc;
    private final String configuredOrigin;

    public WechatSettingsService(
            SettingStore settings,
            SecretStore secrets,
            SecretKeys keys,
            AdministrationPort guard,
            AuthenticationPolicyWriteLock policyLock,
            EffectiveAuthenticationPolicyPort authentication,
            AuditPort audit,
            ObjectMapper mapper,
            JdbcClient jdbc,
            @Value("${LUMIRA_WECHAT_PUBLIC_ORIGIN:}") String configuredOrigin) {
        this.settings = settings;
        this.secrets = secrets;
        this.keys = keys;
        this.guard = guard;
        this.policyLock = policyLock;
        this.authentication = authentication;
        this.audit = audit;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.configuredOrigin = canonicalOrigin(configuredOrigin, true);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read() {
        var current = settings.find(SECTION);
        long version = current.map(SettingStore.Snapshot::version).orElse(0L);
        Data data =
                current.map(snapshot -> mapper.readValue(snapshot.payload(), Data.class))
                        .orElseGet(Data::defaults);
        data.validate();
        var secret =
                current.map(snapshot -> secrets.state(context(snapshot.id())))
                        .orElse(new SecretStorePort.State(0, false));
        boolean ready = ready(data, secret.configured());
        return new View(
                version,
                data.enabled(),
                data.appId(),
                configuredOrigin,
                data.timeoutSeconds(),
                secret.configured(),
                ready);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WechatConfigurationPort.Snapshot snapshot() {
        View view = read();
        return new WechatConfigurationPort.Snapshot(
                view.version(),
                view.enabled(),
                view.appId(),
                view.publicOrigin(),
                view.timeoutSeconds(),
                view.appSecretConfigured(),
                view.ready());
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
        if ("REPLACE".equals(action)) keys.require();
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

    /** Snapshot both non-secret settings and the secret in one short read transaction. */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProviderConfiguration providerConfiguration() {
        var snapshot =
                settings.find(SECTION)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "wechatUnavailable"));
        Data data = mapper.readValue(snapshot.payload(), Data.class);
        data.validate();
        if (!ready(data, secrets.state(context(snapshot.id())).configured()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "wechatUnavailable");
        byte[] secret =
                secrets.reveal(context(snapshot.id()))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "wechatUnavailable"));
        return new ProviderConfiguration(
                snapshot.version(), data.appId(), configuredOrigin, data.timeoutSeconds(), secret);
    }

    @Override
    public boolean ready() {
        return read().ready();
    }

    @Override
    public String currentAppId() {
        String appId = read().appId();
        return appId.isBlank() ? null : appId;
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
        return Boolean.TRUE.equals(data.enabled())
                && !data.appId().isBlank()
                && secretConfigured
                && keys.configured()
                && !configuredOrigin.isBlank();
    }

    private static String context(UUID id) {
        return "settings:" + id + ":wechat-app-secret";
    }

    private static String canonicalOrigin(String value, boolean allowEmpty) {
        if (value == null || value.isBlank()) return allowEmpty ? "" : invalidOrigin();
        if (value.length() > 253 || value.codePoints().anyMatch(Character::isISOControl))
            return invalidOrigin();
        try {
            URI uri = URI.create(value.strip());
            String scheme =
                    uri.getScheme() == null
                            ? ""
                            : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = uri.getHost();
            boolean local =
                    host != null
                            && (host.equalsIgnoreCase("localhost")
                                    || host.equals("127.0.0.1")
                                    || host.equals("[::1]")
                                    || host.equals("::1"));
            if (host == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || !(scheme.equals("https") || scheme.equals("http") && local)
                    || uri.getPort() > 65535
                    || uri.getPort() == 0
                    || uri.getPort() != -1
                            && uri.getPort() != 443
                            && !(scheme.equals("http") && local)) return invalidOrigin();
            String normalizedHost = host.toLowerCase(java.util.Locale.ROOT);
            String port = uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort();
            return scheme + "://" + normalizedHost + port;
        } catch (RuntimeException invalid) {
            return invalidOrigin();
        }
    }

    private static String invalidOrigin() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidWechatSettings");
    }

    private static void invalid(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static void conflict(String code) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
