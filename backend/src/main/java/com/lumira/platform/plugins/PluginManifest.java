package com.lumira.platform.plugins;

import java.util.*;

public record PluginManifest(
        int formatVersion,
        String id,
        String name,
        String version,
        String sdkVersion,
        String entryClass,
        String backend,
        String frontend,
        List<Dependency> dependencies,
        List<String> permissions,
        List<Route> routes,
        List<Rule> apiRules,
        Map<String, String> files,
        List<DataSet> dataSets) {
    public record Dependency(String id, String version) {}

    public record Route(
            String id, String label, String permission, String group, int order, boolean global) {}

    public record Rule(
            String method,
            String path,
            List<String> authorities,
            boolean global,
            boolean publicAccess,
            Boolean authenticatedOnly,
            Boolean previewReadOnly) {
        public Rule {
            authenticatedOnly = Boolean.TRUE.equals(authenticatedOnly);
            previewReadOnly = Boolean.TRUE.equals(previewReadOnly);
        }

        public Rule(
                String method,
                String path,
                List<String> authorities,
                boolean global,
                boolean publicAccess,
                Boolean authenticatedOnly) {
            this(method, path, authorities, global, publicAccess, authenticatedOnly, false);
        }

        public Rule(
                String method,
                String path,
                List<String> authorities,
                boolean global,
                boolean publicAccess) {
            this(method, path, authorities, global, publicAccess, false, false);
        }
    }

    /** Ordered, plugin-owned rows. A shared table requires an explicit equality filter. */
    public record DataSet(String table, String label, String column, String value) {}

    public PluginManifest {
        dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
        permissions = List.copyOf(permissions == null ? List.of() : permissions);
        routes = List.copyOf(routes == null ? List.of() : routes);
        apiRules = List.copyOf(apiRules == null ? List.of() : apiRules);
        files = Map.copyOf(files == null ? Map.of() : files);
        dataSets = List.copyOf(dataSets == null ? List.of() : dataSets);
        if (formatVersion != 1
                || id == null
                || !id.matches("[a-z][a-z0-9-]{0,63}")
                || name == null
                || name.isBlank()
                || version == null
                || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || !"1".equals(sdkVersion)
                || entryClass == null
                || !entryClass.matches("[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+")
                || !"backend/plugin.jar".equals(backend)
                || !"frontend/plugin.js".equals(frontend))
            throw new IllegalArgumentException("invalidPluginPackage");
        if (permissions.stream().anyMatch(p -> !p.matches("platform:" + id + "[:][a-z][a-z-]*")))
            throw new IllegalArgumentException("invalidPluginPermission");
        PluginDataRemoval.validate(id, dataSets);
        var ids = new HashSet<String>();
        for (var d : dependencies)
            if (d == null
                    || d.id().equals(id)
                    || !ids.add(d.id())
                    || !d.id().matches("[a-z][a-z0-9-]{0,63}")
                    || !d.version().matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
                throw new IllegalArgumentException("invalidPluginDependency");
        ids.clear();
        for (var r : routes)
            if (!r.id().matches("[a-z][a-z0-9-]{0,63}")
                    || !ids.add(r.id())
                    || r.permission() != null && !permissions.contains(r.permission())
                    || !Set.of(
                                    "overview",
                                    "ai-management",
                                    "user-management",
                                    "content-management",
                                    "operations",
                                    "administration")
                            .contains(r.group()))
                throw new IllegalArgumentException("invalidPluginRoute");
        for (var r : apiRules)
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(r.method())
                    || !r.path().startsWith("/api/")
                    || r.path().contains("..")
                    || r.authorities() == null
                    || !permissions.containsAll(r.authorities())
                    || !r.publicAccess() && !r.authenticatedOnly() && r.authorities().isEmpty()
                    || r.authenticatedOnly()
                            && (r.publicAccess() || r.global() || !r.authorities().isEmpty())
                    || r.previewReadOnly()
                            && (!r.method().equals("GET")
                                    || r.publicAccess()
                                    || r.authenticatedOnly()
                                    || r.authorities().isEmpty())
                    || r.publicAccess() && !r.method().equals("GET"))
                throw new IllegalArgumentException("invalidPluginPermission");
    }
}
