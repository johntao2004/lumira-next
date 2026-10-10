package com.lumira.platform.identity.api;

/** Non-secret readiness and provider identity projection used by authentication reachability. */
public interface WechatAvailabilityPort {
    /** True only when enabled, app credentials and the fixed public callback origin are ready. */
    boolean ready();

    /** Current non-secret AppID, or null when no configuration exists. */
    String currentAppId();
}
