package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Guards the hand-maintained route table in {@link SecurityConfiguration}. Every controller
 * endpoint must be claimed by an explicit rule; otherwise it silently falls through to {@code
 * denyAll()} and is unreachable, and an endpoint that is publicly reachable must be a deliberate,
 * reviewed entry in {@link #PUBLIC_PREFIXES}.
 */
class RoutePermissionCoverageTest {
    private static final Path SECURITY_SOURCE =
            Path.of("src/main/java/com/lumira/platform/identity/SecurityConfiguration.java");

    /** Public means unauthenticated. Adding an entry here is a security decision. */
    private static final List<String> PUBLIC_PREFIXES =
            List.of("/api/public/", "/api/auth/", "/actuator/");

    private static final Pattern RULE =
            Pattern.compile(
                    "requestMatchers\\(\\s*(?:org\\.springframework\\.http\\.HttpMethod\\.(\\w+)\\s*,\\s*)?((?:\"[^\"]+\"\\s*,?\\s*)+)\\)\\s*\\.(\\w+)");

    private record Rule(String method, PathPattern[] patterns, String kind) {
        boolean matches(String httpMethod, String path) {
            if (method != null && !method.equals(httpMethod)) return false;
            var container = org.springframework.http.server.PathContainer.parsePath(path);
            for (PathPattern pattern : patterns) if (pattern.matches(container)) return true;
            return false;
        }
    }

    private record Endpoint(String method, String path, String source) {}

    @Test
    void everyControllerEndpointIsClaimedByAnExplicitRule() throws Exception {
        List<Rule> rules = parseRules();
        assertTrue(rules.size() > 50, "Security rule table could not be parsed: " + rules.size());
        List<String> unclaimed = new ArrayList<>();
        List<String> unexpectedlyPublic = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            Rule first = null;
            for (Rule rule : rules) {
                if (rule.matches(endpoint.method(), endpoint.path())) {
                    first = rule;
                    break;
                }
            }
            if (first == null) {
                unclaimed.add(
                        endpoint.method() + " " + endpoint.path() + "  <- " + endpoint.source());
            } else if (first.kind().equals("permitAll")
                    && PUBLIC_PREFIXES.stream().noneMatch(endpoint.path()::startsWith)
                    && !ALLOWED_PUBLIC_OUTSIDE_PREFIXES.contains(
                            endpoint.method() + " " + endpoint.path())) {
                unexpectedlyPublic.add(
                        endpoint.method() + " " + endpoint.path() + "  <- " + endpoint.source());
            }
        }
        assertTrue(
                unclaimed.isEmpty(),
                "Endpoints fall through to denyAll (no rule claims them):\n"
                        + String.join("\n", unclaimed));
        assertTrue(
                unexpectedlyPublic.isEmpty(),
                "Endpoints are public but not in the reviewed allowlist:\n"
                        + String.join("\n", unexpectedlyPublic));
    }

    @Test
    void registrationPolicyRoutesRequireTheSettingsReadAndWriteAuthorities() throws Exception {
        String source = Files.readString(SECURITY_SOURCE);
        String getRule =
                "requestMatchers(\n"
                        + "                                                org.springframework.http.HttpMethod.GET,\n"
                        + "                                                \"/api/platform/settings/registration\",\n"
                        + "                                                \"/api/platform/settings/registration/roles\")\n"
                        + "                                        .hasAuthority(\"platform:settings:read\")";
        String putRule =
                "requestMatchers(\n"
                        + "                                                org.springframework.http.HttpMethod.PUT,\n"
                        + "                                                \"/api/platform/settings/registration\")\n"
                        + "                                        .hasAuthority(\"platform:settings:write\")";
        assertTrue(
                source.contains(getRule),
                "Registration policy GET routes must require settings:read");
        assertTrue(
                source.contains(putRule),
                "Registration policy PUT route must require settings:write");
    }

    @Test
    void notificationRoleSelectorRequiresGlobalNotificationWritePermission() throws Exception {
        String source = Files.readString(SECURITY_SOURCE);
        Pattern roleOptionsWriteRule =
                Pattern.compile(
                        "requestMatchers\\(\\s*org\\.springframework\\.http\\.HttpMethod\\.GET,\\s*"
                                + "\"/api/platform/notifications/roles\"\\)\\s*"
                                + "\\.hasAuthority\\(\"platform:notifications:write\"\\)");

        assertTrue(
                roleOptionsWriteRule.matcher(source).find(),
                "Notification role options must use notification write access, not notification read access");
    }

    @Test
    void notificationRecipientLookupRequiresGlobalNotificationWritePermission() throws Exception {
        String source = Files.readString(SECURITY_SOURCE);
        Pattern recipientLookupWriteRule =
                Pattern.compile(
                        "requestMatchers\\(\\s*org\\.springframework\\.http\\.HttpMethod\\.GET,\\s*"
                                + "\"/api/platform/notifications/recipients\",\\s*"
                                + "\"/api/platform/notifications/recipients/\\*\"\\)\\s*"
                                + "\\.hasAuthority\\(\"platform:notifications:write\"\\)");

        assertTrue(
                recipientLookupWriteRule.matcher(source).find(),
                "Notification recipient lookup must require notification write, not users read");
    }

    @Test
    void sensitiveAuthenticationAndRolePreviewRoutesRequireAuthentication() throws Exception {
        String source = Files.readString(SECURITY_SOURCE);
        for (String route :
                List.of(
                        "\"/api/account/reauthentication/code\"",
                        "\"/api/account/reauthentication/passkey/options\"",
                        "\"/api/account/reauthentication/passkey/complete\"",
                        "\"/api/account/role-preview\"",
                        "\"/api/account/role-preview/roles\"",
                        "\"/api/account/role-preview/resource-tickets/*\"",
                        "\"/api/account/role-preview/resource-tickets\"",
                        "\"/api/platform/role-preview/bootstrap\"")) {
            assertTrue(
                    source.contains(route), "Missing exact authenticated route rule for " + route);
        }
        assertTrue(
                source.contains(
                        "/api/account/role-preview/resource-tickets/*\")\n                                        .authenticated()"),
                "Role preview ticket reads must require an authenticated user");
        assertTrue(
                source.contains(
                        "/api/platform/role-preview/bootstrap\")\n                                        .authenticated()"),
                "Role preview bootstrap must require an authenticated operator");
    }

    @Test
    void restrictedSecondFactorRecoveryRoutesArePublicOnlyForPost() throws Exception {
        List<Rule> rules = parseRules();
        for (String path :
                List.of(
                        "/api/auth/second-factor/recovery/request",
                        "/api/auth/second-factor/recovery/verify")) {
            Rule postRule =
                    rules.stream()
                            .filter(rule -> rule.matches("POST", path))
                            .findFirst()
                            .orElse(null);
            assertTrue(
                    postRule != null && postRule.kind().equals("permitAll"),
                    "Pending-session recovery must be reachable by POST: " + path);
            assertTrue(
                    rules.stream()
                            .noneMatch(
                                    rule ->
                                            rule.kind().equals("permitAll")
                                                    && rule.matches("GET", path)),
                    "Recovery endpoint must not be exposed as an anonymous GET: " + path);
        }
    }

    /** Reviewed exceptions: public endpoints that do not live under a public prefix. */
    private static final Set<String> ALLOWED_PUBLIC_OUTSIDE_PREFIXES =
            Set.of(
                    "POST /api/auth/password-recovery/request",
                    "POST /api/auth/password-recovery/confirm");

    private static List<Rule> parseRules() throws Exception {
        String source = Files.readString(SECURITY_SOURCE);
        source = source.substring(0, source.indexOf("anyRequest()"));
        var parser = PathPatternParser.defaultInstance;
        var rules = new ArrayList<Rule>();
        var matcher = RULE.matcher(source);
        while (matcher.find()) {
            List<PathPattern> patterns = new ArrayList<>();
            var literal = Pattern.compile("\"([^\"]+)\"").matcher(matcher.group(2));
            while (literal.find()) patterns.add(parser.parse(literal.group(1)));
            String method = matcher.group(1);
            rules.add(new Rule(method, patterns.toArray(PathPattern[]::new), matcher.group(3)));
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
            for (Method method : type.getDeclaredMethods()) {
                if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class)
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
        assertTrue(result.size() > 100, "Controller scan looks incomplete: " + result.size());
        return result;
    }

    /** Exposes the protected mapping resolution so the real Spring rules decide the paths. */
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
