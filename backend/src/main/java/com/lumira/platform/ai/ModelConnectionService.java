package com.lumira.platform.ai;

import com.lumira.platform.ai.AiProviderProfile.ProviderKind;
import com.lumira.platform.common.DeadlineExchange;
import com.lumira.platform.common.GuardedSockets;
import com.lumira.platform.common.PublicHosts;
import com.lumira.platform.common.SecretStorePort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ModelConnectionService {
    public record ToolCall(String id, String name, String arguments) {}

    public record ChatReply(String content, List<ToolCall> toolCalls) {}

    public record ModelOption(
            String id,
            String name,
            List<String> inputModalities,
            List<String> outputModalities,
            List<String> capabilityCodes) {
        public ModelOption {
            inputModalities = List.copyOf(inputModalities);
            outputModalities = List.copyOf(outputModalities);
            capabilityCodes = List.copyOf(capabilityCodes);
        }

        public ModelOption(
                String id,
                String name,
                List<String> inputModalities,
                List<String> outputModalities) {
            this(id, name, inputModalities, outputModalities, List.of());
        }
    }

    public record ConnectionResult(List<ModelOption> models, boolean configuredModelAvailable) {
        public ConnectionResult {
            models = List.copyOf(models);
        }
    }

    private static final int MAX_RESPONSE_BYTES = 2_000_000;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final SecretStorePort secrets;
    private final AiConnectionSnapshot snapshots;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;

    @Autowired
    public ModelConnectionService(
            SecretStorePort secrets, ObjectMapper mapper, AiConnectionSnapshot snapshots) {
        this(secrets, mapper, snapshots, Duration.ofSeconds(60));
    }

    ModelConnectionService(SecretStorePort secrets, ObjectMapper mapper, Duration requestTimeout) {
        this(secrets, mapper, null, requestTimeout);
    }

    ModelConnectionService(
            SecretStorePort secrets,
            ObjectMapper mapper,
            AiConnectionSnapshot snapshots,
            Duration requestTimeout) {
        this.secrets = secrets;
        this.snapshots = snapshots;
        this.mapper = mapper;
        this.requestTimeout = requestTimeout;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ConnectionResult probe(AiProviderProfile provider) {
        return probe(provider, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ConnectionResult discoverModels(AiProviderProfile provider, String credential) {
        byte[] credentialBytes =
                credential == null || credential.isBlank()
                        ? null
                        : credential.getBytes(StandardCharsets.UTF_8);
        try {
            return withSnapshot(
                    provider, credentialBytes, provider.modelName().isBlank(), this::probeCaptured);
        } finally {
            if (credentialBytes != null) Arrays.fill(credentialBytes, (byte) 0);
        }
    }

    private ConnectionResult probe(AiProviderProfile provider, byte[] credentialOverride) {
        return withSnapshot(provider, credentialOverride, false, this::probeCaptured);
    }

    private ConnectionResult probeCaptured(AiProviderProfile provider, byte[] credential) {
        JsonNode response =
                request(
                        provider,
                        "GET",
                        apiUrl(provider, "/models"),
                        null,
                        provider.requiresCredential(),
                        credential);
        JsonNode entries =
                provider.isDecisionModel() ? response.path("models") : response.path("data");
        var models = new ArrayList<ModelOption>();
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                String id =
                        (provider.isDecisionModel() ? entry.path("name") : entry.path("id"))
                                .asText("")
                                .strip();
                if (!id.isBlank() && models.size() < 1000) {
                    String name = entry.path("name").asText("").strip();
                    if (name.isBlank()) name = id;
                    models.add(
                            new ModelOption(
                                    id,
                                    name,
                                    stringValues(
                                            entry.has("input_modalities")
                                                    ? entry.path("input_modalities")
                                                    : entry.path("architecture")
                                                            .path("input_modalities")),
                                    stringValues(entry.path("output_modalities")),
                                    provider.supportsUserTools(id)
                                            ? List.of("CHAT", "TOOL_CALLING")
                                            : provider.providerCapabilityCodes()));
                }
            }
        }
        return new ConnectionResult(
                models, models.stream().anyMatch(model -> model.id().equals(provider.modelName())));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChatReply chat(
            AiProviderProfile provider,
            String modelName,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {
        if (provider.isDecisionModel())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "aiChatUnsupported");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", modelName);
        payload.put("messages", messages);
        payload.put("stream", false);
        // Lumira carries visible text history only, rather than private reasoning/tool transcripts.
        if (provider.providerKind() == ProviderKind.DEEPSEEK
                && provider.supportsUserTools(modelName)) {
            payload.put("thinking", Map.of("type", "disabled"));
            payload.put("max_tokens", 2048);
        }
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
            payload.put("tool_choice", "auto");
        }
        JsonNode response =
                withSnapshot(
                        provider,
                        null,
                        false,
                        (snapshot, key) ->
                                request(
                                        snapshot,
                                        "POST",
                                        apiUrl(snapshot, "/chat/completions"),
                                        payload,
                                        snapshot.requiresCredential(),
                                        key));
        JsonNode message = response.path("choices").path(0).path("message");
        String content = message.path("content").asText("");
        var calls = new ArrayList<ToolCall>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                JsonNode function = call.path("function");
                calls.add(
                        new ToolCall(
                                call.path("id").asText(""),
                                function.path("name").asText(""),
                                function.path("arguments").isString()
                                        ? function.path("arguments").asText("")
                                        : function.path("arguments").toString()));
            }
        }
        return new ChatReply(content, List.copyOf(calls));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public JsonNode judge(AiProviderProfile provider, String state, Map<String, Object> questions) {
        if (!provider.isDecisionModel())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "aiDecisionProviderRequired");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", provider.modelName());
        payload.put("state", state);
        payload.put("questions", questions);
        return withSnapshot(
                provider,
                null,
                false,
                (snapshot, key) ->
                        request(
                                snapshot,
                                "POST",
                                AiProviderProfile.DECISION_ORIGIN + "/v1/systemone",
                                payload,
                                true,
                                key));
    }

    private JsonNode request(
            AiProviderProfile provider,
            String method,
            String url,
            Object payload,
            boolean requiredKey,
            byte[] tokenBytes) {
        if (provider.isOpenAiCompatibleCloud()
                && !PublicHosts.isAllowed(URI.create(provider.baseUrl()).getHost()))
            throw unavailable();
        try {
            if (requiredKey && (tokenBytes == null || tokenBytes.length == 0)) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "aiProviderCredentialRequired");
            }
            String bearer =
                    tokenBytes != null && tokenBytes.length > 0
                            ? "Bearer " + new String(tokenBytes, StandardCharsets.UTF_8)
                            : null;
            TransportResponse response =
                    DeadlineExchange.run(
                            requestTimeout,
                            scope -> {
                                scope.check();
                                byte[] requestBody =
                                        "POST".equals(method)
                                                ? mapper.writeValueAsBytes(payload)
                                                : null;
                                int status;
                                byte[] body;
                                if (provider.isOpenAiCompatibleCloud()) {
                                    var headers = new LinkedHashMap<String, String>();
                                    headers.put("Accept", "application/json");
                                    if (bearer != null) headers.put("Authorization", bearer);
                                    if (requestBody != null)
                                        headers.put("Content-Type", "application/json");
                                    var cloudResponse =
                                            GuardedSockets.exchangePublicCloud(
                                                    URI.create(url),
                                                    method,
                                                    headers,
                                                    requestBody,
                                                    requestTimeout,
                                                    MAX_RESPONSE_BYTES + 1,
                                                    scope);
                                    status = cloudResponse.status();
                                    body = cloudResponse.body();
                                } else {
                                    HttpRequest.Builder builder =
                                            HttpRequest.newBuilder(URI.create(url))
                                                    .timeout(requestTimeout)
                                                    .header("Accept", "application/json");
                                    if (bearer != null) builder.header("Authorization", bearer);
                                    if (requestBody != null) {
                                        builder.header("Content-Type", "application/json")
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofByteArray(
                                                                requestBody));
                                    } else {
                                        builder.GET();
                                    }
                                    HttpResponse<java.io.InputStream> localResponse =
                                            client.send(
                                                    builder.build(),
                                                    HttpResponse.BodyHandlers.ofInputStream());
                                    status = localResponse.statusCode();
                                    try (java.io.InputStream stream =
                                            scope.track(localResponse.body())) {
                                        body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                                    }
                                }
                                scope.check();
                                return new TransportResponse(status, body);
                            });
            int status = response.status();
            byte[] body = response.body();
            if (status == 401 || status == 403) {
                throw unavailable("aiProviderAuthenticationFailed");
            }
            if (status == 404) {
                throw unavailable("aiModelDiscoveryEndpointUnavailable");
            }
            if (status == 429) {
                throw unavailable("aiProviderRateLimited");
            }
            if (status < 200 || status >= 300 || body.length > MAX_RESPONSE_BYTES) {
                throw unavailable();
            }
            return mapper.readTree(body);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT, "aiProviderRequestTimedOut");
        } catch (DeadlineExchange.Busy busy) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "aiProviderBusy");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        }
    }

    private <T> T withSnapshot(
            AiProviderProfile requested,
            byte[] credentialOverride,
            boolean allowMissingProvider,
            SnapshotOperation<T> operation) {
        if (snapshots != null) {
            try (AiConnectionSnapshot.Captured captured =
                    snapshots.capture(requested, credentialOverride, allowMissingProvider)) {
                return operation.run(captured.profile(), captured.credential());
            }
        }
        byte[] owned =
                credentialOverride == null
                        ? secrets.reveal(secretContext(requested.id())).orElse(null)
                        : Arrays.copyOf(credentialOverride, credentialOverride.length);
        try {
            return operation.run(requested, owned);
        } finally {
            if (owned != null) Arrays.fill(owned, (byte) 0);
        }
    }

    @FunctionalInterface
    private interface SnapshotOperation<T> {
        T run(AiProviderProfile provider, byte[] credential);
    }

    private record TransportResponse(int status, byte[] body) {}

    public static List<Map<String, Object>> userToolDefinitions(boolean canWriteUsers) {
        var definitions = new ArrayList<Map<String, Object>>();
        definitions.add(
                Map.of(
                        "type",
                        "function",
                        "function",
                        Map.of(
                                "name",
                                "users_search",
                                "description",
                                "Search visible user accounts by username or display name. Use an empty search string to list visible users. Do not repeat the same query.",
                                "parameters",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("search", Map.of("type", "string")),
                                        "additionalProperties",
                                        false,
                                        "required",
                                        List.of("search")))));
        if (canWriteUsers) {
            definitions.add(
                    Map.of(
                            "type",
                            "function",
                            "function",
                            Map.of(
                                    "name",
                                    "users_propose_create",
                                    "description",
                                    "Propose creating a user. Never execute it; the administrator must confirm in the application.",
                                    "parameters",
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "username",
                                                    Map.of("type", "string"),
                                                    "displayName",
                                                    Map.of("type", "string")),
                                            "required",
                                            List.of("username", "displayName")))));
            definitions.add(
                    Map.of(
                            "type",
                            "function",
                            "function",
                            Map.of(
                                    "name",
                                    "users_propose_update",
                                    "description",
                                    "Propose changing a visible user's display name or enabled status. A confirmation is required.",
                                    "parameters",
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "id",
                                                    Map.of("type", "string"),
                                                    "version",
                                                    Map.of("type", "integer"),
                                                    "displayName",
                                                    Map.of("type", "string"),
                                                    "enabled",
                                                    Map.of("type", "boolean")),
                                            "required",
                                            List.of("id", "version", "displayName", "enabled")))));
            definitions.add(
                    Map.of(
                            "type",
                            "function",
                            "function",
                            Map.of(
                                    "name",
                                    "users_propose_delete",
                                    "description",
                                    "Propose soft-deleting a visible user. A confirmation is required.",
                                    "parameters",
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "id",
                                                    Map.of("type", "string"),
                                                    "version",
                                                    Map.of("type", "integer")),
                                            "required",
                                            List.of("id", "version")))));
        }
        return List.copyOf(definitions);
    }

    public static String secretContext(java.util.UUID id) {
        return "ai-provider:" + id + ":credential";
    }

    static String apiUrl(AiProviderProfile provider, String endpoint) {
        String base = provider.baseUrl().replaceAll("/+$", "");
        if (provider.isDecisionModel()) return AiProviderProfile.DECISION_ORIGIN + "/v1" + endpoint;
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
            if (!value.isBlank()
                    && value.length() <= 64
                    && values.size() < 16
                    && !values.contains(value)) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }
}
