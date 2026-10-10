package com.lumira.platform.identity.api;

import com.lumira.platform.common.PrimaryMethod;
import java.util.Set;
import java.util.UUID;

/** Bulk validation for policy writes; implementations must check actual usable credentials. */
public interface AuthenticationReachabilityPort {
    boolean allActiveAccountsReachable(Set<PrimaryMethod> globalMethods);

    boolean assignedAccountsReachable(UUID roleId, Set<PrimaryMethod> globalMethods);

    /** True when an active account assigned to this role inherits an MFA requirement. */
    boolean assignedAccountsRequireSecondFactor(UUID roleId);
}
