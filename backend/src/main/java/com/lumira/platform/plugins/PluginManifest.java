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
        List<PermissionGroup> permissionGroups,
        List<Route> routes,
        List<Rule> apiRules,
        Map<String, String> files,
        List<DataSet> dataSets) {
    /** Platform module ids and permission resources must remain platform-owned. */
    private static final Set<String> RESERVED_PLUGIN_IDS =
            Set.of(
                    "users",
                    "roles",
                    "organizations",
                    "audit",
                    "settings",
                    "modules",
                    "navigation",
                    "files",
                    "events",
                    "platform",
                    "identity",
                    "communication");

    public record Dependency(String id, String version) {}

    public record Permission(String code, String label, List<String> requires) {
        public Permission {
            requires = List.copyOf(requires == null ? List.of() : requires);
        }

        public Permission(String code, String label) {
            this(code, label, List.of());
        }
    }

    public record PermissionGroup(String id, String title, List<Permission> permissions) {
        public PermissionGroup {
            permissions = List.copyOf(permissions == null ? List.of() : permissions);
        }
    }

    public record Route(
            String id,
            String label,
            String permission,
            Map<String, String> operations,
            List<String> scopedOperations,
            String group,
            int order,
            boolean global) {
        public Route {
            operations =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(operations == null ? Map.of() : operations));
            scopedOperations = List.copyOf(scopedOperations == null ? List.of() : scopedOperations);
        }

        /** Compatibility constructor for v1 routes without explicit scoped action IDs. */
        public Route(
                String id,
                String label,
                String permission,
                Map<String, String> operations,
                String group,
                int order,
                boolean global) {
            this(id, label, permission, operations, List.of(), group, order, global);
        }

        /** Compatibility constructor for v1 manifests without explicit operation mappings. */
        public Route(
                String id,
                String label,
                String permission,
                String group,
                int order,
                boolean global) {
            this(id, label, permission, Map.of(), List.of(), group, order, global);
        }
    }

    public record Rule(
            String method,
            String path,
            List<String> authorities,
            boolean global,
            boolean publicAccess,
            Boolean authenticatedOnly,
            Boolean previewReadOnly,
            Boolean signedCallback,
            List<String> allAuthorities,
            List<String> globalAuthorities,
            Map<String, String> authorityScopes) {
        public Rule {
            authenticatedOnly = Boolean.TRUE.equals(authenticatedOnly);
            previewReadOnly = Boolean.TRUE.equals(previewReadOnly);
            signedCallback = Boolean.TRUE.equals(signedCallback);
            authorityScopes = Map.copyOf(authorityScopes == null ? Map.of() : authorityScopes);
            allAuthorities = List.copyOf(allAuthorities == null ? List.of() : allAuthorities);
            globalAuthorities =
                    List.copyOf(globalAuthorities == null ? List.of() : globalAuthorities);
        }

        /** Compatibility with published rules before per-authority scopes. */
        public Rule(
                String method,
                String path,
                List<String> authorities,
                boolean global,
                boolean publicAccess,
                Boolean authenticatedOnly,
                Boolean previewReadOnly,
                Boolean signedCallback,
                List<String> allAuthorities,
                List<String> globalAuthorities) {
            this(
                    method,
                    path,
                    authorities,
                    global,
                    publicAccess,
                    authenticatedOnly,
                    previewReadOnly,
                    signedCallback,
                    allAuthorities,
                    globalAuthorities,
                    Map.of());
        }

        /** Compatibility constructor for v1 manifests without mixed-scope global gates. */
        public Rule(
                String method,
                String path,
                List<String> authorities,
                boolean global,
                boolean publicAccess,
                Boolean authenticatedOnly,
                Boolean previewReadOnly,
                Boolean signedCallback,
                List<String> allAuthorities) {
            this(
                    method,
                    path,
                    authorities,
                    global,
                    publicAccess,
                    authenticatedOnly,
                    previewReadOnly,
                    signedCallback,
                    allAuthorities,
                    List.of());
        }

        public Rule(
                String method,
                String path,
                List<String> authorities,
                boolean global,
                boolean publicAccess,
                Boolean authenticatedOnly,
                Boolean previewReadOnly,
                Boolean signedCallback) {
            this(
                    method,
                    path,
                    authorities,
                    global,
                    publicAccess,
                    authenticatedOnly,
                    previewReadOnly,
                    signedCallback,
                    List.of(),
                    List.of());
        }

        public Rule(
                String method,
                String path,
                List<String> authorities,
                boolean global,
                boolean publicAccess,
                Boolean authenticatedOnly,
                Boolean previewReadOnly) {
            this(
                    method,
                    path,
                    authorities,
                    global,
                    publicAccess,
                    authenticatedOnly,
                    previewReadOnly,
                    false,
                    List.of(),
                    List.of());
        }

        public Rule(
                String method,
                String path,
                List<String> authorities,
                boolean global,
                boolean publicAccess,
                Boolean authenticatedOnly) {
            this(
                    method,
                    path,
                    authorities,
                    global,
                    publicAccess,
                    authenticatedOnly,
                    false,
                    false,
                    List.of(),
                    List.of());
        }

        public Rule(
                String method,
                String path,
                List<String> authorities,
                boolean global,
                boolean publicAccess) {
            this(
                    method,
                    path,
                    authorities,
                    global,
                    publicAccess,
                    false,
                    false,
                    false,
                    List.of(),
                    List.of());
        }
    }

    /** Ordered, plugin-owned rows. A shared table requires an explicit equality filter. */
    public record DataSet(String table, String label, String column, String value) {}

    public PluginManifest {
        dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
        permissions = List.copyOf(permissions == null ? List.of() : permissions);
        permissionGroups = normalizePermissionGroups(permissionGroups, id, name, permissions);
        routes = List.copyOf(routes == null ? List.of() : routes);
        apiRules = List.copyOf(apiRules == null ? List.of() : apiRules);
        files = Map.copyOf(files == null ? Map.of() : files);
        dataSets = List.copyOf(dataSets == null ? List.of() : dataSets);
        if ("feedback".equals(id))
            dataSets = dataSets.stream().filter(set -> !isRetiredFeedbackDraftScope(set)).toList();
        var permissionCodes = Set.copyOf(permissions);
        if ((formatVersion != 1 && formatVersion != 2)
                || id == null
                || !id.matches("[a-z][a-z0-9-]{0,63}")
                || RESERVED_PLUGIN_IDS.contains(id)
                || name == null
                || name.isBlank()
                || version == null
                || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || sdkVersion == null
                || !Set.of("1", "2").contains(sdkVersion)
                || entryClass == null
                || !entryClass.matches("[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+")
                || !"backend/plugin.jar".equals(backend)
                || !"frontend/plugin.js".equals(frontend))
            throw new IllegalArgumentException("invalidPluginPackage");
        if (permissions.stream().anyMatch(p -> !pluginPermission(id, p)))
            throw new IllegalArgumentException("invalidPluginPermission");
        validatePermissionGroups(id, permissions, permissionGroups);
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
        var mappedOperationCodes = new HashSet<String>();
        var mappedPageCodes = new HashSet<String>();
        for (var r : routes)
            if (r == null
                    || r.id() == null
                    || !r.id().matches("[a-z][a-z0-9-]{0,63}")
                    || !ids.add(r.id())
                    || r.permission() != null
                            && !routePermissionDeclared(r.permission(), permissions, dependencies)
                    || formatVersion == 2
                            && r.permission() != null
                            && (!permissions.contains(r.permission())
                                    || r.operations().isEmpty()
                                    || !mappedPageCodes.add(r.permission()))
                    || !r.operations().isEmpty()
                            && (r.permission() == null || !permissions.contains(r.permission()))
                    || r.operations().entrySet().stream()
                            .anyMatch(
                                    operation ->
                                            operation.getKey() == null
                                                    || !operation
                                                            .getKey()
                                                            .matches("[a-z][A-Za-z0-9-]{0,63}")
                                                    || operation.getValue() == null
                                                    || !permissionCodes.contains(
                                                            operation.getValue())
                                                    || !pluginPermission(id, operation.getValue())
                                                    || operation.getValue().equals(r.permission()))
                    || r.operations().values().stream()
                            .anyMatch(code -> !mappedOperationCodes.add(code))
                    || r.scopedOperations().stream()
                            .anyMatch(action -> !r.operations().containsKey(action))
                    || new HashSet<>(r.scopedOperations()).size() != r.scopedOperations().size()
                    || !Set.of(
                                    "overview",
                                    "ai-management",
                                    "user-management",
                                    "content-management",
                                    "communication",
                                    "payments",
                                    "operations",
                                    "administration")
                            .contains(r.group()))
                throw new IllegalArgumentException("invalidPluginRoute");
        if (formatVersion == 2 && mappedOperationCodes.stream().anyMatch(mappedPageCodes::contains))
            throw new IllegalArgumentException("invalidPluginRoute");
        for (var group : permissionGroups) {
            var groupCodes =
                    group.permissions().stream()
                            .map(Permission::code)
                            .collect(java.util.stream.Collectors.toSet());
            for (var route : routes)
                if (route.permission() != null
                        && groupCodes.contains(route.permission())
                        && !groupCodes.containsAll(route.operations().values()))
                    throw new IllegalArgumentException("invalidPluginRoute");
        }
        for (var r : apiRules)
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(r.method())
                    || !r.path().startsWith("/api/")
                    || r.path().contains("..")
                    || r.authorities() == null
                    || !permissions.containsAll(r.authorities())
                    || r.authorities().stream().anyMatch(Objects::isNull)
                    || r.allAuthorities().stream()
                            .anyMatch(
                                    authority ->
                                            authority == null
                                                    || !permissionCodes.contains(authority)
                                                    || !pluginPermission(id, authority))
                    || r.globalAuthorities().stream()
                            .anyMatch(
                                    authority ->
                                            authority == null
                                                    || !permissionCodes.contains(authority)
                                                    || !pluginPermission(id, authority)
                                                    || !java.util.stream.Stream.concat(
                                                                    r.authorities().stream(),
                                                                    r.allAuthorities().stream())
                                                            .anyMatch(authority::equals))
                    || r.authorityScopes().entrySet().stream()
                            .anyMatch(
                                    entry ->
                                            !Set.of("global", "scoped").contains(entry.getValue())
                                                    || !java.util.stream.Stream.concat(
                                                                    r.authorities().stream(),
                                                                    r.allAuthorities().stream())
                                                            .anyMatch(entry.getKey()::equals))
                    || !r.authorityScopes().isEmpty()
                            && (!"2".equals(sdkVersion)
                                    || r.publicAccess()
                                    || r.authenticatedOnly())
                    || new HashSet<>(r.authorities()).size() != r.authorities().size()
                    || new HashSet<>(r.allAuthorities()).size() != r.allAuthorities().size()
                    || new HashSet<>(r.globalAuthorities()).size() != r.globalAuthorities().size()
                    || !r.publicAccess()
                            && !r.authenticatedOnly()
                            && r.authorities().isEmpty()
                            && r.allAuthorities().isEmpty()
                            && r.globalAuthorities().isEmpty()
                    || !r.allAuthorities().isEmpty() && (r.publicAccess() || r.authenticatedOnly())
                    || !r.globalAuthorities().isEmpty()
                            && (r.publicAccess() || r.authenticatedOnly())
                    || r.authenticatedOnly()
                            && (r.publicAccess() || r.global() || !r.authorities().isEmpty())
                    || r.previewReadOnly()
                            && (!r.method().equals("GET")
                                    || r.publicAccess()
                                    || r.authenticatedOnly()
                                    || r.authorities().isEmpty()
                                            && r.allAuthorities().isEmpty()
                                            && r.globalAuthorities().isEmpty())
                    || r.signedCallback()
                            && (!r.method().equals("POST")
                                    || !r.path()
                                            .matches("/api/plugins/" + id + "/callbacks/[a-z0-9-]+")
                                    || !r.publicAccess()
                                    || r.global()
                                    || r.authenticatedOnly()
                                    || r.previewReadOnly()
                                    || !r.authorities().isEmpty()
                                    || !r.allAuthorities().isEmpty())
                    || r.publicAccess() && !r.method().equals("GET") && !r.signedCallback())
                throw new IllegalArgumentException("invalidPluginPermission");
        for (var rule : apiRules)
            if ((!rule.allAuthorities().isEmpty()
                            || !rule.globalAuthorities().isEmpty()
                            || formatVersion == 2 && !rule.authorities().isEmpty())
                    && routes.stream()
                            .noneMatch(
                                    route ->
                                            formatVersion == 2
                                                    ? mappedByV2Route(route, rule)
                                                    : mappedByRoute(
                                                            route,
                                                            java.util.stream.Stream.concat(
                                                                            java.util.stream.Stream
                                                                                    .concat(
                                                                                            rule
                                                                                                    .allAuthorities()
                                                                                                    .stream(),
                                                                                            rule
                                                                                                    .authorities()
                                                                                                    .stream()),
                                                                            rule
                                                                                    .globalAuthorities()
                                                                                    .stream())
                                                                    .distinct()
                                                                    .toList()))
                    && !mappedByRoutes(routes, rule, formatVersion == 2))
                throw new IllegalArgumentException("invalidPluginPermission");
    }

    /** Compatibility constructor for manifests and tests written before permission groups. */
    public PluginManifest(
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
        this(
                formatVersion,
                id,
                name,
                version,
                sdkVersion,
                entryClass,
                backend,
                frontend,
                dependencies,
                permissions,
                null,
                routes,
                apiRules,
                files,
                dataSets);
    }

    private static List<PermissionGroup> normalizePermissionGroups(
            List<PermissionGroup> groups, String id, String name, List<String> permissions) {
        if (groups != null && !groups.isEmpty()) return List.copyOf(groups);
        if (permissions == null || permissions.isEmpty()) return List.of();
        var items =
                permissions.stream()
                        .map(
                                code -> {
                                    int separator = code == null ? -1 : code.lastIndexOf(':');
                                    String label =
                                            separator < 0 ? code : code.substring(separator + 1);
                                    return new Permission(code, label, List.of());
                                })
                        .toList();
        return List.of(new PermissionGroup(id, name, items));
    }

    private static void validatePermissionGroups(
            String pluginId, List<String> permissions, List<PermissionGroup> groups) {
        var groupIds = new HashSet<String>();
        var groupedPermissions = new HashSet<String>();
        for (var group : groups) {
            if (group == null
                    || group.id() == null
                    || !group.id().matches("[a-z][a-z0-9-]{0,63}")
                    || !groupIds.add(group.id())
                    || group.title() == null
                    || group.title().isBlank()
                    || group.title().length() > 200
                    || group.permissions().isEmpty())
                throw new IllegalArgumentException("invalidPluginPermissionGroup");
            for (var permission : group.permissions())
                if (permission == null
                        || !permissions.contains(permission.code())
                        || !groupedPermissions.add(permission.code())
                        || permission.label() == null
                        || permission.label().isBlank()
                        || permission.label().length() > 200
                        || permission.requires().stream()
                                .anyMatch(
                                        required ->
                                                required == null
                                                        || !required.matches(
                                                                "platform:[a-z][a-z0-9-]{0,63}:[a-z][a-z-]*"))
                        || new HashSet<>(permission.requires()).size()
                                != permission.requires().size())
                    throw new IllegalArgumentException("invalidPluginPermissionGroup");
        }
        if (permissions.size() != groupedPermissions.size()
                || !groupedPermissions.containsAll(permissions))
            throw new IllegalArgumentException("invalidPluginPermissionGroup");
    }

    private static boolean isRetiredFeedbackDraftScope(DataSet set) {
        return set != null
                && "platform_account_draft".equals(set.table())
                && "draft_key".equals(set.column())
                && "feedback.create".equals(set.value());
    }

    private static boolean routePermissionDeclared(
            String permission, List<String> permissions, List<Dependency> dependencies) {
        if (permissions.contains(permission)) return true;
        return dependencies.stream()
                .map(dependency -> "platform:" + dependency.id() + ":")
                .anyMatch(
                        prefix ->
                                permission.startsWith(prefix)
                                        && permission
                                                .substring(prefix.length())
                                                .matches("[a-z][a-z-]*"));
    }

    private static boolean pluginPermission(String pluginId, String permission) {
        if ("authentication-console".equals(pluginId)
                && permission != null
                && com.lumira.platform.common.AuthenticationConsoleAuthorityCodes.values()
                        .contains(permission)) return true;
        if ("profile-console".equals(pluginId)
                && permission != null
                && com.lumira.platform.common.ProfileConsoleAuthorityCodes.values()
                        .contains(permission)) return true;
        if ("delivery-settings".equals(pluginId)
                && permission != null
                && com.lumira.platform.common.DeliverySettingsAuthorityCodes.values()
                        .contains(permission)) return true;
        if ("site-console".equals(pluginId)
                && permission != null
                && com.lumira.platform.common.SiteConsoleAuthorityCodes.values()
                        .contains(permission)) return true;
        if ("file-console".equals(pluginId)
                && permission != null
                && com.lumira.platform.common.FileConsoleAuthorityCodes.values()
                        .contains(permission)) return true;
        if ("file-retention".equals(pluginId)
                && permission != null
                && Set.of("platform:file-retention:access", "platform:file-retention:update")
                        .contains(permission)) return true;
        if ("ai".equals(pluginId)
                && permission != null
                && com.lumira.platform.common.AiAuthorityCodes.values().contains(permission))
            return true;
        if ("directory".equals(pluginId)
                && permission != null
                && com.lumira.platform.common.DirectoryAuthorityCodes.values().contains(permission))
            return true;
        if ("navigation-console".equals(pluginId)
                && permission != null
                && Set.of(
                                "platform:navigation:access",
                                "platform:navigation:create",
                                "platform:navigation:update",
                                "platform:navigation:delete")
                        .contains(permission)) return true;
        if ("delivery-console".equals(pluginId)
                && permission != null
                && Set.of("platform:mail-deliveries:access", "platform:sms-deliveries:access")
                        .contains(permission)) return true;
        if ("event-console".equals(pluginId)
                && permission != null
                && Set.of("platform:event-outbox:access", "platform:event-outbox:replay")
                        .contains(permission)) return true;
        // Preserve exact legacy page grants when business pages move out of the kernel.
        if ("monitoring".equals(pluginId) && "platform:api-docs:access".equals(permission))
            return true;
        if ("audit-console".equals(pluginId) && "platform:audit:access".equals(permission))
            return true;
        return permission != null
                && permission.matches(
                        "platform:" + java.util.regex.Pattern.quote(pluginId) + ":[a-z][a-z0-9-]*");
    }

    private static boolean mappedByRoute(Route route, List<String> authorities) {
        if (!authorities.contains(route.permission())) return false;
        var mapped = new HashSet<String>(route.operations().values());
        mapped.add(route.permission());
        return mapped.containsAll(authorities);
    }

    /** Each action in a cross-page rule must retain its own conjunctive page gate. */
    private static boolean mappedByRoutes(List<Route> routes, Rule rule, boolean actionsRequired) {
        var pageCodes =
                routes.stream()
                        .map(Route::permission)
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
        if (rule.authorities().stream().anyMatch(pageCodes::contains)) return false;
        var codes =
                java.util.stream.Stream.concat(
                                rule.authorities().stream(), rule.allAuthorities().stream())
                        .distinct()
                        .toList();
        boolean action = false;
        for (String code : codes) {
            if (pageCodes.contains(code)) continue;
            action = true;
            if (routes.stream()
                    .noneMatch(
                            route ->
                                    route.permission() != null
                                            && rule.allAuthorities().contains(route.permission())
                                            && route.operations().containsValue(code)))
                return false;
        }
        return !codes.isEmpty() && (!actionsRequired || action);
    }

    /** V2 rules require their page gate and at least one same-page action to be enforced. */
    private static boolean mappedByV2Route(Route route, Rule rule) {
        if (route.permission() == null || !rule.allAuthorities().contains(route.permission()))
            return false;
        var actions = new HashSet<>(route.operations().values());
        if (rule.authorities().contains(route.permission())
                || !actions.containsAll(rule.authorities())) return false;
        var requiredActions = new HashSet<String>(rule.authorities());
        for (String authority : rule.allAuthorities())
            if (!route.permission().equals(authority)) requiredActions.add(authority);
        return !requiredActions.isEmpty() && actions.containsAll(requiredActions);
    }
}
