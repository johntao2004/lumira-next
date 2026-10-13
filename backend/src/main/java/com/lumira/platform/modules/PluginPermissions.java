package com.lumira.platform.modules;

import java.util.*;

/** Immutable snapshot of permissions and catalog metadata from installed plugin artifacts. */
public final class PluginPermissions {
    public record Permission(String code, String label, List<String> requires) {
        public Permission {
            requires = List.copyOf(requires == null ? List.of() : requires);
        }

        public Permission(String code, String label) {
            this(code, label, List.of());
        }
    }

    public record Page(
            String id,
            String path,
            String title,
            String navigationGroup,
            int navigationOrder,
            String accessPermission,
            List<String> operationPermissions,
            String accessScope,
            Map<String, String> operationScopes) {
        public Page {
            operationPermissions =
                    List.copyOf(operationPermissions == null ? List.of() : operationPermissions);
            var declaredOperationPermissions = Set.copyOf(operationPermissions);
            if (accessScope != null && !Set.of("global", "scoped").contains(accessScope))
                throw new IllegalArgumentException("invalidPluginPermissionScope");
            accessScope = accessScope == null ? "global" : accessScope;
            operationScopes = Map.copyOf(operationScopes == null ? Map.of() : operationScopes);
            if (operationScopes.entrySet().stream()
                    .anyMatch(
                            entry ->
                                    !declaredOperationPermissions.contains(entry.getKey())
                                            || !Set.of("global", "scoped")
                                                    .contains(entry.getValue())))
                throw new IllegalArgumentException("invalidPluginPermissionScope");
        }

        public Page(
                String id,
                String path,
                String title,
                String navigationGroup,
                int navigationOrder,
                String accessPermission,
                List<String> operationPermissions) {
            this(
                    id,
                    path,
                    title,
                    navigationGroup,
                    navigationOrder,
                    accessPermission,
                    operationPermissions,
                    "global",
                    Map.of());
        }

        public Page(
                String path,
                String title,
                String accessPermission,
                String navigationGroup,
                int navigationOrder) {
            this(
                    idFromPath(path),
                    path,
                    title,
                    navigationGroup,
                    navigationOrder,
                    accessPermission,
                    List.of(),
                    "global",
                    Map.of());
        }

        /** Keep older and manually supplied catalog snapshots explicit about missing mappings. */
        public Page(String path, String title) {
            this(idFromPath(path), path, title, "unassigned", 0, null, List.of());
        }

        public Page(
                String id,
                String path,
                String title,
                String accessPermission,
                String navigationGroup,
                int navigationOrder) {
            this(
                    id,
                    path,
                    title,
                    navigationGroup,
                    navigationOrder,
                    accessPermission,
                    List.of(),
                    "global",
                    Map.of());
        }

        private static String idFromPath(String path) {
            if (path == null) return null;
            String prefix = "/dashboard/";
            return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        }
    }

    public record CatalogGroup(
            String id, String title, List<Page> pages, List<Permission> permissions) {
        public CatalogGroup {
            pages = List.copyOf(pages == null ? List.of() : pages);
            permissions = List.copyOf(permissions == null ? List.of() : permissions);
        }
    }

    private static volatile Map<String, List<CatalogGroup>> catalogs = Map.of();

    public static Set<String> values() {
        var result = new HashSet<String>();
        catalogs.values().stream()
                .flatMap(Collection::stream)
                .flatMap(group -> group.permissions().stream())
                .map(Permission::code)
                .forEach(result::add);
        return Set.copyOf(result);
    }

    /** Current plugin permission groups, sorted by plugin id and group id. */
    public static List<CatalogGroup> catalogGroups() {
        return catalogs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream())
                .sorted(Comparator.comparing(CatalogGroup::id))
                .toList();
    }

    /** Replace the full plugin catalog with one consistent snapshot. */
    public static synchronized void replaceAll(
            Map<String, ? extends Collection<CatalogGroup>> current) {
        var snapshot = new TreeMap<String, List<CatalogGroup>>();
        if (current != null)
            current.forEach(
                    (pluginId, groups) -> {
                        if (pluginId == null || groups == null) return;
                        snapshot.put(pluginId, List.copyOf(groups));
                    });
        catalogs = Map.copyOf(snapshot);
    }

    public static synchronized void replace(String pluginId, Collection<CatalogGroup> groups) {
        if (pluginId == null) return;
        var snapshot = new TreeMap<>(catalogs);
        if (groups == null || groups.isEmpty()) snapshot.remove(pluginId);
        else snapshot.put(pluginId, List.copyOf(groups));
        catalogs = Map.copyOf(snapshot);
    }

    public static synchronized void unregister(String pluginId) {
        if (pluginId == null || !catalogs.containsKey(pluginId)) return;
        var snapshot = new TreeMap<>(catalogs);
        snapshot.remove(pluginId);
        catalogs = Map.copyOf(snapshot);
    }

    private PluginPermissions() {}
}
