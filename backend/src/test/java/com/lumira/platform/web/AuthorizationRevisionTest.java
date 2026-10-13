package com.lumira.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.lumira.platform.common.AuthorizationRevision;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizationRevisionTest {
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CHILD_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void activeRoleScopeChangeFromOrganizationToSubtreeChangesRevision() {
        var organization = facts("ORGANIZATION", Set.of(ORGANIZATION_ID));
        var subtree = facts("SUBTREE", Set.of(ORGANIZATION_ID));

        assertNotEquals(
                AuthorizationRevision.from(organization), AuthorizationRevision.from(subtree));
    }

    @Test
    void revisionIsIsolatedByUserId() {
        var original = facts("ORGANIZATION", Set.of(ORGANIZATION_ID));
        var otherUser =
                new AuthorizationRevision.Facts(
                        OTHER_USER_ID,
                        original.authorities(),
                        original.globalAuthorities(),
                        original.directAuthorities(),
                        original.activeRoles(),
                        original.organizationMemberships(),
                        original.organizationTree());

        assertNotEquals(
                AuthorizationRevision.from(original), AuthorizationRevision.from(otherUser));
    }

    @Test
    void organizationMembershipChangeChangesRevision() {
        var original = facts("ORGANIZATION", Set.of(ORGANIZATION_ID));
        var changed = facts("ORGANIZATION", Set.of(CHILD_ORGANIZATION_ID));

        assertNotEquals(AuthorizationRevision.from(original), AuthorizationRevision.from(changed));
    }

    @Test
    void canonicalGrantAndMembershipOrderingDoesNotChangeRevision() {
        var first =
                new AuthorizationRevision.Facts(
                        USER_ID,
                        Set.of("platform:users:access", "platform:users:update"),
                        Set.of("platform:users:access"),
                        Set.of("platform:users:access"),
                        List.of(
                                new AuthorizationRevision.ActiveRole(
                                        ROLE_ID,
                                        "SUBTREE",
                                        Set.of("platform:users:update", "platform:users:access"))),
                        Set.of(ORGANIZATION_ID, CHILD_ORGANIZATION_ID),
                        List.of(
                                new AuthorizationRevision.OrganizationNode(
                                        ORGANIZATION_ID, null, true),
                                new AuthorizationRevision.OrganizationNode(
                                        CHILD_ORGANIZATION_ID, ORGANIZATION_ID, true)));
        var reordered =
                new AuthorizationRevision.Facts(
                        USER_ID,
                        Set.of("platform:users:update", "platform:users:access"),
                        Set.of("platform:users:access"),
                        Set.of("platform:users:access"),
                        List.of(
                                new AuthorizationRevision.ActiveRole(
                                        ROLE_ID,
                                        "SUBTREE",
                                        Set.of("platform:users:access", "platform:users:update"))),
                        Set.of(CHILD_ORGANIZATION_ID, ORGANIZATION_ID),
                        List.of(
                                new AuthorizationRevision.OrganizationNode(
                                        CHILD_ORGANIZATION_ID, ORGANIZATION_ID, true),
                                new AuthorizationRevision.OrganizationNode(
                                        ORGANIZATION_ID, null, true)));

        assertEquals(AuthorizationRevision.from(first), AuthorizationRevision.from(reordered));
    }

    @Test
    void subtreeTreeChangesAffectRevisionButOrganizationScopeDoesNotUseTree() {
        var organization = facts("ORGANIZATION", Set.of(ORGANIZATION_ID));
        var changedTree =
                new AuthorizationRevision.Facts(
                        organization.userId(),
                        organization.authorities(),
                        organization.globalAuthorities(),
                        organization.directAuthorities(),
                        organization.activeRoles(),
                        organization.organizationMemberships(),
                        List.of(
                                new AuthorizationRevision.OrganizationNode(
                                        ORGANIZATION_ID, CHILD_ORGANIZATION_ID, true)));
        var subtree = facts("SUBTREE", Set.of(ORGANIZATION_ID));
        var changedSubtree =
                new AuthorizationRevision.Facts(
                        subtree.userId(),
                        subtree.authorities(),
                        subtree.globalAuthorities(),
                        subtree.directAuthorities(),
                        subtree.activeRoles(),
                        subtree.organizationMemberships(),
                        changedTree.organizationTree());

        assertEquals(
                AuthorizationRevision.from(organization), AuthorizationRevision.from(changedTree));
        assertNotEquals(
                AuthorizationRevision.from(subtree), AuthorizationRevision.from(changedSubtree));
    }

    private static AuthorizationRevision.Facts facts(String scope, Set<UUID> memberships) {
        var tree =
                List.of(
                        new AuthorizationRevision.OrganizationNode(ORGANIZATION_ID, null, true),
                        new AuthorizationRevision.OrganizationNode(
                                CHILD_ORGANIZATION_ID, ORGANIZATION_ID, true));
        return new AuthorizationRevision.Facts(
                USER_ID,
                Set.of("platform:users:access"),
                Set.of("platform:users:access"),
                Set.of(),
                List.of(
                        new AuthorizationRevision.ActiveRole(
                                ROLE_ID, scope, Set.of("platform:users:access"))),
                memberships,
                tree);
    }
}
