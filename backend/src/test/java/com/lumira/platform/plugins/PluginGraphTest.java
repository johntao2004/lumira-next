package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PluginGraphTest {
    @Test
    void deepGraphsInstallWithoutRecursionAndUninstallOnlyTheRequestedModule() {
        var nodes = new ArrayList<PluginGraph.Node>();
        nodes.add(n("n0", false, true, false));
        for (int i = 1; i < 20_000; i++) nodes.add(n("n" + i, false, true, false, "n" + (i - 1)));
        var graph = new PluginGraph(nodes);
        assertEquals(20_000, graph.install("n19999").size());
        assertEquals(
                20_000,
                graph.catalogOrder(nodes.stream().map(PluginGraph.Node::id).toList()).size());
        var removed = graph.uninstall("n19999");
        assertEquals(List.of("n19999"), removed);
    }

    @Test
    void obsoleteUninstalledConsumersDoNotBlockProviderUpgradeButCannotInstall() {
        var provider = new PluginGraph.Node("provider", "2.0.0", List.of(), false, false, false);
        var graph = new PluginGraph(List.of(provider, n("old", false, false, true, "provider")));
        assertEquals(List.of("provider", "old"), graph.catalogOrder(List.of("old", "provider")));
        assertThrows(IllegalArgumentException.class, () -> graph.install("old"));
        var installed = new PluginGraph(List.of(provider, n("old", false, true, true, "provider")));
        assertThrows(IllegalArgumentException.class, () -> installed.catalogOrder(List.of("old")));
    }

    @Test
    void diamondProvidersRemainUntilExplicitRemoval() {
        var graph =
                new PluginGraph(
                        List.of(
                                n("shared", false, true, false),
                                n("left", false, true, false, "shared"),
                                n("right", false, true, false, "shared"),
                                n("app", false, true, true, "left", "right")));
        assertEquals(List.of("app"), graph.uninstall("app"));
        assertThrows(IllegalArgumentException.class, () -> graph.uninstall("shared"));
    }

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
    void topologicalInstallNeverImpliesAutomaticProviderCleanup() {
        var g =
                new PluginGraph(
                        List.of(
                                n("core", true, true, true),
                                n("library", false, true, false, "core"),
                                n("shared", false, true, false, "library"),
                                n("app", false, true, true, "shared")));
        assertEquals(List.of("core", "library", "shared", "app"), g.install("app"));
        assertEquals(List.of("app"), g.uninstall("app"));
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

    @Test
    void brokenConsumerCanBeExplicitlyRemovedWithoutResolvingItsMissingProvider() {
        var graph = new PluginGraph(List.of(n("broken", false, true, true, "missing")));
        assertThrows(IllegalArgumentException.class, () -> graph.install("broken"));
        assertEquals(List.of("broken"), graph.uninstall("broken"));
    }
}
