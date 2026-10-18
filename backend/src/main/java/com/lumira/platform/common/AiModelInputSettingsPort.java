package com.lumira.platform.common;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Typed, non-secret attachment policy; authorization and audit belong to the provider service. */
public interface AiModelInputSettingsPort {
    Map<String, List<String>> read(UUID providerId);

    void save(UUID providerId, Map<String, List<String>> models, String actor);

    void delete(UUID providerId);
}
