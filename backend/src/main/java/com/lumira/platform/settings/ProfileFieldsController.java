package com.lumira.platform.settings;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileRegistrationLimits;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
public class ProfileFieldsController {
    public record View(long version, ProfileFields data) {}

    public record Input(long version, ProfileFields data, String changeReason) {}

    private final ProfileSchemaReader schemas;
    private final SettingStore store;
    private final ObjectMapper mapper;
    private final com.lumira.platform.common.AdministrationPort guard;
    private final ProfileDictionaryPort dictionaries;

    public ProfileFieldsController(
            SettingStore store,
            ObjectMapper mapper,
            com.lumira.platform.common.AdministrationPort guard,
            ProfileDictionaryPort dictionaries,
            ProfileSchemaReader schemas) {
        this.schemas = schemas;
        this.store = store;
        this.mapper = mapper;
        this.guard = guard;
        this.dictionaries = dictionaries;
    }

    private void validate(ProfileFields data, String actor) {
        guard.require(actor, "platform:settings:write");
        guard.requireGlobal(actor, "platform:settings:write");
        try {
            data.validate();
        } catch (ProfileFields.Invalid error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
        for (var field : data.fields())
            if (field.usesDictionary() && !dictionaries.isEnabled(field.dictionaryCode()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidProfileFields");
        var publicFields = ProfileRegistrationLimits.publicFields(data);
        var codes = ProfileRegistrationLimits.dictionaryCodes(publicFields);
        var counts =
                codes.isEmpty()
                        ? java.util.Map.<String, Long>of()
                        : dictionaries.enabledOptionCounts(codes);
        if (!ProfileRegistrationLimits.withinOptionLimits(publicFields, counts))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "registrationDictionaryTooLarge");
    }

    @GetMapping("/api/platform/settings/profile-fields")
    public View read() {
        var schema = schemas.read();
        return new View(schema.version(), schema.data());
    }

    @GetMapping("/api/account/profile-fields")
    public View accountSchema() {
        var schema = schemas.read();
        return new View(schema.version(), schema.data().forHost("account-profile"));
    }

    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/api/platform/settings/profile-fields")
    public View write(
            @BoundedBody(maxBytes = 1024 * 1024, errorCode = "invalidProfileFields") @RequestBody
                    Input input,
            Authentication auth) {
        if (input.data() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidProfileFields");
        var data = input.data().withResolvedPlacements();
        validate(data, auth.getName());
        var saved =
                store.save(
                        "profile-fields",
                        mapper.writeValueAsString(data),
                        auth.getName(),
                        input.version(),
                        input.changeReason());
        return new View(saved.version(), data);
    }
}
