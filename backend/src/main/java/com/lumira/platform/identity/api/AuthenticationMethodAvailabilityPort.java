package com.lumira.platform.identity.api;

import com.lumira.platform.common.PrimaryMethod;
import java.util.Set;

/** Current provider readiness for primary methods that administrators may enable. */
public interface AuthenticationMethodAvailabilityPort {
    Set<PrimaryMethod> availableMethods();

    default boolean available(PrimaryMethod method) {
        return method != null && availableMethods().contains(method);
    }
}
