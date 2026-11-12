package com.lumira.platform.settings;

import com.lumira.platform.common.PluginDependenciesPort;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import java.util.List;
import org.springframework.stereotype.Component;

/** Read the current schema on every lifecycle check; references must be detached manually. */
@Component
public final class ProfileDictionaryDependencies implements PluginDependenciesPort {
    private final ProfileSchemaPort schemas;

    public ProfileDictionaryDependencies(ProfileSchemaPort schemas) {
        this.schemas = schemas;
    }

    @Override
    public List<String> blockers(String providerId) {
        if (!"dictionaries".equals(providerId)) return List.of();
        return schemas.read().data().fields().stream().anyMatch(field -> field.usesDictionary())
                ? List.of("dictionaryReferenced:settings")
                : List.of();
    }
}
