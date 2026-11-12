package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.json.JsonMapper;

class PluginHttpRuleIndexTest {
    @Test
    void compiledDirectoryRulesMatchLegacyOrderForLiteralAndVariableRoutes() throws Exception {
        var manifest =
                new JsonMapper()
                        .readValue(
                                Files.readString(
                                        Path.of("..", "plugins", "directory", "plugin.json")),
                                PluginManifest.class);
        var index = new PluginHttpRuleIndex(manifest.apiRules());
        var paths = new ArrayList<String>();
        for (var rule : manifest.apiRules()) {
            var concrete =
                    rule.path().replaceAll("\\{[^}]+\\}", "00000000-0000-0000-0000-000000000001");
            paths.addAll(
                    List.of(
                            concrete,
                            concrete + "/",
                            concrete + "/extra",
                            concrete.replace("/api/", "/API/")));
        }
        paths.addAll(
                List.of(
                        "/api/platform/users/export",
                        "/api/platform/users/a%2Fb",
                        "/api/platform/users/x;v=1",
                        "/unowned"));
        for (var method : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
            for (var path : paths) {
                var request = PathContainer.parsePath(path);
                var expected =
                        manifest.apiRules().stream()
                                .filter(
                                        rule ->
                                                rule.method().equals(method)
                                                        && PathPatternParser.defaultInstance
                                                                .parse(rule.path())
                                                                .matches(request))
                                .findFirst()
                                .orElse(null);
                assertSame(expected, index.first(method, request), method + " " + path);
                assertEquals(
                        manifest.apiRules().stream()
                                .anyMatch(
                                        rule ->
                                                rule.method().equals(method)
                                                        && rule.previewReadOnly()
                                                        && PathPatternParser.defaultInstance
                                                                .parse(rule.path())
                                                                .matches(request)),
                        index.any(method, request, PluginManifest.Rule::previewReadOnly));
            }
        }
    }

    @Test
    void immutableSnapshotsCanBeReadConcurrentlyWithoutLeakingReplacedRules() throws Exception {
        var oldRule = rule("/api/test/{id}");
        var newRule = rule("/api/test/literal");
        var source = new ArrayList<>(List.of(oldRule));
        var oldIndex = new PluginHttpRuleIndex(source);
        source.clear();
        var replacement = new PluginHttpRuleIndex(List.of(newRule));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var checks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 1000; i++)
                checks.add(
                        workers.submit(
                                () -> {
                                    var request = PathContainer.parsePath("/api/test/literal");
                                    assertSame(oldRule, oldIndex.first("GET", request));
                                    assertSame(newRule, replacement.first("GET", request));
                                    assertNull(replacement.first("DELETE", request));
                                    assertNull(
                                            replacement.first(
                                                    "GET",
                                                    PathContainer.parsePath("/api/test/other")));
                                }));
            for (var check : checks) check.get();
        }
    }

    @Test
    void overlappingRulesKeepManifestPrecedenceAndPreviewFiltering() {
        var broad = rule("/api/test/{id}");
        var literal = rule("/api/test/literal");
        var index = new PluginHttpRuleIndex(List.of(broad, literal));
        var request = PathContainer.parsePath("/api/test/literal");
        assertSame(broad, index.first("GET", request));
        assertTrue(index.any("GET", request, candidate -> candidate == literal));
        assertFalse(index.any("POST", request, candidate -> true));
    }

    @Test
    void malformedRequestPathsDenyWithoutThrowing() {
        var index = new PluginHttpRuleIndex(List.of(rule("/api/test/{id}")));
        for (String path : java.util.Arrays.asList(null, "/api/test/%zz", "/api/test/%")) {
            assertNull(index.first("GET", PluginHttpRuleIndex.requestPath(path)));
            assertFalse(index.any("GET", PluginHttpRuleIndex.requestPath(path), candidate -> true));
        }
    }

    private static PluginManifest.Rule rule(String path) {
        return new PluginManifest.Rule("GET", path, List.of("read"), false, false);
    }
}
