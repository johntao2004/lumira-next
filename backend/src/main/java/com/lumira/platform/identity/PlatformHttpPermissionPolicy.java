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
            List<PlatformOperation> operationsAny) {
        Route {
            operationsAny = List.copyOf(operationsAny);
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
            Set<String> grants = grantsFor(context, route.page().scope());
            if (!grants.contains(route.page().accessPermission())) return false;
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

        page(routes, HttpMethod.GET, PlatformPageId.API_DOCS, "/api/platform/api-docs");

        page(routes, HttpMethod.GET, PlatformPageId.AUDIT, "/api/platform/audit");
        page(
                routes,
                HttpMethod.GET,
                PlatformPageId.MAIL_DELIVERIES,
                "/api/platform/mail-deliveries");
        page(routes, HttpMethod.GET, PlatformPageId.SMS_DELIVERIES, "/api/platform/sms-deliveries");
        page(routes, HttpMethod.GET, PlatformPageId.EVENT_OUTBOX, "/api/platform/event-outbox");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.EVENT_OUTBOX,
                PlatformOperation.EVENT_OUTBOX_REPLAY,
                "/api/platform/event-outbox/*/replay");
        page(routes, HttpMethod.GET, PlatformPageId.MONITORING, "/api/platform/monitoring/summary");

        page(routes, HttpMethod.GET, PlatformPageId.USERS, "/api/platform/users");
        action(
                routes,
                HttpMethod.GET,
                PlatformPageId.USERS,
                PlatformOperation.USER_EXPORT,
                "/api/platform/users/export");
        page(routes, HttpMethod.GET, PlatformPageId.USERS, "/api/platform/users/*");
        page(routes, HttpMethod.GET, PlatformPageId.USERS, "/api/platform/users/*/profile-values");
        page(routes, HttpMethod.GET, PlatformPageId.USERS, "/api/platform/users/*/file-quota");
        action(
                routes,
                HttpMethod.GET,
                PlatformPageId.USERS,
                PlatformOperation.USER_ASSIGN_ROLES,
                "/api/platform/users/*/file-quota/preview");
        any(
                routes,
                HttpMethod.GET,
                PlatformPageId.PROFILE_FIELDS,
                List.of(PlatformOperation.PROFILE_FIELDS_READ_DICTIONARIES),
                "/api/dictionaries/*/items",
                "/api/dictionaries/*/items/*",
                "/api/platform/settings/profile-fields/dictionaries");
        any(
                routes,
                HttpMethod.GET,
                PlatformPageId.USERS,
                List.of(PlatformOperation.USER_READ_ROLES, PlatformOperation.USER_ASSIGN_ROLES),
                "/api/platform/users/*/roles");
        any(
                routes,
                HttpMethod.GET,
                PlatformPageId.USERS,
                List.of(PlatformOperation.USER_READ_ROLES, PlatformOperation.USER_ASSIGN_ROLES),
                "/api/platform/users/*/role-options");
        page(routes, HttpMethod.GET, PlatformPageId.USERS, "/api/platform/users/*/organizations");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.USERS,
                PlatformOperation.USER_CREATE,
                "/api/platform/users");
        any(
                routes,
                HttpMethod.PUT,
                PlatformPageId.USERS,
                List.of(
                        PlatformOperation.USER_UPDATE,
                        PlatformOperation.USER_ASSIGN_ROLES,
                        PlatformOperation.USER_ASSIGN_ORGANIZATIONS),
                "/api/platform/users/*/editor");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.USERS,
                PlatformOperation.USER_ASSIGN_ROLES,
                "/api/platform/users/*/roles");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.USERS,
                PlatformOperation.USER_ASSIGN_ORGANIZATIONS,
                "/api/platform/users/*/organizations");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.USERS,
                PlatformOperation.USER_RESET_PASSWORD,
                "/api/platform/users/*/password-reset");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.USERS,
                PlatformOperation.USER_UPDATE,
                "/api/platform/users/*");
        action(
                routes,
                HttpMethod.DELETE,
                PlatformPageId.USERS,
                PlatformOperation.USER_DELETE,
                "/api/platform/users/*");

        page(routes, HttpMethod.GET, PlatformPageId.ONLINE_USERS, "/api/platform/online-users");
        action(
                routes,
                HttpMethod.DELETE,
                PlatformPageId.ONLINE_USERS,
                PlatformOperation.ONLINE_USER_TERMINATE_SESSION,
                "/api/platform/online-users/*");
        action(
                routes,
                HttpMethod.DELETE,
                PlatformPageId.ONLINE_USERS,
                PlatformOperation.ONLINE_USER_TERMINATE_SESSION,
                "/api/platform/online-users/user/*");

        page(routes, HttpMethod.GET, PlatformPageId.ROLES, "/api/platform/roles");
        page(routes, HttpMethod.GET, PlatformPageId.ROLES, "/api/platform/roles/*");
        page(routes, HttpMethod.GET, PlatformPageId.ROLES, "/api/platform/permissions");
        page(routes, HttpMethod.GET, PlatformPageId.ROLES, "/api/platform/permission-catalog");
        any(
                routes,
                HttpMethod.GET,
                PlatformPageId.ROLES,
                List.of(
                        PlatformOperation.ROLE_READ_AUTHENTICATION_POLICY,
                        PlatformOperation.ROLE_UPDATE_AUTHENTICATION_POLICY),
                "/api/platform/roles/*/authentication-policy");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.ROLES,
                PlatformOperation.ROLE_CREATE,
                "/api/platform/roles");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.ROLES,
                PlatformOperation.ROLE_UPDATE,
                "/api/platform/roles/*");
        action(
                routes,
                HttpMethod.DELETE,
                PlatformPageId.ROLES,
                PlatformOperation.ROLE_DELETE,
                "/api/platform/roles/*");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.ROLES,
                PlatformOperation.ROLE_UPDATE_AUTHENTICATION_POLICY,
                "/api/platform/roles/*/authentication-policy");

        page(routes, HttpMethod.GET, PlatformPageId.ORGANIZATIONS, "/api/platform/organizations");
        page(routes, HttpMethod.GET, PlatformPageId.ORGANIZATIONS, "/api/platform/organizations/*");
        page(
                routes,
                HttpMethod.GET,
                PlatformPageId.ORGANIZATIONS,
                "/api/platform/organizations/*/ancestors");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.ORGANIZATIONS,
                PlatformOperation.ORGANIZATION_CREATE,
                "/api/platform/organizations");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.ORGANIZATIONS,
                PlatformOperation.ORGANIZATION_UPDATE,
                "/api/platform/organizations/*");
        action(
                routes,
                HttpMethod.DELETE,
                PlatformPageId.ORGANIZATIONS,
                PlatformOperation.ORGANIZATION_DELETE,
                "/api/platform/organizations/*");

        page(routes, HttpMethod.GET, PlatformPageId.DICTIONARIES, "/api/platform/dictionaries");
        page(routes, HttpMethod.GET, PlatformPageId.DICTIONARIES, "/api/platform/dictionaries/*");
        action(
                routes,
                HttpMethod.GET,
                PlatformPageId.DICTIONARIES,
                PlatformOperation.DICTIONARY_EXPORT,
                "/api/platform/dictionaries/*/export");
        page(
                routes,
                HttpMethod.GET,
                PlatformPageId.DICTIONARIES,
                "/api/platform/dictionaries/*/items");
        page(
                routes,
                HttpMethod.GET,
                PlatformPageId.DICTIONARIES,
                "/api/platform/dictionaries/*/items/*");
        page(
                routes,
                HttpMethod.GET,
                PlatformPageId.DICTIONARIES,
                "/api/platform/dictionaries/*/items/*/ancestors");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.DICTIONARIES,
                PlatformOperation.DICTIONARY_IMPORT,
                "/api/platform/dictionaries/preview");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.DICTIONARIES,
                PlatformOperation.DICTIONARY_IMPORT,
                "/api/platform/dictionaries/import");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.DICTIONARIES,
                PlatformOperation.DICTIONARY_IMPORT,
                "/api/platform/dictionaries/file-preview");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.DICTIONARIES,
                PlatformOperation.DICTIONARY_IMPORT,
                "/api/platform/dictionaries/file-import");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.DICTIONARIES,
                PlatformOperation.DICTIONARY_CREATE,
                "/api/platform/dictionaries");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.DICTIONARIES,
                PlatformOperation.DICTIONARY_CREATE,
                "/api/platform/dictionaries/*/items");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.DICTIONARIES,
                PlatformOperation.DICTIONARY_UPDATE,
                "/api/platform/dictionaries/*");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.DICTIONARIES,
                PlatformOperation.DICTIONARY_UPDATE,
                "/api/platform/dictionaries/*/items/*");
        action(
                routes,
                HttpMethod.DELETE,
                PlatformPageId.DICTIONARIES,
                PlatformOperation.DICTIONARY_DELETE,
                "/api/platform/dictionaries/*");
        action(
                routes,
                HttpMethod.DELETE,
                PlatformPageId.DICTIONARIES,
                PlatformOperation.DICTIONARY_DELETE,
                "/api/platform/dictionaries/*/items/*");

        page(routes, HttpMethod.GET, PlatformPageId.BRANDING, "/api/platform/settings/branding");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.BRANDING,
                PlatformOperation.BRANDING_UPDATE,
                "/api/platform/settings/branding");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.BRANDING,
                PlatformOperation.BRANDING_UPDATE,
                "/api/platform/settings/branding/assets");

        settings(routes, PlatformPageId.SMTP, PlatformOperation.SMTP_UPDATE, "/smtp");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.SMTP,
                PlatformOperation.SMTP_TEST,
                "/api/platform/settings/smtp/test");
        settings(routes, PlatformPageId.SMS, PlatformOperation.SMS_UPDATE, "/sms");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.SMS,
                PlatformOperation.SMS_TEST,
                "/api/platform/settings/sms/test");
        settings(
                routes,
                PlatformPageId.PROFILE_FIELDS,
                PlatformOperation.PROFILE_FIELDS_UPDATE,
                "/profile-fields");
        settings(
                routes,
                PlatformPageId.AGREEMENTS,
                PlatformOperation.AGREEMENTS_UPDATE,
                "/agreements");
        settings(
                routes, PlatformPageId.WATERMARK, PlatformOperation.WATERMARK_UPDATE, "/watermark");
        settings(
                routes,
                PlatformPageId.MAINTENANCE,
                PlatformOperation.MAINTENANCE_UPDATE,
                "/maintenance");
        action(
                routes,
                HttpMethod.GET,
                PlatformPageId.MAINTENANCE,
                PlatformOperation.MAINTENANCE_UPDATE,
                "/api/platform/settings/maintenance/roles");
        settings(routes, PlatformPageId.SECURITY, PlatformOperation.SECURITY_UPDATE, "/security");
        settings(
                routes,
                PlatformPageId.AUTHENTICATION,
                PlatformOperation.AUTHENTICATION_UPDATE,
                "/authentication");
        settings(routes, PlatformPageId.PASSKEY, PlatformOperation.PASSKEY_UPDATE, "/passkey");
        settings(routes, PlatformPageId.WECHAT, PlatformOperation.WECHAT_UPDATE, "/wechat");
        settings(routes, PlatformPageId.CAPTCHA, PlatformOperation.CAPTCHA_UPDATE, "/captcha");
        settings(
                routes,
                PlatformPageId.REGISTRATION,
                PlatformOperation.REGISTRATION_UPDATE,
                "/registration");
        any(
                routes,
                HttpMethod.GET,
                PlatformPageId.REGISTRATION,
                List.of(
                        PlatformOperation.REGISTRATION_READ_ROLES,
                        PlatformOperation.REGISTRATION_ASSIGN_DEFAULT_ROLE),
                "/api/platform/settings/registration/roles");
        settings(
                routes,
                PlatformPageId.FILE_RETENTION,
                PlatformOperation.FILE_RETENTION_UPDATE,
                "/file-retention");
        settingHistory(routes);

        page(routes, HttpMethod.GET, PlatformPageId.AI_MODELS, "/api/platform/ai/providers");
        page(
                routes,
                HttpMethod.GET,
                PlatformPageId.AI_MODELS,
                "/api/platform/ai/providers/*/models");
        page(routes, HttpMethod.GET, PlatformPageId.AI_CONSOLE, "/api/platform/ai/capabilities");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.AI_MODELS,
                PlatformOperation.AI_MODELS_TEST,
                "/api/platform/ai/providers/discover-models");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.AI_MODELS,
                PlatformOperation.AI_MODELS_TEST,
                "/api/platform/ai/providers/*/test");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.AI_MODELS,
                PlatformOperation.AI_MODELS_CREATE,
                "/api/platform/ai/providers");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.AI_MODELS,
                PlatformOperation.AI_MODELS_UPDATE,
                "/api/platform/ai/providers/*");
        action(
                routes,
                HttpMethod.DELETE,
                PlatformPageId.AI_MODELS,
                PlatformOperation.AI_MODELS_DELETE,
                "/api/platform/ai/providers/*");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.AI_MODELS,
                PlatformOperation.AI_MODELS_TEST,
                "/api/platform/ai/judgments");
        page(
                routes,
                HttpMethod.GET,
                PlatformPageId.AI_CONSOLE,
                "/api/platform/ai/console/providers",
                "/api/platform/ai/console/providers/*/models");
        page(routes, HttpMethod.GET, PlatformPageId.AI_CONSOLE, "/api/platform/ai/attachments/*");
        any(
                routes,
                HttpMethod.POST,
                PlatformPageId.AI_CONSOLE,
                List.of(
                        PlatformOperation.AI_CONSOLE_READ_USERS,
                        PlatformOperation.AI_CONSOLE_CREATE_USER,
                        PlatformOperation.AI_CONSOLE_UPDATE_USER,
                        PlatformOperation.AI_CONSOLE_DELETE_USER),
                "/api/platform/ai/chat");
        any(
                routes,
                HttpMethod.POST,
                PlatformPageId.AI_CONSOLE,
                List.of(
                        PlatformOperation.AI_CONSOLE_CREATE_USER,
                        PlatformOperation.AI_CONSOLE_UPDATE_USER,
                        PlatformOperation.AI_CONSOLE_DELETE_USER),
                "/api/platform/ai/actions/*/confirm");
        any(
                routes,
                HttpMethod.DELETE,
                PlatformPageId.AI_CONSOLE,
                List.of(
                        PlatformOperation.AI_CONSOLE_CREATE_USER,
                        PlatformOperation.AI_CONSOLE_UPDATE_USER,
                        PlatformOperation.AI_CONSOLE_DELETE_USER),
                "/api/platform/ai/actions/*");

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

        page(routes, HttpMethod.GET, PlatformPageId.NAVIGATION, "/api/platform/navigation/menus");
        page(routes, HttpMethod.GET, PlatformPageId.NAVIGATION, "/api/platform/navigation/menus/*");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.NAVIGATION,
                PlatformOperation.NAVIGATION_CREATE,
                "/api/platform/navigation/menus");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.NAVIGATION,
                PlatformOperation.NAVIGATION_UPDATE,
                "/api/platform/navigation/menus/*");
        action(
                routes,
                HttpMethod.PUT,
                PlatformPageId.NAVIGATION,
                PlatformOperation.NAVIGATION_UPDATE,
                "/api/platform/navigation/menus/reorder");
        action(
                routes,
                HttpMethod.PATCH,
                PlatformPageId.NAVIGATION,
                PlatformOperation.NAVIGATION_UPDATE,
                "/api/platform/navigation/menus/*/status");
        action(
                routes,
                HttpMethod.DELETE,
                PlatformPageId.NAVIGATION,
                PlatformOperation.NAVIGATION_DELETE,
                "/api/platform/navigation/menus/*");

        page(routes, HttpMethod.GET, PlatformPageId.FILES_ALL, "/api/platform/files");
        page(routes, HttpMethod.GET, PlatformPageId.FILES_ALL, "/api/platform/files/trash");
        action(
                routes,
                HttpMethod.GET,
                PlatformPageId.FILES_ALL,
                PlatformOperation.FILE_PREVIEW,
                "/api/platform/files/*/preview");
        action(
                routes,
                HttpMethod.GET,
                PlatformPageId.FILES_ALL,
                PlatformOperation.FILE_DOWNLOAD,
                "/api/platform/files/*/content");
        action(
                routes,
                HttpMethod.DELETE,
                PlatformPageId.FILES_ALL,
                PlatformOperation.FILE_DELETE,
                "/api/platform/files/*");
        action(
                routes,
                HttpMethod.DELETE,
                PlatformPageId.FILES_ALL,
                PlatformOperation.FILE_PURGE,
                "/api/platform/files/*/purge");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.FILES_ALL,
                PlatformOperation.FILE_RESTORE,
                "/api/platform/files/*/restore");

        page(routes, HttpMethod.GET, PlatformPageId.NOTIFICATIONS, "/api/platform/notifications");
        action(
                routes,
                HttpMethod.GET,
                PlatformPageId.NOTIFICATIONS,
                PlatformOperation.NOTIFICATION_CREATE,
                "/api/platform/notifications/roles");
        action(
                routes,
                HttpMethod.GET,
                PlatformPageId.NOTIFICATIONS,
                PlatformOperation.NOTIFICATION_CREATE,
                "/api/platform/notifications/recipients");
        action(
                routes,
                HttpMethod.GET,
                PlatformPageId.NOTIFICATIONS,
                PlatformOperation.NOTIFICATION_CREATE,
                "/api/platform/notifications/recipients/*");
        action(
                routes,
                HttpMethod.POST,
                PlatformPageId.NOTIFICATIONS,
                PlatformOperation.NOTIFICATION_CREATE,
                "/api/platform/notifications");

        return List.copyOf(routes);
    }

    private static void settings(
            List<Route> routes, PlatformPageId page, PlatformOperation update, String suffix) {
        String path = "/api/platform/settings" + suffix;
        page(routes, HttpMethod.GET, page, path);
        action(routes, HttpMethod.PUT, page, update, path);
    }

    private static void settingHistory(List<Route> routes) {
        record Section(String id, PlatformPageId page, PlatformOperation update) {}
        for (Section section :
                List.of(
                        new Section(
                                "branding",
                                PlatformPageId.BRANDING,
                                PlatformOperation.BRANDING_UPDATE),
                        new Section("smtp", PlatformPageId.SMTP, PlatformOperation.SMTP_UPDATE),
                        new Section("sms", PlatformPageId.SMS, PlatformOperation.SMS_UPDATE),
                        new Section(
                                "profile-fields",
                                PlatformPageId.PROFILE_FIELDS,
                                PlatformOperation.PROFILE_FIELDS_UPDATE),
                        new Section(
                                "agreements",
                                PlatformPageId.AGREEMENTS,
                                PlatformOperation.AGREEMENTS_UPDATE),
                        new Section(
                                "watermark",
                                PlatformPageId.WATERMARK,
                                PlatformOperation.WATERMARK_UPDATE),
                        new Section(
                                "maintenance",
                                PlatformPageId.MAINTENANCE,
                                PlatformOperation.MAINTENANCE_UPDATE),
                        new Section(
                                "passkey",
                                PlatformPageId.PASSKEY,
                                PlatformOperation.PASSKEY_UPDATE),
                        new Section(
                                "file-retention",
                                PlatformPageId.FILE_RETENTION,
                                PlatformOperation.FILE_RETENTION_UPDATE))) {
            page(
                    routes,
                    HttpMethod.GET,
                    section.page(),
                    "/api/platform/settings/" + section.id() + "/history");
            page(
                    routes,
                    HttpMethod.GET,
                    section.page(),
                    "/api/platform/settings/" + section.id() + "/history/*");
            action(
                    routes,
                    HttpMethod.POST,
                    section.page(),
                    section.update(),
                    "/api/platform/settings/" + section.id() + "/rollback");
        }
    }

    private static void page(
            List<Route> routes, HttpMethod method, PlatformPageId page, String... paths) {
        for (String path : paths) routes.add(new Route(method.name(), path, page, List.of()));
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
