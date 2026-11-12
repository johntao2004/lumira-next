package com.lumira.platform.identity;

import com.lumira.platform.common.AuthorizationRevision;
import com.lumira.platform.common.AuthorizationRevisionPort;
import java.util.*;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationRevisionService implements AuthorizationRevisionPort {
    private final JdbcClient jdbc;

    public AuthorizationRevisionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String revision(
            UUID userId, Set<String> effectiveAuthorities, Set<String> globalAuthorities) {
        Set<String> directAuthorities = new HashSet<>();
        var roleScopes = new TreeMap<UUID, String>();
        var roleAuthorities = new TreeMap<UUID, Set<String>>();
        Set<UUID> memberships = new HashSet<>();

        jdbc.sql(
                        """
                        SELECT 'DIRECT' AS kind, NULL::uuid AS role_id, NULL AS data_scope, authority, NULL::uuid AS org_id
                          FROM platform_authority WHERE user_id=:userId
                        UNION ALL
                        SELECT 'ROLE' AS kind, r.id AS role_id, r.data_scope, ra.authority, NULL::uuid AS org_id
                          FROM platform_user_role ur
                          JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                          LEFT JOIN platform_role_authority ra ON ra.role_id=r.id
                         WHERE ur.user_id=:userId
                        UNION ALL
                        SELECT 'MEMBERSHIP' AS kind, NULL::uuid AS role_id, NULL AS data_scope, NULL AS authority, organization_id AS org_id
                          FROM platform_user_organization WHERE user_id=:userId
                        """)
                .param("userId", userId)
                .query(
                        (RowCallbackHandler)
                                rs -> {
                                    String kind = rs.getString(1);
                                    if ("DIRECT".equals(kind)) {
                                        String auth = rs.getString(4);
                                        if (auth != null) directAuthorities.add(auth);
                                    } else if ("ROLE".equals(kind)) {
                                        UUID roleId = rs.getObject(2, UUID.class);
                                        if (roleId != null) {
                                            roleScopes.putIfAbsent(roleId, rs.getString(3));
                                            roleAuthorities.computeIfAbsent(
                                                    roleId, ignored -> new TreeSet<>());
                                            String auth = rs.getString(4);
                                            if (auth != null) roleAuthorities.get(roleId).add(auth);
                                        }
                                    } else if ("MEMBERSHIP".equals(kind)) {
                                        UUID orgId = rs.getObject(5, UUID.class);
                                        if (orgId != null) memberships.add(orgId);
                                    }
                                });

        var activeRoles =
                roleScopes.entrySet().stream()
                        .map(
                                entry ->
                                        new AuthorizationRevision.ActiveRole(
                                                entry.getKey(),
                                                entry.getValue(),
                                                roleAuthorities.getOrDefault(
                                                        entry.getKey(), Set.of())))
                        .toList();

        List<AuthorizationRevision.OrganizationNode> organizationTree =
                activeRoles.stream().anyMatch(role -> "SUBTREE".equals(role.dataScope()))
                        ? jdbc.sql("SELECT id,parent_id,enabled FROM platform_organization")
                                .query(
                                        (rs, rowNum) ->
                                                new AuthorizationRevision.OrganizationNode(
                                                        rs.getObject(1, UUID.class),
                                                        rs.getObject(2, UUID.class),
                                                        rs.getBoolean(3)))
                                .list()
                        : List.of();
        return AuthorizationRevision.from(
                new AuthorizationRevision.Facts(
                        userId,
                        effectiveAuthorities,
                        globalAuthorities,
                        Set.copyOf(directAuthorities),
                        activeRoles,
                        Set.copyOf(memberships),
                        organizationTree));
    }
}
