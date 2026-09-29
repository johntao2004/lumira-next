package com.lumira.platform.common;

/** Optional content-policy hook.  The feedback module can run without a policy provider. */
public interface ContentPolicyPort {
    ScanResult evaluate(String text);

    record ScanResult(boolean blocked) {}
}
