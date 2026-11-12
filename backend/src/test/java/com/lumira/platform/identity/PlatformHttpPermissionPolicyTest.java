package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class PlatformHttpPermissionPolicyTest {
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID ACTOR_ID = UUID.fromString("f9b061a0-5423-4af7-ad1a-ff8b3dc99274");

    @Test
    void ownerPageAloneAllowsReadsButDoesNotAuthorizeAnAction() {
        assertTrue(
                decide(
                        "GET",
                        "/api/platform/users/" + USER_ID,
                        Set.of("platform:users:access"),
                        Set.of()));
        assertFalse(
                decide(
                        "PUT",
                        "/api/platform/users/" + USER_ID,
                        Set.of("platform:users:access"),
                        Set.of()));
    }

    @Test
    void actionAloneAndUnrelatedSiblingActionCannotSubstituteForPageAndOwnAction() {
        assertFalse(
                decide(
                        "PUT",
                        "/api/platform/users/" + USER_ID,
                        Set.of("platform:users:update"),
                        Set.of("platform:users:update")));
        assertFalse(
                decide(
                        "PUT",
                        "/api/platform/users/" + USER_ID,
                        Set.of("platform:users:access", "platform:users:delete"),
                        Set.of("platform:users:delete")));
        assertFalse(
                decide(
                        "PUT",
                        "/api/platform/users/" + USER_ID,
                        Set.of("platform:users:access", "platform:settings:update"),
                        Set.of("platform:settings:update")));
    }

    @Test
    void globalPagesAndActionsNeedGlobalEffectiveGrants() {
        assertFalse(
                decide("GET", "/api/platform/roles", Set.of("platform:roles:access"), Set.of()));
        assertTrue(
                decide(
                        "GET",
                        "/api/platform/roles",
                        Set.of("platform:roles:access"),
                        Set.of("platform:roles:access")));

        assertFalse(
                decide(
                        "POST",
                        "/api/platform/users",
                        Set.of("platform:users:access", "platform:users:create"),
                        Set.of()));
        assertTrue(
                decide(
                        "POST",
                        "/api/platform/users",
                        Set.of("platform:users:access", "platform:users:create"),
                        Set.of("platform:users:create")));
    }

    @Test
    void scopedAndGlobalActionsAreCheckedAgainstTheirDeclaredScope() {
        assertTrue(
                decide(
                        "PUT",
                        "/api/platform/users/" + USER_ID,
                        Set.of("platform:users:access", "platform:users:update"),
                        Set.of()));
        assertTrue(
                decide(
                        "POST",
                        "/api/platform/ai/actions/" + USER_ID + "/confirm",
                        Set.of("platform:ai-console:access", "platform:ai-console:update-user"),
                        Set.of()));
        assertFalse(
                decide(
                        "POST",
                        "/api/platform/ai/actions/" + USER_ID + "/confirm",
                        Set.of("platform:ai-console:access", "platform:ai-console:create-user"),
                        Set.of()));

        assertTrue(
                decide(
                        "POST",
                        "/api/platform/organizations",
                        Set.of("platform:organizations:access", "platform:organizations:create"),
                        Set.of("platform:organizations:create")));
        assertFalse(
                decide(
                        "POST",
                        "/api/platform/organizations",
                        Set.of("platform:organizations:access", "platform:organizations:create"),
                        Set.of()));
    }

    @Test
    void aiChatRequiresTheConsolePageAndAtLeastOneConsoleCapability() {
        String path = "/api/platform/ai/chat";
        assertFalse(decide("POST", path, Set.of("platform:ai-console:access"), Set.of()));
        assertFalse(
                decide(
                        "POST",
                        path,
                        Set.of("platform:ai-console:access", "platform:ai-models:test"),
                        Set.of("platform:ai-models:test")));
        assertTrue(
                decide(
                        "POST",
                        path,
                        Set.of("platform:ai-console:access", "platform:ai-console:read-users"),
                        Set.of()));
        assertTrue(
                decide(
                        "POST",
                        path,
                        Set.of("platform:ai-console:access", "platform:ai-console:create-user"),
                        Set.of("platform:ai-console:create-user")));
        assertTrue(
                decide(
                        "POST",
                        path,
                        Set.of("platform:ai-console:access", "platform:ai-console:update-user"),
                        Set.of()));
        assertTrue(
                decide(
                        "POST",
                        path,
                        Set.of("platform:ai-console:access", "platform:ai-console:delete-user"),
                        Set.of()));
    }

    @Test
    void auxiliaryReadRoutesAcceptOnlyTheirOwnerPageAndDeclaredLookupOperations() {
        String userRoles = "/api/platform/users/" + USER_ID + "/roles";
        assertTrue(
                decide(
                        "GET",
                        userRoles,
                        Set.of("platform:users:access", "platform:users:read-roles"),
                        Set.of("platform:users:access", "platform:users:read-roles")));
        assertTrue(
                decide(
                        "GET",
                        userRoles,
                        Set.of("platform:users:access", "platform:users:assign-roles"),
                        Set.of("platform:users:access")));
        assertFalse(
                decide(
                        "GET",
                        userRoles,
                        Set.of("platform:users:access", "platform:users:update"),
                        Set.of("platform:users:access")));

        assertFalse(
                decide(
                        "GET",
                        "/api/platform/settings/profile-fields/dictionaries",
                        Set.of("platform:profile-fields:access", "platform:profile-fields:update"),
                        Set.of(
                                "platform:profile-fields:access",
                                "platform:profile-fields:update")));
        assertTrue(
                decide(
                        "GET",
                        "/api/platform/settings/profile-fields/dictionaries",
                        Set.of(
                                "platform:profile-fields:access",
                                "platform:profile-fields:read-dictionaries"),
                        Set.of(
                                "platform:profile-fields:access",
                                "platform:profile-fields:read-dictionaries")));
    }

    @Test
    void userRoleOptionsRequireUsersPageAndLookupGrantWithoutOpeningRoleManagement() {
        String options = "/api/platform/users/" + USER_ID + "/role-options";
        Set<String> readGrants = Set.of("platform:users:access", "platform:users:read-roles");
        assertTrue(decide("GET", options, readGrants, Set.of("platform:users:read-roles")));
        assertTrue(
                decide(
                        "GET",
                        options,
                        Set.of("platform:users:access", "platform:users:assign-roles"),
                        Set.of()));
        assertFalse(decide("GET", options, Set.of("platform:users:access"), Set.of()));
        assertFalse(
                decide(
                        "GET",
                        options,
                        Set.of("platform:users:read-roles"),
                        Set.of("platform:users:read-roles")));
        assertFalse(
                decide(
                        "GET",
                        options,
                        readGrants,
                        Set.of())); // USER_READ_ROLES is globally scoped.
        assertFalse(
                decide(
                        "GET",
                        "/api/platform/roles",
                        readGrants,
                        Set.of("platform:users:read-roles")));

        Set<String> userRoleGrants =
                Set.of(
                        "platform:users:access",
                        "platform:users:read-roles",
                        "platform:users:assign-roles",
                        "platform:roles:create",
                        "platform:roles:update",
                        "platform:roles:delete",
                        "platform:roles:update-authentication-policy");
        Set<String> userRoleGlobalGrants =
                Set.of(
                        "platform:users:read-roles",
                        "platform:roles:create",
                        "platform:roles:update",
                        "platform:roles:delete",
                        "platform:roles:update-authentication-policy");
        assertFalse(decide("GET", "/api/platform/roles", userRoleGrants, userRoleGlobalGrants));
        assertFalse(
                decide(
                        "GET",
                        "/api/platform/roles/" + USER_ID,
                        userRoleGrants,
                        userRoleGlobalGrants));
        assertFalse(decide("POST", "/api/platform/roles", userRoleGrants, userRoleGlobalGrants));
        assertFalse(
                decide(
                        "PUT",
                        "/api/platform/roles/" + USER_ID,
                        userRoleGrants,
                        userRoleGlobalGrants));
        assertFalse(
                decide(
                        "DELETE",
                        "/api/platform/roles/" + USER_ID,
                        userRoleGrants,
                        userRoleGlobalGrants));
        assertFalse(
                decide(
                        "PUT",
                        "/api/platform/roles/" + USER_ID + "/authentication-policy",
                        userRoleGrants,
                        userRoleGlobalGrants));
    }

    @Test
    void aiConsoleProviderMetadataIsIsolatedFromAiModelManagement() {
        assertTrue(
                decide(
                        "GET",
                        "/api/platform/ai/console/providers",
                        Set.of("platform:ai-console:access"),
                        Set.of()));
        assertTrue(
                decide(
                        "GET",
                        "/api/platform/ai/console/providers/" + USER_ID + "/models",
                        Set.of("platform:ai-console:access"),
                        Set.of()));
        assertFalse(
                decide(
                        "GET",
                        "/api/platform/ai/console/providers",
                        Set.of("platform:ai-models:access"),
                        Set.of("platform:ai-models:access")));
    }

    @Test
    void aiCapabilitiesBelongToTheScopedConsolePageWithoutAnActionGrant() throws Exception {
        String path = "/api/platform/ai/capabilities";
        var route = PlatformHttpPermissionPolicy.routeFor("GET", path);

        assertEquals(null, route, "AI business is no longer a host policy");
        var manifest =
                new tools.jackson.databind.json.JsonMapper()
                        .readValue(
                                java.nio.file.Files.readString(
                                        java.nio.file.Path.of(
                                                "..", "plugins", "ai", "plugin.json")),
                                com.lumira.platform.plugins.PluginManifest.class);
        var rule =
                manifest.apiRules().stream()
                        .filter(
                                candidate ->
                                        candidate.method().equals("GET")
                                                && candidate.path().equals(path))
                        .findFirst()
                        .orElseThrow();
        assertEquals(List.of("platform:ai-console:access"), rule.allAuthorities());
        assertEquals("scoped", rule.authorityScopes().get("platform:ai-console:access"));
        assertTrue(rule.authorities().isEmpty());
        assertFalse(
                decide(
                        "GET",
                        path,
                        Set.of("platform:ai-models:access"),
                        Set.of("platform:ai-models:access")));
        assertTrue(decide("GET", path, Set.of("platform:ai-console:access"), Set.of()));
    }

    @Test
    void typedAuthorizationSnapshotIsRequiredAndUnmappedHostRoutesFailClosed() {
        var request = new MockHttpServletRequest("GET", "/api/platform/roles");
        request.addHeader("X-Lumira-Page-Id", "roles");
        request.addHeader("X-Lumira-Permission", "platform:roles:access");
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        "operator",
                        "",
                        List.of(
                                new SimpleGrantedAuthority("platform:roles:access"),
                                new SimpleGrantedAuthority("platform:users:access")));

        assertFalse(PlatformHttpPermissionPolicy.authorize(authentication, request, null));
        try (var ignored =
                AuthenticationRequestContext.bind(
                        new AuthenticationRequestContext.Snapshot(
                                "somebody-else",
                                ACTOR_ID,
                                null,
                                authorization(
                                        "somebody-else",
                                        Set.of("platform:roles:access"),
                                        Set.of("platform:roles:access"))))) {
            assertFalse(PlatformHttpPermissionPolicy.authorize(authentication, request, null));
        }

        assertFalse(
                decide(
                        "GET",
                        "/api/platform/not-a-host-route",
                        Set.of("platform:roles:access"),
                        Set.of("platform:roles:access")));
    }

    private static boolean decide(
            String method, String path, Set<String> effective, Set<String> global) {
        var request = new MockHttpServletRequest(method, path);
        var authorities = effective.stream().map(SimpleGrantedAuthority::new).toList();
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated("operator", "", authorities);
        var context = authorization("operator", effective, global);
        try (var ignored =
                AuthenticationRequestContext.bind(
                        new AuthenticationRequestContext.Snapshot(
                                "operator", ACTOR_ID, null, context))) {
            return PlatformHttpPermissionPolicy.authorize(
                    authentication,
                    request,
                    com.lumira.platform.plugins.PluginManifestAuthorizationTestSupport.forPlugins(
                            "directory", "ai", "profile-console", "authentication-console"));
        }
    }

    private static RequestAuthorizationContext authorization(
            String principal, Set<String> effective, Set<String> global) {
        return new RequestAuthorizationContext(
                principal,
                ACTOR_ID,
                "session",
                effective,
                global,
                Map.of(),
                effective,
                global,
                Map.of(),
                "revision",
                "revision",
                true,
                null);
    }
}
