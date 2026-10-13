package com.lumira.platform.identity;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PlatformPageId;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/** Requires the API docs page grant and its global data scope for the API description. */
@Component
public final class ApiDocsAccessPolicy {
    public static final String PERMISSION = PlatformPageId.API_DOCS.accessPermission();

    private final AdministrationPort administration;

    public ApiDocsAccessPolicy(AdministrationPort administration) {
        this.administration = administration;
    }

    public boolean allows(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        boolean hasPermission =
                authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch(PERMISSION::equals);
        return hasPermission && administration.hasGlobal(authentication.getName(), PERMISSION);
    }
}
