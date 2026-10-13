package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.platform.modules.PluginPermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PluginRuntimeCatalogTest {
    @AfterEach
    void clearCatalog() {
        PluginPermissions.replaceAll(Map.of());
    }

    @Test
    void routeSnapshotRetainsPermissionAndNavigationMetadata() {
        String accessA = "platform:catalog-fixture:a-access";
        String readA = "platform:catalog-fixture:a-read";
        String accessB = "platform:catalog-fixture:b-access";
        var permissions = List.of(accessA, readA, accessB);
        var permissionGroup =
                new PluginManifest.PermissionGroup(
                        "management",
                        "Catalog fixture",
                        List.of(
                                new PluginManifest.Permission(accessA, "Access A"),
                                new PluginManifest.Permission(readA, "Read A"),
                                new PluginManifest.Permission(accessB, "Access B")));
        var manifest =
                new PluginManifest(
                        1,
                        "catalog-fixture",
                        "Catalog fixture",
                        "1.0.0",
                        "1",
                        "com.example.CatalogFixturePlugin",
                        "backend/plugin.jar",
                        "frontend/plugin.js",
                        List.of(),
                        permissions,
                        List.of(permissionGroup),
                        List.of(
                                new PluginManifest.Route(
                                        "catalog-a",
                                        "catalogA",
                                        accessA,
                                        Map.of("read", readA),
                                        List.of("read"),
                                        "operations",
                                        10,
                                        true),
                                new PluginManifest.Route(
                                        "catalog-b",
                                        "catalogB",
                                        accessB,
                                        Map.of(),
                                        List.of(),
                                        "administration",
                                        20,
                                        false)),
                        List.of(),
                        Map.of(),
                        List.of());

        var catalog = PluginRuntime.catalogForManifests(List.of(manifest));
        var group = catalog.get("catalog-fixture").getFirst();

        assertEquals(
                List.of(
                        new PluginPermissions.Page(
                                "catalog-a",
                                "/dashboard/catalog-a",
                                "catalogA",
                                "operations",
                                10,
                                accessA,
                                List.of(readA),
                                "global",
                                Map.of(readA, "scoped")),
                        new PluginPermissions.Page(
                                "catalog-b",
                                "/dashboard/catalog-b",
                                "catalogB",
                                "administration",
                                20,
                                accessB,
                                List.of(),
                                "scoped",
                                Map.of())),
                group.pages());
    }

    @Test
    void explicitAllOfRequiresPageAndOperationWhileLegacyAuthoritiesStayAnyOf() {
        var allOf =
                new PluginManifest.Rule(
                        "POST",
                        "/api/plugins/catalog-fixture/orders/refund",
                        List.of(),
                        true,
                        false,
                        false,
                        false,
                        false,
                        List.of(
                                "platform:catalog-fixture:orders-access",
                                "platform:catalog-fixture:orders-refund"));
        assertFalse(
                PluginRuntime.authorityRuleMatches(
                        allOf, java.util.Set.of("platform:catalog-fixture:orders-access")));
        assertFalse(
                PluginRuntime.authorityRuleMatches(
                        allOf, java.util.Set.of("platform:catalog-fixture:orders-refund")));
        assertTrue(
                PluginRuntime.authorityRuleMatches(
                        allOf,
                        java.util.Set.of(
                                "platform:catalog-fixture:orders-access",
                                "platform:catalog-fixture:orders-refund")));

        var legacyAny =
                new PluginManifest.Rule(
                        "GET",
                        "/api/plugins/catalog-fixture/legacy",
                        List.of("platform:catalog-fixture:read", "platform:catalog-fixture:manage"),
                        false,
                        false);
        assertTrue(
                PluginRuntime.authorityRuleMatches(
                        legacyAny, java.util.Set.of("platform:catalog-fixture:manage")));
    }
}
