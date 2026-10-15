package com.lumira.platform.roles;

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
                        "users",
                        "userManagement",
                        page(
                                PlatformPageId.USERS,
                                "userList",
                                "user-management",
                                10,
                                PlatformOperation.USER_CREATE,
                                PlatformOperation.USER_UPDATE,
                                PlatformOperation.USER_DELETE,
                                PlatformOperation.USER_RESET_PASSWORD,
                                PlatformOperation.USER_ASSIGN_ROLES,
                                PlatformOperation.USER_ASSIGN_ORGANIZATIONS,
                                PlatformOperation.USER_READ_ROLES,
                                PlatformOperation.USER_EXPORT),
                        page(
                                PlatformPageId.ONLINE_USERS,
                                "onlineUsers",
                                "user-management",
                                20,
                                PlatformOperation.ONLINE_USER_TERMINATE_SESSION)),
                group(
                        "roles",
                        "roles",
                        page(
                                PlatformPageId.ROLES,
                                "roles",
                                "user-management",
                                30,
                                PlatformOperation.ROLE_CREATE,
                                PlatformOperation.ROLE_UPDATE,
                                PlatformOperation.ROLE_DELETE,
                                PlatformOperation.ROLE_PREVIEW,
                                PlatformOperation.ROLE_READ_AUTHENTICATION_POLICY,
                                PlatformOperation.ROLE_UPDATE_AUTHENTICATION_POLICY)),
                group(
                        "organizations",
                        "organizations",
                        page(
                                PlatformPageId.ORGANIZATIONS,
                                "organizations",
                                "user-management",
                                40,
                                PlatformOperation.ORGANIZATION_CREATE,
                                PlatformOperation.ORGANIZATION_UPDATE,
                                PlatformOperation.ORGANIZATION_DELETE)),
                group(
                        "audit",
                        "audit",
                        page(PlatformPageId.AUDIT, "audit", "operations", 30),
                        page(PlatformPageId.MAIL_DELIVERIES, "mailDeliveries", "communication", 40),
                        page(PlatformPageId.SMS_DELIVERIES, "smsDeliveries", "communication", 50),
                        page(
                                PlatformPageId.EVENT_OUTBOX,
                                "eventOutbox",
                                "operations",
                                40,
                                PlatformOperation.EVENT_OUTBOX_REPLAY),
                        page(PlatformPageId.MONITORING, "monitoring", "operations", 10),
                        page(PlatformPageId.API_DOCS, "apiDocs", "operations", 20)),
                group(
                        "dictionaries",
                        "dictionaries",
                        page(
                                PlatformPageId.DICTIONARIES,
                                "dictionaries",
                                "content-management",
                                20,
                                PlatformOperation.DICTIONARY_CREATE,
                                PlatformOperation.DICTIONARY_UPDATE,
                                PlatformOperation.DICTIONARY_DELETE,
                                PlatformOperation.DICTIONARY_IMPORT,
                                PlatformOperation.DICTIONARY_EXPORT)),
                group(
                        "settings",
                        "settings",
                        page(
                                PlatformPageId.BRANDING,
                                "siteSettings",
                                "administration",
                                20,
                                PlatformOperation.BRANDING_UPDATE),
                        page(
                                PlatformPageId.SMTP,
                                "smtpSettings",
                                "communication",
                                20,
                                PlatformOperation.SMTP_UPDATE,
                                PlatformOperation.SMTP_TEST),
                        page(
                                PlatformPageId.SMS,
                                "smsSettings",
                                "communication",
                                30,
                                PlatformOperation.SMS_UPDATE,
                                PlatformOperation.SMS_TEST),
                        page(
                                PlatformPageId.PROFILE_FIELDS,
                                "profileFields",
                                "user-management",
                                50,
                                PlatformOperation.PROFILE_FIELDS_UPDATE,
                                PlatformOperation.PROFILE_FIELDS_READ_DICTIONARIES),
                        page(
                                PlatformPageId.AGREEMENTS,
                                "agreements",
                                "administration",
                                40,
                                PlatformOperation.AGREEMENTS_UPDATE),
                        page(
                                PlatformPageId.WATERMARK,
                                "watermark",
                                "administration",
                                30,
                                PlatformOperation.WATERMARK_UPDATE),
                        page(
                                PlatformPageId.MAINTENANCE,
                                "maintenance",
                                "administration",
                                50,
                                PlatformOperation.MAINTENANCE_UPDATE),
                        page(
                                PlatformPageId.SECURITY,
                                "authenticationSecurity",
                                "user-management",
                                60,
                                PlatformOperation.SECURITY_UPDATE),
                        page(
                                PlatformPageId.AUTHENTICATION,
                                "authenticationMethods",
                                "user-management",
                                70,
                                PlatformOperation.AUTHENTICATION_UPDATE),
                        page(
                                PlatformPageId.PASSKEY,
                                "passkeySettings",
                                "user-management",
                                80,
                                PlatformOperation.PASSKEY_UPDATE),
                        page(
                                PlatformPageId.WECHAT,
                                "wechatSettings",
                                "user-management",
                                90,
                                PlatformOperation.WECHAT_UPDATE),
                        page(
                                PlatformPageId.CAPTCHA,
                                "captchaSettingsTitle",
                                "user-management",
                                100,
                                PlatformOperation.CAPTCHA_UPDATE),
                        page(
                                PlatformPageId.REGISTRATION,
                                "registrationSettingsTitle",
                                "user-management",
                                110,
                                PlatformOperation.REGISTRATION_UPDATE,
                                PlatformOperation.REGISTRATION_READ_ROLES,
                                PlatformOperation.REGISTRATION_ASSIGN_DEFAULT_ROLE),
                        page(
                                PlatformPageId.FILE_RETENTION,
                                "fileRetention",
                                "administration",
                                195,
                                PlatformOperation.FILE_RETENTION_UPDATE),
                        page(
                                PlatformPageId.AI_CONSOLE,
                                "aiConsole",
                                "ai-management",
                                10,
                                PlatformOperation.AI_CONSOLE_READ_USERS,
                                PlatformOperation.AI_CONSOLE_CREATE_USER,
                                PlatformOperation.AI_CONSOLE_UPDATE_USER,
                                PlatformOperation.AI_CONSOLE_DELETE_USER),
                        page(
                                PlatformPageId.AI_MODELS,
                                "aiModels",
                                "ai-management",
                                20,
                                PlatformOperation.AI_MODELS_CREATE,
                                PlatformOperation.AI_MODELS_UPDATE,
                                PlatformOperation.AI_MODELS_DELETE,
                                PlatformOperation.AI_MODELS_TEST)),
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
                                PlatformOperation.MODULE_UNINSTALL)),
                group(
                        "navigation",
                        "navigationManagement",
                        page(
                                PlatformPageId.NAVIGATION,
                                "navigationManagement",
                                "administration",
                                60,
                                PlatformOperation.NAVIGATION_CREATE,
                                PlatformOperation.NAVIGATION_UPDATE,
                                PlatformOperation.NAVIGATION_DELETE)),
                group(
                        "files",
                        "allFiles",
                        page(
                                PlatformPageId.FILES_ALL,
                                "allFiles",
                                "content-management",
                                10,
                                PlatformOperation.FILES_ALL_UPDATE_QUOTA,
                                PlatformOperation.FILE_DELETE,
                                PlatformOperation.FILE_RESTORE,
                                PlatformOperation.FILE_PURGE,
                                PlatformOperation.FILE_PREVIEW,
                                PlatformOperation.FILE_DOWNLOAD)),
                group(
                        "notifications",
                        "notificationCenter",
                        page(
                                PlatformPageId.NOTIFICATIONS,
                                "notificationCenter",
                                "communication",
                                10,
                                PlatformOperation.NOTIFICATION_CREATE)));
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
