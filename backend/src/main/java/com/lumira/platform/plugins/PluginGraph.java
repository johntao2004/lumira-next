package com.lumira.platform.plugins;

import java.util.*;

/** Dependency decisions are based on installed modules, including stopped ones. */
public final class PluginGraph {
    public record Node(
            String id,
            String version,
            List<PluginManifest.Dependency> dependencies,
            boolean core,
            boolean installed,
            boolean explicit) {}

    private final Map<String, Node> nodes;

    public PluginGraph(Collection<Node> nodes) {
        var map = new LinkedHashMap<String, Node>();
        for (var n : nodes)
            if (map.putIfAbsent(n.id(), n) != null)
                throw new IllegalArgumentException("duplicatePlugin");
        this.nodes = Map.copyOf(map);
    }

    public List<String> install(String root) {
        var result = new ArrayList<String>();
        visit(root, new HashSet<>(), new HashSet<>(), result);
        return List.copyOf(result);
    }

    private void visit(String id, Set<String> visiting, Set<String> done, List<String> result) {
        if (done.contains(id)) return;
        var n = nodes.get(id);
        if (n == null) throw new IllegalArgumentException("pluginDependencyMissing:" + id);
        if (!visiting.add(id)) throw new IllegalArgumentException("pluginDependencyCycle:" + id);
        for (var d : n.dependencies()) {
            var child = nodes.get(d.id());
            if (child == null)
                throw new IllegalArgumentException("pluginDependencyMissing:" + d.id());
            if (!child.version().equals(d.version()))
                throw new IllegalArgumentException("pluginVersionConflict:" + d.id());
            visit(d.id(), visiting, done, result);
        }
        visiting.remove(id);
        done.add(id);
        result.add(id);
    }

    public List<String> uninstall(String root) {
        var n = nodes.get(root);
        if (n == null || n.core()) throw new IllegalArgumentException("requiredModule");
        var blockers =
                nodes.values().stream()
                        .filter(
                                x ->
                                        x.installed()
                                                && !x.id().equals(root)
                                                && x.dependencies().stream()
                                                        .anyMatch(d -> d.id().equals(root)))
                        .map(Node::id)
                        .sorted()
                        .toList();
        if (!blockers.isEmpty())
            throw new IllegalArgumentException("pluginRequiredBy:" + String.join(",", blockers));
        if (!n.installed()) return List.of();
        var removed = new LinkedHashSet<String>();
        removed.add(root);
        var candidates = new LinkedHashSet<String>();
        collectDependencies(root, candidates);
        boolean changed;
        do {
            changed = false;
            for (String id : candidates) {
                var d = nodes.get(id);
                if (d.core() || !d.installed() || d.explicit() || removed.contains(id)) continue;
                boolean used =
                        nodes.values().stream()
                                .anyMatch(
                                        x ->
                                                x.installed()
                                                        && !removed.contains(x.id())
                                                        && x.dependencies().stream()
                                                                .anyMatch(
                                                                        dep ->
                                                                                dep.id()
                                                                                        .equals(
                                                                                                id)));
                if (!used) {
                    removed.add(id);
                    changed = true;
                }
            }
        } while (changed);
        // Stop consumers before their providers.
        var ordered = new ArrayList<String>();
        for (String id : install(root)) if (removed.contains(id)) ordered.add(id);
        Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    private void collectDependencies(String id, Set<String> out) {
        var n = nodes.get(id);
        if (n == null) return;
        for (var d : n.dependencies()) if (out.add(d.id())) collectDependencies(d.id(), out);
    }
}
