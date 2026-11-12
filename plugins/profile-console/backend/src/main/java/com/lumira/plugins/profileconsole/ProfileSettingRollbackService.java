package com.lumira.plugins.profileconsole;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.*;
import com.lumira.platform.settings.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProfileSettingRollbackService {
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties("credentials")
    public record Input(long version, long sourceVersion, String changeReason) {}

    public record View(
            long version,
            Object data,
            Boolean passwordConfigured,
            Boolean credentialConfigured,
            Boolean encryptionConfigured) {}

    private final SettingStore store;
    private final ObjectMapper mapper;
    private final AdministrationPort guard;
    private final ProfileDictionaryPort dictionaries;

    public ProfileSettingRollbackService(
            SettingStore store,
            ObjectMapper mapper,
            AdministrationPort guard,
            ProfileDictionaryPort dictionaries) {
        this.store = store;
        this.mapper = mapper;
        this.guard = guard;
        this.dictionaries = dictionaries;
    }

    @Transactional
    public View rollback(Input input, String actor) {
        if (input == null || input.version() < 0 || input.sourceVersion() < 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSettingRevision");
        guard.requireOperationGlobal(actor, PlatformOperation.PROFILE_FIELDS_UPDATE);
        var revision =
                store.revision("profile-fields", input.sourceVersion())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "settingRevisionNotFound"));
        ProfileFields data =
                mapper.readValue(revision.payload(), ProfileFields.class).withResolvedPlacements();
        ProfileFieldsValidation.validate(data, dictionaries);
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
