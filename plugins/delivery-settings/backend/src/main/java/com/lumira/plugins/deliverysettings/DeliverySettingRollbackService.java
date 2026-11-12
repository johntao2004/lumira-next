package com.lumira.plugins.deliverysettings;

import com.lumira.platform.common.*;
import com.lumira.platform.settings.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class DeliverySettingRollbackService {
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
    }

    public record Input(
            long version, long sourceVersion, String changeReason, Credentials credentials) {}

    public record View(
            long version,
            Object data,
            Boolean passwordConfigured,
            Boolean credentialConfigured,
            Boolean encryptionConfigured) {}

    private final SettingStore store;
    private final ObjectMapper mapper;
    private final SmtpSettingsService smtp;
    private final SmsSettingsService sms;
    private final AdministrationPort guard;

    public DeliverySettingRollbackService(
            SettingStore store,
            ObjectMapper mapper,
            SmtpSettingsService smtp,
            SmsSettingsService sms,
            AdministrationPort guard) {
        this.store = store;
        this.mapper = mapper;
        this.smtp = smtp;
        this.sms = sms;
        this.guard = guard;
    }

    @Transactional
    public View rollback(String section, Input input, String actor) {
        var operation = DeliverySettingsSections.operation(section);
        if (input == null || input.version() < 0 || input.sourceVersion() < 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSettingRevision");
        guard.requireOperationGlobal(actor, operation);
        var revision =
                store.revision(section, input.sourceVersion())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "settingRevisionNotFound"));
        return switch (section) {
            case "smtp" -> {
                SmtpSettings data = mapper.readValue(revision.payload(), SmtpSettings.class);
                var credentials =
                        input.credentials() == null
                                ? null
                                : new SmtpSettingsService.Credentials(
                                        input.credentials().action(), input.credentials().value());
                var saved =
                        smtp.save(
                                actor,
                                new SmtpSettingsService.Input(
                                        input.version(),
                                        data,
                                        credentials,
                                        input.changeReason(),
                                        input.sourceVersion()));
                yield new View(
                        saved.version(),
                        saved.data(),
                        saved.passwordConfigured(),
                        null,
                        saved.encryptionConfigured());
            }
            case "sms" -> {
                SmsSettings data = mapper.readValue(revision.payload(), SmsSettings.class);
                var credentials =
                        input.credentials() == null
                                ? null
                                : new SmsSettingsService.Credentials(
                                        input.credentials().action(), input.credentials().value());
                var saved =
                        sms.save(
                                actor,
                                new SmsSettingsService.Input(
                                        input.version(),
                                        data,
                                        credentials,
                                        input.changeReason(),
                                        input.sourceVersion()));
                yield new View(
                        saved.version(),
                        saved.data(),
                        null,
                        saved.credentialConfigured(),
                        saved.encryptionConfigured());
            }
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "settingNotFound");
        };
    }
}
