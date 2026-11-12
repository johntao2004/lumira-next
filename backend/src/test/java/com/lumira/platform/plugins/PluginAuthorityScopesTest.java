package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PluginAuthorityScopesTest {
    @Test
    void mixedAlternativesPreserveGlobalReadOrScopedAssignment() {
        var rule =
                new PluginManifest.Rule(
                        "GET",
                        "/api/platform/users/{id}/roles",
                        List.of("access-read", "assign"),
                        false,
                        false,
                        false,
                        true,
                        false,
                        List.of("page"),
                        List.of(),
                        Map.of("page", "scoped", "access-read", "global", "assign", "scoped"));
        List<String> codes = List.of("page", "access-read", "assign");
        for (int effectiveMask = 0; effectiveMask < 8; effectiveMask++) {
            Set<String> effective = subset(codes, effectiveMask);
            for (int globalMask = 0; globalMask < 8; globalMask++) {
                Set<String> global = subset(codes, globalMask);
                boolean expected =
                        effective.contains("page")
                                && (effective.contains("assign")
                                        || effective.contains("access-read")
                                                && global.contains("access-read"));
                assertEquals(
                        expected,
                        PluginRuntime.authorityRuleMatches(rule, effective, global),
                        effective + " / " + global);
            }
        }
    }

    @Test
    void crossPagePrintAlwaysRequiresBothPagesAndItsScopedAction() {
        var rule =
                new PluginManifest.Rule(
                        "POST",
                        "/api/platform/users/template-print",
                        List.of("print"),
                        false,
                        false,
                        false,
                        false,
                        false,
                        List.of("users", "templates"),
                        List.of(),
                        Map.of("users", "scoped", "templates", "global", "print", "scoped"));
        List<String> codes = List.of("users", "templates", "print");
        for (int effectiveMask = 0; effectiveMask < 8; effectiveMask++) {
            Set<String> effective = subset(codes, effectiveMask);
            for (int globalMask = 0; globalMask < 8; globalMask++) {
                Set<String> global = subset(codes, globalMask);
                assertEquals(
                        effective.containsAll(codes) && global.contains("templates"),
                        PluginRuntime.authorityRuleMatches(rule, effective, global));
            }
        }
    }

    @Test
    void crossPageDeclarationCannotOmitTheOtherActionsPageGate() {
        var pages =
                List.of(
                        new PluginManifest.Route(
                                "orders",
                                "Orders",
                                "platform:shop:orders-access",
                                Map.of("read", "platform:shop:orders-read"),
                                "overview",
                                1,
                                false),
                        new PluginManifest.Route(
                                "items",
                                "Items",
                                "platform:shop:items-access",
                                Map.of("read", "platform:shop:items-read"),
                                "overview",
                                2,
                                false));
        var rule =
                new PluginManifest.Rule(
                        "GET",
                        "/api/shop/search",
                        List.of("platform:shop:orders-read", "platform:shop:items-read"),
                        false,
                        false,
                        false,
                        true,
                        false,
                        List.of("platform:shop:orders-access", "platform:shop:items-access"),
                        List.of());
        assertDoesNotThrow(() -> manifest(pages, rule));
        var missing =
                new PluginManifest.Rule(
                        rule.method(),
                        rule.path(),
                        rule.authorities(),
                        false,
                        false,
                        false,
                        true,
                        false,
                        List.of("platform:shop:orders-access"),
                        List.of());
        assertThrows(IllegalArgumentException.class, () -> manifest(pages, missing));
        var invalidScope =
                new PluginManifest.Rule(
                        rule.method(),
                        rule.path(),
                        rule.authorities(),
                        false,
                        false,
                        false,
                        true,
                        false,
                        rule.allAuthorities(),
                        List.of(),
                        Map.of("platform:shop:orders-access", "owner"));
        assertThrows(IllegalArgumentException.class, () -> manifest(pages, invalidScope));
        var unusedScope =
                new PluginManifest.Rule(
                        rule.method(),
                        rule.path(),
                        rule.authorities(),
                        false,
                        false,
                        false,
                        true,
                        false,
                        rule.allAuthorities(),
                        List.of(),
                        Map.of("platform:shop:unused", "global"));
        assertThrows(IllegalArgumentException.class, () -> manifest(pages, unusedScope));
    }

    @Test
    void publishedConstructorRetainsLegacyAnyGlobalGate() {
        var rule =
                new PluginManifest.Rule(
                        "GET",
                        "/api/shop",
                        List.of("read", "update"),
                        false,
                        false,
                        false,
                        true,
                        false,
                        List.of("page"),
                        List.of("read", "update"));
        assertTrue(rule.authorityScopes().isEmpty());
        assertTrue(
                PluginRuntime.authorityRuleMatches(rule, Set.of("page", "read"), Set.of("read")));
        assertFalse(PluginRuntime.authorityRuleMatches(rule, Set.of("page", "read"), Set.of()));
    }

    @Test
    void explicitScopesRequireTheNewSdkCapabilityInsteadOfSilentlyDowngrading() {
        var page =
                new PluginManifest.Route(
                        "orders",
                        "Orders",
                        "platform:shop:orders-access",
                        Map.of("read", "platform:shop:orders-read"),
                        "overview",
                        1,
                        false);
        var rule =
                new PluginManifest.Rule(
                        "GET",
                        "/api/shop/orders",
                        List.of("platform:shop:orders-read"),
                        false,
                        false,
                        false,
                        true,
                        false,
                        List.of("platform:shop:orders-access"),
                        List.of(),
                        Map.of("platform:shop:orders-read", "global"));
        var supported = manifest(List.of(page), rule);
        assertEquals("2", supported.sdkVersion());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PluginManifest(
                                supported.formatVersion(),
                                supported.id(),
                                supported.name(),
                                supported.version(),
                                "1",
                                supported.entryClass(),
                                supported.backend(),
                                supported.frontend(),
                                supported.dependencies(),
                                supported.permissions(),
                                supported.routes(),
                                supported.apiRules(),
                                supported.files(),
                                supported.dataSets()));
    }

    private static Set<String> subset(List<String> values, int mask) {
        var selected = new HashSet<String>();
        for (int i = 0; i < values.size(); i++)
            if ((mask & (1 << i)) != 0) selected.add(values.get(i));
        return Set.copyOf(selected);
    }

    private static PluginManifest manifest(
            List<PluginManifest.Route> routes, PluginManifest.Rule rule) {
        return new PluginManifest(
                1,
                "shop",
                "Shop",
                "1.0.0",
                rule.authorityScopes().isEmpty() ? "1" : "2",
                "example.ShopPlugin",
                "backend/plugin.jar",
                "frontend/plugin.js",
                List.of(),
                List.of(
                        "platform:shop:orders-access",
                        "platform:shop:orders-read",
                        "platform:shop:items-access",
                        "platform:shop:items-read"),
                routes,
                List.of(rule),
                Map.of(),
                List.of());
    }
}
