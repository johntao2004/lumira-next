package com.lumira.platform.common;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Narrow contribution point for OTP, WeChat, CAPTCHA, registration, and role-preview packages. */
public interface AuthenticationCapabilityContributor {
    String id();

    Contribution authenticationCapabilities();

    /** Methods and a stable revision for credentials already proved and usable by this account. */
    AccountMethods accountMethods(UUID userId);

    record AccountMethods(Set<PrimaryMethod> usableMethods, String authenticationRevision) {
        public AccountMethods {
            usableMethods = Set.copyOf(usableMethods == null ? Set.of() : usableMethods);
            if (authenticationRevision == null || authenticationRevision.isBlank())
                throw new IllegalArgumentException("authenticationRevision");
        }
    }

    record Contribution(
            Set<PrimaryMethod> methods,
            boolean loginCode,
            boolean wechat,
            boolean captcha,
            boolean rolePreview,
            Registration registration,
            Captcha captchaPolicy) {
        public Contribution {
            methods = Set.copyOf(methods == null ? Set.of() : methods);
            registration =
                    registration == null
                            ? new Registration(false, false, List.of(), null)
                            : registration;
            captchaPolicy =
                    captchaPolicy == null ? new Captcha(false, null, List.of()) : captchaPolicy;
        }
    }

    record Registration(boolean supported, boolean available, List<String> channels, String path) {
        public Registration {
            channels = List.copyOf(channels == null ? List.of() : channels);
        }
    }

    record Captcha(
            boolean required, String type, List<String> actions, List<String> alwaysActions) {
        public Captcha {
            actions = List.copyOf(actions == null ? List.of() : actions);
            alwaysActions = List.copyOf(alwaysActions == null ? List.of() : alwaysActions);
        }

        public Captcha(boolean required, String type, List<String> actions) {
            this(required, type, actions, List.of());
        }
    }
}
