package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Keeps the explicit host route policy aligned with every controller and its HTTP test matrix. */
class RoutePermissionCoverageTest {
    private static final Path SECURITY_SOURCE =
            Path.of("src/main/java/com/lumira/platform/identity/SecurityConfiguration.java");
    private static final String MATRIX_RESOURCE = "http-security/host-http-cases.json";
    private static final String TEST_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID MATRIX_ACTOR_ID =
            UUID.fromString("f9b061a0-5423-4af7-ad1a-ff8b3dc99274");
    private static final AntPathMatcher ANT = new AntPathMatcher();
    private static final Pattern SECURITY_RULE =
            Pattern.compile(
                    "requestMatchers\\(\\s*"
                            + "(?:org\\.springframework\\.http\\.HttpMethod\\.(\\w+)\\s*,\\s*)?"
                            + "((?:\"[^\"]+\"\\s*,?\\s*)+)\\)\\s*\\.(\\w+)");

    private static final Set<String> AUTHENTICATED_SHELL_ROUTES =
            Set.of(
                    "GET /api/platform/modules/plugins/frontend",
                    "GET /api/platform/modules/plugins/assets/x/x",
                    "GET /api/platform/navigation",
                    "POST /api/platform/role-preview/bootstrap");

    private record HttpCase(
            String pageId,
            String accessPermission,
            String accessScope,
            String operationPermission,
            String operationScope,
            String method,
            String path,
            String target,
            List<String> allowedOperationPermissions,
            Object payload,
            List<Integer> bodyBytes,
            String contentType,
            List<Integer> allowedStatuses,
            List<String> legacyPermissions,
            List<String> requiredPages) {}

    private record Endpoint(String method, String path, String source) {}

    private record SecurityRule(String method, PathPattern[] patterns, String kind) {
        boolean matches(String httpMethod, String path) {
            if (method != null && !method.equals(httpMethod)) return false;
            var container = org.springframework.http.server.PathContainer.parsePath(path);
            for (PathPattern pattern : patterns) if (pattern.matches(container)) return true;
            return false;
        }
    }

    @Test
    void everyHostPlatformControllerEndpointHasAnExplicitPageAndActionOwner() throws Exception {
        List<String> unowned = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            if (!endpoint.path().startsWith("/api/platform/")) continue;
            for (String path : policyLookupPaths(endpoint)) {
                String key = endpoint.method() + " " + path;
                if (AUTHENTICATED_SHELL_ROUTES.contains(key)) continue;
                if (PlatformHttpPermissionPolicy.routeFor(endpoint.method(), path) == null)
                    unowned.add(key + " <- " + endpoint.source());
            }
        }
        assertTrue(
                unowned.isEmpty(),
                "Host platform controller routes lack a page/action owner:\n"
                        + String.join("\n", unowned));
    }

    @Test
    void durableHttpCasesMatchThePolicyAndCoverEveryRouteAndAnyOfAction() throws Exception {
        List<HttpCase> cases = loadCases();
        assertFalse(cases.isEmpty(), "The independent-permissions HTTP matrix is empty");

        var routeKeys = new TreeSet<String>();
        for (PlatformHttpPermissionPolicy.Route route :
                PlatformHttpPermissionPolicy.routesForTesting())
            assertTrue(
                    routeKeys.add(route.method() + " " + route.path()),
                    "Duplicate host route policy entry " + route.method() + " " + route.path());

        for (HttpCase httpCase : cases) {
            assertTrue(
                    httpCase.path().startsWith("/platform/")
                            || httpCase.path().startsWith("/dictionaries/"),
                    httpCase.path());
            assertNotNull(httpCase.accessPermission(), httpCase.path());
            assertNotNull(httpCase.accessScope(), httpCase.path());
            var route =
                    PlatformHttpPermissionPolicy.routeFor(
                            httpCase.method(), "/api" + pathOnly(httpCase.path()));
            assertNotNull(
                    route, "No policy route for " + httpCase.method() + " " + httpCase.path());
            assertEquals(httpCase.pageId(), route.page().id(), httpCase.path());
            List<String> requiredPages =
                    httpCase.requiredPages() == null
                            ? List.of(httpCase.pageId())
                            : httpCase.requiredPages();
            assertEquals(
                    route.pagesAll().stream().map(PlatformPageId::id).toList(),
                    requiredPages,
                    "Required page set for " + httpCase.method() + " " + httpCase.path());
            assertEquals(
                    route.page().accessPermission(), httpCase.accessPermission(), httpCase.path());
            assertEquals(route.page().scope().name(), httpCase.accessScope(), httpCase.path());
            assertEquals(
                    route.operationsAny().stream().map(PlatformOperation::permission).toList(),
                    httpCase.allowedOperationPermissions(),
                    "Complete action allow-list for " + httpCase.method() + " " + httpCase.path());
            assertFalse(
                    httpCase.allowedStatuses().contains(403),
                    "A positive HTTP case must not accept authorization denial: "
                            + httpCase.path());
            if (httpCase.target() != null) {
                assertEquals("qa-actor", httpCase.target(), httpCase.path());
                assertEquals("GET", httpCase.method(), httpCase.path());
                assertTrue(httpCase.path().contains(TEST_ID), httpCase.path());
                assertEquals(List.of(200), httpCase.allowedStatuses(), httpCase.path());
            }
            if (httpCase.operationPermission() == null) {
                assertTrue(route.operationsAny().isEmpty(), httpCase.path());
                assertEquals(null, httpCase.operationScope(), httpCase.path());
            } else {
                PlatformOperation operation =
                        route.operationsAny().stream()
                                .filter(
                                        candidate ->
                                                candidate
                                                        .permission()
                                                        .equals(httpCase.operationPermission()))
                                .findFirst()
                                .orElse(null);
                assertNotNull(
                        operation,
                        "Case operation is not accepted by policy for " + httpCase.path());
                assertEquals(operation.scope().name(), httpCase.operationScope(), httpCase.path());
            }
        }

        var caseKeys = new TreeSet<String>();
        for (HttpCase httpCase : cases) {
            String key =
                    httpCase.method()
                            + " "
                            + pathOnly(httpCase.path())
                            + " "
                            + httpCase.operationPermission();
            assertTrue(caseKeys.add(key), "Duplicate HTTP matrix case " + key);
        }

        for (PlatformHttpPermissionPolicy.Route route :
                PlatformHttpPermissionPolicy.routesForTesting()) {
            String routePath = route.path();
            boolean caseCoversRoute =
                    cases.stream()
                            .anyMatch(
                                    httpCase ->
                                            route.method().equals(httpCase.method())
                                                    && ANT.match(
                                                            routePath,
                                                            "/api" + pathOnly(httpCase.path())));
            assertTrue(
                    caseCoversRoute, "No HTTP case covers " + route.method() + " " + route.path());
            for (PlatformOperation operation : route.operationsAny())
                assertTrue(
                        cases.stream()
                                .anyMatch(
                                        httpCase ->
                                                route.method().equals(httpCase.method())
                                                        && ANT.match(
                                                                routePath,
                                                                "/api" + pathOnly(httpCase.path()))
                                                        && operation
                                                                .permission()
                                                                .equals(
                                                                        httpCase
                                                                                .operationPermission())),
                        "OR action is absent from the HTTP matrix: " + operation.permission());
        }
    }

    @Test
    void hostHttpMatrixAuthorizesOnlyWithEveryRequiredPageAndActionAtItsDeclaredScope()
            throws Exception {
        for (HttpCase httpCase : loadCases()) {
            var route =
                    PlatformHttpPermissionPolicy.routeFor(
                            httpCase.method(), "/api" + pathOnly(httpCase.path()));
            assertNotNull(route, httpCase.path());

            var effective = new TreeSet<String>();
            var global = new TreeSet<String>();
            route.pagesAll()
                    .forEach(
                            page ->
                                    addGrant(
                                            effective,
                                            global,
                                            page.accessPermission(),
                                            page.scope()));
            List<PlatformOperation> caseOperations =
                    httpCase.operationPermission() == null
                            ? List.of()
                            : route.operationsAny().stream()
                                    .filter(
                                            operation ->
                                                    operation
                                                            .permission()
                                                            .equals(httpCase.operationPermission()))
                                    .toList();
            if (httpCase.operationPermission() != null)
                assertFalse(caseOperations.isEmpty(), "Missing case action for " + httpCase.path());
            caseOperations.forEach(
                    operation ->
                            addGrant(effective, global, operation.permission(), operation.scope()));

            assertTrue(
                    authorizeCase(httpCase, effective, global),
                    "Declared page/action grants should authorize " + httpCase.path());

            for (PlatformPageId page : route.pagesAll()) {
                var withoutPage = new TreeSet<>(effective);
                var withoutPageGlobal = new TreeSet<>(global);
                withoutPage.remove(page.accessPermission());
                withoutPageGlobal.remove(page.accessPermission());
                assertFalse(
                        authorizeCase(httpCase, withoutPage, withoutPageGlobal),
                        "Missing required page must deny "
                                + httpCase.path()
                                + " without "
                                + page.id());

                var wrongPageScope = new TreeSet<>(effective);
                var wrongPageGlobalScope = new TreeSet<>(global);
                moveGrantToWrongScope(
                        wrongPageScope,
                        wrongPageGlobalScope,
                        page.accessPermission(),
                        page.scope());
                assertFalse(
                        authorizeCase(httpCase, wrongPageScope, wrongPageGlobalScope),
                        "Wrong page scope must deny " + httpCase.path() + " for " + page.id());
            }

            if (!route.operationsAny().isEmpty()) {
                var withoutActions = new TreeSet<>(effective);
                var withoutActionsGlobal = new TreeSet<>(global);
                route.operationsAny()
                        .forEach(
                                operation -> {
                                    withoutActions.remove(operation.permission());
                                    withoutActionsGlobal.remove(operation.permission());
                                });
                assertFalse(
                        authorizeCase(httpCase, withoutActions, withoutActionsGlobal),
                        "Missing action grant must deny " + httpCase.path());
            }

            for (PlatformOperation operation : caseOperations) {
                var wrongActionScope = new TreeSet<>(effective);
                var wrongActionGlobalScope = new TreeSet<>(global);
                moveGrantToWrongScope(
                        wrongActionScope,
                        wrongActionGlobalScope,
                        operation.permission(),
                        operation.scope());
                assertFalse(
                        authorizeCase(httpCase, wrongActionScope, wrongActionGlobalScope),
                        "Wrong action scope must deny "
                                + httpCase.path()
                                + " for "
                                + operation.permission());
            }
        }
    }

    @Test
    void auxiliaryLookupsUseTheirOwningPageAndOnlyTheirOwnReadOrWriteAction() {
        assertOperations(
                "GET",
                "/api/platform/users/" + TEST_ID + "/roles",
                "users",
                "platform:users:read-roles",
                "platform:users:assign-roles");
        assertOperations(
                "GET",
                "/api/platform/roles/" + TEST_ID + "/authentication-policy",
                "roles",
                "platform:roles:read-authentication-policy",
                "platform:roles:update-authentication-policy");
        assertOperations(
                "GET",
                "/api/platform/settings/registration/roles",
                "registration",
                "platform:registration:read-roles",
                "platform:registration:assign-default-role");
        assertOperations(
                "GET",
                "/api/platform/settings/profile-fields/dictionaries",
                "profile-fields",
                "platform:profile-fields:read-dictionaries");
        assertOperations(
                "GET",
                "/api/dictionaries/languages/items",
                "profile-fields",
                "platform:profile-fields:read-dictionaries");
        assertOperations(
                "GET",
                "/api/dictionaries/languages/items/" + TEST_ID,
                "profile-fields",
                "platform:profile-fields:read-dictionaries");

        assertNull(
                PlatformHttpPermissionPolicy.routeFor(
                        "GET", "/api/platform/users/" + TEST_ID + "/file-quota"));
        assertNull(
                PlatformHttpPermissionPolicy.routeFor(
                        "GET", "/api/platform/users/" + TEST_ID + "/file-quota/preview"));
        assertOperations(
                "GET",
                "/api/platform/users/" + TEST_ID + "/file-quota/preview",
                "users",
                "platform:users:assign-roles");

        // FileConsoleHttpPolicyCoverageTest freezes the optional owner's page/action scopes.
        // The host must not retain a second route after that owner has been uninstalled.
        assertNull(
                PlatformHttpPermissionPolicy.routeFor("GET", "/api/platform/settings/file-quota"));
        assertNull(
                PlatformHttpPermissionPolicy.routeFor("PUT", "/api/platform/settings/file-quota"));
    }

    @Test
    void publicAndAuthenticatedShellAndBootstrapRoutesRemainDeliberateExceptions()
            throws Exception {
        String source = Files.readString(SECURITY_SOURCE);
        for (String path :
                List.of(
                        "\"/api/platform/modules/plugins/frontend\"",
                        "\"/api/platform/modules/plugins/assets/**\"",
                        "\"/api/platform/navigation\"",
                        "\"/api/platform/role-preview/bootstrap\""))
            assertTrue(source.contains(path), "Missing authenticated shell route " + path);
        assertTrue(
                source.contains("plugins.getIfAvailable()"),
                "Unowned plugin API routes must continue to delegate to PluginAuthorizationPort");
    }

    @Test
    void pendingSecondFactorRecoveryIsPublicOnlyForPost() throws Exception {
        List<SecurityRule> rules = securityRules();
        for (String path :
                List.of(
                        "/api/auth/second-factor/recovery/request",
                        "/api/auth/second-factor/recovery/verify")) {
            assertTrue(
                    rules.stream()
                            .anyMatch(
                                    rule ->
                                            rule.kind().equals("permitAll")
                                                    && rule.matches("POST", path)),
                    "Pending-session recovery POST must remain public: " + path);
            assertFalse(
                    rules.stream()
                            .anyMatch(
                                    rule ->
                                            rule.kind().equals("permitAll")
                                                    && rule.matches("GET", path)),
                    "Recovery endpoint must not be exposed as an anonymous GET: " + path);
        }
    }

    @Test
    void accountRolePreviewAndReauthenticationRoutesRemainAuthenticated() throws Exception {
        List<SecurityRule> rules = securityRules();
        for (String route :
                List.of(
                        "GET /api/account/role-preview",
                        "GET /api/account/role-preview/roles",
                        "POST /api/account/role-preview",
                        "DELETE /api/account/role-preview",
                        "GET /api/account/role-preview/resource-tickets/x",
                        "POST /api/account/role-preview/resource-tickets",
                        "POST /api/platform/role-preview/bootstrap",
                        "POST /api/account/reauthentication/code",
                        "POST /api/account/reauthentication/passkey/options",
                        "POST /api/account/reauthentication/passkey/complete")) {
            String[] parts = route.split(" ", 2);
            assertTrue(
                    rules.stream()
                            .anyMatch(
                                    rule ->
                                            rule.kind().equals("authenticated")
                                                    && rule.matches(parts[0], parts[1])),
                    "Missing authenticated security rule for " + route);
        }
    }

    private static void assertOperations(
            String method, String path, String pageId, String... expected) {
        var route = PlatformHttpPermissionPolicy.routeFor(method, path);
        var owner =
                switch (pageId) {
                    case "users", "roles" -> "directory";
                    case "profile-fields" -> "profile-console";
                    case "security",
                            "authentication",
                            "passkey",
                            "wechat",
                            "captcha",
                            "registration" ->
                            "authentication-console";
                    default -> null;
                };
        if (route == null && owner != null) {
            // These business endpoints now belong to the independently loaded directory package.
            try {
                var manifest =
                        new ObjectMapper()
                                .readValue(
                                        Files.readString(
                                                Path.of("..", "plugins", owner, "plugin.json")),
                                        com.lumira.platform.plugins.PluginManifest.class);
                var container = org.springframework.http.server.PathContainer.parsePath(path);
                var rule =
                        manifest.apiRules().stream()
                                .filter(
                                        candidate ->
                                                candidate.method().equals(method)
                                                        && PathPatternParser.defaultInstance
                                                                .parse(candidate.path())
                                                                .matches(container))
                                .findFirst()
                                .orElseThrow();
                var page =
                        manifest.routes().stream()
                                .filter(candidate -> candidate.id().equals(pageId))
                                .findFirst()
                                .orElseThrow();
                assertTrue(rule.allAuthorities().contains(page.permission()), path);
                assertEquals(List.of(expected), rule.authorities(), path);
                return;
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        }
        assertNotNull(route, method + " " + path);
        assertEquals(pageId, route.page().id(), path);
        assertEquals(
                List.of(expected),
                route.operationsAny().stream().map(PlatformOperation::permission).toList(),
                path);
    }

    private static String pathOnly(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private static List<String> policyLookupPaths(Endpoint endpoint) {
        return List.of(endpoint.path());
    }

    private static List<HttpCase> loadCases() throws Exception {
        try (InputStream stream =
                RoutePermissionCoverageTest.class
                        .getClassLoader()
                        .getResourceAsStream(MATRIX_RESOURCE)) {
            assertNotNull(stream, "Missing " + MATRIX_RESOURCE);
            return new ObjectMapper().readValue(stream, new TypeReference<>() {});
        }
    }

    private static void addGrant(
            Set<String> effective,
            Set<String> global,
            String permission,
            PlatformOperation.Scope scope) {
        effective.add(permission);
        if (scope == PlatformOperation.Scope.GLOBAL) global.add(permission);
    }

    private static void addWrongScopeGrant(
            Set<String> effective,
            Set<String> global,
            String permission,
            PlatformOperation.Scope scope) {
        if (scope == PlatformOperation.Scope.GLOBAL) effective.add(permission);
        else global.add(permission);
    }

    private static void moveGrantToWrongScope(
            Set<String> effective,
            Set<String> global,
            String permission,
            PlatformOperation.Scope scope) {
        effective.remove(permission);
        global.remove(permission);
        addWrongScopeGrant(effective, global, permission, scope);
    }

    private static boolean authorizeCase(
            HttpCase httpCase, Set<String> effective, Set<String> global) {
        String path = "/api" + pathOnly(httpCase.path());
        var request = new MockHttpServletRequest(httpCase.method(), path);
        var authorities = effective.stream().map(SimpleGrantedAuthority::new).toList();
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated("matrix-actor", "", authorities);
        var context =
                new RequestAuthorizationContext(
                        "matrix-actor",
                        MATRIX_ACTOR_ID,
                        "matrix-session",
                        effective,
                        global,
                        Map.of(),
                        effective,
                        global,
                        Map.of(),
                        "revision",
                        "revision",
                        true,
                        null);
        try (var ignored =
                AuthenticationRequestContext.bind(
                        new AuthenticationRequestContext.Snapshot(
                                "matrix-actor", MATRIX_ACTOR_ID, null, context))) {
            return PlatformHttpPermissionPolicy.authorize(authentication, request, null);
        }
    }

    private static List<SecurityRule> securityRules() throws Exception {
        String source = Files.readString(SECURITY_SOURCE);
        source = source.substring(0, source.indexOf("anyRequest()"));
        var rules = new ArrayList<SecurityRule>();
        var matcher = SECURITY_RULE.matcher(source);
        var parser = PathPatternParser.defaultInstance;
        while (matcher.find()) {
            List<PathPattern> patterns = new ArrayList<>();
            var literal = Pattern.compile("\"([^\"]+)\"").matcher(matcher.group(2));
            while (literal.find()) patterns.add(parser.parse(literal.group(1)));
            rules.add(
                    new SecurityRule(
                            matcher.group(1),
                            patterns.toArray(PathPattern[]::new),
                            matcher.group(3)));
        }
        return rules;
    }

    private static List<Endpoint> endpoints() throws Exception {
        var context = new StaticApplicationContext();
        context.refresh();
        var mapping = new ExposedMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var found = new TreeSet<String>();
        var scannedControllers = new TreeSet<String>();
        var result = new ArrayList<Endpoint>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.lumira.platform")) {
            Class<?> type =
                    ClassUtils.forName(
                            definition.getBeanClassName(),
                            RoutePermissionCoverageTest.class.getClassLoader());
            if (type.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .getPath()
                    .contains("/test-classes/")) continue;
            scannedControllers.add(type.getName());
            for (Method method : type.getDeclaredMethods()) {
                if (org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                                method, RequestMapping.class)
                        == null) continue;
                RequestMappingInfo info = mapping.mappingFor(method, type);
                if (info == null || info.getPathPatternsCondition() == null) continue;
                var methods = info.getMethodsCondition().getMethods();
                var verbs =
                        methods.isEmpty()
                                ? Set.of("GET")
                                : methods.stream()
                                        .map(Enum::name)
                                        .collect(java.util.stream.Collectors.toSet());
                for (PathPattern pattern : info.getPathPatternsCondition().getPatterns()) {
                    String path = pattern.getPatternString().replaceAll("\\{[^/}]+}", "x");
                    for (String verb : verbs) {
                        String key =
                                verb + " " + path + " " + type.getName() + "#" + method.getName();
                        if (found.add(key))
                            result.add(
                                    new Endpoint(
                                            verb,
                                            path,
                                            type.getSimpleName() + "#" + method.getName()));
                    }
                }
            }
        }
        var expectedControllers = new TreeSet<String>();
        try (var sources = Files.walk(Path.of("src/main/java/com/lumira/platform"))) {
            for (var source : sources.filter(file -> file.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                if (!Pattern.compile(
                                "@(?:org\\.springframework\\.web\\.bind\\.annotation\\.)?RestController\\b")
                        .matcher(text)
                        .find()) continue;
                var declaration = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;").matcher(text);
                assertTrue(declaration.find(), "Controller source has no package: " + source);
                expectedControllers.add(
                        declaration.group(1)
                                + "."
                                + source.getFileName().toString().replace(".java", ""));
            }
        }
        assertEquals(
                expectedControllers,
                scannedControllers,
                "Classpath scan must cover every current kernel controller source");
        assertFalse(result.isEmpty(), "Controller mapping scan is empty");
        return result;
    }

    private static final class ExposedMapping extends RequestMappingHandlerMapping {
        RequestMappingInfo mappingFor(Method method, Class<?> type) {
            try {
                return getMappingForMethod(method, type);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
