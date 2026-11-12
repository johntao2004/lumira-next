package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.PlatformPageId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pre-extraction HTTP matrix independently freezes page/action scopes for all directory APIs.
 */
class DirectoryHttpPolicyCoverageTest {
    private record Case(
            String pageId,
            String accessPermission,
            String accessScope,
            String operationPermission,
            String operationScope,
            String method,
            String path,
            List<String> allowedOperationPermissions,
            List<String> requiredPages) {}

    @Test
    void everyMigratedRouteAndAlternativeRetainsItsOriginalPageAndScopeRequirements()
            throws Exception {
        var mapper = new JsonMapper();
        var root = Path.of("..", "plugins", "directory");
        var manifest =
                mapper.readValue(
                        Files.readString(root.resolve("plugin.json")), PluginManifest.class);
        var cases =
                mapper.readerFor(new TypeReference<List<Case>>() {})
                        .without(
                                tools.jackson.databind.DeserializationFeature
                                        .FAIL_ON_UNKNOWN_PROPERTIES)
                        .<List<Case>>readValue(
                                Files.readString(root.resolve("backend/src/test/http-cases.json")));
        var index = new PluginHttpRuleIndex(manifest.apiRules());
        var covered = new HashSet<PluginManifest.Rule>();
        var alternatives = new HashSet<String>();
        for (var fixture : cases) {
            String path = "/api" + fixture.path().split("\\?", 2)[0];
            var rule = index.first(fixture.method(), PluginHttpRuleIndex.requestPath(path));
            assertNotNull(rule, fixture.method() + " " + path);
            covered.add(rule);
            assertEquals(fixture.allowedOperationPermissions(), rule.authorities(), path);
            var pages =
                    (fixture.requiredPages() == null
                                    ? List.of(fixture.pageId())
                                    : fixture.requiredPages())
                            .stream()
                                    .map(
                                            id ->
                                                    Arrays.stream(PlatformPageId.values())
                                                            .filter(page -> page.id().equals(id))
                                                            .findFirst()
                                                            .orElseThrow())
                                    .toList();
            assertEquals(
                    pages.stream().map(PlatformPageId::accessPermission).toList(),
                    rule.allAuthorities(),
                    path);
            assertEquals(
                    fixture.accessScope().toLowerCase(Locale.ROOT),
                    rule.authorityScopes().get(fixture.accessPermission()),
                    path);
            var effective = new HashSet<String>();
            var global = new HashSet<String>();
            for (var page : pages) {
                effective.add(page.accessPermission());
                if (page.scope().name().equals("GLOBAL")) global.add(page.accessPermission());
            }
            if (fixture.operationPermission() != null) {
                assertEquals(
                        fixture.operationScope().toLowerCase(Locale.ROOT),
                        rule.authorityScopes().get(fixture.operationPermission()),
                        path);
                effective.add(fixture.operationPermission());
                if (fixture.operationScope().equals("GLOBAL"))
                    global.add(fixture.operationPermission());
                alternatives.add(
                        rule.method() + " " + rule.path() + " " + fixture.operationPermission());
            }
            assertTrue(
                    PluginRuntime.authorityRuleMatches(rule, effective, global),
                    "Positive " + path);
            assertFalse(
                    PluginRuntime.authorityRuleMatches(rule, Set.of(), Set.of()), "Empty " + path);
            for (var required : List.copyOf(effective)) {
                var without = new HashSet<>(effective);
                without.remove(required);
                assertFalse(
                        PluginRuntime.authorityRuleMatches(rule, without, global),
                        "Missing " + required + " " + path);
                if (global.contains(required)) {
                    var scopedOnly = new HashSet<>(global);
                    scopedOnly.remove(required);
                    assertFalse(
                            PluginRuntime.authorityRuleMatches(rule, effective, scopedOnly),
                            "Wrong scope " + required + " " + path);
                }
            }
        }
        assertEquals(
                new HashSet<>(manifest.apiRules()),
                covered,
                "Every manifest route needs an independent HTTP fixture");
        for (var rule : manifest.apiRules())
            for (var action : rule.authorities())
                assertTrue(
                        alternatives.contains(rule.method() + " " + rule.path() + " " + action),
                        "Uncovered OR action " + action);
    }
}
