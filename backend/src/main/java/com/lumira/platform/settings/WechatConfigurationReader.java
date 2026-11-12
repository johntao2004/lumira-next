package com.lumira.platform.settings;

import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.common.SecretStorePort;
import com.lumira.platform.identity.api.WechatConfigurationPort;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Kernel provider configuration remains usable without the optional editor. */
@Service
public class WechatConfigurationReader implements WechatConfigurationPort {
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
    private final ObjectMapper mapper;
    private final String configuredOrigin;

    public WechatConfigurationReader(
            SettingStore settings,
            SecretStore secrets,
            SecretKeys keys,
            ObjectMapper mapper,
            @Value("${LUMIRA_WECHAT_PUBLIC_ORIGIN:}") String configuredOrigin) {
        this.settings = settings;
        this.secrets = secrets;
        this.keys = keys;
        this.mapper = mapper;
        this.configuredOrigin = canonicalOrigin(configuredOrigin, true);
    }

    public String publicOrigin() {
        return configuredOrigin;
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

    public boolean ready(Data data, boolean secretConfigured) {
        return Boolean.TRUE.equals(data.enabled())
                && !data.appId().isBlank()
                && secretConfigured
                && keys.configured()
                && !configuredOrigin.isBlank();
    }

    public static String context(UUID id) {
        return "settings:" + id + ":wechat-app-secret";
    }

    public static String canonicalOrigin(String value, boolean allowEmpty) {
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
}
