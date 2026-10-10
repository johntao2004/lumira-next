package com.lumira.platform.identity.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-validated authorization facts for one request. Real identity and authentication evidence
 * stay separate from the business view used by SQL, navigation and read guards.
 */
public record RequestAuthorizationContext(
        String principalName,
        UUID actorId,
        String sessionPrimaryId,
        Set<String> realAuthorities,
        Set<String> realGlobalAuthorities,
        Map<String, Set<String>> realScopes,
        Set<String> effectiveAuthorities,
        Set<String> effectiveGlobalAuthorities,
        Map<String, Set<String>> effectiveScopes,
        String revision,
        String requestedRevision,
        boolean rolePreviewSupported,
        Preview preview) {

    public RequestAuthorizationContext {
        realAuthorities = Set.copyOf(realAuthorities == null ? Set.of() : realAuthorities);
        realGlobalAuthorities =
                Set.copyOf(realGlobalAuthorities == null ? Set.of() : realGlobalAuthorities);
        effectiveAuthorities =
                Set.copyOf(effectiveAuthorities == null ? Set.of() : effectiveAuthorities);
        effectiveGlobalAuthorities =
                Set.copyOf(
                        effectiveGlobalAuthorities == null ? Set.of() : effectiveGlobalAuthorities);
        realScopes = immutableScopes(realScopes);
        effectiveScopes = immutableScopes(effectiveScopes);
    }

    private static Map<String, Set<String>> immutableScopes(Map<String, Set<String>> source) {
        if (source == null || source.isEmpty()) return Map.of();
        return source.entrySet().stream()
                .collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry ->
                                        Set.copyOf(
                                                entry.getValue() == null
                                                        ? Set.of()
                                                        : entry.getValue())));
    }

    public boolean previewActive() {
        return preview != null && "ACTIVE".equals(preview.state());
    }

    public boolean previewBlocked() {
        return preview != null && "BLOCKED".equals(preview.state());
    }

    public UUID targetRoleId() {
        return preview == null ? null : preview.roleId();
    }

    public record Preview(
            String state,
            UUID roleId,
            String roleName,
            long roleVersion,
            java.time.OffsetDateTime startedAt,
            java.time.OffsetDateTime expiresAt,
            boolean limitedByActor,
            String blockedReason) {}
}
