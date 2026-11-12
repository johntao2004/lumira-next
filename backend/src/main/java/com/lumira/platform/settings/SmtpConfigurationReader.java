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
public class SmtpConfigurationReader implements MailConfigurationPort {
    private final SettingStore settings;
    private final SecretStore secrets;
    private final SecretKeys keys;
    private final ObjectMapper mapper;

    public SmtpConfigurationReader(
            SettingStore settings, SecretStore secrets, SecretKeys keys, ObjectMapper mapper) {
        this.settings = settings;
        this.secrets = secrets;
        this.keys = keys;
        this.mapper = mapper;
    }

    private static void invalid(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static String context(UUID id) {
        return "settings:" + id + ":smtp-password";
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MailConfigurationPort.Settings readSettings() {
        SmtpSettings data =
                settings.find("smtp")
                        .map(snapshot -> mapper.readValue(snapshot.payload(), SmtpSettings.class))
                        .orElseGet(SmtpSettings::defaults);
        data.validate();
        return new MailConfigurationPort.Settings(
                data.enabled(),
                data.host(),
                data.port(),
                data.security(),
                data.authentication(),
                data.username(),
                data.fromAddress(),
                data.fromName());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public boolean ready() {
        var current = settings.find("smtp");
        if (current.isEmpty()) return false;
        var snapshot = current.orElseThrow();
        SmtpSettings data;
        try {
            data = mapper.readValue(snapshot.payload(), SmtpSettings.class);
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
        if (Boolean.TRUE.equals(data.authentication()))
            return secrets.state(context(snapshot.id())).configured() && keys.configured();
        return true;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Configuration load() {
        var snapshot =
                settings.find("smtp")
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.SERVICE_UNAVAILABLE, "smtpUnavailable"));
        var data = mapper.readValue(snapshot.payload(), SmtpSettings.class);
        data.validate();
        if (!data.enabled())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "smtpUnavailable");
        byte[] password = null;
        if (data.authentication()) {
            try {
                password =
                        secrets.reveal(context(snapshot.id()))
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.SERVICE_UNAVAILABLE,
                                                        "smtpPasswordRequired"));
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "secretKeyUnavailable");
            }
        }
        return new Configuration(
                new MailConfigurationPort.Settings(
                        data.enabled(),
                        data.host(),
                        data.port(),
                        data.security(),
                        data.authentication(),
                        data.username(),
                        data.fromAddress(),
                        data.fromName()),
                password);
    }
}
