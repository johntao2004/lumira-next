package com.lumira.platform.identity.api;

/** Login availability policy consumed by authentication flows. */
public interface MaintenancePolicyPort {
    boolean canLogin(String username);
}
