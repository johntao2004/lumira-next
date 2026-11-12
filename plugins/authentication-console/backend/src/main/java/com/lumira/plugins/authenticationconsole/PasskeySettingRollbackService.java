package com.lumira.plugins.authenticationconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.settings.*;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PasskeySettingRollbackService {
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties("credentials")
    public record Input(long version, long sourceVersion, String changeReason) {}

    public record View(
            long version,
            Object data,
            Boolean passwordConfigured,
            Boolean credentialConfigured,
            Boolean encryptionConfigured) {}

    private static final Set<String> SECTIONS = Set.of("passkey");

    private final SettingStore store;
    private final ObjectMapper mapper;
    private final PasskeySettingsService passkey;
    private final AdministrationPort guard;

    public PasskeySettingRollbackService(
            SettingStore store,
            ObjectMapper mapper,
            PasskeySettingsService passkey,
            AdministrationPort guard) {
        this.store = store;
        this.mapper = mapper;
        this.passkey = passkey;
        this.guard = guard;
    }

    @Transactional
    public View rollback(String section, Input input, String actor) {
        if (!SECTIONS.contains(section))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "settingNotFound");
        if (input == null || input.version() < 0 || input.sourceVersion() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSettingRevision");
        }
        guard.requireOperationGlobal(
                actor, com.lumira.platform.common.PlatformOperation.PASSKEY_UPDATE);
        var revision =
                store.revision(section, input.sourceVersion())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "settingRevisionNotFound"));
        return switch (section) {
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
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "settingNotFound");
        };
    }
}
