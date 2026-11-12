package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PersonalFilesHttpPolicyCoverageTest {
    @Test
    void actualMappingsRetainAllTenAuthenticatedAccountEndpoints() throws Exception {
        var manifest =
                new JsonMapper()
                        .readValue(
                                Files.readString(
                                        Path.of("..", "plugins", "personal-files", "plugin.json")),
                                PluginManifest.class);
        var expected =
                Set.of(
                        "GET /api/account/files",
                        "GET /api/account/files/usage",
                        "GET /api/account/files/trash",
                        "POST /api/account/files",
                        "GET /api/account/files/{id}",
                        "GET /api/account/files/{id}/preview",
                        "GET /api/account/files/{id}/content",
                        "DELETE /api/account/files/{id}",
                        "POST /api/account/files/{id}/restore",
                        "DELETE /api/account/files/{id}/purge");
        assertEquals(
                expected,
                new HashSet<>(
                        manifest.apiRules().stream()
                                .map(rule -> rule.method() + " " + rule.path())
                                .toList()));
        for (var rule : manifest.apiRules()) {
            assertTrue(rule.authenticatedOnly());
            assertFalse(rule.publicAccess());
            assertTrue(rule.authorities().isEmpty());
            assertTrue(rule.allAuthorities().isEmpty());
        }
        var host =
                org.mockito.Mockito.mock(
                        org.springframework.web.servlet.mvc.method.annotation
                                .RequestMappingHandlerMapping.class);
        org.mockito.Mockito.when(host.getBuilderConfiguration())
                .thenReturn(
                        new org.springframework.web.servlet.mvc.method.RequestMappingInfo
                                .BuilderConfiguration());
        var actual = new HashSet<String>();
        var type = com.lumira.plugins.personalfiles.FileController.class;
        for (var method : type.getMethods()) {
            var mapping = PluginHttpMappings.mapping(type, method, host);
            if (mapping == null) continue;
            for (var path : mapping.getPatternValues())
                for (var verb : mapping.getMethodsCondition().getMethods())
                    assertTrue(actual.add(verb.name() + " " + path));
        }
        assertEquals(expected, actual);
    }
}
