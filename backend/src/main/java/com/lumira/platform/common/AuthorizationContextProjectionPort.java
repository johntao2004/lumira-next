package com.lumira.platform.common;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Narrow read-only view of the authorization snapshot validated for the current request. */
public interface AuthorizationContextProjectionPort {
    record Snapshot(
            Set<String> effectiveAuthorities,
            Set<String> effectiveGlobalAuthorities,
            boolean previewActive,
            UUID targetRoleId,
            long targetRoleVersion) {
        public Snapshot {
            effectiveAuthorities =
                    Set.copyOf(effectiveAuthorities == null ? Set.of() : effectiveAuthorities);
            effectiveGlobalAuthorities =
                    Set.copyOf(
                            effectiveGlobalAuthorities == null
                                    ? Set.of()
                                    : effectiveGlobalAuthorities);
        }
    }

    Optional<Snapshot> current(String principalName);
}
