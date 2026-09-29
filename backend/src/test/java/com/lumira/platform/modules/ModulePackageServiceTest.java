package com.lumira.platform.modules;

import com.lumira.platform.audit.AuditRepository;
import com.lumira.platform.identity.AdministrationGuard;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModulePackageServiceTest {
    private final ModuleAdministrationAuthorizer guard = mock(ModuleAdministrationAuthorizer.class);
    private final ModulePackageStore packages = mock(ModulePackageStore.class);
    private final ModuleCapabilityPort capabilities = mock(ModuleCapabilityPort.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final ModuleRegistry registry = new ModuleRegistry(List.of(
            new PlatformModule("identity", "Identity", "1.0.0", List.of(), true),
            new PlatformModule("platform", "Platform", "1.0.0", List.of("identity"), true),
            new PlatformModule("audit-console", "Audit console", "1.0.0", List.of("platform")),
            new PlatformModule("audit-reader", "Audit reader", "1.0.0", List.of("audit-console")),
            new PlatformModule("optional", "Optional", "1.0.0", List.of("platform"))));
    private final ModulePackageService service = new ModulePackageService(
            packages, registry, capabilities, guard, audit, new ObjectMapper());

    private ModulePackageService.Manifest manifest(String module, String version, List<String> dependencies) {
        return new ModulePackageService.Manifest(module, "Optional module", version, "schema-1",
                "lumira.platform/v1", "a".repeat(64), "oci://registry.example/lumira/optional@sha256:" + "a".repeat(64),
                dependencies, List.of("optional:example:read"), List.of("/dashboard/optional"));
    }

    private ModulePackageStore.Entry entry(ModulePackageService.Manifest manifest, String status, long revision) {
        String payload = new ObjectMapper().writeValueAsString(manifest);
        return new ModulePackageStore.Entry(UUID.randomUUID(), manifest.moduleId(), manifest.version(), manifest.schemaVersion(),
                manifest.apiVersion(), manifest.artifactDigest(), "b".repeat(64), manifest.artifactRef(), payload,
                status, revision, "admin", "test reason", OffsetDateTime.now(), OffsetDateTime.now(),
                "ACTIVE".equals(status) ? OffsetDateTime.now() : null, "SUPERSEDED".equals(status) ? OffsetDateTime.now() : null);
    }

    @Test
    void stagesCanonicalManifestAndAuditsOnlyNewPackage() {
        var manifest = manifest("optional", "1.0.0", List.of("platform"));
        var row = entry(manifest, "STAGED", 1);
        when(packages.stage(any())).thenReturn(new ModulePackageStore.StageResult(row, true));
        var view = service.stage("admin", new ModulePackageService.StageRequest(manifest, "Install package"));
        assertEquals("STAGED", view.status());
        assertTrue(view.runtimeReady());
        verify(audit).append("admin", "module.package.staged", row.id());

        reset(audit);
        when(packages.stage(any())).thenReturn(new ModulePackageStore.StageResult(row, false));
        service.stage("admin", new ModulePackageService.StageRequest(manifest, "Install package"));
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsUnsafeOrIncompatibleManifestBeforePersistence() {
        var unsafe = new ModulePackageService.Manifest("optional", "Optional", "1.0.0", "schema-1",
                "lumira.platform/v1", "not-a-digest", "file://../../package", List.of("platform"), List.of(), List.of());
        var error = assertThrows(ResponseStatusException.class,
                () -> service.stage("admin", new ModulePackageService.StageRequest(unsafe, "reason")));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(packages);

        var mismatched = manifest("optional", "1.0.0", List.of());
        error = assertThrows(ResponseStatusException.class,
                () -> service.stage("admin", new ModulePackageService.StageRequest(mismatched, "reason")));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(packages);
    }

    @Test
    void activationRequiresDeployedCodeAndEnabledDependencies() {
        var manifest = manifest("optional", "2.0.0", List.of("platform"));
        var first = entry(manifest, "STAGED", 1);
        when(packages.find(first.id())).thenReturn(java.util.Optional.of(first));
        var error = assertThrows(ResponseStatusException.class,
                () -> service.activate("admin", first.id(), new ModulePackageService.LifecycleRequest(1L, "activate")));
        assertEquals("packageCodeVersionMismatch", error.getReason());
        verify(packages, never()).activate(any(), anyLong());

        manifest = manifest("optional", "1.0.0", List.of("platform"));
        var second = entry(manifest, "STAGED", 1);
        when(packages.find(second.id())).thenReturn(java.util.Optional.of(second));
        when(capabilities.enabledModules()).thenReturn(List.of("identity"));
        error = assertThrows(ResponseStatusException.class,
                () -> service.activate("admin", second.id(), new ModulePackageService.LifecycleRequest(1L, "activate")));
        assertEquals("packageDependencyConflict", error.getReason());
    }

    @Test
    void packageActivationUsesTheCoreModuleCapabilitySnapshotForDependencies() {
        var manifest = manifest("audit-reader", "1.0.0", List.of("audit-console"));
        var row = entry(manifest, "STAGED", 1);
        when(packages.find(row.id())).thenReturn(java.util.Optional.of(row));
        when(capabilities.enabledModules()).thenReturn(List.of("identity", "platform"));
        var error = assertThrows(ResponseStatusException.class,
                () -> service.activate("admin", row.id(), new ModulePackageService.LifecycleRequest(1L, "activate")));
        assertEquals("packageDependencyConflict", error.getReason());
        verify(packages, never()).activate(any(), anyLong());

        when(capabilities.enabledModules()).thenReturn(List.of("identity", "platform", "audit-console"));
        var active = entry(manifest, "ACTIVE", 2);
        when(packages.activate(row.id(), 1)).thenReturn(new ModulePackageStore.Activation(active, true));
        assertEquals("ACTIVE", service.activate("admin", row.id(),
                new ModulePackageService.LifecycleRequest(1L, "activate")).status());
    }

    @Test
    void activationAndRejectionMapOptimisticStateAndAuditChanges() {
        var manifest = manifest("optional", "1.0.0", List.of("platform"));
        var row = entry(manifest, "STAGED", 1);
        when(packages.find(row.id())).thenReturn(java.util.Optional.of(row));
        when(capabilities.enabledModules()).thenReturn(List.of("identity", "platform"));
        var active = entry(manifest, "ACTIVE", 2);
        when(packages.activate(row.id(), 1)).thenReturn(new ModulePackageStore.Activation(active, true));
        var view = service.activate("admin", row.id(), new ModulePackageService.LifecycleRequest(1L, "activate"));
        assertEquals("ACTIVE", view.status());
        verify(audit).append("admin", "module.package.activated", active.id());

        var rejected = entry(manifest, "REJECTED", 2);
        when(packages.reject(row.id(), 1)).thenThrow(new ModulePackageStore.Rejected("stalePackage"));
        var error = assertThrows(ResponseStatusException.class,
                () -> service.reject("admin", row.id(), new ModulePackageService.LifecycleRequest(1L, "reject")));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertNull(rejected.activatedAt());
    }

    @Test
    void mapsStoreLifecycleRejectionsToClientConflicts() {
        var manifest = manifest("optional", "1.0.0", List.of("platform"));
        when(packages.stage(any())).thenThrow(new ModulePackageStore.Rejected("packageAlreadyRejected"));
        var stageError = assertThrows(ResponseStatusException.class,
                () -> service.stage("admin", new ModulePackageService.StageRequest(manifest, "retry")));
        assertEquals(HttpStatus.CONFLICT, stageError.getStatusCode());
        verifyNoInteractions(audit);

        var row = entry(manifest, "STAGED", 1);
        when(packages.find(row.id())).thenReturn(java.util.Optional.of(row));
        when(capabilities.enabledModules()).thenReturn(List.of("identity", "platform"));
        when(packages.activate(row.id(), 1)).thenThrow(new ModulePackageStore.Rejected("stalePackage"));
        var activationError = assertThrows(ResponseStatusException.class,
                () -> service.activate("admin", row.id(), new ModulePackageService.LifecycleRequest(1L, "activate")));
        assertEquals(HttpStatus.CONFLICT, activationError.getStatusCode());
    }
}
