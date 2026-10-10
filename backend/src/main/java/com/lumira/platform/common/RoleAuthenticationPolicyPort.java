package com.lumira.platform.common;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Roles-owned authentication policy consumed through a stable module boundary. */
public interface RoleAuthenticationPolicyPort {
    List<RolePolicy> activeForUser(UUID userId);

    RolePolicy forRole(UUID roleId);

    record RolePolicy(
            UUID roleId,
            long roleVersion,
            long version,
            UUID revision,
            LoginMode loginMode,
            Set<PrimaryMethod> methods,
            MfaMode mfaMode,
            CaptchaMode captchaMode) {
        public RolePolicy {
            methods = Set.copyOf(methods);
        }
    }

    enum LoginMode {
        INHERIT,
        RESTRICT
    }

    enum MfaMode {
        INHERIT,
        REQUIRED
    }

    enum CaptchaMode {
        INHERIT,
        ALWAYS
    }
}
