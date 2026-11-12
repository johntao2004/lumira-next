package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.modules.ModuleStateStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PluginRuntimeLookupTest {
    @Test
    void endpointUsesItsOwnersRuleEvenWhenAnotherPluginHasAnOverlappingPublicRule() {
        var runtime = mock(PluginRuntime.class, CALLS_REAL_METHODS);
        var states = mock(ModuleStateStore.class);
        Map<String, PluginArchive.Artifact> artifacts = new java.util.LinkedHashMap<>();
        for (String owner : List.of("public-plugin", "private-plugin")) {
            var manifest = mock(PluginManifest.class);
            var rule = mock(PluginManifest.Rule.class);
            when(manifest.id()).thenReturn(owner);
            when(manifest.apiRules()).thenReturn(List.of(rule));
            when(rule.method()).thenReturn("GET");
            when(rule.path()).thenReturn("/api/shared");
            when(rule.publicAccess()).thenReturn(owner.equals("public-plugin"));
            when(rule.authorities())
                    .thenReturn(
                            owner.equals("private-plugin") ? List.of("private:read") : List.of());
            artifacts.put(
                    owner,
                    new PluginArchive.Artifact(owner, manifest, java.nio.file.Path.of("unused")));
        }
        ReflectionTestUtils.setField(runtime, "artifacts", artifacts);
        ReflectionTestUtils.setField(
                runtime, "routeIndexes", new java.util.concurrent.ConcurrentHashMap<>());
        ReflectionTestUtils.setField(runtime, "states", states);
        doReturn(true).when(runtime).isReadyForTraffic();
        when(states.isEnabled("private-plugin")).thenReturn(true);
        assertFalse(runtime.endpointAuthorized("private-plugin", "GET", "/api/shared", null));
        var authenticated =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "fixture",
                        "unused",
                        List.of(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("private:read")));
        assertTrue(
                runtime.endpointAuthorized("private-plugin", "GET", "/api/shared", authenticated));
        when(states.isEnabled("private-plugin")).thenReturn(false);
        assertFalse(
                runtime.endpointAuthorized("private-plugin", "GET", "/api/shared", authenticated));
        verify(states, times(3)).isEnabled("private-plugin");
        verifyNoMoreInteractions(states);
    }

    @Test
    void onlyMatchingPluginReadsStateAndDisabledPluginIsRejectedImmediately() {
        var runtime = mock(PluginRuntime.class, CALLS_REAL_METHODS);
        var states = mock(ModuleStateStore.class);
        Map<String, PluginArchive.Artifact> artifacts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            String id = "plugin" + i;
            var manifest = mock(PluginManifest.class);
            var rule = mock(PluginManifest.Rule.class);
            when(manifest.id()).thenReturn(id);
            when(manifest.apiRules()).thenReturn(List.of(rule));
            when(rule.method()).thenReturn("GET");
            when(rule.path()).thenReturn("/api/plugins/" + id + "/status");
            when(rule.publicAccess()).thenReturn(true);
            artifacts.put(
                    id, new PluginArchive.Artifact(id, manifest, java.nio.file.Path.of("unused")));
        }
        ReflectionTestUtils.setField(runtime, "artifacts", artifacts);
        ReflectionTestUtils.setField(
                runtime, "routeIndexes", new java.util.concurrent.ConcurrentHashMap<>());
        ReflectionTestUtils.setField(runtime, "states", states);
        doReturn(true).when(runtime).isReadyForTraffic();
        assertFalse(runtime.authorize("GET", "/api/unknown", Set.of(), false));
        verifyNoInteractions(states);
        when(states.isEnabled("plugin99")).thenReturn(true, false);
        assertTrue(runtime.authorize("GET", "/api/plugins/plugin99/status", Set.of(), false));
        assertFalse(runtime.authorize("GET", "/api/plugins/plugin99/status", Set.of(), false));
        verify(states, times(2)).isEnabled("plugin99");
        verifyNoMoreInteractions(states);
    }

    @Test
    void replacedArtifactAndRollbackUseTheirOwnPatternsWithoutRecompilingSteadyRequests() {
        var runtime = mock(PluginRuntime.class, CALLS_REAL_METHODS);
        var states = mock(ModuleStateStore.class);
        var artifacts =
                new java.util.concurrent.ConcurrentHashMap<String, PluginArchive.Artifact>();
        ReflectionTestUtils.setField(runtime, "artifacts", artifacts);
        ReflectionTestUtils.setField(
                runtime, "routeIndexes", new java.util.concurrent.ConcurrentHashMap<>());
        ReflectionTestUtils.setField(runtime, "states", states);
        doReturn(true).when(runtime).isReadyForTraffic();
        when(states.isEnabled("fixture")).thenReturn(true);
        var oldManifest = mock(PluginManifest.class);
        var nextManifest = mock(PluginManifest.class);
        when(oldManifest.id()).thenReturn("fixture");
        when(nextManifest.id()).thenReturn("fixture");
        when(oldManifest.apiRules())
                .thenReturn(
                        List.of(
                                new PluginManifest.Rule(
                                        "GET", "/api/old", List.of(), false, true)));
        when(nextManifest.apiRules())
                .thenReturn(
                        List.of(
                                new PluginManifest.Rule(
                                        "GET", "/api/next", List.of(), false, true)));
        var oldArtifact =
                new PluginArchive.Artifact("old", oldManifest, java.nio.file.Path.of("unused"));
        var nextArtifact =
                new PluginArchive.Artifact("next", nextManifest, java.nio.file.Path.of("unused"));
        artifacts.put("fixture", oldArtifact);
        for (int i = 0; i < 20; i++)
            assertTrue(runtime.authorize("GET", "/api/old", Set.of(), false));
        verify(oldManifest, times(1)).apiRules();
        artifacts.put("fixture", nextArtifact);
        assertFalse(runtime.authorize("GET", "/api/old", Set.of(), false));
        assertTrue(runtime.authorize("GET", "/api/next", Set.of(), false));
        verify(nextManifest, times(1)).apiRules();
        artifacts.put("fixture", oldArtifact);
        assertTrue(runtime.authorize("GET", "/api/old", Set.of(), false));
        assertFalse(runtime.authorize("GET", "/api/next", Set.of(), false));
        verify(oldManifest, times(2)).apiRules();
        artifacts.clear();
        assertFalse(runtime.authorize("GET", "/api/old", Set.of(), false));
    }
}
