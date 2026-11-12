package com.lumira.platform.settings;

import com.lumira.platform.common.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Stored runtime configuration remains usable when its optional management plugin is absent. */
@Service
public class SmsConfigurationReader implements SmsConfigurationPort {
    private final SettingStore settings;
    private final SecretStore secrets;
    private final SecretKeys keys;
    private final ObjectMapper mapper;

    public SmsConfigurationReader(
            SettingStore settings, SecretStore secrets, SecretKeys keys, ObjectMapper mapper) {
        this.settings = settings;
        this.secrets = secrets;
        this.keys = keys;
        this.mapper = mapper;
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
        SmsSettings data =
                settings.find("sms")
                        .map(snapshot -> mapper.readValue(snapshot.payload(), SmsSettings.class))
                        .orElseGet(SmsSettings::defaults);
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
}
