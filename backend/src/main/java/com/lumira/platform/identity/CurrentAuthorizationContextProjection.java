/**
 * 将已验证的请求快照转换为共享只读授权投影，仅在主体匹配时返回，避免借用其他主体的请求权限。 Adapts validated request snapshots into shared
 * read-only authorization projections only for the matching principal, preventing reuse of another
 * principal’s request authority.
 */
package com.lumira.platform.identity;

import com.lumira.platform.common.AuthorizationContextProjectionPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 将身份过滤器已验证的快照适配为共享只读投影端口。 Adapts the identity filter's validated snapshot to the shared read-only
 * projection port.
 */
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
