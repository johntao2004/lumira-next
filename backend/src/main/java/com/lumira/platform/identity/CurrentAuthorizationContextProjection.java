package com.lumira.platform.identity;

import com.lumira.platform.common.AuthorizationContextProjectionPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Adapts the identity filter's validated snapshot to the shared read-only projection port. */
@Component
public class CurrentAuthorizationContextProjection implements AuthorizationContextProjectionPort {
    @Override
    public Optional<Snapshot> current(String principalName) {
        AuthenticationRequestContext.Snapshot snapshot = AuthenticationRequestContext.current();
        if (snapshot == null
                || snapshot.authorization() == null
                || !principalName.equals(snapshot.principalName())) return Optional.empty();
        return Optional.of(
                new Snapshot(
                        snapshot.authorization().effectiveAuthorities(),
                        snapshot.authorization().effectiveGlobalAuthorities(),
                        snapshot.authorization().previewActive(),
                        snapshot.authorization().targetRoleId(),
                        snapshot.authorization().preview() == null
                                ? 0
                                : snapshot.authorization().preview().roleVersion()));
    }
}
