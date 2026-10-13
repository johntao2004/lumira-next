package com.lumira.platform.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Stable opaque fingerprint for one user's effective grants and data visibility. */
public final class AuthorizationRevision {
    public record ActiveRole(UUID id, String dataScope, Set<String> authorities) {
        public ActiveRole {
            authorities = Set.copyOf(authorities == null ? Set.of() : authorities);
        }
    }

    public record OrganizationNode(UUID id, UUID parentId, boolean enabled) {}

    public record Facts(
            UUID userId,
            Set<String> authorities,
            Set<String> globalAuthorities,
            Set<String> directAuthorities,
            List<ActiveRole> activeRoles,
            Set<UUID> organizationMemberships,
            List<OrganizationNode> organizationTree) {
        public Facts {
            authorities = Set.copyOf(authorities == null ? Set.of() : authorities);
            globalAuthorities =
                    Set.copyOf(globalAuthorities == null ? Set.of() : globalAuthorities);
            directAuthorities =
                    Set.copyOf(directAuthorities == null ? Set.of() : directAuthorities);
            activeRoles = List.copyOf(activeRoles == null ? List.of() : activeRoles);
            organizationMemberships =
                    Set.copyOf(
                            organizationMemberships == null ? Set.of() : organizationMemberships);
            organizationTree = List.copyOf(organizationTree == null ? List.of() : organizationTree);
        }
    }

    private AuthorizationRevision() {}

    public static String from(Facts facts) {
        var canonical = new TreeSet<String>();
        canonical.add("user:" + facts.userId());
        facts.authorities().forEach(value -> canonical.add("authority:" + value));
        facts.globalAuthorities().forEach(value -> canonical.add("global:" + value));
        facts.directAuthorities().forEach(value -> canonical.add("direct:" + value));
        facts.activeRoles().stream()
                .sorted(java.util.Comparator.comparing(role -> role.id().toString()))
                .forEach(
                        role -> {
                            canonical.add("role:" + role.id() + ":" + role.dataScope());
                            role.authorities()
                                    .forEach(
                                            value ->
                                                    canonical.add(
                                                            "role-authority:"
                                                                    + role.id()
                                                                    + ":"
                                                                    + value));
                        });
        facts.organizationMemberships().forEach(value -> canonical.add("membership:" + value));
        if (facts.activeRoles().stream().anyMatch(role -> "SUBTREE".equals(role.dataScope())))
            facts.organizationTree()
                    .forEach(
                            node ->
                                    canonical.add(
                                            "organization-tree:"
                                                    + node.id()
                                                    + ":"
                                                    + node.parentId()
                                                    + ":"
                                                    + node.enabled()));

        try {
            var digest = MessageDigest.getInstance("SHA-256");
            canonical.forEach(
                    value -> {
                        digest.update(value.getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) '\n');
                    });
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
