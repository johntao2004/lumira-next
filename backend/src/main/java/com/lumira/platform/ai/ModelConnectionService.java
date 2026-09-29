package com.lumira.platform.ai;

import com.lumira.platform.ai.AiProviderProfile.ProviderKind;
import com.lumira.platform.common.SecretStorePort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ModelConnectionService {
    public record ToolCall(String id, String name, String arguments) {}
    public record ChatReply(String content, List<ToolCall> toolCalls) {}
    public record ModelOption(String id, String name, List<String> inputModalities, List<String> outputModalities) {
        public ModelOption {
            inputModalities = List.copyOf(inputModalities);
            outputModalities = List.copyOf(outputModalities);
        }
    }
    public record ConnectionResult(List<ModelOption> models, boolean configuredModelAvailable) {
        public ConnectionResult { models = List.copyOf(models); }
    }

    private static final int MAX_RESPONSE_BYTES = 2_000_000;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final SecretStorePort secrets;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;

    @Autowired
    public ModelConnectionService(SecretStorePort secrets, ObjectMapper mapper) {
        this(secrets, mapper, Duration.ofSeconds(60));
    }

    ModelConnectionService(SecretStorePort secrets, ObjectMapper mapper, Duration requestTimeout) {
        this.secrets = secrets;
        this.mapper = mapper;
        this.requestTimeout = requestTimeout;
    }

    public ConnectionResult probe(AiProviderProfile provider) {
        return probe(provider, null);
    }

    public ConnectionResult discoverModels(AiProviderProfile provider, String credential) {
        byte[] credentialBytes = credential == null || credential.isBlank()
            ? null : credential.getBytes(StandardCharsets.UTF_8);
        try {
            return probe(provider, credentialBytes);
        } finally {
            if (credentialBytes != null) Arrays.fill(credentialBytes, (byte) 0);
        }
    }

    private ConnectionResult probe(AiProviderProfile provider, byte[] credentialOverride) {
        JsonNode response = request(provider, "GET", apiUrl(provider, "/models"), null,
            provider.requiresCredential(), credentialOverride);
        JsonNode entries = provider.isDecisionModel() ? response.path("models") : response.path("data");
        var models = new ArrayList<ModelOption>();
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                String id = (provider.isDecisionModel() ? entry.path("name") : entry.path("id")).asText("").strip();
                if (!id.isBlank() && models.size() < 1000) {
                    String name = entry.path("name").asText("").strip();
                    if (name.isBlank()) name = id;
                    models.add(new ModelOption(id, name, stringValues(entry.path("input_modalities")),
                        stringValues(entry.path("output_modalities"))));
                }
            }
        }
        return new ConnectionResult(models, models.stream().anyMatch(model -> model.id().equals(provider.modelName())));
    }

    public ChatReply chat(AiProviderProfile provider, String modelName, List<Map<String, Object>> messages,
                          List<Map<String, Object>> tools) {
        if (provider.isDecisionModel()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "aiChatUnsupported");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", modelName);
        payload.put("messages", messages);
        payload.put("stream", false);
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
            payload.put("tool_choice", "auto");
        }
        JsonNode response = request(provider, "POST", apiUrl(provider, "/chat/completions"), payload,
            provider.requiresCredential());
        JsonNode message = response.path("choices").path(0).path("message");
        String content = message.path("content").asText("");
        var calls = new ArrayList<ToolCall>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                JsonNode function = call.path("function");
                calls.add(new ToolCall(call.path("id").asText(""), function.path("name").asText(""),
                    function.path("arguments").isString() ? function.path("arguments").asText("") : function.path("arguments").toString()));
            }
        }
        return new ChatReply(content, List.copyOf(calls));
    }

    public JsonNode judge(AiProviderProfile provider, String state, Map<String, Object> questions) {
        if (!provider.isDecisionModel()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "aiDecisionProviderRequired");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", provider.modelName());
        payload.put("state", state);
        payload.put("questions", questions);
        return request(provider, "POST", "https://api.typesafe.ai/v1/systemone", payload, true);
    }

    private JsonNode request(AiProviderProfile provider, String method, String url, Object payload, boolean requiredKey) {
        return request(provider, method, url, payload, requiredKey, null);
    }

    private JsonNode request(AiProviderProfile provider, String method, String url, Object payload,
            boolean requiredKey, byte[] credentialOverride) {
        if (provider.isOpenAiCompatibleCloud() &&
                !AiProviderService.isPublicCloudHost(URI.create(provider.baseUrl()).getHost())) throw unavailable();
        byte[] tokenBytes = credentialOverride == null
            ? secrets.reveal(secretContext(provider.id())).orElse(null) : credentialOverride;
        try {
            if (requiredKey && (tokenBytes == null || tokenBytes.length == 0)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "aiProviderCredentialRequired");
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                .header("Accept", "application/json");
            if (tokenBytes != null && tokenBytes.length > 0) {
                builder.header("Authorization", "Bearer " + new String(tokenBytes, StandardCharsets.UTF_8));
            }
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            } else {
                builder.GET();
            }
            HttpResponse<java.io.InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (java.io.InputStream stream = response.body()) {
                // Bounded read: never buffer more than the limit plus one byte from an untrusted endpoint.
                body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw unavailable("aiProviderAuthenticationFailed");
            }
            if (response.statusCode() == 404) {
                throw unavailable("aiModelDiscoveryEndpointUnavailable");
            }
            if (response.statusCode() == 429) {
                throw unavailable("aiProviderRateLimited");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300 || body.length > MAX_RESPONSE_BYTES) {
                throw unavailable();
            }
            return mapper.readTree(body);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "aiProviderRequestTimedOut");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        } finally {
            if (tokenBytes != null) Arrays.fill(tokenBytes, (byte) 0);
        }
    }

    public static List<Map<String, Object>> userToolDefinitions(boolean canWriteUsers) {
        var definitions = new ArrayList<Map<String, Object>>();
        definitions.add(Map.of("type", "function", "function", Map.of(
                "name", "users_search", "description", "Search visible user accounts by username or display name.",
                "parameters", Map.of("type", "object", "properties", Map.of("search", Map.of("type", "string")), "required", List.of("search")))));
        if (canWriteUsers) {
            definitions.add(Map.of("type", "function", "function", Map.of(
                "name", "users_propose_create", "description", "Propose creating a user. Never execute it; the administrator must confirm in the application.",
                "parameters", Map.of("type", "object", "properties", Map.of("username", Map.of("type", "string"), "displayName", Map.of("type", "string")), "required", List.of("username", "displayName")))));
            definitions.add(Map.of("type", "function", "function", Map.of(
                "name", "users_propose_update", "description", "Propose changing a visible user's display name or enabled status. A confirmation is required.",
                "parameters", Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string"), "version", Map.of("type", "integer"), "displayName", Map.of("type", "string"), "enabled", Map.of("type", "boolean")), "required", List.of("id", "version", "displayName", "enabled")))));
            definitions.add(Map.of("type", "function", "function", Map.of(
                "name", "users_propose_delete", "description", "Propose soft-deleting a visible user. A confirmation is required.",
                "parameters", Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string"), "version", Map.of("type", "integer")), "required", List.of("id", "version")))));
        }
        return List.copyOf(definitions);
    }

    public static String secretContext(java.util.UUID id) { return "ai-provider:" + id + ":credential"; }

    static String apiUrl(AiProviderProfile provider, String endpoint) {
        String base = provider.baseUrl().replaceAll("/+$", "");
        if (provider.isDecisionModel()) return "https://api.typesafe.ai/v1" + endpoint;
        if (provider.providerKind() == ProviderKind.DEEPSEEK) return base + endpoint;
        if (!base.matches(".*/v[0-9]+$")) base += "/v1";
        return base + endpoint;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "aiProviderUnavailable");
    }

    private static ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, reason);
    }

    private static List<String> stringValues(JsonNode node) {
        if (!node.isArray()) return List.of();
        var values = new ArrayList<String>();
        for (JsonNode item : node) {
            String value = item.isTextual() ? item.asText().strip() : "";
            if (!value.isBlank() && value.length() <= 64 && values.size() < 16 && !values.contains(value)) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }
}
