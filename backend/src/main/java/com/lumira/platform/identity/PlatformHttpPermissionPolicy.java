package com.lumira.platform.identity;

import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.PluginAuthorizationPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.AntPathMatcher;

/** Route-owned HTTP authorization for the host dashboard API. */
final class PlatformHttpPermissionPolicy {
    private static final AntPathMatcher PATHS = new AntPathMatcher();
    private static final List<Route> ROUTES = routes();

    record Route(
            String method,
            String path,
            PlatformPageId page,
            List<PlatformOperation> operationsAny,
            List<PlatformPageId> pagesAll) {
        Route(
                String method,
                String path,
                PlatformPageId page,
                List<PlatformOperation> operationsAny) {
            this(method, path, page, operationsAny, List.of(page));
        }

        Route {
            operationsAny = List.copyOf(operationsAny);
            pagesAll = List.copyOf(pagesAll);
            if (!pagesAll.contains(page))
                throw new IllegalArgumentException("Route page must be required");
            if (operationsAny.stream().anyMatch(operation -> operation.page() != page))
                throw new IllegalArgumentException("Route operation belongs to another page");
        }
    }

    private PlatformHttpPermissionPolicy() {}

    static boolean authorize(
            Authentication authentication,
            HttpServletRequest request,
            PluginAuthorizationPort pluginAuthorization) {
        String method = request.getMethod();
        String path = pathWithinApplication(request);
        Route route = routeFor(method, path);
        if (route != null) {
            if (authentication == null
                    || !authentication.isAuthenticated()
                    || authentication instanceof AnonymousAuthenticationToken) return false;
            RequestAuthorizationContext context = authorizationContext(authentication);
            if (context == null) return false;
            for (PlatformPageId requiredPage : route.pagesAll()) {
                if (!grantsFor(context, requiredPage.scope())
                        .contains(requiredPage.accessPermission())) return false;
            }
            Set<String> grants = grantsFor(context, route.page().scope());
            if (route.operationsAny().isEmpty()) return true;
            return route.operationsAny().stream()
                    .anyMatch(
                            operation ->
                                    grantsFor(context, operation.scope())
                                            .contains(operation.permission()));
        }

        return authorizePlugin(authentication, request, pluginAuthorization);
    }

    private static boolean authorizePlugin(
            Authentication authentication,
            HttpServletRequest request,
            PluginAuthorizationPort pluginAuthorization) {
        if (pluginAuthorization == null) return false;
        Set<String> authorities =
                authentication == null
                        ? Set.of()
                        : authentication.getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return pluginAuthorization.authorize(
                request.getMethod(),
                pathWithinApplication(request),
                authorities,
                authentication != null
                        && authentication.isAuthenticated()
                        && !(authentication instanceof AnonymousAuthenticationToken));
    }

    private static RequestAuthorizationContext authorizationContext(Authentication authentication) {
        AuthenticationRequestContext.Snapshot snapshot = AuthenticationRequestContext.current();
        if (snapshot == null
                || snapshot.authorization() == null
                || !authentication.getName().equals(snapshot.principalName())) return null;
        return snapshot.authorization();
    }

    static Route routeFor(String method, String path) {
        for (Route route : ROUTES)
            if (route.method().equals(method) && PATHS.match(route.path(), path)) return route;
        return null;
    }

    static List<Route> routesForTesting() {
        return ROUTES;
    }

    private static Set<String> grantsFor(
            RequestAuthorizationContext context, PlatformOperation.Scope scope) {
        return scope == PlatformOperation.Scope.GLOBAL
                ? context.effectiveGlobalAuthorities()
                : context.effectiveAuthorities();
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath + "/"))
            return path.substring(contextPath.length());
        return path;
    }

    private static List<Route> routes() {
        var routes = new ArrayList<Route>();

        page(routes, HttpMethod.GET, PlatformPageId.MODULES, "/api/platform/modules");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.MODULES,
                PlatformOperation.MODULE_UPDATE,
                "/api/platform/modules/*/state");
        page(routes, HttpMethod.GET, PlatformPageId.MODULES, "/api/platform/modules/plugins");
        page(
                routes,
                HttpMethod.GET,
                PlatformPageId.MODULES,
                "/api/platform/modules/plugins/operations/*");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.MODULES,
                PlatformOperation.MODULE_INSTALL,
                "/api/platform/modules/plugins/import");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.MODULES,
                PlatformOperation.MODULE_INSTALL,
                "/api/platform/modules/plugins/*/install-plan");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.MODULES,
                PlatformOperation.MODULE_UNINSTALL,
                "/api/platform/modules/plugins/*/uninstall-plan");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.MODULES,
                PlatformOperation.MODULE_INSTALL,
                "/api/platform/modules/plugins/*/install");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.MODULES,
                PlatformOperation.MODULE_UNINSTALL,
                "/api/platform/modules/plugins/*/uninstall");

        // Generic identity dictionary projection remains available without its optional schema
        // editor.
        any(
                routes,
                HttpMethod.GET,
                PlatformPageId.PROFILE_FIELDS,
                List.of(PlatformOperation.PROFILE_FIELDS_READ_DICTIONARIES),
                "/api/dictionaries/*/items",
                "/api/dictionaries/*/items/*");
        return List.copyOf(routes);
    }

    private static void page(
            List<Route> routes, HttpMethod method, PlatformPageId page, String... paths) {
        for (String path : paths) routes.add(new Route(method.name(), path, page, List.of()));
    }

    private static void pages(
            List<Route> routes,
            HttpMethod method,
            PlatformPageId primaryPage,
            List<PlatformPageId> pagesAll,
            String... paths) {
        for (String path : paths)
            routes.add(new Route(method.name(), path, primaryPage, List.of(), pagesAll));
    }

    private static void action(
            List<Route> routes,
            HttpMethod method,
            PlatformPageId page,
            PlatformOperation operation,
            String... paths) {
        for (String path : paths)
            routes.add(new Route(method.name(), path, page, List.of(operation)));
    }

    private static void any(
            List<Route> routes,
            HttpMethod method,
            PlatformPageId page,
            List<PlatformOperation> operations,
            String... paths) {
        for (String path : paths) routes.add(new Route(method.name(), path, page, operations));
    }
}
