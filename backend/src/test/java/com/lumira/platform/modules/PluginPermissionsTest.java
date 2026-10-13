package com.lumira.platform.modules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PluginPermissionsTest {
    @AfterEach
    void clearCatalog() {
        PluginPermissions.replaceAll(Map.of());
    }

    @Test
    void replacingCatalogRemovesPermissionsFromPreviouslyInstalledPlugins() {
        var retiredPluginGroup =
                new PluginPermissions.CatalogGroup(
                        "retired-plugin:admin",
                        "Administration",
                        List.of(new PluginPermissions.Page("/dashboard/retired", "Retired page")),
                        List.of(
                                new PluginPermissions.Permission(
                                        "platform:retired-plugin:read", "Read")));
        var activePluginGroup =
                new PluginPermissions.CatalogGroup(
                        "active-plugin:admin",
                        "Administration",
                        List.of(new PluginPermissions.Page("/dashboard/active", "Active page")),
                        List.of(
                                new PluginPermissions.Permission(
                                        "platform:active-plugin:read",
                                        "Read",
                                        List.of("platform:users:update"))));

        PluginPermissions.replaceAll(Map.of("retired-plugin", List.of(retiredPluginGroup)));
        assertTrue(PluginPermissions.values().contains("platform:retired-plugin:read"));

        PluginPermissions.replaceAll(Map.of("active-plugin", List.of(activePluginGroup)));

        assertEquals(java.util.Set.of("platform:active-plugin:read"), PluginPermissions.values());
        assertEquals(List.of(activePluginGroup), PluginPermissions.catalogGroups());
    }

    @Test
    void pluginQualifiedGroupIdsRemainUniqueWhenPluginsReuseLocalGroupIds() {
        var feedbackManagement =
                new PluginPermissions.CatalogGroup(
                        "feedback:management", "Feedback", List.of(), List.of());
        var paymentManagement =
                new PluginPermissions.CatalogGroup(
                        "payment-core:management", "Payments", List.of(), List.of());

        PluginPermissions.replaceAll(
                Map.of(
                        "feedback", List.of(feedbackManagement),
                        "payment-core", List.of(paymentManagement)));

        assertEquals(
                List.of("feedback:management", "payment-core:management"),
                PluginPermissions.catalogGroups().stream()
                        .map(PluginPermissions.CatalogGroup::id)
                        .toList());
    }

    @Test
    void pageCatalogRetainsExplicitAccessAndOperationScopes() {
        var page =
                new PluginPermissions.Page(
                        "mfa-management",
                        "/dashboard/mfa-management",
                        "MFA management",
                        "user-management",
                        120,
                        "platform:mfa:management-access",
                        List.of("platform:mfa:management-read", "platform:mfa:management-reset"),
                        "global",
                        Map.of(
                                "platform:mfa:management-read", "global",
                                "platform:mfa:management-reset", "scoped"));

        assertEquals("mfa-management", page.id());
        assertEquals("platform:mfa:management-access", page.accessPermission());
        assertEquals(
                List.of("platform:mfa:management-read", "platform:mfa:management-reset"),
                page.operationPermissions());
        assertEquals("global", page.accessScope());
        assertEquals("scoped", page.operationScopes().get("platform:mfa:management-reset"));
    }

    @Test
    void pageCatalogRejectsScopesForUndeclaredOperations() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PluginPermissions.Page(
                                "orders",
                                "/dashboard/orders",
                                "Orders",
                                "payments",
                                20,
                                "platform:shop:orders-access",
                                List.of("platform:shop:orders-read"),
                                "global",
                                Map.of("platform:shop:orders-refund", "global")));
    }
}
