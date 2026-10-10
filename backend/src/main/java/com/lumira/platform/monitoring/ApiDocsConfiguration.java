package com.lumira.platform.monitoring;

import com.lumira.platform.common.CsrfTokenContract;
import com.lumira.platform.common.PluginAuthorizationPort;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springdoc.api.AbstractOpenApiResource;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.filters.GlobalOpenApiMethodFilter;
import org.springdoc.webmvc.core.providers.SpringWebMvcProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.web.accept.ApiVersionStrategy;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Runtime-generated OpenAPI metadata for the application's Spring MVC mappings. */
@Configuration(proxyBeanMethods = false)
public class ApiDocsConfiguration {
    public static final String SESSION_SCHEME = "lumiraSession";
    public static final String CSRF_SCHEME = "csrfToken";

    /*
     * Springdoc's addRestControllers registry is static and has no removal API.
     * Register only a stable JDK type; plugin Class objects must never enter it.
     */
    static {
        AbstractOpenApiResource.addRestControllers(Object.class);
    }

    private static final Set<Route> PUBLIC_ROUTES =
            Set.of(
                    new Route(HttpMethod.GET, "/api/auth/methods"),
                    new Route(HttpMethod.GET, "/api/auth/registration/profile-schema"),
                    new Route(HttpMethod.GET, "/api/auth/second-factor/pending"),
                    new Route(HttpMethod.POST, "/api/auth/second-factor/confirm"),
                    new Route(HttpMethod.POST, "/api/auth/second-factor/cancel"),
                    new Route(HttpMethod.POST, "/api/auth/second-factor/enrollment/start"),
                    new Route(HttpMethod.POST, "/api/auth/second-factor/enrollment/confirm"),
                    new Route(HttpMethod.POST, "/api/auth/password-recovery/request"),
                    new Route(HttpMethod.POST, "/api/auth/password-recovery/confirm"),
                    new Route(HttpMethod.GET, "/api/auth/passkeys/authentication/availability"),
                    new Route(HttpMethod.POST, "/api/auth/passkeys/authentication/options"),
                    new Route(HttpMethod.POST, "/api/auth/passkeys/authentication/complete"),
                    new Route(HttpMethod.GET, "/api/auth/csrf"),
                    new Route(HttpMethod.GET, "/api/public/localization/locales"),
                    new Route(HttpMethod.GET, "/api/public/localization/bundle/{locale}"),
                    new Route(HttpMethod.GET, "/api/public/maintenance"),
                    new Route(HttpMethod.GET, "/api/public/watermark"),
                    new Route(HttpMethod.GET, "/api/public/agreements"),
                    new Route(HttpMethod.GET, "/api/public/branding"),
                    new Route(HttpMethod.GET, "/api/public/branding/assets/{id}"));

    private static final Set<HttpMethod> STATE_CHANGING_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    @Bean
    @Primary
    SpringWebMvcProvider liveSpringWebMvcProvider(Optional<ApiVersionStrategy> apiVersionStrategy) {
        return new LiveSpringWebMvcProvider(apiVersionStrategy);
    }

    @Bean
    GlobalOpenApiMethodFilter apiRestControllerMethodFilter(
            ObjectProvider<RequestMappingHandlerMapping> liveMappings) {
        return method -> isResponseControllerMethod(method, liveMappings);
    }

    private static boolean isResponseControllerMethod(
            Method method, ObjectProvider<RequestMappingHandlerMapping> liveMappings) {
        if (isResponseControllerType(method.getDeclaringClass())
                || AnnotatedElementUtils.hasAnnotation(method, ResponseBody.class)) {
            return true;
        }
        return liveMappings
                .orderedStream()
                .flatMap(mapping -> mapping.getHandlerMethods().values().stream())
                .filter(handler -> handler.getMethod().equals(method))
                .anyMatch(handler -> isResponseControllerType(handler.getBeanType()));
    }

    private static boolean isResponseControllerType(Class<?> type) {
        return AnnotatedElementUtils.hasAnnotation(type, RestController.class)
                || AnnotatedElementUtils.hasAnnotation(type, ResponseBody.class);
    }

    @Bean
    OpenAPI platformOpenAPI() {
        String description =
                """
                OpenAPI is generated at request time from currently registered Spring MVC mappings under /api.
                Protected routes use the LUMIRA_NEXT_SESSION session cookie; login is handled by the platform's authentication filter.
                The GET /api/auth/csrf endpoint returns the authoritative CSRF headerName and token. The current header is X-CSRF-TOKEN and is required on state-changing requests, including public POST requests.
                Each route also enforces its server-side authority and data scope. Those complete route-level ACL rules are not represented by this schema; a listed route is not a grant of access.
                No request credentials or sample secret values are embedded in this document.
                """;
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Lumira Next API")
                                .version("runtime-generated")
                                .description(description))
                .servers(List.of(new Server().url("/").description("Current application origin")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        SESSION_SCHEME,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.COOKIE)
                                                .name("LUMIRA_NEXT_SESSION")
                                                .description(
                                                        "HttpOnly session cookie established by platform login."))
                                .addSecuritySchemes(
                                        CSRF_SCHEME,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name(CsrfTokenContract.HEADER_NAME)
                                                .description(
                                                        "For state-changing requests, send the token returned by GET /api/auth/csrf.")))
                .addSecurityItem(new SecurityRequirement().addList(SESSION_SCHEME));
    }

    @Bean
    OpenApiCustomizer apiDocsSecurityCustomizer(
            ObjectProvider<PluginAuthorizationPort> pluginAuthorizations) {
        return openAPI -> {
            if (openAPI.getPaths() == null) {
                return;
            }
            PluginAuthorizationPort pluginAuthorization = pluginAuthorizations.getIfAvailable();
            for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
                for (Map.Entry<PathItem.HttpMethod, io.swagger.v3.oas.models.Operation>
                        operationEntry : pathEntry.getValue().readOperationsMap().entrySet()) {
                    var operation = operationEntry.getValue();
                    var method = HttpMethod.valueOf(operationEntry.getKey().name());
                    if (PUBLIC_ROUTES.contains(new Route(method, pathEntry.getKey()))) {
                        operation.setSecurity(
                                STATE_CHANGING_METHODS.contains(method)
                                        ? List.of(new SecurityRequirement().addList(CSRF_SCHEME))
                                        : List.of());
                    } else if (method == HttpMethod.GET
                            && isPublicPluginRoute(
                                    pluginAuthorization, method, pathEntry.getKey())) {
                        operation.setSecurity(List.of());
                    } else if (STATE_CHANGING_METHODS.contains(method)) {
                        operation.setSecurity(
                                List.of(
                                        new SecurityRequirement()
                                                .addList(SESSION_SCHEME)
                                                .addList(CSRF_SCHEME)));
                    }
                }
            }
        };
    }

    private boolean isPublicPluginRoute(
            PluginAuthorizationPort pluginAuthorization, HttpMethod method, String path) {
        if (pluginAuthorization == null) {
            return false;
        }
        try {
            return pluginAuthorization.authorize(method.name(), path, Set.of(), false);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private record Route(HttpMethod method, String path) {}

    /** Reads the live host MVC mapping table without retaining plugin handlers between reads. */
    private static final class LiveSpringWebMvcProvider extends SpringWebMvcProvider {
        private LiveSpringWebMvcProvider(Optional<ApiVersionStrategy> apiVersionStrategy) {
            super(apiVersionStrategy);
        }

        @Override
        public Map<RequestMappingInfo, HandlerMethod> getHandlerMethods() {
            Map<RequestMappingInfo, HandlerMethod> snapshot = new LinkedHashMap<>();
            applicationContext.getBeansOfType(RequestMappingHandlerMapping.class).values().stream()
                    .map(RequestMappingHandlerMapping::getHandlerMethods)
                    .forEach(current -> current.forEach(snapshot::putIfAbsent));
            return snapshot;
        }
    }
}
