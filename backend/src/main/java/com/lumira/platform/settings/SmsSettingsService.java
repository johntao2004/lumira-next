package com.lumira.platform.settings;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.common.SecretStorePort;
import com.lumira.platform.common.SmsConfigurationPort;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class SmsSettingsService implements SmsConfigurationPort {
    public record Credentials(
            String action,
            @com.fasterxml.jackson.annotation.JsonProperty(
                            access =
                                    com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
                    String value) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }

        public void validate() {
            if (!("KEEP".equals(action) || "REPLACE".equals(action) || "CLEAR".equals(action)))
                invalid("invalidSmsCredentials");
            if ("REPLACE".equals(action)) {
                if (value == null
                        || value.isEmpty()
                        || value.getBytes(StandardCharsets.UTF_8).length > 4096
                        || value.codePoints().anyMatch(Character::isISOControl))
                    invalid("invalidSmsCredentials");
            } else if (value != null) invalid("invalidSmsCredentials");
        }
    }

    public record Input(
            long version,
            SmsSettings data,
            Credentials credentials,
            String changeReason,
            Long sourceVersion) {}

    public record View(
            long version,
            SmsSettings data,
            boolean credentialConfigured,
            boolean encryptionConfigured) {}

    private final SettingStore settings;
    private final SecretStore secrets;
    private final SecretKeys keys;
    private final AdministrationPort guard;
    private final ObjectMapper mapper;
    private final AuditPort audit;

    public SmsSettingsService(
            SettingStore settings,
            SecretStore secrets,
            SecretKeys keys,
            AdministrationPort guard,
            ObjectMapper mapper,
            AuditPort audit) {
        this.settings = settings;
        this.secrets = secrets;
        this.keys = keys;
        this.guard = guard;
        this.mapper = mapper;
        this.audit = audit;
    }

    private static void invalid(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static ResponseStatusException unavailable(String code) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, code);
    }

    private static String context(UUID id) {
        return "settings:" + id + ":sms-provider-secret";
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SmsConfigurationPort.Settings readSettings() {
        SmsSettings data = read().data();
        data.validate();
        return data.connectionSettings();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public boolean ready() {
        var current = settings.find("sms");
        if (current.isEmpty()) return false;
        var snapshot = current.orElseThrow();
        SmsSettings data;
        try {
            data = mapper.readValue(snapshot.payload(), SmsSettings.class);
        } catch (RuntimeException invalidPayload) {
            return false;
        }
        if (data == null) return false;
        try {
            data.validate();
        } catch (ResponseStatusException invalidConfiguration) {
            return false;
        }
        if (!Boolean.TRUE.equals(data.enabled())) return false;
        return secrets.state(context(snapshot.id())).configured() && keys.configured();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Configuration load() {
        var snapshot = settings.find("sms").orElseThrow(() -> unavailable("smsUnavailable"));
        var data = mapper.readValue(snapshot.payload(), SmsSettings.class);
        data.validate();
        if (!data.enabled()) throw unavailable("smsUnavailable");
        try {
            byte[] secret =
                    secrets.reveal(context(snapshot.id()))
                            .orElseThrow(() -> unavailable("smsCredentialRequired"));
            return new Configuration(data.connectionSettings(), secret);
        } catch (IllegalStateException error) {
            throw unavailable("secretKeyUnavailable");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read() {
        return settings.find("sms")
                .map(
                        snapshot -> {
                            var data = mapper.readValue(snapshot.payload(), SmsSettings.class);
                            boolean configured = secrets.state(context(snapshot.id())).configured();
                            if (!data.enabled()
                                    && data.provider().equals("webhook")
                                    && data.endpoint().isBlank()
                                    && !configured) {
                                data =
                                        new SmsSettings(
                                                false,
                                                "",
                                                data.endpoint(),
                                                data.sender(),
                                                data.template(),
                                                data.timeoutSeconds(),
                                                data.market(),
                                                data.region(),
                                                data.accessKeyId(),
                                                data.appId(),
                                                data.templateId(),
                                                data.templateParams(),
                                                data.senderId(),
                                                data.signature());
                            }
                            return new View(
                                    snapshot.version(), data, configured, keys.configured());
                        })
                .orElseGet(() -> new View(0, SmsSettings.defaults(), false, keys.configured()));
    }

    @Transactional
    public View save(String actor, Input input) {
        guard.requireOperationGlobal(actor, PlatformOperation.SMS_UPDATE);
        if (input == null || input.data() == null || input.credentials() == null)
            invalid("invalidSms");
        input.data().validate();
        input.credentials().validate();
        var current = settings.find("sms");
        var state =
                current.map(s -> secrets.state(context(s.id())))
                        .orElse(new SecretStorePort.State(0, false));
        String action = input.credentials().action();
        if ("KEEP".equals(action)
                && state.configured()
                && !input.data()
                        .sameCredentialDestination(
                                mapper.readValue(
                                        current.orElseThrow().payload(), SmsSettings.class)))
            invalid("smsCredentialReplacementRequired");
        boolean willHaveCredential =
                "REPLACE".equals(action) || ("KEEP".equals(action) && state.configured());
        if (input.data().enabled()) {
            if (!willHaveCredential) invalid("smsCredentialRequired");
            keys.require();
        }
        var saved =
                settings.save(
                        "sms",
                        mapper.writeValueAsString(input.data()),
                        actor,
                        input.version(),
                        input.changeReason(),
                        input.sourceVersion());
        if (!"KEEP".equals(action)) {
            byte[] value =
                    "REPLACE".equals(action)
                            ? input.credentials().value().getBytes(StandardCharsets.UTF_8)
                            : null;
            try {
                secrets.save(context(saved.id()), state.version(), value);
            } finally {
                if (value != null) Arrays.fill(value, (byte) 0);
            }
            audit.append(
                    actor,
                    "REPLACE".equals(action)
                            ? "setting.smsSecretUpdated"
                            : "setting.smsSecretCleared",
                    saved.id());
        }
        return new View(saved.version(), input.data(), willHaveCredential, keys.configured());
    }
}
