package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
