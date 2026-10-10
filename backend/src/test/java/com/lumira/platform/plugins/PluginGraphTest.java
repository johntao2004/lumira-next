package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PluginGraphTest {
    private PluginGraph.Node n(
            String id, boolean core, boolean installed, boolean explicit, String... dependencies) {
        return new PluginGraph.Node(
                id,
                "1.0.0",
                Arrays.stream(dependencies)
                        .map(d -> new PluginManifest.Dependency(d, "1.0.0"))
                        .toList(),
                core,
                installed,
                explicit);
    }

    @Test
    void topologicalInstallAndRecursiveAutomaticCleanup() {
        var g =
                new PluginGraph(
                        List.of(
                                n("core", true, true, true),
                                n("library", false, true, false, "core"),
                                n("shared", false, true, false, "library"),
                                n("app", false, true, true, "shared")));
        assertEquals(List.of("core", "library", "shared", "app"), g.install("app"));
        assertEquals(List.of("app", "shared", "library"), g.uninstall("app"));
    }

    @Test
    void stoppedInstalledConsumerStillProtectsSharedDependency() {
        var g =
                new PluginGraph(
                        List.of(
                                n("shared", false, true, false),
                                n("app", false, true, true, "shared"),
                                n("stopped", false, true, true, "shared")));
        assertEquals(List.of("app"), g.uninstall("app"));
        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> g.uninstall("shared"))
                        .getMessage()
                        .contains("stopped"));
    }

    @Test
    void explicitDependencyAndCoreAreAlwaysRetained() {
        var g =
                new PluginGraph(
                        List.of(
                                n("core", true, true, true),
                                n("library", false, true, true, "core"),
                                n("app", false, true, true, "library")));
        assertEquals(List.of("app"), g.uninstall("app"));
        assertThrows(IllegalArgumentException.class, () -> g.uninstall("core"));
    }

    @Test
    void missingCyclesAndVersionConflictsBlockInstallation() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PluginGraph(List.of(n("app", false, false, true, "missing")))
                                .install("app"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PluginGraph(
                                        List.of(
                                                n("a", false, false, true, "b"),
                                                n("b", false, false, true, "a")))
                                .install("a"));
        var bad = new PluginGraph.Node("lib", "2.0.0", List.of(), false, false, false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PluginGraph(List.of(bad, n("app", false, false, true, "lib")))
                                .install("app"));
    }
}
