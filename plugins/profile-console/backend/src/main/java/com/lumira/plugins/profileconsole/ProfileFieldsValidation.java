package com.lumira.plugins.profileconsole;

import com.lumira.platform.identity.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Normal publication and historical restoration share the same bounded schema checks. */
final class ProfileFieldsValidation {
    static void validate(ProfileFields data, ProfileDictionaryPort dictionaries) {
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

    private ProfileFieldsValidation() {}
}
