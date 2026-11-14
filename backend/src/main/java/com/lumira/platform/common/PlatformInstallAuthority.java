package com.lumira.platform.common;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Installing a plugin loads in-process code and runs its migrations as the application database
 * account. The actor must already hold every current host permission globally.
 */
public final class PlatformInstallAuthority {
    private PlatformInstallAuthority() {}

    public static Set<String> currentPlatformPermissions() {
        var permissions = new LinkedHashSet<String>();
        for (PlatformPageId page : PlatformPageId.values())
            permissions.add(page.accessPermission());
        for (PlatformOperation operation : PlatformOperation.values())
            permissions.add(operation.permission());
        return Set.copyOf(permissions);
    }

    public static void require(AdministrationPort guard, String actor) {
        for (String permission : currentPlatformPermissions())
            if (!guard.hasGlobal(actor, permission))
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "moduleInstallRequiresPlatformAuthority");
    }
}
