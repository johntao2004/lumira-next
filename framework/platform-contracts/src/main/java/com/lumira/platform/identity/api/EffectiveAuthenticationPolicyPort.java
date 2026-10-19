package com.lumira.platform.identity.api;

import com.lumira.platform.common.PrimaryMethod;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 账户当前生效的认证事实。 Current effective authentication facts for an account. */
public interface EffectiveAuthenticationPolicyPort {
    /**
     * 返回账户当前认证策略及凭据版本事实，不代表本次认证已通过。 Returns current account policy and credential revisions, not
     * proof of successful authentication.
     */
    EffectivePolicy resolve(UUID userId);

    /**
     * 判断除用户自愿启用外，角色或全局策略是否要求 MFA。 Reports MFA requirements from role/global policy excluding
     * voluntary user enrollment.
     */
    boolean requiredByPolicyExceptSelf(UUID userId);

    /**
     * 判断当前第二因子能力是否可用；不可用不能绕过强制策略。 Reports second-factor availability; unavailability must not
     * bypass required policy.
     */
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
