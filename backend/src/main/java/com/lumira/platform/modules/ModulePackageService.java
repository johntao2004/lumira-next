package com.lumira.platform.modules;

import com.lumira.platform.common.AuditPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Declaration-first module package lifecycle.
 *
 * <p>A package can be staged before the corresponding application code is deployed. Activation
 * is intentionally fenced by the statically registered code version, so an administrator cannot
 * make an unknown jar or browser bundle executable through a database write.</p>
 */
@Service
public class ModulePackageService {
    public static final String READ = "platform:modules:read";
    public static final String WRITE = "platform:modules:write";
    private static final String API_VERSION = "lumira.platform/v1";
    private static final Pattern MODULE_ID = Pattern.compile("[a-z][a-z0-9-]{0,99}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+([-.+][0-9A-Za-z.-]+)?");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,63}");
    private static final Pattern PERMISSION = Pattern.compile("[a-z][a-z0-9-]{0,63}:[a-z][a-z0-9-]{0,63}:[a-z][a-z0-9-]{0,63}");
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ARTIFACT_REF = Pattern.compile("(?:https|oci|s3)://[^\\s]{1,2047}");

    public record Manifest(String moduleId, String name, String version, String schemaVersion,
                           String apiVersion, String artifactDigest, String artifactRef,
                           List<String> dependencies, List<String> permissions, List<String> routes) {
        public Manifest {
            dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
            permissions = List.copyOf(permissions == null ? List.of() : permissions);
            routes = List.copyOf(routes == null ? List.of() : routes);
        }
    }

    public record StageRequest(Manifest manifest, String reason) {
    }

    public record LifecycleRequest(Long revision, String reason) {
    }

    public record View(UUID id, String moduleId, String name, String packageVersion,
                       String schemaVersion, String apiVersion, String artifactDigest,
                       String manifestDigest, String artifactRef, List<String> dependencies,
                       List<String> permissions, List<String> routes, String status,
                       long revision, String installedBy, String installReason,
                       java.time.OffsetDateTime createdAt, java.time.OffsetDateTime updatedAt,
                       java.time.OffsetDateTime activatedAt, java.time.OffsetDateTime supersededAt,
                       boolean runtimeReady) {
    }

    private final ModulePackageStore packages;
    private final ModuleRegistry registry;
    private final ModuleCapabilityPort capabilities;
    private final ModuleAdministrationAuthorizer guard;
    private final AuditPort audit;
    private final ObjectMapper mapper;

    public ModulePackageService(ModulePackageStore packages, ModuleRegistry registry,
                                ModuleCapabilityPort capabilities, ModuleAdministrationAuthorizer guard,
                                AuditPort audit, ObjectMapper mapper) {
        this.packages = packages;
        this.registry = registry;
        this.capabilities = capabilities;
        this.guard = guard;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<View> list(String actor, String moduleId) {
        guard.requireGlobal(actor, READ);
        if (moduleId != null && !moduleId.isBlank() && !MODULE_ID.matcher(moduleId).matches()) {
            throw bad("invalidModulePackage");
        }
        return packages.list(moduleId == null ? "" : moduleId.strip()).stream().map(this::view).toList();
    }

    @Transactional
    public View stage(String actor, StageRequest input) {
        guard.require(actor, WRITE);
        guard.requireGlobal(actor, WRITE);
        if (input == null || input.manifest() == null || input.reason() == null
                || input.reason().isBlank() || input.reason().length() > 1000) {
            throw bad("invalidModulePackage");
        }
        Manifest manifest = normalize(input.manifest());
        String payload = mapper.writeValueAsString(manifest);
        String manifestDigest = digest(payload);
        ModulePackageStore.Stage stage = new ModulePackageStore.Stage(
                null, manifest.moduleId(), manifest.version(), manifest.schemaVersion(),
                manifest.apiVersion(), manifest.artifactDigest(), manifestDigest,
                manifest.artifactRef(), payload, actor, input.reason().strip());
        // The unique digest key makes concurrent registration idempotent.
        ModulePackageStore.StageResult result;
        try {
            result = packages.stage(stage);
        } catch (ModulePackageStore.Rejected error) {
            throw rejected(error);
        }
        if (result.created()) audit.append(actor, "module.package.staged", result.entry().id());
        return view(result.entry());
    }

    @Transactional
    public View activate(String actor, UUID id, LifecycleRequest input) {
        guard.require(actor, WRITE);
        guard.requireGlobal(actor, WRITE);
        long revision = requireRevision(input);
        requireReason(input);
        ModulePackageStore.Entry entry = packages.find(id).orElseThrow(() -> notFound("packageNotFound"));
        Manifest manifest = readManifest(entry.manifest());
        PlatformModule module = registered(manifest.moduleId());
        if (!module.version().equals(manifest.version())) throw conflict("packageCodeVersionMismatch");
        if (!new HashSet<>(module.dependencies()).equals(new HashSet<>(manifest.dependencies()))) throw conflict("packageDependencyConflict");
        var enabled = new HashSet<>(capabilities.enabledModules());
        if (module.dependencies().stream().anyMatch(dependency -> !enabled.contains(dependency))) {
            throw conflict("packageDependencyConflict");
        }
        ModulePackageStore.Activation result;
        try {
            result = packages.activate(id, revision);
        } catch (ModulePackageStore.Rejected error) {
            throw rejected(error);
        }
        if (result.changed()) audit.append(actor, "module.package.activated", result.entry().id());
        return view(result.entry());
    }

    @Transactional
    public View reject(String actor, UUID id, LifecycleRequest input) {
        guard.require(actor, WRITE);
        guard.requireGlobal(actor, WRITE);
        long revision = requireRevision(input);
        requireReason(input);
        ModulePackageStore.Entry result;
        try {
            result = packages.reject(id, revision);
        } catch (ModulePackageStore.Rejected error) {
            throw rejected(error);
        }
        audit.append(actor, "module.package.rejected", result.id());
        return view(result);
    }

    private Manifest normalize(Manifest input) {
        String moduleId = text(input.moduleId(), 100, MODULE_ID, "invalidModulePackage");
        String name = text(input.name(), 200, null, "invalidModulePackage");
        String version = text(input.version(), 64, VERSION, "invalidModulePackage");
        String schemaVersion = text(input.schemaVersion(), 64, TOKEN, "invalidModulePackage");
        String apiVersion = text(input.apiVersion(), 64, TOKEN, "invalidModulePackage");
        if (!API_VERSION.equals(apiVersion)) throw conflict("packageApiVersionMismatch");
        String artifactDigest = input.artifactDigest() == null ? "" : input.artifactDigest().strip().toLowerCase(Locale.ROOT);
        if (!DIGEST.matcher(artifactDigest).matches()) throw bad("invalidModulePackage");
        String artifactRef = text(input.artifactRef(), 2048, null, "invalidModulePackage");
        if (!ARTIFACT_REF.matcher(artifactRef).matches() || artifactRef.contains("..")) throw bad("invalidModulePackage");
        List<String> dependencies = tokens(input.dependencies(), MODULE_ID, 32, "invalidModulePackage");
        List<String> permissions = tokens(input.permissions(), PERMISSION, 256, "invalidModulePackage");
        List<String> routes = routes(input.routes());
        PlatformModule registered = registry.modules().stream().filter(item -> item.id().equals(moduleId)).findFirst().orElse(null);
        if (registered != null && !new HashSet<>(registered.dependencies()).equals(new HashSet<>(dependencies))) {
            throw conflict("packageDependencyConflict");
        }
        return new Manifest(moduleId, name, version, schemaVersion, apiVersion, artifactDigest,
                artifactRef, dependencies, permissions, routes);
    }

    private List<String> tokens(List<String> input, Pattern pattern, int max, String code) {
        if (input == null || input.size() > max) throw bad(code);
        Set<String> unique = new HashSet<>();
        for (String value : input) {
            if (value == null || !pattern.matcher(value).matches() || !unique.add(value)) throw bad(code);
        }
        return unique.stream().sorted().toList();
    }

    private List<String> routes(List<String> input) {
        if (input == null || input.size() > 128) throw bad("invalidModulePackage");
        Set<String> unique = new HashSet<>();
        for (String route : input) {
            if (route == null || route.length() < 2 || route.length() > 256 || !route.startsWith("/")
                    || route.contains("..") || route.contains("\\") || route.contains(" ") || !unique.add(route)) {
                throw bad("invalidModulePackage");
            }
        }
        return unique.stream().sorted().toList();
    }

    private String text(String value, int max, Pattern pattern, String code) {
        if (value == null) throw bad(code);
        String normalized = value.strip();
        if (normalized.isBlank() || normalized.length() > max || (pattern != null && !pattern.matcher(normalized).matches())) {
            throw bad(code);
        }
        return normalized;
    }

    private Manifest readManifest(String payload) {
        try {
            return normalize(mapper.readValue(payload, Manifest.class));
        } catch (RuntimeException error) {
            throw new IllegalStateException("Invalid module manifest stored in database", error);
        }
    }

    private View view(ModulePackageStore.Entry entry) {
        Manifest manifest = readManifest(entry.manifest());
        boolean ready = registry.modules().stream().anyMatch(module -> module.id().equals(entry.moduleId())
                && module.version().equals(entry.packageVersion()));
        return new View(entry.id(), entry.moduleId(), manifest.name(), entry.packageVersion(), entry.schemaVersion(),
                entry.apiVersion(), entry.artifactDigest(), entry.manifestDigest(), entry.artifactRef(),
                manifest.dependencies(), manifest.permissions(), manifest.routes(), entry.status(), entry.revision(),
                entry.installedBy(), entry.installReason(), entry.createdAt(), entry.updatedAt(), entry.activatedAt(),
                entry.supersededAt(), ready);
    }

    private PlatformModule registered(String moduleId) {
        return registry.modules().stream().filter(item -> item.id().equals(moduleId)).findFirst()
                .orElseThrow(() -> conflict("moduleNotRegistered"));
    }

    private long requireRevision(LifecycleRequest input) {
        if (input == null || input.revision() == null || input.revision() < 1) throw bad("invalidModulePackage");
        return input.revision();
    }

    private void requireReason(LifecycleRequest input) {
        if (input.reason() == null || input.reason().isBlank() || input.reason().length() > 1000) {
            throw bad("invalidModulePackage");
        }
    }

    private String digest(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private ResponseStatusException rejected(ModulePackageStore.Rejected error) {
        return switch (error.getMessage()) {
            case "packageNotFound" -> notFound(error.getMessage());
            case "stalePackage", "packageStateConflict", "packageAlreadyRejected" -> conflict(error.getMessage());
            default -> bad(error.getMessage());
        };
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }
}
