package com.lumira.platform.common;

/** Platform content policy, independent of any page or business module. */
public interface ContentPolicyPort {
    ScanResult evaluate(String text);

    default ScanResult evaluate(String actor, String text, String source) {
        return evaluate(text);
    }

    record ScanResult(boolean blocked) {}
}
