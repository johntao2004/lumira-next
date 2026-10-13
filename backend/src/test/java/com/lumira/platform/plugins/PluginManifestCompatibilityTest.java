package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PluginManifestCompatibilityTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void oldApiRulesKeepAuthenticatedOnlyDisabledWhenFieldIsAbsent() {
        var rule =
                mapper.readValue(
                        """
                {"method":"GET","path":"/api/legacy","authorities":["platform:legacy:read"],
                "global":false,"publicAccess":false}
                """,
                        PluginManifest.Rule.class);
        assertFalse(rule.authenticatedOnly());
        assertEquals(List.of(), rule.allAuthorities());
        assertEquals(List.of(), rule.globalAuthorities());
    }

    @Test
    void explicitRouteOperationsAndApiAllOfAreAcceptedAndPreserved() {
        var route =
                mapper.readValue(
                        """
                        {"id":"orders","label":"orders","permission":"platform:shop:orders-access",
                         "operations":{"read":"platform:shop:orders-read","refund":"platform:shop:orders-refund"},
                         "scopedOperations":["refund"],
                         "group":"payments","order":10,"global":true}
                        """,
                        PluginManifest.Route.class);
        var rule =
                mapper.readValue(
                        """
                        {"method":"POST","path":"/api/plugins/shop/orders/*/refund",
                         "authorities":[],"allAuthorities":["platform:shop:orders-access","platform:shop:orders-refund"],
                         "global":true,"publicAccess":false}
                        """,
                        PluginManifest.Rule.class);
        var manifest = manifest("shop", rule, route);

        assertEquals("platform:shop:orders-access", manifest.routes().getFirst().permission());
        assertEquals(
                Map.of(
                        "read", "platform:shop:orders-read",
                        "refund", "platform:shop:orders-refund"),
                manifest.routes().getFirst().operations());
        assertEquals(List.of("refund"), manifest.routes().getFirst().scopedOperations());
        assertEquals(
                List.of("platform:shop:orders-access", "platform:shop:orders-refund"),
                manifest.apiRules().getFirst().allAuthorities());
    }

    @Test
    void formatV2RequiresOwnPagePermissionsAndUniqueActionMappings() {
        var orders =
                new PluginManifest.Route(
                        "orders",
                        "orders",
                        "platform:shop:orders-access",
                        Map.of("read", "platform:shop:orders-read"),
                        "payments",
                        10,
                        true);
        var settings =
                new PluginManifest.Route(
                        "settings",
                        "settings",
                        "platform:shop:settings-access",
                        Map.of("update", "platform:shop:settings-update"),
                        "payments",
                        20,
                        true);
        var readOrders =
                new PluginManifest.Rule(
                        "GET",
                        "/api/shop/orders",
                        List.of(),
                        false,
                        false,
                        false,
                        false,
                        false,
                        List.of("platform:shop:orders-access", "platform:shop:orders-read"));
        var permissions =
                List.of(
                        "platform:shop:orders-access",
                        "platform:shop:orders-read",
                        "platform:shop:settings-access",
                        "platform:shop:settings-update");

        var valid = manifest(2, "shop", permissions, List.of(orders, settings), readOrders);
        assertEquals(2, valid.formatVersion());

        var legacyAnyOfPageGate =
                new PluginManifest.Rule(
                        "GET",
                        "/api/shop/orders",
                        List.of("platform:shop:orders-access", "platform:shop:orders-read"),
                        false,
                        false);
        var missingAction =
                new PluginManifest.Rule(
                        "GET",
                        "/api/shop/orders",
                        List.of(),
                        false,
                        false,
                        false,
                        false,
                        false,
                        List.of("platform:shop:orders-access"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifest(
                                2,
                                "shop",
                                permissions,
                                List.of(orders, settings),
                                legacyAnyOfPageGate));
        assertThrows(
                IllegalArgumentException.class,
                () -> manifest(2, "shop", permissions, List.of(orders, settings), missingAction));

        var actionAnyOfWithAccessAllOf =
                new PluginManifest.Rule(
                        "GET",
                        "/api/shop/orders",
                        List.of("platform:shop:orders-read"),
                        false,
                        false,
                        false,
                        false,
                        false,
                        List.of("platform:shop:orders-access"));
        assertEquals(
                2,
                manifest(
                                2,
                                "shop",
                                permissions,
                                List.of(orders, settings),
                                actionAnyOfWithAccessAllOf)
                        .formatVersion());

        var pageWithoutOperations =
                new PluginManifest.Route(
                        "orders", "orders", "platform:shop:orders-access", "payments", 10, true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifest(
                                2,
                                "shop",
                                permissions,
                                List.of(pageWithoutOperations, settings),
                                readOrders));

        var repeatedPageAccess =
                new PluginManifest.Route(
                        "settings",
                        "settings",
                        "platform:shop:orders-access",
                        Map.of("update", "platform:shop:settings-update"),
                        "payments",
                        20,
                        true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifest(
                                2,
                                "shop",
                                permissions,
                                List.of(orders, repeatedPageAccess),
                                readOrders));

        var reusedOperation =
                new PluginManifest.Route(
                        "settings",
                        "settings",
                        "platform:shop:settings-access",
                        Map.of("update", "platform:shop:orders-read"),
                        "payments",
                        20,
                        true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifest(
                                2,
                                "shop",
                                permissions,
                                List.of(orders, reusedOperation),
                                readOrders));

        var crossLinkedAction =
                new PluginManifest.Route(
                        "orders",
                        "orders",
                        "platform:shop:orders-access",
                        Map.of("read", "platform:shop:settings-access"),
                        "payments",
                        10,
                        true);
        assertThrows(
                IllegalArgumentException.class,
                () -> manifest(2, "shop", permissions, List.of(crossLinkedAction, settings)));

        var dependencyPage =
                new PluginManifest.Route(
                        "orders",
                        "orders",
                        "platform:payment-core:orders-access",
                        Map.of("read", "platform:shop:orders-read"),
                        "payments",
                        10,
                        true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifestWithDependencies(
                                2,
                                "shop",
                                permissions,
                                List.of(dependencyPage),
                                List.of(new PluginManifest.Dependency("payment-core", "1.0.0")),
                                readOrders));
    }

    @Test
    void unknownOrUnmappedAllOfAuthoritiesFailClosed() {
        var route =
                new PluginManifest.Route(
                        "orders",
                        "orders",
                        "platform:shop:orders-access",
                        Map.of("read", "platform:shop:orders-read"),
                        "payments",
                        10,
                        true);
        var unknownRule =
                new PluginManifest.Rule(
                        "GET",
                        "/api/plugins/shop/orders",
                        List.of(),
                        true,
                        false,
                        false,
                        false,
                        false,
                        List.of("platform:shop:orders-access", "platform:shop:unknown"));
        var unmappedRule =
                new PluginManifest.Rule(
                        "GET",
                        "/api/plugins/shop/orders",
                        List.of(),
                        true,
                        false,
                        false,
                        false,
                        false,
                        List.of("platform:shop:orders-access", "platform:shop:orders-update"));

        assertThrows(IllegalArgumentException.class, () -> manifest("shop", unknownRule, route));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifest(
                                "shop",
                                List.of(
                                        "platform:shop:orders-access",
                                        "platform:shop:orders-read",
                                        "platform:shop:orders-update"),
                                List.of(route),
                                unmappedRule));
    }

    @Test
    void mixedScopeGlobalGateMustBeOwnedAndMappedByTheSameRoute() throws Exception {
        var route =
                mapper.readValue(
                        """
                        {"id":"mfa-management","label":"mfaManagement",
                         "permission":"platform:mfa:management-access",
                         "operations":{"read":"platform:mfa:management-read","reset":"platform:mfa:management-reset"},
                         "scopedOperations":["reset"],"group":"administration","order":10,"global":true}
                        """,
                        PluginManifest.Route.class);
        var mixedScopeRule =
                mapper.readValue(
                        """
                        {"method":"POST","path":"/api/mfa/manage/*/reset","authorities":[],
                         "allAuthorities":["platform:mfa:management-access","platform:mfa:management-reset"],
                         "globalAuthorities":["platform:mfa:management-access"],
                         "global":false,"publicAccess":false}
                        """,
                        PluginManifest.Rule.class);
        var accepted = manifest("mfa", mixedScopeRule, route);
        assertEquals(
                List.of("platform:mfa:management-access"),
                accepted.apiRules().getFirst().globalAuthorities());

        var unknownGlobalGate =
                mapper.readValue(
                        """
                        {"method":"POST","path":"/api/mfa/manage/*/reset","authorities":[],
                         "allAuthorities":["platform:mfa:management-access","platform:mfa:management-reset"],
                         "globalAuthorities":["platform:mfa:other-access"],
                         "global":false,"publicAccess":false}
                        """,
                        PluginManifest.Rule.class);
        assertThrows(
                IllegalArgumentException.class, () -> manifest("mfa", unknownGlobalGate, route));

        var unmappedGlobalGate =
                mapper.readValue(
                        """
                        {"method":"POST","path":"/api/mfa/manage/*/reset",
                         "authorities":["platform:mfa:unused"],
                         "allAuthorities":["platform:mfa:management-access","platform:mfa:management-reset"],
                         "globalAuthorities":["platform:mfa:unused"],
                         "global":false,"publicAccess":false}
                        """,
                        PluginManifest.Rule.class);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifest(
                                "mfa",
                                List.of(
                                        "platform:mfa:management-access",
                                        "platform:mfa:management-read",
                                        "platform:mfa:management-reset",
                                        "platform:mfa:unused"),
                                List.of(route),
                                unmappedGlobalGate));
    }

    @Test
    void globalGateMustAlsoBeAnExplicitlyRequiredAuthority() throws Exception {
        var route =
                mapper.readValue(
                        """
                        {"id":"orders","label":"orders","permission":"platform:shop:orders-access",
                         "operations":{"read":"platform:shop:orders-read","refund":"platform:shop:orders-refund"},
                         "group":"payments","order":10,"global":true}
                        """,
                        PluginManifest.Route.class);
        var unrequiredGate =
                mapper.readValue(
                        """
                        {"method":"POST","path":"/api/plugins/shop/orders/*/refund","authorities":["platform:shop:orders-read"],
                         "allAuthorities":["platform:shop:orders-access"],
                         "globalAuthorities":["platform:shop:orders-refund"],
                         "global":false,"publicAccess":false}
                        """,
                        PluginManifest.Rule.class);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifest(
                                "shop",
                                List.of(
                                        "platform:shop:orders-access",
                                        "platform:shop:orders-read",
                                        "platform:shop:orders-refund"),
                                List.of(route),
                                unrequiredGate));
    }

    @Test
    void explicitRouteOperationCannotComeFromDependencyOrRemainUndeclared() {
        var dependencyActionRoute =
                new PluginManifest.Route(
                        "orders",
                        "orders",
                        "platform:shop:orders-access",
                        Map.of("read", "platform:payment-core:orders-read"),
                        "payments",
                        10,
                        true);
        var ungroupedActionRoute =
                new PluginManifest.Route(
                        "orders",
                        "orders",
                        "platform:shop:orders-access",
                        Map.of("read", "platform:shop:orders-read"),
                        "payments",
                        10,
                        true);
        var authenticatedRule =
                new PluginManifest.Rule(
                        "GET", "/api/plugins/shop/orders", List.of(), false, false, true);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifest(
                                "shop",
                                List.of("platform:shop:orders-access"),
                                List.of(dependencyActionRoute),
                                authenticatedRule));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifest(
                                "shop",
                                List.of("platform:shop:orders-access"),
                                List.of(ungroupedActionRoute),
                                authenticatedRule));
    }

    @Test
    void scopedOperationIdsMustNameAnExplicitRouteOperation() {
        var route =
                new PluginManifest.Route(
                        "orders",
                        "orders",
                        "platform:shop:orders-access",
                        Map.of("read", "platform:shop:orders-read"),
                        List.of("refund"),
                        "payments",
                        10,
                        true);
        var rule =
                new PluginManifest.Rule("GET", "/api/plugins/shop/orders", List.of(), true, false);

        assertThrows(IllegalArgumentException.class, () -> manifest("shop", rule, route));
    }

    @Test
    void explicitlyAuthenticatedApiRuleRetainsItsValue() {
        var rule =
                mapper.readValue(
                        """
                {"method":"GET","path":"/api/new","authorities":[],
                "global":false,"publicAccess":false,"authenticatedOnly":true}
                """,
                        PluginManifest.Rule.class);
        assertTrue(rule.authenticatedOnly());
    }

    @Test
    void permissionRequiresMetadataIsOptionalAndPreserved() {
        var current =
                mapper.readValue(
                        """
                {"code":"platform:mfa:reset","label":"Reset","requires":["platform:users:update"]}
                """,
                        PluginManifest.Permission.class);
        var legacy =
                mapper.readValue(
                        """
                {"code":"platform:legacy-plugin:read","label":"Read"}
                """,
                        PluginManifest.Permission.class);

        assertEquals(List.of("platform:users:update"), current.requires());
        assertEquals(List.of(), legacy.requires());
    }

    @Test
    void previewReadDeclarationMustBeExplicitAndSafe() {
        var oldRule =
                mapper.readValue(
                        """
                {"method":"GET","path":"/api/legacy","authorities":["platform:legacy:read"],"global":false,"publicAccess":false}
                """,
                        PluginManifest.Rule.class);
        var declared =
                mapper.readValue(
                        """
                {"method":"GET","path":"/api/feedback","authorities":["platform:feedback:read"],"global":false,"publicAccess":false,"previewReadOnly":true}
                """,
                        PluginManifest.Rule.class);
        assertFalse(oldRule.previewReadOnly());
        assertTrue(declared.previewReadOnly());
    }

    @Test
    void signedCallbacksAreLimitedToExactPostRoutesInTheirOwningPluginNamespace() {
        var callback =
                new PluginManifest.Rule(
                        "POST",
                        "/api/plugins/payment-core/callbacks/wechat-pay",
                        List.of(),
                        false,
                        true,
                        false,
                        false,
                        true);
        assertTrue(manifest("payment-core", callback).apiRules().getFirst().signedCallback());

        assertInvalidCallback(
                new PluginManifest.Rule(
                        "POST", "/api/platform/users", List.of(), false, true, false, false, true));
        assertInvalidCallback(
                new PluginManifest.Rule(
                        "POST",
                        "/api/plugins/alipay-pay/callbacks/notify",
                        List.of(),
                        false,
                        true,
                        false,
                        false,
                        true));
        assertInvalidCallback(
                new PluginManifest.Rule(
                        "POST",
                        "/api/plugins/payment-core/callbacks/{provider}",
                        List.of(),
                        false,
                        true,
                        false,
                        false,
                        true));
        assertInvalidCallback(
                new PluginManifest.Rule(
                        "GET",
                        "/api/plugins/payment-core/callbacks/alipay-pay",
                        List.of(),
                        false,
                        true,
                        false,
                        false,
                        true));
    }

    @Test
    void routesCanUsePermissionsFromDeclaredDependenciesAndNewCatalogs() {
        var route =
                new PluginManifest.Route(
                        "wechat-pay-orders",
                        "wechatPayOrders",
                        "platform:payment-core:read",
                        "payments",
                        20,
                        true);
        var manifest =
                new PluginManifest(
                        1,
                        "wechat-pay",
                        "WeChat Pay",
                        "1.0.1",
                        "1",
                        "com.example.WechatPayPlugin",
                        "backend/plugin.jar",
                        "frontend/plugin.js",
                        List.of(new PluginManifest.Dependency("payment-core", "1.0.1")),
                        List.of(),
                        List.of(route),
                        List.of(),
                        Map.of(),
                        List.of());

        assertEquals("payments", manifest.routes().getFirst().group());
        assertEquals("platform:payment-core:read", manifest.routes().getFirst().permission());
    }

    @Test
    void routeCannotUsePermissionFromAnUndeclaredDependency() {
        var route =
                new PluginManifest.Route(
                        "wechat-pay-orders",
                        "wechatPayOrders",
                        "platform:payment-core:read",
                        "payments",
                        20,
                        true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PluginManifest(
                                1,
                                "wechat-pay",
                                "WeChat Pay",
                                "1.0.1",
                                "1",
                                "com.example.WechatPayPlugin",
                                "backend/plugin.jar",
                                "frontend/plugin.js",
                                List.of(),
                                List.of(),
                                List.of(route),
                                List.of(),
                                Map.of(),
                                List.of()));
    }

    @Test
    void legacyPermissionListGetsOneFallbackGroup() {
        var manifest =
                new PluginManifest(
                        1,
                        "legacy-plugin",
                        "Legacy plugin",
                        "1.0.0",
                        "1",
                        "com.example.LegacyPlugin",
                        "backend/plugin.jar",
                        "frontend/plugin.js",
                        List.of(),
                        List.of("platform:legacy-plugin:read", "platform:legacy-plugin:write"),
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of());

        assertEquals(1, manifest.permissionGroups().size());
        assertEquals("legacy-plugin", manifest.permissionGroups().getFirst().id());
        assertEquals("Legacy plugin", manifest.permissionGroups().getFirst().title());
        assertEquals(
                List.of("read", "write"),
                manifest.permissionGroups().getFirst().permissions().stream()
                        .map(PluginManifest.Permission::label)
                        .toList());
    }

    @Test
    void platformResourceIdsCannotBeClaimedByPluginsEvenWithoutPermissions() {
        for (String reservedId : List.of("users", "roles", "settings", "modules"))
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new PluginManifest(
                                    1,
                                    reservedId,
                                    "Spoofed platform resource",
                                    "1.0.0",
                                    "1",
                                    "com.example.SpoofedPlugin",
                                    "backend/plugin.jar",
                                    "frontend/plugin.js",
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    Map.of(),
                                    List.of()));
    }

    @Test
    void declaredPermissionGroupMustCoverOnlyItsOwnManifestAuthorities() {
        var group =
                new PluginManifest.PermissionGroup(
                        "management",
                        "Management",
                        List.of(
                                new PluginManifest.Permission(
                                        "platform:test-plugin:read", "Read")));
        var manifest =
                new PluginManifest(
                        1,
                        "test-plugin",
                        "Test plugin",
                        "1.0.0",
                        "1",
                        "com.example.TestPlugin",
                        "backend/plugin.jar",
                        "frontend/plugin.js",
                        List.of(),
                        List.of("platform:test-plugin:read"),
                        List.of(group),
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of());

        assertEquals("management", manifest.permissionGroups().getFirst().id());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PluginManifest(
                                1,
                                "test-plugin",
                                "Test plugin",
                                "1.0.0",
                                "1",
                                "com.example.TestPlugin",
                                "backend/plugin.jar",
                                "frontend/plugin.js",
                                List.of(),
                                List.of("platform:test-plugin:read"),
                                List.of(
                                        new PluginManifest.PermissionGroup(
                                                "management",
                                                "Management",
                                                List.of(
                                                        new PluginManifest.Permission(
                                                                "platform:roles:write", "Write")))),
                                List.of(),
                                List.of(),
                                Map.of(),
                                List.of()));
    }

    private void assertInvalidCallback(PluginManifest.Rule rule) {
        assertThrows(IllegalArgumentException.class, () -> manifest("payment-core", rule));
    }

    private PluginManifest manifest(String id, PluginManifest.Rule rule) {
        return manifest(id, rule, null);
    }

    private PluginManifest manifest(
            String id, PluginManifest.Rule rule, PluginManifest.Route route) {
        var permissions =
                route == null
                        ? List.<String>of()
                        : java.util.stream.Stream.concat(
                                        java.util.stream.Stream.of(route.permission()),
                                        route.operations().values().stream())
                                .toList();
        return manifest(id, permissions, route == null ? List.of() : List.of(route), rule);
    }

    private PluginManifest manifest(
            String id,
            List<String> permissions,
            List<PluginManifest.Route> routes,
            PluginManifest.Rule rule) {
        return manifest(1, id, permissions, routes, rule);
    }

    private PluginManifest manifest(
            int formatVersion,
            String id,
            List<String> permissions,
            List<PluginManifest.Route> routes,
            PluginManifest.Rule... rules) {
        return manifestWithDependencies(formatVersion, id, permissions, routes, List.of(), rules);
    }

    private PluginManifest manifestWithDependencies(
            int formatVersion,
            String id,
            List<String> permissions,
            List<PluginManifest.Route> routes,
            List<PluginManifest.Dependency> dependencies,
            PluginManifest.Rule... rules) {
        return new PluginManifest(
                formatVersion,
                id,
                "Payment",
                "1.0.0",
                "1",
                "com.example.PaymentPlugin",
                "backend/plugin.jar",
                "frontend/plugin.js",
                dependencies,
                permissions,
                routes,
                List.of(rules),
                Map.of(),
                List.of());
    }
}
