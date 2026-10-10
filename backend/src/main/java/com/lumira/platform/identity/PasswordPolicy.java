package com.lumira.platform.identity;

import com.lumira.platform.identity.api.SecurityPolicyPort;

/** Shared new-password rules for authenticated changes and recovery. */
public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static boolean valid(String value) {
        return valid(value, SecurityPolicyPort.Policy.defaults());
    }

    public static boolean valid(String value, SecurityPolicyPort.Policy policy) {
        return policy != null && policy.passwordValid(value);
    }
}
