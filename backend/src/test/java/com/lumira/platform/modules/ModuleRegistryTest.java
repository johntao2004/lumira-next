package com.lumira.platform.modules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModuleRegistryTest {
    @Test
    void catalogSnapshotsIncludeRegisteredPluginsWhileTheirRuntimeIsUnloaded() {
        var registry =
                new ModuleRegistry(
                        List.of(
                                new PlatformModule(
                                        "platform", "Platform", "1.0.0", List.of(), true)));
        com.lumira.platform.common.ModuleCatalogPort catalog = registry;
        var before = catalog.moduleIds();
        registry.registerPlugin(module("business", "platform"));
        registry.live("business", false);
        assertEquals(java.util.Set.of("platform"), before);
        assertEquals(java.util.Set.of("platform", "business"), catalog.moduleIds());
        assertThrows(UnsupportedOperationException.class, () -> before.add("other"));
    }

    @Test
    void completeReplacementCanRestoreAPluginCatalogAndValidatesBeforePublishing() {
        var registry =
                new ModuleRegistry(
                        List.of(
                                new PlatformModule(
                                        "platform", "Platform", "1.0.0", List.of(), true)));
        registry.replacePlugins(List.of(module("business", "platform")));
        registry.live("business", true);
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.replacePlugins(List.of(module("broken", "missing"))));
        assertEquals(java.util.Set.of("platform", "business"), registry.moduleIds());
        assertTrue(registry.isPlugin("business"));
        assertTrue(registry.isLive("business"));
        registry.replacePlugins(List.of(module("business", "platform"), module("new-plugin")));
        registry.replacePlugins(List.of(module("business", "platform")));
        assertFalse(registry.isPlugin("new-plugin"));
        assertEquals(java.util.Set.of("platform", "business"), registry.moduleIds());
        assertTrue(registry.isLive("business"));
    }

    @Test
    void acceptsDeepGraphsWithoutDependingOnCandidateOrderOrThreadStack() {
        var candidates = new java.util.ArrayList<PlatformModule>();
        for (int i = 19_999; i > 0; i--) candidates.add(module("node" + i, "node" + (i - 1)));
        candidates.add(module("node0"));
        var registry = new ModuleRegistry(candidates);
        assertEquals("node0", registry.modules().getFirst().id());
        assertEquals("node19999", registry.modules().getLast().id());
    }

    PlatformModule module(String id, String... deps) {
        return new PlatformModule(id, id, "1.0.0", List.of(deps));
    }

    @Test
    void coreDependenciesMustAlsoBeCore() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ModuleRegistry(
                                List.of(
                                        new PlatformModule(
                                                "core", "Core", "1.0", List.of("optional"), true),
                                        module("optional"))));
    }

    @Test
    void auditConsoleIsOptionalWithoutPromotingOtherOptionalModules() {
        var registry =
                new ModuleRegistry(
                        List.of(
                                module("audit-console", "platform"),
                                new PlatformModule(
                                        "platform", "Platform", "1.0.0", List.of(), true),
                                module("optional", "platform")));
        assertFalse(registry.require("audit-console").core());
        assertFalse(registry.require("audit-console").required());
        assertFalse(registry.require("optional").core());
    }

    @Test
    void preservesDepthFirstCandidateOrderAcrossIndependentBranches() {
        var registry =
                new ModuleRegistry(
                        List.of(module("first", "base"), module("second"), module("base")));
        assertEquals(
                List.of("base", "first", "second"),
                registry.modules().stream().map(PlatformModule::id).toList());
    }

    @Test
    void ordersDependenciesFirst() {
        var registry =
                new ModuleRegistry(List.of(module("business", "identity"), module("identity")));
        assertEquals(
                List.of("identity", "business"),
                registry.modules().stream().map(PlatformModule::id).toList());
    }

    @Test
    void rejectsMissingDependencies() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModuleRegistry(List.of(module("business", "missing"))));
    }

    @Test
    void rejectsCycles() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModuleRegistry(List.of(module("a", "b"), module("b", "a"))));
    }

    @Test
    void rejectsDuplicates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModuleRegistry(List.of(module("a"), module("a"))));
    }
}
