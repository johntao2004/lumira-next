package com.lumira.platform.ai;

import static com.lumira.platform.ai.AiProviderProfile.ProviderKind;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.PublicHosts;
import com.lumira.platform.common.SecretStorePort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
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
            List<String> capabilityCodes,
            boolean enabled,
            Long version,
            CredentialChange credential) {}

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
            List<String> capabilityCodes,
            boolean enabled,
            long version,
            boolean credentialConfigured) {}

    /** Deliberately excludes base URL, credential state and persistence version. */
    public record ConsoleProviderView(
            UUID id,
            String name,
            ProviderKind providerKind,
            String modelName,
            List<String> capabilityCodes,
            boolean enabled) {
        public ConsoleProviderView {
            capabilityCodes = List.copyOf(capabilityCodes);
        }
    }

    private final AiProviderRepository repository;
    private final ModelConnectionService connections;
    private final SecretStorePort secrets;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final ObjectMapper mapper;

    public AiProviderService(
            AiProviderRepository repository,
            ModelConnectionService connections,
            SecretStorePort secrets,
            AdministrationPort guard,
            AuditPort audit,
            ObjectMapper mapper) {
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
        String model = profile.modelName() == null ? "" : profile.modelName().strip();
        if (model.isEmpty()) return new ModelConnectionService.ConnectionResult(List.of(), false);
        List<String> capabilities = consoleCapabilities(profile);
        return new ModelConnectionService.ConnectionResult(
                List.of(
                        new ModelConnectionService.ModelOption(
                                model, model, List.of(), List.of(), capabilities)),
                true);
    }

    private static List<String> consoleCapabilities(AiProviderRepository.ConsoleProfile profile) {
        var capabilities = new LinkedHashSet<>(profile.capabilityCodes());
        if (AiProviderProfile.supportsUserTools(profile.providerKind(), profile.modelName()))
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
                        normalized.modelName(),
                        normalized.capabilities(),
                        normalized.enabled(),
                        1);
        repository.insert(profile, json(normalized.capabilities()), actor);
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
                        normalized.modelName(),
                        normalized.capabilities(),
                        normalized.enabled(),
                        input.version());
        if (repository.update(profile, json(normalized.capabilities()), actor) != 1) stale();
        saveCredential(id, input.credential());
        requireConfiguredCredential(normalized.kind(), id);
        audit.append(actor, "ai.provider.updated", id);
        return view(find(id));
    }

    @Transactional
    public void delete(UUID id, long version, String actor) {
        requireWrite(actor, PlatformOperation.AI_MODELS_DELETE);
        if (version < 1 || repository.delete(id, version) != 1) stale();
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
            modelName = existing.modelName();
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
        String model =
                input.modelName() == null || input.modelName().isBlank()
                        ? (input.providerKind() == ProviderKind.TYPESAFE_JEV ? "jev-latest" : "")
                        : input.modelName().strip();
        if (name.isEmpty()
                || name.length() > 120
                || model.isEmpty()
                || model.length() > 256
                || !model.matches("[A-Za-z0-9_.:/-]{1,256}")) invalid();
        if (!creating && (input.version() == null || input.version() < 1)) invalid();

        ProviderKind kind = input.providerKind();
        String baseUrl = normalizeBaseUrl(kind, input.baseUrl());
        List<String> capabilities = normalizeCapabilities(kind, input.capabilityCodes());
        if (kind == ProviderKind.TYPESAFE_JEV && model.isBlank()) model = "jev-latest";
        return new Normalized(name, kind, baseUrl, model, capabilities, input.enabled());
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
                profile.providerCapabilityCodes(),
                profile.enabled(),
                profile.version(),
                configured);
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
            String modelName,
            List<String> capabilities,
            boolean enabled) {}
}
