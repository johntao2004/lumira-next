package com.lumira.platform.common;

/** Server-declared operations. Each operation belongs to exactly one dashboard page. */
public enum PlatformOperation {
    USER_CREATE(PlatformPageId.USERS, "create"),
    USER_UPDATE(PlatformPageId.USERS, "update", Scope.SCOPED),
    USER_DELETE(PlatformPageId.USERS, "delete", Scope.SCOPED),
    USER_RESET_PASSWORD(PlatformPageId.USERS, "reset-password", Scope.SCOPED),
    USER_ASSIGN_ROLES(PlatformPageId.USERS, "assign-roles", Scope.SCOPED),
    USER_ASSIGN_ORGANIZATIONS(PlatformPageId.USERS, "assign-organizations", Scope.SCOPED),
    USER_EXPORT(PlatformPageId.USERS, "export", Scope.SCOPED),
    USER_IMPORT(PlatformPageId.USERS, "import", Scope.SCOPED),
    USER_PRINT(PlatformPageId.USERS, "print", Scope.SCOPED),
    USER_READ_ROLES(PlatformPageId.USERS, "read-roles"),
    ONLINE_USER_TERMINATE_SESSION(PlatformPageId.ONLINE_USERS, "terminate-session"),
    ROLE_CREATE(PlatformPageId.ROLES, "create"),
    ROLE_UPDATE(PlatformPageId.ROLES, "update"),
    ROLE_DELETE(PlatformPageId.ROLES, "delete"),
    ROLE_PREVIEW(PlatformPageId.ROLES, "preview"),
    ROLE_READ_AUTHENTICATION_POLICY(PlatformPageId.ROLES, "read-authentication-policy"),
    ROLE_UPDATE_AUTHENTICATION_POLICY(PlatformPageId.ROLES, "update-authentication-policy"),
    ORGANIZATION_CREATE(PlatformPageId.ORGANIZATIONS, "create"),
    ORGANIZATION_UPDATE(PlatformPageId.ORGANIZATIONS, "update"),
    ORGANIZATION_DELETE(PlatformPageId.ORGANIZATIONS, "delete"),
    EVENT_OUTBOX_REPLAY(PlatformPageId.EVENT_OUTBOX, "replay"),
    DICTIONARY_CREATE(PlatformPageId.DICTIONARIES, "create"),
    DICTIONARY_UPDATE(PlatformPageId.DICTIONARIES, "update"),
    DICTIONARY_DELETE(PlatformPageId.DICTIONARIES, "delete"),
    DICTIONARY_IMPORT(PlatformPageId.DICTIONARIES, "import"),
    DICTIONARY_EXPORT(PlatformPageId.DICTIONARIES, "export"),
    BRANDING_UPDATE(PlatformPageId.BRANDING, "update"),
    SMTP_UPDATE(PlatformPageId.SMTP, "update"),
    SMTP_TEST(PlatformPageId.SMTP, "test"),
    SMS_UPDATE(PlatformPageId.SMS, "update"),
    SMS_TEST(PlatformPageId.SMS, "test"),
    PROFILE_FIELDS_UPDATE(PlatformPageId.PROFILE_FIELDS, "update"),
    PROFILE_FIELDS_READ_DICTIONARIES(PlatformPageId.PROFILE_FIELDS, "read-dictionaries"),
    AGREEMENTS_UPDATE(PlatformPageId.AGREEMENTS, "update"),
    WATERMARK_UPDATE(PlatformPageId.WATERMARK, "update"),
    MAINTENANCE_UPDATE(PlatformPageId.MAINTENANCE, "update"),
    SECURITY_UPDATE(PlatformPageId.SECURITY, "update"),
    AUTHENTICATION_UPDATE(PlatformPageId.AUTHENTICATION, "update"),
    PASSKEY_UPDATE(PlatformPageId.PASSKEY, "update"),
    WECHAT_UPDATE(PlatformPageId.WECHAT, "update"),
    CAPTCHA_UPDATE(PlatformPageId.CAPTCHA, "update"),
    REGISTRATION_UPDATE(PlatformPageId.REGISTRATION, "update"),
    REGISTRATION_READ_ROLES(PlatformPageId.REGISTRATION, "read-roles"),
    REGISTRATION_ASSIGN_DEFAULT_ROLE(PlatformPageId.REGISTRATION, "assign-default-role"),
    FILE_RETENTION_UPDATE(PlatformPageId.FILE_RETENTION, "update"),
    AI_CONSOLE_READ_USERS(PlatformPageId.AI_CONSOLE, "read-users", Scope.SCOPED),
    AI_CONSOLE_CREATE_USER(PlatformPageId.AI_CONSOLE, "create-user"),
    AI_CONSOLE_UPDATE_USER(PlatformPageId.AI_CONSOLE, "update-user", Scope.SCOPED),
    AI_CONSOLE_DELETE_USER(PlatformPageId.AI_CONSOLE, "delete-user", Scope.SCOPED),
    AI_MODELS_CREATE(PlatformPageId.AI_MODELS, "create"),
    AI_MODELS_UPDATE(PlatformPageId.AI_MODELS, "update"),
    AI_MODELS_DELETE(PlatformPageId.AI_MODELS, "delete"),
    AI_MODELS_TEST(PlatformPageId.AI_MODELS, "test"),
    MODULE_INSTALL(PlatformPageId.MODULES, "install"),
    MODULE_UPDATE(PlatformPageId.MODULES, "update"),
    MODULE_UNINSTALL(PlatformPageId.MODULES, "uninstall"),
    NAVIGATION_CREATE(PlatformPageId.NAVIGATION, "create"),
    NAVIGATION_UPDATE(PlatformPageId.NAVIGATION, "update"),
    NAVIGATION_DELETE(PlatformPageId.NAVIGATION, "delete"),
    FILES_ALL_UPDATE_QUOTA(PlatformPageId.FILES_ALL, "update-quota"),
    FILE_DELETE(PlatformPageId.FILES_ALL, "delete"),
    FILE_PURGE(PlatformPageId.FILES_ALL, "purge"),
    FILE_PREVIEW(PlatformPageId.FILES_ALL, "preview"),
    FILE_DOWNLOAD(PlatformPageId.FILES_ALL, "download"),
    FILE_RESTORE(PlatformPageId.FILES_ALL, "restore"),
    NOTIFICATION_CREATE(PlatformPageId.NOTIFICATIONS, "create"),
    TEMPLATE_CREATE(PlatformPageId.PRINT_TEMPLATES, "create"),
    TEMPLATE_UPDATE(PlatformPageId.PRINT_TEMPLATES, "update"),
    TEMPLATE_PUBLISH(PlatformPageId.PRINT_TEMPLATES, "publish"),
    TEMPLATE_USE(PlatformPageId.PRINT_TEMPLATES, "use");

    private final PlatformPageId page;
    private final String action;
    private final Scope scope;

    public enum Scope {
        GLOBAL,
        SCOPED
    }

    PlatformOperation(PlatformPageId page, String action) {
        this(page, action, Scope.GLOBAL);
    }

    PlatformOperation(PlatformPageId page, String action, Scope scope) {
        this.page = page;
        this.action = action;
        this.scope = scope;
    }

    public PlatformPageId page() {
        return page;
    }

    public String action() {
        return action;
    }

    public Scope scope() {
        return scope;
    }

    public String permission() {
        return "platform:" + page.id() + ":" + action;
    }
}
