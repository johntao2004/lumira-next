package com.lumira.platform.settings;

import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProfileSchemaReader implements ProfileSchemaPort {
    private final SettingStore store;
    private final ObjectMapper mapper;

    public ProfileSchemaReader(SettingStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override
    public Snapshot read() {
        return store.find("profile-fields")
                .map(
                        s ->
                                new Snapshot(
                                        s.version(),
                                        mapper.readValue(s.payload(), ProfileFields.class)
                                                .withDefaultAvatar()
                                                .withResolvedPlacements()
                                                .sorted()))
                .orElseGet(
                        () -> new Snapshot(0, ProfileFields.defaults().withResolvedPlacements()));
    }
}
