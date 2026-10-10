package com.lumira.platform.common;

import java.util.Set;
import java.util.UUID;

/** Settings-owned global authentication policy consumed through a stable module boundary. */
public interface GlobalAuthenticationPolicyPort {
    Policy current();

    record Policy(
            long version, UUID revision, Set<PrimaryMethod> enabledMethods, boolean mfaRequired) {
        public Policy {
            enabledMethods = Set.copyOf(enabledMethods);
        }
    }
}
