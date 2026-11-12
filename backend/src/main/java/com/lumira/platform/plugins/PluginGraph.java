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
        return order(List.of(root), false);
    }

    /** Old uninstalled packages may remain in the catalog while their providers are upgraded. */
    public List<String> catalogOrder(Collection<String> roots) {
        return order(roots, true);
    }

    private List<String> order(Collection<String> roots, boolean catalog) {
        var result = new ArrayList<String>();
        var visiting = new HashSet<String>();
        var done = new HashSet<String>();
        record Frame(String id, boolean complete) {}
        var stack = new ArrayDeque<Frame>();
        var rootList = new ArrayList<>(roots);
        for (int i = rootList.size() - 1; i >= 0; i--)
            stack.push(new Frame(rootList.get(i), false));
        while (!stack.isEmpty()) {
            var frame = stack.pop();
            String id = frame.id();
            if (frame.complete()) {
                visiting.remove(id);
                done.add(id);
                result.add(id);
                continue;
            }
            if (done.contains(id)) continue;
            var node = nodes.get(id);
            if (node == null) throw new IllegalArgumentException("pluginDependencyMissing:" + id);
            if (!visiting.add(id))
                throw new IllegalArgumentException("pluginDependencyCycle:" + id);
            stack.push(new Frame(id, true));
            var dependencies = node.dependencies();
            for (int i = dependencies.size() - 1; i >= 0; i--) {
                var dependency = dependencies.get(i);
                var child = nodes.get(dependency.id());
                if (child == null)
                    throw new IllegalArgumentException(
                            "pluginDependencyMissing:" + dependency.id());
                if (!child.version().equals(dependency.version()) && (!catalog || node.installed()))
                    throw new IllegalArgumentException("pluginVersionConflict:" + dependency.id());
                stack.push(new Frame(dependency.id(), false));
            }
        }
        return List.copyOf(result);
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
        // Uninstall only the explicitly requested module. Even implicitly installed providers
        // can hold business data and active tasks; removing orphan dependencies needs its own
        // reviewed plan and must never be an automatic cascade.
        return List.of(root);
    }
}
