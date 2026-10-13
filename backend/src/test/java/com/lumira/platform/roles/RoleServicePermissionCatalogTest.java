package com.lumira.platform.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.UserDirectoryPort;
import com.lumira.platform.modules.PluginPermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RoleServicePermissionCatalogTest {
    @AfterEach
    void clearPluginPermissions() {
        PluginPermissions.replaceAll(Map.of());
    }

    @Test
    void everyHostPageAndOperationHasOneStableIndependentOwner() {
        var roles = mock(RoleRepository.class);
        when(roles.permissionHistory()).thenReturn(List.of("platform:retired:write"));
        var service = service(roles);

        var catalog = service.permissionCatalog();
        var pages = catalog.stream().flatMap(group -> group.pages().stream()).toList();
        var accessCodes =
                pages.stream().map(RoleService.PermissionCatalogPage::accessPermission).toList();
        assertEquals(PlatformPageId.values().length, pages.size());
        assertEquals(
                pages.size(),
                pages.stream().map(RoleService.PermissionCatalogPage::id).distinct().count());
        assertEquals(accessCodes.size(), accessCodes.stream().distinct().count());
        assertEquals(
                PlatformPageId.values().length,
                accessCodes.stream()
                        .filter(code -> code != null && code.endsWith(":access"))
                        .count());

        var operationOwner = new java.util.HashMap<String, RoleService.PermissionCatalogPage>();
        for (var page : pages) {
            assertTrue(page.id() != null && !page.id().isBlank());
            assertEquals("/dashboard/" + page.id(), page.path());
            assertTrue(page.operationScopes().keySet().containsAll(page.operationPermissions()));
            for (String operation : page.operationPermissions()) {
                assertFalse(
                        operationOwner.containsKey(operation),
                        "Duplicate operation owner: " + operation);
                operationOwner.put(operation, page);
                assertTrue(operation.startsWith("platform:" + page.id() + ":"), operation);
            }
        }
        assertEquals(
                Set.of(PlatformOperation.values()).stream()
                        .map(PlatformOperation::permission)
                        .collect(Collectors.toSet()),
                operationOwner.keySet());

        var byId =
                pages.stream()
                        .collect(
                                Collectors.toMap(
                                        RoleService.PermissionCatalogPage::id, page -> page));
        assertEquals("platform:users:access", byId.get("users").accessPermission());
        assertEquals("scoped", byId.get("users").accessScope());
        assertTrue(byId.get("users").operationPermissions().contains("platform:users:export"));
        assertEquals("scoped", byId.get("users").operationScopes().get("platform:users:export"));
        assertEquals("platform:branding:access", byId.get("branding").accessPermission());
        assertFalse(
                byId.get("branding").operationPermissions().contains("platform:settings:update"));
        assertEquals("global", byId.get("audit").accessScope());
        assertEquals(
                "scoped",
                byId.get("ai-console").operationScopes().get("platform:ai-console:update-user"));

        var allCodes =
                catalog.stream()
                        .flatMap(group -> group.permissions().stream())
                        .map(RoleService.PermissionCatalogPermission::code)
                        .collect(Collectors.toSet());
        for (var page : pages) {
            assertTrue(allCodes.contains(page.accessPermission()));
            assertTrue(allCodes.containsAll(page.operationPermissions()));
        }
        assertFalse(
                catalog.stream()
                        .flatMap(group -> group.permissions().stream())
                        .filter(permission -> !permission.retired())
                        .anyMatch(
                                permission ->
                                        permission.code().endsWith(":write")
                                                || permission.code().endsWith(":manage")));
        assertTrue(
                catalog.stream()
                        .flatMap(group -> group.permissions().stream())
                        .anyMatch(
                                permission ->
                                        permission.code().equals("platform:retired:write")
                                                && permission.retired()));
        assertFalse(
                pages.stream()
                        .anyMatch(
                                page ->
                                        page.path().equals("/dashboard/account/settings")
                                                || page.path().equals("/dashboard/my-files")));
    }

    @Test
    void pluginCatalogUsesOwningRouteAndExplicitOperationScopeMappingOnly() {
        String access = "platform:catalog-fixture:access";
        String update = "platform:catalog-fixture:update";
        String extra = "platform:catalog-fixture:unused";
        PluginPermissions.replaceAll(
                Map.of(
                        "catalog-fixture",
                        List.of(
                                new PluginPermissions.CatalogGroup(
                                        "catalog-fixture:management",
                                        "Catalog fixture",
                                        List.of(
                                                new PluginPermissions.Page(
                                                        "settings",
                                                        "/dashboard/settings",
                                                        "Settings",
                                                        "administration",
                                                        10,
                                                        access,
                                                        List.of(update),
                                                        "scoped",
                                                        Map.of(update, "scoped"))),
                                        List.of(
                                                new PluginPermissions.Permission(access, "access"),
                                                new PluginPermissions.Permission(update, "update"),
                                                new PluginPermissions.Permission(
                                                        extra, "unused"))))));
        var service = service(mock(RoleRepository.class));

        var group =
                service.permissionCatalog().stream()
                        .filter(item -> item.id().equals("catalog-fixture:management"))
                        .findFirst()
                        .orElseThrow();
        var page = group.pages().getFirst();

        assertEquals("settings", page.id());
        assertEquals("/dashboard/settings", page.path());
        assertEquals(access, page.accessPermission());
        assertEquals(List.of(update), page.operationPermissions());
        assertEquals("scoped", page.accessScope());
        assertEquals(Map.of(update, "scoped"), page.operationScopes());
        assertEquals("MAPPED", page.mappingStatus());
        assertFalse(page.operationPermissions().contains(extra));
    }

    private static RoleService service(RoleRepository roles) {
        return new RoleService(
                roles,
                mock(UserDirectoryPort.class),
                mock(AdministrationPort.class),
                mock(com.lumira.platform.common.AuditPort.class));
    }
}
