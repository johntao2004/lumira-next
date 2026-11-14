/**
 * 维护模块依赖顺序和插件存活状态，拒绝重名、循环依赖及核心模块对可停用模块的依赖。 Tracks module dependency order and plugin liveness,
 * rejecting duplicate IDs, cycles and core-module dependencies on disableable modules.
 */
package com.lumira.platform.modules;

import java.util.*;

public final class ModuleRegistry implements com.lumira.platform.common.ModuleCatalogPort {
    @Override
    public Set<String> moduleIds() {
        return modules().stream()
                .map(PlatformModule::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private record Catalog(List<PlatformModule> modules, Set<String> plugins) {}

    private volatile Catalog catalog;
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
        var done = new HashSet<String>();
        var visiting = new HashSet<String>();
        var levels = new ArrayDeque<Iterator<String>>();
        var path = new ArrayDeque<PlatformModule>();
        levels.push(indexed.keySet().iterator());
        while (!levels.isEmpty()) {
            if (!levels.peek().hasNext()) {
                levels.pop();
                if (!path.isEmpty()) {
                    var completed = path.pop();
                    visiting.remove(completed.id());
                    done.add(completed.id());
                    ordered.add(completed);
                }
                continue;
            }
            String id = levels.peek().next();
            if (done.contains(id)) continue;
            var module = indexed.get(id);
            if (module == null) throw new IllegalArgumentException("Missing dependency: " + id);
            if (!visiting.add(id))
                throw new IllegalArgumentException("Circular module dependency: " + id);
            path.push(module);
            levels.push(module.dependencies().iterator());
        }
        for (var module : ordered)
            if (module.core())
                for (var dependency : module.dependencies())
                    if (!indexed.get(dependency).core())
                        throw new IllegalArgumentException(
                                "Core module depends on manageable module: " + module.id());
        catalog = new Catalog(List.copyOf(ordered), Set.of());
    }

    public synchronized void registerPlugin(PlatformModule module) {
        var current = catalog;
        var candidates = new ArrayList<>(current.modules());
        candidates.removeIf(m -> m.id().equals(module.id()) && current.plugins().contains(m.id()));
        if (candidates.stream().anyMatch(m -> m.id().equals(module.id())))
            throw new IllegalArgumentException("pluginCoreConflict");
        candidates.add(module);
        var validated = new ModuleRegistry(candidates);
        var plugins = new HashSet<>(current.plugins());
        plugins.add(module.id());
        catalog = new Catalog(validated.modules(), Set.copyOf(plugins));
    }

    public boolean isPlugin(String id) {
        return catalog.plugins().contains(id);
    }

    /**
     * 在发布前校验完整替换集合，使导入失败时可恢复原有目录。 Validate the complete replacement before publishing; failed
     * imports can restore the catalog.
     */
    public synchronized void replacePlugins(List<PlatformModule> replacements) {
        var current = catalog;
        var candidates =
                new ArrayList<>(
                        current.modules().stream()
                                .filter(module -> !current.plugins().contains(module.id()))
                                .toList());
        candidates.addAll(replacements);
        var validated = new ModuleRegistry(candidates);
        var replacementIds =
                replacements.stream()
                        .map(PlatformModule::id)
                        .collect(java.util.stream.Collectors.toSet());
        live.retainAll(replacementIds);
        catalog = new Catalog(validated.modules(), Set.copyOf(replacementIds));
    }

    public boolean isLive(String id) {
        return !catalog.plugins().contains(id) || live.contains(id);
    }

    public void live(String id, boolean value) {
        if (value) live.add(id);
        else live.remove(id);
    }

    public List<PlatformModule> modules() {
        return catalog.modules();
    }

    public PlatformModule require(String id) {
        return catalog.modules().stream()
                .filter(module -> module.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown module: " + id));
    }
}
