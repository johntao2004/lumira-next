package com.lumira.platform.ai;

import static com.lumira.platform.ai.AiProviderProfile.ProviderKind;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AiModelInputSettingsPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.PublicHosts;
import com.lumira.platform.common.SecretStorePort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AiProviderService {
    public record CredentialChange(
            String action, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String value) {
        @Override
        public String toString() {
            return "CredentialChange[redacted]";
        }
    }

    public record ProviderWrite(
            String name,
            ProviderKind providerKind,
            String baseUrl,
            String modelName,
            List<String> modelNames,
            List<String> capabilityCodes,
            boolean enabled,
            Long version,
            CredentialChange credential,
            Map<String, List<String>> modelFileFormats) {
        public ProviderWrite(
                String name,
                ProviderKind providerKind,
                String baseUrl,
                String modelName,
                List<String> modelNames,
                List<String> capabilityCodes,
                boolean enabled,
                Long version,
                CredentialChange credential) {
            this(
                    name,
                    providerKind,
                    baseUrl,
                    modelName,
                    modelNames,
                    capabilityCodes,
                    enabled,
                    version,
                    credential,
                    null);
        }

        public ProviderWrite(
                String name,
                ProviderKind providerKind,
                String baseUrl,
                String modelName,
                List<String> capabilityCodes,
                boolean enabled,
                Long version,
                CredentialChange credential) {
            this(
                    name,
                    providerKind,
                    baseUrl,
                    modelName,
                    null,
                    capabilityCodes,
                    enabled,
                    version,
                    credential);
        }
    }

    public record ModelDiscoveryRequest(
            ProviderKind providerKind,
            String baseUrl,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String credentialValue,
            UUID providerId) {
        @Override
        public String toString() {
            return "ModelDiscoveryRequest[providerKind="
                    + providerKind
                    + ", credentialValue=redacted]";
        }
    }

    public record ProviderView(
            UUID id,
            String name,
            ProviderKind providerKind,
            String baseUrl,
            String modelName,
            List<String> modelNames,
            List<String> capabilityCodes,
            boolean enabled,
            long version,
            boolean credentialConfigured,
            Map<String, List<String>> modelFileFormats) {
        public ProviderView(
                UUID id,
                String name,
                ProviderKind providerKind,
                String baseUrl,
                String modelName,
                List<String> capabilityCodes,
                boolean enabled,
                long version,
                boolean credentialConfigured) {
            this(
                    id,
                    name,
                    providerKind,
                    baseUrl,
                    modelName,
                    modelName == null ? List.of() : List.of(modelName),
                    capabilityCodes,
                    enabled,
                    version,
                    credentialConfigured,
                    Map.of());
        }
    }

    /** Deliberately excludes base URL, credential state and persistence version. */
    public record ConsoleProviderView(
            UUID id,
            String name,
            ProviderKind providerKind,
            String modelName,
            List<String> modelNames,
            List<String> capabilityCodes,
            boolean enabled) {
        public ConsoleProviderView {
            modelNames = List.copyOf(modelNames);
            capabilityCodes = List.copyOf(capabilityCodes);
        }

        public ConsoleProviderView(
                UUID id,
                String name,
                ProviderKind providerKind,
                String modelName,
                List<String> capabilityCodes,
                boolean enabled) {
            this(
                    id,
                    name,
                    providerKind,
                    modelName,
                    modelName == null ? List.of() : List.of(modelName),
                    capabilityCodes,
                    enabled);
        }
    }

    private final AiProviderRepository repository;
    private final ModelConnectionService connections;
    private final SecretStorePort secrets;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final ObjectMapper mapper;
    private final AiModelInputSettingsPort inputSettings;

    public AiProviderService(
            AiProviderRepository repository,
            ModelConnectionService connections,
            SecretStorePort secrets,
            AdministrationPort guard,
            AuditPort audit,
            ObjectMapper mapper,
            AiModelInputSettingsPort inputSettings) {
        this.inputSettings = inputSettings;
        this.repository = repository;
        this.connections = connections;
        this.secrets = secrets;
        this.guard = guard;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ProviderView> list(String actor) {
        guard.requirePage(actor, PlatformPageId.AI_MODELS);
        return repository.findAll().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<ConsoleProviderView> listForConsole(String actor) {
        guard.requirePage(actor, PlatformPageId.AI_CONSOLE);
        return repository.findEnabledForConsole().stream()
                .map(
                        profile ->
                                new ConsoleProviderView(
                                        profile.id(),
                                        profile.name(),
                                        profile.providerKind(),
                                        profile.modelName(),
                                        profile.modelNames(),
                                        consoleCapabilities(profile),
                                        profile.enabled()))
                .toList();
    }

    /** Returns only the configured model metadata; it never calls the provider's network API. */
    @Transactional(readOnly = true)
    public ModelConnectionService.ConnectionResult configuredModelsForConsole(
            UUID id, String actor) {
        guard.requirePage(actor, PlatformPageId.AI_CONSOLE);
        var profile =
                repository
                        .findForConsole(id)
                        .filter(AiProviderRepository.ConsoleProfile::enabled)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (profile.providerKind() == ProviderKind.TYPESAFE_JEV
                || !profile.capabilityCodes().contains("CHAT")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "aiChatUnsupported");
        }
        List<String> models = profile.modelNames();
        if (models.isEmpty()) return new ModelConnectionService.ConnectionResult(List.of(), false);
        var formats = modelFileFormats(profile.id(), profile.providerKind(), models);
        return new ModelConnectionService.ConnectionResult(
                models.stream()
                        .map(
                                model ->
                                        new ModelConnectionService.ModelOption(
                                                model,
                                                model,
                                                AiModelInputPolicy.vision(formats.get(model))
                                                        ? List.of("text", "image")
                                                        : List.of("text"),
                                                List.of("text"),
                                                consoleCapabilities(profile, model),
                                                formats.get(model)))
                        .toList(),
                true);
    }

    private static List<String> consoleCapabilities(AiProviderRepository.ConsoleProfile profile) {
        return consoleCapabilities(profile, profile.modelName());
    }

    private static List<String> consoleCapabilities(
            AiProviderRepository.ConsoleProfile profile, String modelName) {
        var capabilities = new LinkedHashSet<>(profile.capabilityCodes());
        if (AiProviderProfile.supportsUserTools(profile.providerKind(), modelName))
            capabilities.add("TOOL_CALLING");
        return capabilities.stream().sorted().toList();
    }

    @Transactional
    public ProviderView create(ProviderWrite input, String actor) {
        requireWrite(actor, PlatformOperation.AI_MODELS_CREATE);
        Normalized normalized = normalize(input, true);
        validateCredentialChange(input.credential());
        UUID id = UUID.randomUUID();
        var profile =
                new AiProviderProfile(
                        id,
                        normalized.name(),
                        normalized.kind(),
                        normalized.baseUrl(),
                        normalized.encodedModelNames(),
                        normalized.capabilities(),
                        normalized.enabled(),
                        1);
        repository.insert(profile, json(normalized.capabilities()), actor);
        saveInputSettings(id, input, normalized, actor);
        saveCredential(id, input.credential());
        requireConfiguredCredential(normalized.kind(), id);
        audit.append(actor, "ai.provider.created", id);
        return view(profile);
    }

    @Transactional
    public ProviderView update(UUID id, ProviderWrite input, String actor) {
        requireWrite(actor, PlatformOperation.AI_MODELS_UPDATE);
        if (input == null || input.version() == null || input.version() < 1) invalid();
        var existing = find(id);
        Normalized normalized = normalize(input, false);
        validateCredentialChange(input.credential());
        boolean connectionChanged =
                existing.providerKind() != normalized.kind()
                        || !normalizeBaseUrl(existing.providerKind(), existing.baseUrl())
                                .equals(normalized.baseUrl());
        String context = ModelConnectionService.secretContext(id);
        var currentSecret = secrets.state(context);
        String credentialAction = credentialAction(input.credential());
        if (currentSecret.configured()
                && connectionChanged
                && !"REPLACE".equals(credentialAction)
                && !"CLEAR".equals(credentialAction)) invalid();
        boolean configuredAfterChange =
                switch (credentialAction) {
                    case "REPLACE" -> true;
                    case "CLEAR" -> false;
                    default -> currentSecret.configured();
                };
        requireConfiguredCredential(normalized.kind(), configuredAfterChange);
        var profile =
                new AiProviderProfile(
                        id,
                        normalized.name(),
                        normalized.kind(),
                        normalized.baseUrl(),
                        normalized.encodedModelNames(),
                        normalized.capabilities(),
                        normalized.enabled(),
                        input.version());
        if (repository.update(profile, json(normalized.capabilities()), actor) != 1) stale();
        saveInputSettings(id, input, normalized, actor);
        saveCredential(id, input.credential());
        requireConfiguredCredential(normalized.kind(), id);
        audit.append(actor, "ai.provider.updated", id);
        return view(find(id));
    }

    @Transactional
    public void delete(UUID id, long version, String actor) {
        requireWrite(actor, PlatformOperation.AI_MODELS_DELETE);
        if (version < 1 || repository.delete(id, version) != 1) stale();
        inputSettings.delete(id);
        var state = secrets.state(ModelConnectionService.secretContext(id));
        if (state.configured())
            secrets.save(ModelConnectionService.secretContext(id), state.version(), null);
        audit.append(actor, "ai.provider.deleted", id);
    }

    public ModelConnectionService.ConnectionResult test(UUID id, String actor) {
        requireWrite(actor, PlatformOperation.AI_MODELS_TEST);
        var profile = find(id);
        return connections.probe(profile);
    }

    public ModelConnectionService.ConnectionResult discoverModels(
            ModelDiscoveryRequest input, String actor) {
        requireWrite(actor, PlatformOperation.AI_MODELS_TEST);
        if (input == null
                || input.providerKind() == null
                || input.providerKind() == ProviderKind.TYPESAFE_JEV) invalid();
        validateDiscoveryCredential(input.credentialValue());
        String baseUrl = normalizeBaseUrl(input.providerKind(), input.baseUrl());

        UUID id = UUID.randomUUID();
        long version = 1;
        String modelName = "";
        if (input.providerId() != null) {
            var existing = find(input.providerId());
            if (existing.providerKind() != input.providerKind()
                    || !normalizeBaseUrl(existing.providerKind(), existing.baseUrl())
                            .equals(baseUrl)) invalid();
            id = existing.id();
            version = existing.version();
            modelName = existing.encodedModelNames();
        }
        var transientProfile =
                new AiProviderProfile(
                        id,
                        "Model discovery",
                        input.providerKind(),
                        baseUrl,
                        modelName,
                        input.providerKind().providerCapabilityCodes(),
                        true,
                        version);
        return connections.discoverModels(transientProfile, input.credentialValue());
    }

    public ModelConnectionService.ConnectionResult models(UUID id, String actor) {
        guard.requirePage(actor, PlatformPageId.AI_MODELS);
        var profile = find(id);
        if (!profile.enabled())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "aiProviderDisabled");
        if (profile.isDecisionModel() || !profile.providerCapabilityCodes().contains("CHAT")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "aiChatUnsupported");
        }
        return connections.probe(profile);
    }

    @Transactional(readOnly = true)
    public AiProviderProfile requireEnabled(UUID id, String actor) {
        guard.requirePage(actor, PlatformPageId.AI_MODELS);
        var profile = find(id);
        if (!profile.enabled())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "aiProviderDisabled");
        return profile;
    }

    @Transactional(readOnly = true)
    public AiProviderProfile requireEnabledForConsole(UUID id, String actor) {
        guard.requirePage(actor, PlatformPageId.AI_CONSOLE);
        var profile = find(id);
        if (!profile.enabled())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "aiProviderDisabled");
        return profile;
    }

    private void saveCredential(UUID id, CredentialChange change) {
        if (change == null) return;
        validateCredentialChange(change);
        String action = credentialAction(change);
        String context = ModelConnectionService.secretContext(id);
        var current = secrets.state(context);
        if ("KEEP".equals(action)) {
            return;
        }
        if ("CLEAR".equals(action)) {
            if (current.configured()) secrets.save(context, current.version(), null);
            return;
        }
        byte[] value = change.value().getBytes(StandardCharsets.UTF_8);
        try {
            secrets.save(context, current.version(), value);
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }

    private void requireConfiguredCredential(ProviderKind kind, UUID id) {
        requireConfiguredCredential(
                kind, secrets.state(ModelConnectionService.secretContext(id)).configured());
    }

    private static void requireConfiguredCredential(ProviderKind kind, boolean configured) {
        if (kind != ProviderKind.OLLAMA && kind != ProviderKind.LM_STUDIO && !configured) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "aiProviderCredentialRequired");
        }
    }

    private static String credentialAction(CredentialChange change) {
        return change == null || change.action() == null ? "KEEP" : change.action();
    }

    private static void validateCredentialChange(CredentialChange change) {
        if (change == null) return;
        String action = credentialAction(change);
        if ("KEEP".equals(action) || "CLEAR".equals(action)) {
            if (change.value() != null) invalid();
            return;
        }
        if (!"REPLACE".equals(action)
                || change.value() == null
                || change.value().isBlank()
                || change.value().getBytes(StandardCharsets.UTF_8).length > 8192
                || change.value().codePoints().anyMatch(Character::isISOControl)) invalid();
    }

    private Normalized normalize(ProviderWrite input, boolean creating) {
        if (input == null || input.providerKind() == null) invalid();
        String name = input.name() == null ? "" : input.name().strip();
        List<String> requestedModels = input.modelNames();
        if (requestedModels == null || requestedModels.isEmpty()) {
            String fallback = input.modelName();
            if ((fallback == null || fallback.isBlank())
                    && input.providerKind() == ProviderKind.TYPESAFE_JEV) {
                fallback = "jev-latest";
            }
            requestedModels =
                    fallback == null || fallback.isBlank() ? List.of() : List.of(fallback);
        }
        var models = new LinkedHashSet<String>();
        for (String requested : requestedModels) {
            String model = requested == null ? "" : requested.strip();
            if (model.isEmpty()
                    || model.length() > 256
                    || !model.matches("[A-Za-z0-9_.:/-]{1,256}")
                    || !models.add(model)) invalid();
        }
        List<String> modelNames = List.copyOf(models);
        String encodedModels = String.join(",", modelNames);
        if (name.isEmpty()
                || name.length() > 120
                || encodedModels.isEmpty()
                || encodedModels.length() > 256) invalid();
        if (!creating && (input.version() == null || input.version() < 1)) invalid();

        ProviderKind kind = input.providerKind();
        String baseUrl = normalizeBaseUrl(kind, input.baseUrl());
        List<String> capabilities = normalizeCapabilities(kind, input.capabilityCodes());
        return new Normalized(name, kind, baseUrl, modelNames, capabilities, input.enabled());
    }

    private static String normalizeBaseUrl(ProviderKind kind, String raw) {
        if (kind == ProviderKind.TYPESAFE_JEV) {
            if (raw != null
                    && !raw.isBlank()
                    && !AiProviderProfile.DECISION_ORIGIN.equals(raw.strip())) invalid();
            return AiProviderProfile.DECISION_ORIGIN;
        }
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) value = kind.defaultBaseUrl();
        if (value.isEmpty() || value.length() > 512) invalid();
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) invalid();
            if (kind == ProviderKind.OLLAMA || kind == ProviderKind.LM_STUDIO) {
                if (!"http".equals(uri.getScheme())
                        || !(host.equalsIgnoreCase("localhost")
                                || host.equals("127.0.0.1")
                                || host.equals("::1"))
                        || uri.getPort() < 1
                        || uri.getPort() > 65535
                        || !(uri.getPath().isEmpty()
                                || uri.getPath().equals("/")
                                || uri.getPath().equals("/v1"))) invalid();
            } else {
                String path = uri.getRawPath() == null ? "" : uri.getRawPath();
                if (!"https".equalsIgnoreCase(uri.getScheme())
                        || (uri.getPort() != -1 && uri.getPort() != 443)
                        || path.contains("\\")
                        || path.contains("..")
                        || !path.matches("[A-Za-z0-9._~/-]*")
                        || !isAllowedCloudHost(host)) invalid();
            }
            return value.replaceAll("/+$", "");
        } catch (IllegalArgumentException exception) {
            invalid();
            return "";
        }
    }

    static boolean isAllowedCloudHost(String host) {
        return PublicHosts.isAllowed(host);
    }

    static boolean isPublicCloudHost(String host) {
        return PublicHosts.resolvesPublic(host);
    }

    private static void validateDiscoveryCredential(String value) {
        if (value == null || value.isBlank()) return;
        if (value.getBytes(StandardCharsets.UTF_8).length > 8192
                || value.codePoints().anyMatch(Character::isISOControl)) invalid();
    }

    private static List<String> normalizeCapabilities(ProviderKind kind, List<String> requested) {
        // Kept in the request DTO for backward compatibility, but never trusted as
        // operator-selected model capabilities. The provider adapter owns this list.
        return kind.providerCapabilityCodes();
    }

    private String json(List<String> values) {
        return mapper.writeValueAsString(values);
    }

    private AiProviderProfile find(UUID id) {
        return repository
                .find(id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "aiProviderNotFound"));
    }

    private ProviderView view(AiProviderProfile profile) {
        boolean configured =
                secrets.state(ModelConnectionService.secretContext(profile.id())).configured();
        return new ProviderView(
                profile.id(),
                profile.name(),
                profile.providerKind(),
                profile.baseUrl(),
                profile.modelName(),
                profile.modelNames(),
                profile.providerCapabilityCodes(),
                profile.enabled(),
                profile.version(),
                configured,
                modelFileFormats(profile.id(), profile.providerKind(), profile.modelNames()));
    }

    private void saveInputSettings(
            UUID id, ProviderWrite input, Normalized normalized, String actor) {
        var requested =
                input.modelFileFormats() == null
                        ? inputSettings.read(id)
                        : input.modelFileFormats();
        var retained = new LinkedHashMap<String, List<String>>();
        requested.forEach(
                (model, formats) -> {
                    if (normalized.modelNames().contains(model)) retained.put(model, formats);
                });
        if (input.modelFileFormats() != null)
            AiModelInputPolicy.normalize(input.modelFileFormats(), normalized.modelNames());
        inputSettings.save(
                id, AiModelInputPolicy.normalize(retained, normalized.modelNames()), actor);
    }

    private Map<String, List<String>> modelFileFormats(
            UUID id, ProviderKind kind, List<String> models) {
        var saved = inputSettings.read(id);
        var result = new LinkedHashMap<String, List<String>>();
        for (String model : models)
            result.put(
                    model,
                    saved.getOrDefault(model, AiModelInputPolicy.defaults(kind, model, false)));
        return Map.copyOf(result);
    }

    public List<String> supportedFileFormats(AiProviderProfile provider, String model) {
        return modelFileFormats(provider.id(), provider.providerKind(), List.of(model)).get(model);
    }

    private void requireWrite(String actor, PlatformOperation operation) {
        guard.requireOperationGlobal(actor, operation);
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidAiProvider");
    }

    private static void stale() {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
    }

    private record Normalized(
            String name,
            ProviderKind kind,
            String baseUrl,
            List<String> modelNames,
            List<String> capabilities,
            boolean enabled) {
        private String encodedModelNames() {
            return String.join(",", modelNames);
        }
    }
}
