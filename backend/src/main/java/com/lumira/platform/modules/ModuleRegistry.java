package com.lumira.platform.modules;

import java.util.*;

public final class ModuleRegistry {
    private volatile List<PlatformModule> modules;
    private final Set<String> plugins = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> live = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ModuleRegistry(List<PlatformModule> candidates) {
        if (candidates == null)
            throw new IllegalArgumentException("Module candidates are required");
        Map<String, PlatformModule> indexed = new LinkedHashMap<>();
        for (var module : candidates) {
            if (module == null)
                throw new IllegalArgumentException("Module candidate cannot be null");
            if (indexed.putIfAbsent(module.id(), module) != null)
                throw new IllegalArgumentException("Duplicate module: " + module.id());
        }
        var ordered = new ArrayList<PlatformModule>();
        var visiting = new HashSet<String>();
        var done = new HashSet<String>();
        for (String id : indexed.keySet()) visit(id, indexed, visiting, done, ordered);
        for (var module : ordered)
            if (module.core())
                for (var dependency : module.dependencies())
                    if (!indexed.get(dependency).core())
                        throw new IllegalArgumentException(
                                "Core module depends on manageable module: " + module.id());
        modules = List.copyOf(ordered);
    }

    private static void visit(
            String id,
            Map<String, PlatformModule> indexed,
            Set<String> visiting,
            Set<String> done,
            List<PlatformModule> ordered) {
        if (done.contains(id)) return;
        var module = indexed.get(id);
        if (module == null) throw new IllegalArgumentException("Missing dependency: " + id);
        if (!visiting.add(id))
            throw new IllegalArgumentException("Circular module dependency: " + id);
        for (String dependency : module.dependencies())
            visit(dependency, indexed, visiting, done, ordered);
        visiting.remove(id);
        done.add(id);
        ordered.add(module);
    }

    public synchronized void registerPlugin(PlatformModule module) {
        var candidates = new ArrayList<>(modules);
        candidates.removeIf(m -> m.id().equals(module.id()) && plugins.contains(m.id()));
        if (candidates.stream().anyMatch(m -> m.id().equals(module.id())))
            throw new IllegalArgumentException("pluginCoreConflict");
        candidates.add(module);
        modules = new ModuleRegistry(candidates).modules();
        plugins.add(module.id());
    }

    public boolean isPlugin(String id) {
        return plugins.contains(id);
    }

    public boolean isLive(String id) {
        return !plugins.contains(id) || live.contains(id);
    }

    public void live(String id, boolean value) {
        if (value) live.add(id);
        else live.remove(id);
    }

    public List<PlatformModule> modules() {
        return modules;
    }

    public PlatformModule require(String id) {
        return modules.stream()
                .filter(module -> module.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown module: " + id));
    }
}
