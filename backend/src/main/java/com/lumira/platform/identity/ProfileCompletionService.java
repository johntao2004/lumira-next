package com.lumira.platform.identity;

import com.lumira.platform.identity.api.AvatarStatusPort;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileCompletionService {
    public record View(long version, long schemaVersion, ProfileCompletion.Score score) {}

    private final ProfileRepository profiles;
    private final ProfileValuesService values;
    private final ProfileSchemaPort schemas;
    private final ProfileDictionaryPort dictionaries;
    private final AvatarStatusPort avatars;

    public ProfileCompletionService(
            ProfileRepository profiles,
            ProfileValuesService values,
            ProfileSchemaPort schemas,
            ProfileDictionaryPort dictionaries,
            AvatarStatusPort avatars) {
        this.profiles = profiles;
        this.values = values;
        this.schemas = schemas;
        this.dictionaries = dictionaries;
        this.avatars = avatars;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(String actor) {
        var profile = profiles.find(actor, false);
        var schema = schemas.read();
        var accountSchema = schema.data().forHost("account-profile");
        var custom = values.storedValues(profile.id());
        var builtin =
                Map.of(
                        "displayName",
                        profile.displayName(),
                        "realName",
                        profile.realName(),
                        "birthDate",
                        profile.birthMonth(),
                        "region",
                        profile.region());
        var completed = new HashSet<String>();
        var selections = new HashMap<String, ProfileDictionaryPort.Selection>();
        if (schema.data().avatar().filter(ProfileFields.Field::visible).isPresent()
                && avatars.hasAvatar(profile.id())) completed.add("avatarUrl");
        for (var field : accountSchema.fields()) {
            if (!field.visible()) continue;
            String value = field.custom() ? custom.get(field.key()) : builtin.get(field.key());
            if (value == null || value.isBlank()) continue;
            if (field.custom())
                try {
                    ProfileValues.merge(
                            new ProfileFields(List.of(field)),
                            Map.of(),
                            Map.of(field.key(), value));
                    if (field.usesDictionary()) {
                        selections.put(
                                field.key(),
                                new ProfileDictionaryPort.Selection(
                                        field.dictionaryCode(), UUID.fromString(value)));
                        continue;
                    }
                } catch (ResponseStatusException e) {
                    if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 404)
                        continue;
                    throw e;
                }
            completed.add(field.key());
        }
        var available = dictionaries.enabledSelections(selections.values());
        selections.forEach(
                (key, selection) -> {
                    if (available.contains(selection)) completed.add(key);
                });
        return new View(
                profile.version(),
                schema.version(),
                ProfileCompletion.calculate(accountSchema, completed));
    }
}
