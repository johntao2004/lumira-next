package com.lumira.plugins.verificationdebugger;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Fail-closed development gate for the loopback receiver. */
final class VerificationDebuggerGate {
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    private final boolean enabled;

    private VerificationDebuggerGate(boolean enabled) {
        this.enabled = enabled;
    }

    static VerificationDebuggerGate from(Environment environment) {
        String configured = environment.getProperty("lumira.sms.debug-enabled");
        if (configured == null) configured = environment.getProperty("LUMIRA_SMS_DEBUG_ENABLED");
        boolean requested = Boolean.parseBoolean(configured);
        boolean production =
                Arrays.stream(environment.getActiveProfiles())
                        .map(profile -> profile.toLowerCase(Locale.ROOT))
                        .anyMatch(PRODUCTION_PROFILES::contains);
        return new VerificationDebuggerGate(requested && !production);
    }

    static VerificationDebuggerGate forTest(boolean requested, String... profiles) {
        boolean production =
                Arrays.stream(profiles)
                        .map(profile -> profile.toLowerCase(Locale.ROOT))
                        .anyMatch(PRODUCTION_PROFILES::contains);
        return new VerificationDebuggerGate(requested && !production);
    }

    boolean enabled() {
        return enabled;
    }
}
