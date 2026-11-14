package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PluginPageReadManifestTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final String ACCESS = "platform:shop:access";
    private static final String READ = "platform:shop:read";
    private static final String OTHER = "platform:shop:other-access";

    @Test
    void pageReadFlagDefaultsToFalseAndExistingConstructorRemainsCompatible() {
        var decoded =
                mapper.readValue(
                        """
                {"method":"GET","path":"/api/shop","authorities":[],
                 "allAuthorities":["platform:shop:access"],"global":true,"publicAccess":false}
                """,
                        PluginManifest.Rule.class);
        assertFalse(decoded.pageReadOnly());
        assertThrows(IllegalArgumentException.class, () -> manifest("shop", 2, decoded));
        var legacy =
                new PluginManifest.Rule(
                        "GET",
                        "/api/shop",
                        List.of(),
                        true,
                        false,
                        false,
                        false,
                        false,
                        List.of(ACCESS, READ),
                        List.of());
        assertFalse(legacy.pageReadOnly());
        assertDoesNotThrow(() -> manifest("shop", 2, legacy));
    }

    @Test
    void explicitReadRequiresExactlyItsPageGateAndMatchingScope() {
        assertDoesNotThrow(() -> manifest("shop", 2, rule("GET", "/api/shop", ACCESS)));
        assertThrows(
                IllegalArgumentException.class,
                () -> manifest("shop", 1, rule("GET", "/api/shop", ACCESS)));
        for (var gates :
                List.of(List.<String>of(), List.of(ACCESS, READ), List.of(ACCESS, OTHER))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            manifest(
                                    "shop",
                                    2,
                                    new PluginManifest.Rule(
                                            "GET",
                                            "/api/shop",
                                            List.of(),
                                            true,
                                            false,
                                            false,
                                            false,
                                            false,
                                            gates,
                                            List.of(),
                                            true)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifest(
                                "shop",
                                2,
                                new PluginManifest.Rule(
                                        "GET",
                                        "/api/shop",
                                        List.of(READ),
                                        true,
                                        false,
                                        false,
                                        false,
                                        false,
                                        List.of(ACCESS),
                                        List.of(),
                                        true)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifest(
                                "shop",
                                2,
                                new PluginManifest.Rule(
                                        "GET",
                                        "/api/shop",
                                        List.of(),
                                        true,
                                        false,
                                        false,
                                        false,
                                        false,
                                        List.of(ACCESS),
                                        List.of(ACCESS),
                                        true)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manifest(
                                "shop",
                                2,
                                new PluginManifest.Rule(
                                        "GET",
                                        "/api/shop",
                                        List.of(),
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        List.of(ACCESS),
                                        List.of(),
                                        true)));
        assertThrows(
                IllegalArgumentException.class,
                () -> manifest("shop", 2, rule("GET", "/api/shop", READ)));
    }

    @Test
    void mutationsMixedMethodsAndUnapprovedReadPostPathsCannotClaimPageOnlyRead() {
        for (String method :
                List.of("POST", "PUT", "PATCH", "DELETE", "GET,POST", "GET POST", "*")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manifest("shop", 2, rule(method, "/api/shop", ACCESS)),
                    method);
        }
        for (String path :
                List.of(
                        "/api/platform/sensitive-words",
                        "/api/platform/sensitive-words/import",
                        "/api/platform/sensitive-words/*",
                        "/api/platform/sensitive-words/check/extra",
                        "/api/platform/sensitive-policies/preview/*")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            manifest(
                                    "sensitive-words",
                                    2,
                                    rule("POST", path, "platform:sensitive-words:access")),
                    path);
        }
        for (String path :
                List.of(
                        "/api/platform/sensitive-words/check",
                        "/api/platform/sensitive-policies/preview")) {
            assertDoesNotThrow(
                    () ->
                            manifest(
                                    "sensitive-words",
                                    2,
                                    rule("POST", path, "platform:sensitive-words:access")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manifest("shop", 2, rule("POST", path, ACCESS)));
        }
    }

    @Test
    void pageReadFlagCannotCombineWithAuthorizationBypassesOrPreviewSessionFlag() {
        for (int flag = 0; flag < 4; flag++) {
            var rule =
                    new PluginManifest.Rule(
                            "GET",
                            "/api/shop",
                            List.of(),
                            true,
                            flag == 0,
                            flag == 1,
                            flag == 2,
                            flag == 3,
                            List.of(ACCESS),
                            List.of(),
                            true);
            assertThrows(IllegalArgumentException.class, () -> manifest("shop", 2, rule));
        }
        for (boolean publicAccess : List.of(true, false)) {
            var rule =
                    new PluginManifest.Rule(
                            "GET",
                            "/api/shop",
                            List.of(),
                            false,
                            publicAccess,
                            !publicAccess,
                            false,
                            false,
                            List.of(),
                            List.of(),
                            true);
            assertThrows(IllegalArgumentException.class, () -> manifest("shop", 2, rule));
        }
    }

    @Test
    void everyBundledManifestUsedByTheApprovedGraphParsesThroughTheRealValidator()
            throws Exception {
        Path root = Path.of("plugins");
        if (!Files.isDirectory(root)) root = Path.of("..", "plugins");
        try (var directories = Files.list(root)) {
            var manifests =
                    directories
                            .map(directory -> directory.resolve("plugin.json"))
                            .filter(Files::isRegularFile)
                            .sorted()
                            .toList();
            assertFalse(manifests.isEmpty());
            var ids = new java.util.HashSet<String>();
            for (var path : manifests) {
                var manifest =
                        assertDoesNotThrow(
                                () ->
                                        mapper.readValue(
                                                Files.readString(path), PluginManifest.class),
                                path.toString());
                assertTrue(ids.add(manifest.id()), path.toString());
                for (var rule : manifest.apiRules()) {
                    if (rule.pageReadOnly()) {
                        assertFalse(rule.previewReadOnly());
                        assertEquals(1, rule.allAuthorities().size());
                    }
                }
            }
            assertTrue(
                    ids.containsAll(
                            Set.of(
                                    "feedback",
                                    "localization",
                                    "sensitive-words",
                                    "mfa",
                                    "payment-core",
                                    "wechat-pay",
                                    "alipay-pay",
                                    "diffuse-avatar")));
        }
    }

    private PluginManifest.Rule rule(String method, String path, String access) {
        return new PluginManifest.Rule(
                method,
                path,
                List.of(),
                true,
                false,
                false,
                false,
                false,
                List.of(access),
                List.of(),
                true);
    }

    private PluginManifest manifest(String id, int formatVersion, PluginManifest.Rule rule) {
        var access = "platform:" + id + ":access";
        var read = "platform:" + id + ":read";
        var other = "platform:" + id + ":other-access";
        var otherRead = "platform:" + id + ":other-read";
        var routes =
                List.of(
                        new PluginManifest.Route(
                                "main",
                                "Main",
                                access,
                                Map.of("read", read),
                                "administration",
                                1,
                                true),
                        new PluginManifest.Route(
                                "other",
                                "Other",
                                other,
                                Map.of("read", otherRead),
                                "administration",
                                2,
                                true));
        return new PluginManifest(
                formatVersion,
                id,
                "Test",
                "1.0.0",
                "1",
                "com.example.TestPlugin",
                "backend/plugin.jar",
                "frontend/plugin.js",
                List.of(),
                List.of(access, read, other, otherRead),
                routes,
                List.of(rule),
                Map.of(),
                List.of());
    }
}
