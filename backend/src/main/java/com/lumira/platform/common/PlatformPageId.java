package com.lumira.platform.common;

/** Stable IDs for host dashboard pages that participate in platform authorization. */
public enum PlatformPageId {
    USERS("users", PlatformOperation.Scope.SCOPED),
    ONLINE_USERS("online-users"),
    ROLES("roles"),
    ORGANIZATIONS("organizations", PlatformOperation.Scope.SCOPED),
    AUDIT("audit"),
    MAIL_DELIVERIES("mail-deliveries"),
    SMS_DELIVERIES("sms-deliveries"),
    EVENT_OUTBOX("event-outbox"),
    MONITORING("monitoring"),
    API_DOCS("api-docs"),
    DICTIONARIES("dictionaries"),
    BRANDING("branding"),
    SMTP("smtp"),
    SMS("sms"),
    PROFILE_FIELDS("profile-fields"),
    AGREEMENTS("agreements"),
    WATERMARK("watermark"),
    MAINTENANCE("maintenance"),
    SECURITY("security"),
    AUTHENTICATION("authentication"),
    PASSKEY("passkey"),
    WECHAT("wechat"),
    CAPTCHA("captcha"),
    REGISTRATION("registration"),
    FILE_RETENTION("file-retention"),
    AI_CONSOLE("ai-console", PlatformOperation.Scope.SCOPED),
    AI_MODELS("ai-models"),
    MODULES("modules"),
    NAVIGATION("navigation"),
    FILES_ALL("files-all"),
    NOTIFICATIONS("notifications");

    private final String id;
    private final PlatformOperation.Scope scope;

    PlatformPageId(String id) {
        this(id, PlatformOperation.Scope.GLOBAL);
    }

    PlatformPageId(String id, PlatformOperation.Scope scope) {
        this.id = id;
        this.scope = scope;
    }

    public String id() {
        return id;
    }

    public String accessPermission() {
        return "platform:" + id + ":access";
    }

    public PlatformOperation.Scope scope() {
        return scope;
    }
}
