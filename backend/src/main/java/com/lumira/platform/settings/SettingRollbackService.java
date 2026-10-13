package com.lumira.platform.settings;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.dictionaries.api.DictionaryLookupPort;
import com.lumira.platform.identity.api.ProfileFields;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class SettingRollbackService {
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

    private static final Set<String> SECTIONS =
            Set.of(
                    "branding",
                    "smtp",
                    "sms",
                    "profile-fields",
                    "agreements",
                    "watermark",
                    "maintenance",
                    "passkey");

    private final SettingStore store;
    private final ObjectMapper mapper;
    private final MaintenanceService maintenance;
    private final PasskeySettingsService passkey;
    private final SmtpSettingsService smtp;
    private final SmsSettingsService sms;
    private final AdministrationPort guard;
    private final DictionaryLookupPort dictionaries;
    private final BrandingAssetReferences brandingAssets;

    public SettingRollbackService(
            SettingStore store,
            ObjectMapper mapper,
            MaintenanceService maintenance,
            PasskeySettingsService passkey,
            SmtpSettingsService smtp,
            SmsSettingsService sms,
            AdministrationPort guard,
            DictionaryLookupPort dictionaries) {
        this(store, mapper, maintenance, passkey, smtp, sms, guard, dictionaries, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SettingRollbackService(
            SettingStore store,
            ObjectMapper mapper,
            MaintenanceService maintenance,
            PasskeySettingsService passkey,
            SmtpSettingsService smtp,
            SmsSettingsService sms,
            AdministrationPort guard,
            DictionaryLookupPort dictionaries,
            BrandingAssetReferences brandingAssets) {
        this.store = store;
        this.mapper = mapper;
        this.maintenance = maintenance;
        this.passkey = passkey;
        this.smtp = smtp;
        this.sms = sms;
        this.guard = guard;
        this.dictionaries = dictionaries;
        this.brandingAssets = brandingAssets;
    }

    @Transactional
    public View rollback(String section, Input input, String actor) {
        if (!SECTIONS.contains(section))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "settingNotFound");
        if (input == null || input.version() < 0 || input.sourceVersion() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSettingRevision");
        }
        guard.requireOperationGlobal(actor, SettingStore.updateOperation(section));
        var revision =
                store.revision(section, input.sourceVersion())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "settingRevisionNotFound"));
        return switch (section) {
            case "branding" -> {
                Branding data = mapper.readValue(revision.payload(), Branding.class);
                data.validate();
                var saved =
                        store.save(
                                section,
                                revision.payload(),
                                actor,
                                input.version(),
                                input.changeReason(),
                                input.sourceVersion());
                if (brandingAssets != null) brandingAssets.retain(data);
                yield new View(saved.version(), data, null, null, null);
            }
            case "agreements" -> {
                Agreements data = mapper.readValue(revision.payload(), Agreements.class);
                data.validate();
                var saved =
                        store.save(
                                section,
                                revision.payload(),
                                actor,
                                input.version(),
                                input.changeReason(),
                                input.sourceVersion());
                yield new View(saved.version(), data, null, null, null);
            }
            case "watermark" -> {
                WatermarkSettings data =
                        mapper.readValue(revision.payload(), WatermarkSettings.class);
                data.validate();
                var saved =
                        store.save(
                                section,
                                revision.payload(),
                                actor,
                                input.version(),
                                input.changeReason(),
                                input.sourceVersion());
                yield new View(saved.version(), data, null, null, null);
            }
            case "profile-fields" -> rollbackProfileFields(revision.payload(), input, actor);
            case "maintenance" -> {
                MaintenanceSettings data =
                        mapper.readValue(revision.payload(), MaintenanceSettings.class);
                var saved =
                        maintenance.save(
                                data,
                                actor,
                                input.version(),
                                input.changeReason(),
                                input.sourceVersion());
                yield new View(saved.version(), saved.data(), null, null, null);
            }
            case "passkey" -> {
                PasskeySettings data = mapper.readValue(revision.payload(), PasskeySettings.class);
                var saved =
                        passkey.save(
                                data,
                                actor,
                                input.version(),
                                input.changeReason(),
                                input.sourceVersion());
                yield new View(saved.version(), saved.data(), null, null, null);
            }
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

    private View rollbackProfileFields(String payload, Input input, String actor) {
        ProfileFields data =
                mapper.readValue(payload, ProfileFields.class).withResolvedPlacements();
        try {
            data.validate();
        } catch (ProfileFields.Invalid error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
        for (var field : data.fields()) {
            if (field.usesDictionary() && !dictionaries.isEnabled(field.dictionaryCode())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidProfileFields");
            }
        }
        var saved =
                store.save(
                        "profile-fields",
                        mapper.writeValueAsString(data),
                        actor,
                        input.version(),
                        input.changeReason(),
                        input.sourceVersion());
        return new View(saved.version(), data, null, null, null);
    }
}
