package com.lumira.platform.common;

import java.util.*;

/**
 * Stable text-generation capability. Credentials and provider implementations stay in the owning AI
 * plugin.
 */
public interface AiTextGenerationPort {
    record Provider(UUID id, String name, String modelName) {}

    List<Provider> available();

    String chat(UUID providerId, String modelName, List<Map<String, String>> messages);
}
