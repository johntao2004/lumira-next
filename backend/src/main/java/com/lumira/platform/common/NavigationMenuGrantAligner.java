package com.lumira.platform.common;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Built-in menu rows keep the page access grant and the same global/scoped rule as the catalog.
 * Fresh databases still contain a few older operation keys; startup rewrites those built-in rows
 * without editing the published B52 checksum. File retention is a real page, so a missing built-in
 * row is inserted here rather than left as a grant with no menu entry.
 */
public final class NavigationMenuGrantAligner {
    public static final UUID FILE_RETENTION_MENU_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000191");

    public record Desired(String sectionKey, String permissionKey, boolean globalOnly) {}

    private NavigationMenuGrantAligner() {}

    public static List<Desired> hostPages() {
        return Arrays.stream(PlatformPageId.values())
                .map(
                        page ->
                                new Desired(
                                        page.id(),
                                        page.accessPermission(),
                                        page.scope() == PlatformOperation.Scope.GLOBAL))
                .toList();
    }

    public static boolean needsUpdate(
            String permissionKey,
            boolean globalOnly,
            String desiredPermission,
            boolean desiredGlobal) {
        return (permissionKey == null
                        ? desiredPermission != null
                        : !permissionKey.equals(desiredPermission))
                || globalOnly != desiredGlobal;
    }

    public static void alignHost(JdbcClient jdbc) {
        if (jdbc == null || !menuTablePresent(jdbc)) return;
        for (Desired desired : hostPages()) alignBuiltIn(jdbc, desired, null);
        ensureFileRetentionPage(jdbc);
    }

    public static void alignBuiltIn(JdbcClient jdbc, Desired desired, String moduleId) {
        if (jdbc == null
                || desired == null
                || desired.sectionKey() == null
                || desired.sectionKey().isBlank()) return;
        jdbc.sql(
                        """
                        UPDATE platform_navigation_menu
                           SET permission_key=:permission,
                               global_only=:globalOnly,
                               version=version+1,
                               updated_at=now()
                         WHERE section_key=:section
                           AND menu_type='PAGE'
                           AND deleted=false
                           AND built_in=true
                           AND (CAST(:module AS varchar) IS NULL OR module_id=:module)
                           AND (permission_key IS DISTINCT FROM :permission
                                OR global_only IS DISTINCT FROM :globalOnly)
                        """)
                .param("permission", desired.permissionKey())
                .param("globalOnly", desired.globalOnly())
                .param("section", desired.sectionKey())
                .param("module", moduleId)
                .update();
    }

    public static void ensureFileRetentionPage(JdbcClient jdbc) {
        if (jdbc == null || !menuTablePresent(jdbc)) return;
        jdbc.sql(
                        """
                        INSERT INTO platform_navigation_menu (
                            id, parent_id, menu_code, label_key, label_default, menu_type,
                            section_key, path, permission_key, global_only, sort_order,
                            enabled, built_in, deleted, version)
                        SELECT :id, parent.id, 'file-retention', 'fileRetention', '文件保留', 'PAGE',
                               'file-retention', '/dashboard/file-retention',
                               'platform:file-retention:access', true, 195, true, true, false, 1
                          FROM platform_navigation_menu parent
                         WHERE parent.menu_code='administration'
                           AND parent.menu_type='CATALOG'
                           AND parent.deleted=false
                           AND NOT EXISTS (
                               SELECT 1 FROM platform_navigation_menu existing
                                WHERE existing.deleted=false
                                  AND (existing.section_key='file-retention'
                                       OR existing.menu_code='file-retention'
                                       OR existing.id=:id))
                        """)
                .param("id", FILE_RETENTION_MENU_ID)
                .update();
    }

    private static boolean menuTablePresent(JdbcClient jdbc) {
        return Boolean.TRUE.equals(
                jdbc.sql("SELECT to_regclass('public.platform_navigation_menu') IS NOT NULL")
                        .query(Boolean.class)
                        .single());
    }
}
