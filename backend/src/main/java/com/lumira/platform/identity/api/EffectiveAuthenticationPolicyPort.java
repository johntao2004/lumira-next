package com.lumira.platform.identity.api;

import com.lumira.platform.common.PrimaryMethod;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Current effective authentication facts for an account. */
public interface EffectiveAuthenticationPolicyPort {
    EffectivePolicy resolve(UUID userId);

    boolean requiredByPolicyExceptSelf(UUID userId);

    boolean secondFactorAvailable();

    record RequirementSource(String kind, UUID roleId) {}

    record EffectivePolicy(
            UUID revision,
            Set<PrimaryMethod> allowedMethods,
            Set<PrimaryMethod> usableMethods,
            boolean mfaRequired,
            List<RequirementSource> requirementSources,
            boolean captchaRequired,
            UUID primaryCredentialRevision,
            UUID factorCredentialRevision) {
        public EffectivePolicy(
                UUID revision,
                Set<PrimaryMethod> allowedMethods,
                Set<PrimaryMethod> usableMethods,
                boolean mfaRequired,
                List<RequirementSource> requirementSources,
                boolean captchaRequired) {
            this(
                    revision,
                    allowedMethods,
                    usableMethods,
                    mfaRequired,
                    requirementSources,
                    captchaRequired,
                    new UUID(0, 0),
                    new UUID(0, 0));
        }

        public EffectivePolicy {
            allowedMethods = Set.copyOf(allowedMethods);
            usableMethods = Set.copyOf(usableMethods);
            requirementSources = List.copyOf(requirementSources);
            if (primaryCredentialRevision == null || factorCredentialRevision == null)
                throw new IllegalArgumentException("credential revisions are required");
        }
    }
}
