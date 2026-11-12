package com.lumira.platform.notifications;

/** Canonical validation for optional in-app notification links. */
final class NotificationHref {
    private NotificationHref() {}

    static String normalize(String value) {
        if (value != null && value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid notification link");
        if (value == null || value.isBlank()) return null;

        String href = value.strip();
        if (!isSafe(href)) throw new IllegalArgumentException("Invalid notification link");
        return href;
    }

    static String safeOrNull(String value) {
        return isSafe(value) ? value : null;
    }

    private static boolean isSafe(String value) {
        if (value == null) return true;
        if (value.length() > 512
                || !value.startsWith("/")
                || value.startsWith("//")
                || value.codePoints().anyMatch(Character::isISOControl)) return false;
        int pathEnd = value.length();
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        if (query >= 0) pathEnd = Math.min(pathEnd, query);
        if (fragment >= 0) pathEnd = Math.min(pathEnd, fragment);
        // WHATWG special URLs interpret backslashes in the path as authority separators.
        return value.substring(0, pathEnd).indexOf('\\') < 0;
    }
}
