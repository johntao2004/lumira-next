package com.lumira.plugins.directory.roles;

import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import java.util.*;

/** Versioned, declarative catalog for host dashboard pages and their independent grants. */
final class PlatformPermissionCatalog {
    static final int VERSION = 2;

    record Permission(String code, String label) {}

    record Page(
            String id,
            String path,
            String title,
            String navigationGroup,
            int navigationOrder,
            String accessPermission,
            String accessScope,
            List<String> operationPermissions,
            Map<String, String> operationScopes) {
        Page {
            operationPermissions = List.copyOf(operationPermissions);
            operationScopes = Map.copyOf(operationScopes);
        }
    }

    record Group(String id, String title, List<Page> pages, List<Permission> permissions) {
        Group {
            pages = List.copyOf(pages);
            permissions = List.copyOf(permissions);
        }
    }

    private static Page page(
            PlatformPageId page,
            String title,
            String navigationGroup,
            int navigationOrder,
            PlatformOperation... operations) {
        for (var operation : operations)
            if (operation.page() != page)
                throw new IllegalArgumentException(
                        "Operation belongs to another page: " + operation);
        return new Page(
                page.id(),
                "/dashboard/" + page.id(),
                title,
                navigationGroup,
                navigationOrder,
                page.accessPermission(),
                page.scope().name().toLowerCase(Locale.ROOT),
                Arrays.stream(operations).map(PlatformOperation::permission).toList(),
                Arrays.stream(operations)
                        .collect(
                                java.util.stream.Collectors.toUnmodifiableMap(
                                        PlatformOperation::permission,
                                        operation ->
                                                operation
                                                        .scope()
                                                        .name()
                                                        .toLowerCase(Locale.ROOT))));
    }

    private static Group group(String id, String title, Page... pages) {
        List<Page> pageList = List.of(pages);
        var permissions = new LinkedHashMap<String, Permission>();
        for (var page : pageList) {
            permissions.put(
                    page.accessPermission(),
                    new Permission(page.accessPermission(), "permissionPageAccess"));
            for (var code : page.operationPermissions())
                permissions.put(code, new Permission(code, actionLabel(code)));
        }
        return new Group(id, title, pageList, List.copyOf(permissions.values()));
    }

    static List<Group> groups() {
        return List.of(
                group(
                        "modules",
                        "modules",
                        page(
                                PlatformPageId.MODULES,
                                "modules",
                                "administration",
                                10,
                                PlatformOperation.MODULE_INSTALL,
                                PlatformOperation.MODULE_UPDATE,
                                PlatformOperation.MODULE_UNINSTALL)));
    }

    static Set<String> permissions() {
        return groups().stream()
                .flatMap(group -> group.permissions().stream())
                .map(Permission::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static Set<String> accessPermissions() {
        return groups().stream()
                .flatMap(group -> group.pages().stream())
                .map(Page::accessPermission)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static Set<String> operationPermissions() {
        return Arrays.stream(PlatformOperation.values())
                .map(PlatformOperation::permission)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String actionLabel(String permission) {
        if ("platform:roles:preview".equals(permission)) return "permissionPreviewRole";
        if ("platform:files-all:preview".equals(permission)) return "permissionPreviewFiles";
        if ("platform:files-all:download".equals(permission)) return "downloadFile";
        if ("platform:files-all:update-quota".equals(permission))
            return "permissionUpdateFileQuota";
        String action = permission.substring(permission.lastIndexOf(':') + 1);
        return switch (action) {
            case "create", "create-user" -> "permissionCreate";
            case "update", "update-user" -> "permissionUpdate";
            case "delete", "delete-user" -> "permissionDelete";
            case "import" -> "permissionImport";
            case "export" -> "permissionExport";
            case "purge" -> "permissionPurgeFiles";
            case "restore" -> "permissionRestoreFiles";
            case "install" -> "installPlugin";
            case "uninstall" -> "uninstallPlugin";
            case "replay" -> "permissionReplayEvents";
            case "reset-password" -> "resetPassword";
            case "terminate-session" -> "revokeOnlineSession";
            case "assign-roles" -> "permissionAssignRoles";
            case "assign-organizations" -> "permissionAssignOrganizations";
            case "read-roles" -> "permissionReadRoles";
            case "read-authentication-policy" -> "permissionReadAuthenticationPolicy";
            case "update-authentication-policy" -> "permissionUpdateAuthenticationPolicy";
            case "assign-default-role" -> "permissionAssignDefaultRole";
            case "test" -> "permissionTestConnection";
            case "read-users" -> "permissionReadUsers";
            case "read-dictionaries" -> "permissionReadDictionaries";
            default -> action;
        };
    }

    private PlatformPermissionCatalog() {}
}
