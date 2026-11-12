package com.lumira.platform.common;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Preserves the SDK 1 text-generation entry point while implementation lives in an owned plugin.
 */
@Component
public final class AiTextGenerationBridge implements AiTextGenerationPort {
    private final ObjectProvider<PluginServicesPort> plugins;

    public AiTextGenerationBridge(ObjectProvider<PluginServicesPort> plugins) {
        this.plugins = plugins;
    }

    @Override
    public List<Provider> available() {
        return plugins.getObject()
                .service(
                        "ai",
                        AiTextGenerationServicePort.class,
                        AiTextGenerationServicePort::available);
    }

    @Override
    public String chat(UUID providerId, String modelName, List<Map<String, String>> messages) {
        return plugins.getObject()
                .service(
                        "ai",
                        AiTextGenerationServicePort.class,
                        provider -> provider.chat(providerId, modelName, messages));
    }
}
