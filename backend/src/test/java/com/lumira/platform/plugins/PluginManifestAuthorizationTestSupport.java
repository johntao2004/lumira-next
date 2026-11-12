package com.lumira.platform.plugins;

import com.lumira.platform.common.PluginAuthorizationPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;

/** MVC fixtures use the installed package's real declaration and the production scoped matcher. */
public final class PluginManifestAuthorizationTestSupport {
    public static PluginAuthorizationPort forPlugins(String... ids) {
        var indexes = new ArrayList<PluginHttpRuleIndex>();
        var mapper = new tools.jackson.databind.json.JsonMapper();
        try {
            for (String id : ids) {
                var manifest =
                        mapper.readValue(
                                Files.readString(Path.of("..", "plugins", id, "plugin.json")),
                                PluginManifest.class);
                indexes.add(new PluginHttpRuleIndex(manifest.apiRules()));
            }
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
        return (method, path, authorities, authenticated) -> {
            var snapshot = AuthenticationRequestContext.current();
            var global =
                    snapshot == null || snapshot.authorization() == null
                            ? Set.<String>of()
                            : snapshot.authorization().effectiveGlobalAuthorities();
            var request = PluginHttpRuleIndex.requestPath(path);
            for (var index : indexes) {
                var rule = index.first(method, request);
                if (rule != null)
                    return rule.publicAccess()
                            || authenticated
                                    && (rule.authenticatedOnly()
                                            || PluginRuntime.authorityRuleMatches(
                                                    rule, authorities, global));
            }
            return false;
        };
    }

    private PluginManifestAuthorizationTestSupport() {}
}
