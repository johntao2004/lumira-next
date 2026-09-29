package com.lumira.platform.identity;

import java.util.List;

public final class PasskeyRequests {
    private PasskeyRequests() {}

    public record Operation(String currentPassword) {}

    public record Registration(
            String challengeId,
            String id,
            String rawId,
            String type,
            Response response,
            String authenticatorAttachment,
            List<String> transports,
            String label) {
        public record Response(String clientDataJSON, String attestationObject) {}
    }

    public record Authentication(
            String challengeId,
            String id,
            String rawId,
            String type,
            Response response,
            String authenticatorAttachment) {
        public record Response(String clientDataJSON, String authenticatorData,
                               String signature, String userHandle) {}
    }
}
