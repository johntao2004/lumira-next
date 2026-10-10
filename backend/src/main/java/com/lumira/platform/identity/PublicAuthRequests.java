package com.lumira.platform.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Strict, body-only input contracts for public authentication routes. */
public final class PublicAuthRequests {
    private PublicAuthRequests() {}

    public abstract static class StrictInput {
        private final Set<String> unknown = new LinkedHashSet<>();

        @JsonAnySetter
        public void unknown(String key, Object ignored) {
            unknown.add(key);
        }

        public boolean hasUnknownFields() {
            return !unknown.isEmpty();
        }
    }

    public static final class Challenge extends StrictInput {
        public String channel;
        public String contact;
        public String locale;
        public String captchaProofId;

        @Override
        public String toString() {
            return "PublicChallenge[redacted]";
        }
    }

    public static final class Verify extends StrictInput {
        public String challengeId;
        public String code;
        public String captchaProofId;

        @Override
        public String toString() {
            return "PublicChallengeVerification[redacted]";
        }
    }

    public static final class RegistrationCompletion extends StrictInput {
        public String proofId;
        public String username;
        public String displayName;

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public String password;

        public Long policyVersion;
        public Long agreementVersion;
        public Boolean acceptAgreements;
        public String requestId;
        public String captchaProofId;
        public Long profileSchemaVersion;
        public Map<String, String> profileValues;
        public String wechatRegistrationProofId;

        public Map<String, String> normalizedProfileValues() {
            return profileValues == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(profileValues));
        }

        @Override
        public String toString() {
            return "RegistrationCompletion[redacted]";
        }
    }

    public static final class LoginCodeCompletion extends StrictInput {
        public String challengeId;
        public String code;
        public String captchaProofId;

        @Override
        public String toString() {
            return "LoginCodeCompletion[redacted]";
        }
    }

    public static final class RecoveryRequest extends StrictInput {
        public String username;
        public String channel;
        public String contact;
        public String locale;
        public String captchaProofId;

        @Override
        public String toString() {
            return "PasswordRecoveryRequest[redacted]";
        }
    }

    public static final class RecoveryVerify extends StrictInput {
        public String challengeId;
        public String code;
        public String captchaProofId;

        @Override
        public String toString() {
            return "PasswordRecoveryVerification[redacted]";
        }
    }

    public static final class RecoveryConfirmation extends StrictInput {
        public String proofId;
        public String username;

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public String token;

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public String newPassword;

        public String captchaProofId;

        @Override
        public String toString() {
            return "PasswordRecoveryConfirmation[redacted]";
        }
    }
}
