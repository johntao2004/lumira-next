package com.lumira.platform.ai;

import com.lumira.platform.common.AiTextGenerationServicePort;
import com.lumira.platform.common.SecretStorePort;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@org.springframework.context.annotation.Primary
public class AiTextGenerationAdapter implements AiTextGenerationServicePort {
    private final AiProviderRepository providers;
    private final ModelConnectionService connections;
    private final SecretStorePort secrets;

    public AiTextGenerationAdapter(
            AiProviderRepository providers,
            ModelConnectionService connections,
            SecretStorePort secrets) {
        this.providers = providers;
        this.connections = connections;
        this.secrets = secrets;
    }

    private boolean usable(AiProviderProfile provider) {
        return provider.enabled()
                && !provider.isDecisionModel()
                && provider.capabilityCodes().contains("CHAT")
                && provider.modelName() != null
                && !provider.modelName().isBlank()
                && (!provider.requiresCredential()
                        || secrets.state(ModelConnectionService.secretContext(provider.id()))
                                .configured());
    }

    @Override
    public List<Provider> available() {
        return providers.findAll().stream()
                .filter(this::usable)
                .map(p -> new Provider(p.id(), p.name(), p.modelName()))
                .toList();
    }

    @Override
    public String chat(UUID id, String model, List<Map<String, String>> messages) {
        var p =
                providers
                        .find(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "localizationAiUnavailable"));
        if (!usable(p) || !p.modelName().equals(model))
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "localizationAiUnavailable");
        return connections
                .chat(
                        p,
                        model,
                        messages.stream()
                                .map(m -> new java.util.LinkedHashMap<String, Object>(m))
                                .map(m -> (Map<String, Object>) m)
                                .toList(),
                        List.of())
                .content();
    }
}
